package mcpserver

// Remote MCP 集成测试:真 Postgres + 真 MinIO。
// 端到端路径用官方 go-sdk 的 MCP client 打 httptest 服务器(与真实 agent 同一条路);
// 细粒度分支直接调工具处理函数(同包可见),ctx 里注入 principal。
// 两个环境变量都设了才跑:
//   TEST_DB_URL='postgres://gopan:gopan@127.0.0.1:5433/gopan_test?sslmode=disable' \
//   TEST_S3_ENDPOINT=127.0.0.1:9000 go test ./internal/mcpserver/

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/pressly/goose/v3"

	gdb "github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

const testBaseURL = "http://mcp.test"

type mcpEnv struct {
	pool    *pgxpool.Pool
	q       *store.Queries
	obj     *objstore.Store
	srv     *httptest.Server
	s       *Server
	nodes   *service.Nodes
	uploads *service.Uploads
	tokens  *service.MCPTokens
	oauth   *service.OAuth
	tickets *service.PackTickets
	alice   uuid.UUID // 主角
	bob     uuid.UUID // 用于越权测试的第二个用户
}

func setupEnv(t *testing.T) *mcpEnv {
	t.Helper()
	dbURL := os.Getenv("TEST_DB_URL")
	ep := os.Getenv("TEST_S3_ENDPOINT")
	if dbURL == "" || ep == "" {
		t.Skip("TEST_DB_URL / TEST_S3_ENDPOINT 未设置,跳过 MCP 集成测试")
	}
	sqlDB, err := sql.Open("pgx", dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		t.Fatal(err)
	}
	goose.SetBaseFS(gdb.Migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("postgres"); err != nil {
		t.Fatal(err)
	}
	if err := goose.Up(sqlDB, "migrations"); err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(context.Background(), dbURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)

	obj, err := objstore.New(&config.Config{
		S3Endpoint: ep, S3PublicEndpoint: ep,
		S3Key: envOrDef("TEST_S3_KEY", "gopan"), S3Secret: envOrDef("TEST_S3_SECRET", "gopan-minio-dev"),
		S3Bucket: "gopan-test", S3Region: "us-east-1",
		PresignPutTTL: time.Hour, PresignGetTTL: 15 * time.Minute,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := obj.EnsureBucket(context.Background()); err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	q := store.New(pool)
	secret := []byte("test-secret-test-secret-test-secret")
	auth := service.NewAuth(q, secret, 15*time.Minute, 14*24*time.Hour, true, 1<<30)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	tokens := service.NewMCPTokens(q)
	oauth := service.NewOAuth(q)
	shares := service.NewShares(q, auth)
	packer := service.NewPacker(q, obj, shares)
	tickets := service.NewPackTickets(secret, 10*time.Minute)

	resA, err := auth.Register(ctx, "mcp_alice", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	resB, err := auth.Register(ctx, "mcp_bob", "password123", "1.1.1.2")
	if err != nil {
		t.Fatal(err)
	}

	srv := httptest.NewServer(NewHandler(nodes, uploads, tokens, oauth, packer, tickets))
	t.Cleanup(srv.Close)
	return &mcpEnv{
		pool: pool, q: q, obj: obj, srv: srv,
		s:     &Server{nodes: nodes, uploads: uploads, tokens: tokens, oauth: oauth, packer: packer, tickets: tickets},
		nodes: nodes, uploads: uploads, tokens: tokens, oauth: oauth, tickets: tickets,
		alice: resA.User.ID, bob: resB.User.ID,
	}
}

func envOrDef(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// ctxFor 构造带 principal(与可选全部 scope)和 baseURL 的 ctx,模拟 withAuth 注入后的请求上下文。
func ctxFor(userID uuid.UUID, scopes ...string) context.Context {
	if len(scopes) == 0 {
		scopes = service.MCPScopes
	}
	m := make(map[string]struct{}, len(scopes))
	for _, sc := range scopes {
		m[sc] = struct{}{}
	}
	ctx := context.WithValue(context.Background(), principalKey{}, &service.MCPPrincipal{UserID: userID, Scopes: m})
	return context.WithValue(ctx, baseURLKey{}, testBaseURL)
}

// bearerTransport 给 MCP client 的每个请求加 Authorization 头。
type bearerTransport struct{ token string }

func (b bearerTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	r.Header.Set("Authorization", "Bearer "+b.token)
	return http.DefaultTransport.RoundTrip(r)
}

func (e *mcpEnv) connect(t *testing.T, token string) *mcp.ClientSession {
	t.Helper()
	client := mcp.NewClient(&mcp.Implementation{Name: "gopan-test-client", Version: "0.0.1"}, nil)
	sess, err := client.Connect(context.Background(), &mcp.StreamableClientTransport{
		Endpoint:             e.srv.URL,
		HTTPClient:           &http.Client{Transport: bearerTransport{token: token}},
		MaxRetries:           -1,
		DisableStandaloneSSE: true,
	}, nil)
	if err != nil {
		t.Fatalf("MCP client 连接失败:%v", err)
	}
	t.Cleanup(func() { sess.Close() })
	return sess
}

func resultText(res *mcp.CallToolResult) string {
	var sb strings.Builder
	for _, c := range res.Content {
		if tc, ok := c.(*mcp.TextContent); ok {
			sb.WriteString(tc.Text)
		}
	}
	return sb.String()
}

// callTool 走真 MCP 协议调工具,成功后把 structuredContent 解到 out。
func callTool(t *testing.T, sess *mcp.ClientSession, name string, args map[string]any, out any) {
	t.Helper()
	res, err := sess.CallTool(context.Background(), &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		t.Fatalf("%s 协议错误:%v", name, err)
	}
	if res.IsError {
		t.Fatalf("%s 工具报错:%s", name, resultText(res))
	}
	if out != nil {
		b, err := json.Marshal(res.StructuredContent)
		if err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal(b, out); err != nil {
			t.Fatalf("%s 结构化输出解码失败:%v", name, err)
		}
	}
}

// callToolErr 期望工具返回执行错误(isError=true),返回错误文本。
func callToolErr(t *testing.T, sess *mcp.ClientSession, name string, args map[string]any) string {
	t.Helper()
	res, err := sess.CallTool(context.Background(), &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		t.Fatalf("%s 协议错误:%v", name, err)
	}
	if !res.IsError {
		t.Fatalf("%s 应返回工具错误,got %s", name, resultText(res))
	}
	return resultText(res)
}

func httpPutBytes(t *testing.T, u string, body []byte) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPut, u, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("预签名 PUT 状态码 %d", resp.StatusCode)
	}
}

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// rawPost 直接 POST MCP 端点,用于验证 withAuth 的 HTTP 语义。
func (e *mcpEnv) rawPost(t *testing.T, authorization string) int {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, e.srv.URL, strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	if authorization != "" {
		req.Header.Set("Authorization", authorization)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp.StatusCode
}

// TestMCPEndToEndOverHTTP 用真 MCP client 走完整协议:API Key 认证、
// 工具清单、文件夹生命周期、scope 受限 token、吊销后 401。
func TestMCPEndToEndOverHTTP(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()

	plain, row, err := e.tokens.Create(ctx, e.alice, "e2e", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, plain)

	// 工具清单:26 个工具全部注册
	lt, err := sess.ListTools(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(lt.Tools) != 26 {
		t.Fatalf("应注册 26 个工具,got %d", len(lt.Tools))
	}

	// create_folder → list_files → search_files → get_file_info → rename → trash → restore
	var docs NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"name": "docs"}, &docs)
	if docs.Kind != "folder" || docs.Name != "docs" || docs.ParentID != nil {
		t.Fatalf("create_folder 输出不符:%+v", docs)
	}
	var sub NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"parent_id": docs.ID, "name": "周报"}, &sub)

	var listed ListFilesOutput
	callTool(t, sess, "list_files", map[string]any{"parent_id": docs.ID}, &listed)
	if listed.Total != 1 || len(listed.Items) != 1 || listed.Items[0].Name != "周报" {
		t.Fatalf("list_files 不符:%+v", listed)
	}

	var found SearchFilesOutput
	callTool(t, sess, "search_files", map[string]any{"query": "周报"}, &found)
	if found.Total != 1 || found.Items[0].ID != sub.ID {
		t.Fatalf("search_files 不符:%+v", found)
	}

	var info NodeOutput
	callTool(t, sess, "get_file_info", map[string]any{"node_id": docs.ID}, &info)
	if info.Kind != "folder" || info.SubtreeCount == nil {
		t.Fatalf("get_file_info 文件夹应带 subtree 统计:%+v", info)
	}

	var renamed NodeOutput
	callTool(t, sess, "rename_node", map[string]any{"node_id": sub.ID, "name": "月报"}, &renamed)
	if renamed.Name != "月报" {
		t.Fatalf("rename_node 不符:%+v", renamed)
	}

	var ok SuccessOutput
	callTool(t, sess, "trash_nodes", map[string]any{"node_ids": []string{docs.ID}}, &ok)
	if !ok.Success {
		t.Fatalf("trash_nodes 应成功:%+v", ok)
	}
	if msg := callToolErr(t, sess, "get_file_info", map[string]any{"node_id": docs.ID}); !strings.Contains(msg, "NOT_FOUND") {
		t.Fatalf("已删节点应 NOT_FOUND,got %q", msg)
	}
	var restored NodesOutput
	callTool(t, sess, "restore_nodes", map[string]any{"node_ids": []string{docs.ID}}, &restored)
	if len(restored.Items) != 1 || restored.Items[0].ID != docs.ID {
		t.Fatalf("restore_nodes 不符:%+v", restored)
	}

	// 非法参数走 MCP 协议应回工具错误而非协议崩溃
	if msg := callToolErr(t, sess, "get_file_info", map[string]any{"node_id": "not-a-uuid"}); !strings.Contains(msg, "INVALID_INPUT") {
		t.Fatalf("非法 ID 应 INVALID_INPUT,got %q", msg)
	}

	// 只读 token:读得动,写被 FORBIDDEN
	roPlain, _, err := e.tokens.Create(ctx, e.alice, "ro", []string{"files:read"})
	if err != nil {
		t.Fatal(err)
	}
	roSess := e.connect(t, roPlain)
	var roList ListFilesOutput
	callTool(t, roSess, "list_files", map[string]any{}, &roList)
	if roList.Total < 1 {
		t.Fatalf("只读 token 应能列文件:%+v", roList)
	}
	if msg := callToolErr(t, roSess, "create_folder", map[string]any{"name": "hack"}); !strings.Contains(msg, "FORBIDDEN") {
		t.Fatalf("缺 files:write 应 FORBIDDEN,got %q", msg)
	}

	// withAuth 的 DB 侧分支:伪造 key 401,吊销后 401
	if code := e.rawPost(t, "Bearer gopan_key_forged-token-000000000000"); code != http.StatusUnauthorized {
		t.Fatalf("伪造 API Key 应 401,got %d", code)
	}
	if err := e.tokens.Revoke(ctx, e.alice, row.ID); err != nil {
		t.Fatal(err)
	}
	if code := e.rawPost(t, "Bearer "+plain); code != http.StatusUnauthorized {
		t.Fatalf("吊销后应 401,got %d", code)
	}
}

// TestMCPOAuthTokenOverHTTP 覆盖 authenticate 的 gopan_oauth_ 分支:
// PKCE 授权码换 access token,再用它走 MCP 协议。
func TestMCPOAuthTokenOverHTTP(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()

	redirectURI := "http://127.0.0.1:49152/callback"
	client, err := e.oauth.RegisterClient(ctx, "Test Agent", []string{redirectURI})
	if err != nil {
		t.Fatal(err)
	}
	verifier := "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
	digest := sha256.Sum256([]byte(verifier))
	challenge := base64.RawURLEncoding.EncodeToString(digest[:])
	redirect, err := e.oauth.Authorize(ctx, e.alice, service.OAuthAuthorizationInput{
		ClientID: client.ClientID, RedirectURI: redirectURI, ResponseType: "code",
		Scopes: []string{"files:read", "files:write"}, State: "s1",
		CodeChallenge: challenge, CodeChallengeMethod: "S256",
	}, true)
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(redirect)
	if err != nil {
		t.Fatal(err)
	}
	tok, err := e.oauth.ExchangeAuthorizationCode(ctx, u.Query().Get("code"), client.ClientID, redirectURI, verifier)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(tok.AccessToken, "gopan_oauth_") {
		t.Fatalf("access token 前缀不符:%q", tok.AccessToken)
	}

	sess := e.connect(t, tok.AccessToken)
	var folder NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"name": "oauth-made"}, &folder)
	var listed ListFilesOutput
	callTool(t, sess, "list_files", map[string]any{}, &listed)
	if listed.Total != 1 || listed.Items[0].Name != "oauth-made" {
		t.Fatalf("OAuth token 建的目录应可见:%+v", listed)
	}
	// 授权范围外的 scope 照样被拒
	if msg := callToolErr(t, sess, "trash_nodes", map[string]any{"node_ids": []string{folder.ID}}); !strings.Contains(msg, "FORBIDDEN") {
		t.Fatalf("OAuth 未授权 files:delete 应 FORBIDDEN,got %q", msg)
	}
	grantPrincipal, err := e.oauth.Authenticate(ctx, tok.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	rootPath := "/oauth-made"
	if err = e.nodes.SetMCPAccessRoot(ctx, e.alice, grantPrincipal.CredentialID, "oauth", &rootPath); err != nil {
		t.Fatal(err)
	}
	rotated, err := e.oauth.Refresh(ctx, tok.RefreshToken, client.ClientID)
	if err != nil {
		t.Fatal(err)
	}
	rotatedPrincipal, err := e.oauth.Authenticate(ctx, rotated.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if rotatedPrincipal.CredentialID != grantPrincipal.CredentialID || rotatedPrincipal.TokenID == grantPrincipal.TokenID {
		t.Fatal("OAuth grant identity did not survive rotation")
	}
	rotatedSession := e.connect(t, rotated.AccessToken)
	var restricted NodeOutput
	callTool(t, rotatedSession, "create_folder", map[string]any{"name": "inside-after-refresh"}, &restricted)
	if restricted.ParentID == nil || *restricted.ParentID != folder.ID {
		t.Fatal("OAuth refresh lost directory boundary")
	}
}

// TestMCPNodeTools 直接调用工具处理函数,覆盖节点类工具的正常/错误/越权分支。
func TestMCPNodeTools(t *testing.T) {
	e := setupEnv(t)
	ctxA, ctxB := ctxFor(e.alice), ctxFor(e.bob)
	s := e.s

	// createFolder:根下、子目录、非法/不存在的 parent
	_, docs, err := s.createFolder(ctxA, nil, CreateFolderInput{Name: "docs"})
	if err != nil || docs.Kind != "folder" {
		t.Fatalf("createFolder: %+v err=%v", docs, err)
	}
	_, sub, err := s.createFolder(ctxA, nil, CreateFolderInput{ParentID: &docs.ID, Name: "sub"})
	if err != nil || sub.ParentID == nil || *sub.ParentID != docs.ID {
		t.Fatalf("createFolder(子目录): %+v err=%v", sub, err)
	}
	bad := "not-a-uuid"
	_, _, err = s.createFolder(ctxA, nil, CreateFolderInput{ParentID: &bad, Name: "x"})
	wantCode(t, err, "INVALID_INPUT")
	ghost := uuid.NewString()
	if _, _, err = s.createFolder(ctxA, nil, CreateFolderInput{ParentID: &ghost, Name: "x"}); err == nil {
		t.Fatal("parent 不存在应报错")
	}

	// listFiles:默认 NAME 排序、显式 order、非法 order、非法/不存在的 parent
	_, page, err := s.listFiles(ctxA, nil, ListFilesInput{})
	if err != nil || page.Total != 1 || page.Items[0].Name != "docs" {
		t.Fatalf("listFiles(根): %+v err=%v", page, err)
	}
	if _, page, err = s.listFiles(ctxA, nil, ListFilesInput{ParentID: &docs.ID, Order: "updated_at", Desc: true}); err != nil || page.Total != 1 {
		t.Fatalf("listFiles(order): %+v err=%v", page, err)
	}
	_, _, err = s.listFiles(ctxA, nil, ListFilesInput{Order: "bogus"})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.listFiles(ctxA, nil, ListFilesInput{ParentID: &bad})
	wantCode(t, err, "INVALID_INPUT")
	if _, _, err = s.listFiles(ctxA, nil, ListFilesInput{ParentID: &ghost}); err != service.ErrNotFound {
		t.Fatalf("列不存在的目录应 NOT_FOUND,got %v", err)
	}

	// searchFiles:命中 / 未命中
	if _, hit, err := s.searchFiles(ctxA, nil, SearchFilesInput{Query: "sub"}); err != nil || hit.Total != 1 || hit.Items[0].ID != sub.ID {
		t.Fatalf("searchFiles 命中不符:%+v err=%v", hit, err)
	}
	if _, miss, err := s.searchFiles(ctxA, nil, SearchFilesInput{Query: "nothing-here"}); err != nil || miss.Total != 0 {
		t.Fatalf("searchFiles 未命中不符:%+v err=%v", miss, err)
	}

	// getFileInfo:正常 / 非法 ID / 不存在
	if _, info, err := s.getFileInfo(ctxA, nil, NodeIDInput{NodeID: docs.ID}); err != nil || info.Kind != "folder" || info.SubtreeBytes == nil {
		t.Fatalf("getFileInfo: %+v err=%v", info, err)
	}
	_, _, err = s.getFileInfo(ctxA, nil, NodeIDInput{NodeID: "zzz"})
	wantCode(t, err, "INVALID_INPUT")
	if _, _, err = s.getFileInfo(ctxA, nil, NodeIDInput{NodeID: ghost}); err != service.ErrNotFound {
		t.Fatalf("不存在的节点应 NOT_FOUND,got %v", err)
	}

	// renameNode:成功 / 同层撞名 / 非法 ID
	if _, renamed, err := s.renameNode(ctxA, nil, RenameInput{NodeID: sub.ID, Name: "报告"}); err != nil || renamed.Name != "报告" {
		t.Fatalf("renameNode: %+v err=%v", renamed, err)
	}
	_, other, err := s.createFolder(ctxA, nil, CreateFolderInput{ParentID: &docs.ID, Name: "归档"})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = s.renameNode(ctxA, nil, RenameInput{NodeID: other.ID, Name: "报告"}); err == nil {
		t.Fatal("重命名撞同层名应被拒")
	}
	_, _, err = s.renameNode(ctxA, nil, RenameInput{NodeID: "zzz", Name: "x"})
	wantCode(t, err, "INVALID_INPUT")

	// moveNodes:移到根 / 移进自己子树 / 空 ids / 非法目标
	_, moved, err := s.moveNodes(ctxA, nil, MoveCopyInput{NodeIDs: []string{sub.ID}})
	if err != nil || len(moved.Items) != 1 || moved.Items[0].ParentID != nil {
		t.Fatalf("moveNodes 到根: %+v err=%v", moved, err)
	}
	if _, _, err = s.moveNodes(ctxA, nil, MoveCopyInput{NodeIDs: []string{docs.ID}, TargetFolderID: &other.ID}); err == nil {
		t.Fatal("移进自己的子树应被拒")
	}
	_, _, err = s.moveNodes(ctxA, nil, MoveCopyInput{NodeIDs: []string{}})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.moveNodes(ctxA, nil, MoveCopyInput{NodeIDs: []string{docs.ID}, TargetFolderID: &bad})
	wantCode(t, err, "INVALID_INPUT")

	// copyNodes:整树复制,重名自动加后缀
	_, copied, err := s.copyNodes(ctxA, nil, MoveCopyInput{NodeIDs: []string{docs.ID}})
	if err != nil || len(copied.Items) != 1 || copied.Items[0].Name != "docs (1)" {
		t.Fatalf("copyNodes: %+v err=%v", copied, err)
	}

	// trashNodes + restoreNodes:软删可还原;还原不存在的报 NOT_FOUND
	_, ok, err := s.trashNodes(ctxA, nil, NodeIDsInput{NodeIDs: []string{docs.ID}})
	if err != nil || !ok.Success {
		t.Fatalf("trashNodes: %+v err=%v", ok, err)
	}
	if _, _, err = s.getFileInfo(ctxA, nil, NodeIDInput{NodeID: docs.ID}); err != service.ErrNotFound {
		t.Fatalf("软删后应 NOT_FOUND,got %v", err)
	}
	_, back, err := s.restoreNodes(ctxA, nil, NodeIDsInput{NodeIDs: []string{docs.ID}})
	if err != nil || len(back.Items) != 1 || back.Items[0].ID != docs.ID {
		t.Fatalf("restoreNodes: %+v err=%v", back, err)
	}
	if _, _, err = s.restoreNodes(ctxA, nil, NodeIDsInput{NodeIDs: []string{ghost}}); err != service.ErrNotFound {
		t.Fatalf("还原不存在的应 NOT_FOUND,got %v", err)
	}
	_, _, err = s.trashNodes(ctxA, nil, NodeIDsInput{NodeIDs: []string{}})
	wantCode(t, err, "INVALID_INPUT")

	// 越权:bob 对 alice 的节点读 / 改 / 移 / 复制 / 还原一律 NOT_FOUND
	if _, _, err = s.getFileInfo(ctxB, nil, NodeIDInput{NodeID: docs.ID}); err != service.ErrNotFound {
		t.Fatalf("越权读应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.renameNode(ctxB, nil, RenameInput{NodeID: docs.ID, Name: "pwned"}); err == nil {
		t.Fatal("越权改名应被拒")
	}
	if _, _, err = s.moveNodes(ctxB, nil, MoveCopyInput{NodeIDs: []string{docs.ID}}); err != service.ErrNotFound {
		t.Fatalf("越权移动应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.copyNodes(ctxB, nil, MoveCopyInput{NodeIDs: []string{docs.ID}}); err != service.ErrNotFound {
		t.Fatalf("越权复制应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.restoreNodes(ctxB, nil, NodeIDsInput{NodeIDs: []string{docs.ID}}); err != service.ErrNotFound {
		t.Fatalf("越权还原应 NOT_FOUND,got %v", err)
	}
	// trashNodes 的软删按 owner_id 过滤,对他人节点静默无效:调用"成功"但不产生任何影响
	if _, ok, err := s.trashNodes(ctxB, nil, NodeIDsInput{NodeIDs: []string{docs.ID}}); err != nil || !ok.Success {
		t.Fatalf("越权 trash 静默无效路径:%+v err=%v", ok, err)
	}
	if _, _, err = s.getFileInfo(ctxA, nil, NodeIDInput{NodeID: docs.ID}); err != nil {
		t.Fatalf("越权 trash 后 alice 的节点必须原样健在:%v", err)
	}
	// bob 的视角看不到 alice 的任何东西
	if _, page, err := s.listFiles(ctxB, nil, ListFilesInput{}); err != nil || page.Total != 0 {
		t.Fatalf("bob 根目录应为空:%+v err=%v", page, err)
	}
	if _, hit, err := s.searchFiles(ctxB, nil, SearchFilesInput{Query: "docs"}); err != nil || hit.Total != 0 {
		t.Fatalf("bob 搜索不应命中 alice 的文件:%+v err=%v", hit, err)
	}
}

// TestMCPUploadTools 覆盖上传五件套 + read_text_file:
// 单 PUT 全流程(含服务端 finalize)、秒传、multipart 分片签名、abort、错误与越权。
func TestMCPUploadTools(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	ctxA, ctxB := ctxFor(e.alice), ctxFor(e.bob)
	s := e.s

	// —— 参数校验 ——
	_, _, err := s.prepareUpload(ctxA, nil, PrepareUploadInput{Name: "a.txt", Size: 0})
	wantCode(t, err, "INVALID_INPUT")
	bad := "zzz"
	_, _, err = s.prepareUpload(ctxA, nil, PrepareUploadInput{ParentID: &bad, Name: "a.txt", Size: 1})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.prepareUpload(ctxA, nil, PrepareUploadInput{Name: "a.txt", Size: 1, Transport: "carrier-pigeon"})
	wantCode(t, err, "INVALID_INPUT")
	badSha := "XYZ"
	_, _, err = s.prepareUpload(ctxA, nil, PrepareUploadInput{Name: "a.txt", Size: 1, SHA256: &badSha})
	wantCode(t, err, "INVALID_INPUT")

	// —— 单 PUT 全流程 ——
	data := []byte("gopan MCP 上传集成测试文本\n第二行 hello")
	_, up, err := s.prepareUpload(ctxA, nil, PrepareUploadInput{
		Name: "note.txt", Size: int64(len(data)), ContentType: "text/plain", Transport: "single_put",
	})
	if err != nil || up.Mode != "single_put" || up.Status != "uploading" || up.PutURL == "" || up.UploadID == "" {
		t.Fatalf("prepareUpload(single_put): %+v err=%v", up, err)
	}
	httpPutBytes(t, up.PutURL, data)
	_, done, err := s.completeUpload(ctxA, nil, UploadPartsInput{UploadID: up.UploadID})
	if err != nil || done.Status != "finalizing" {
		t.Fatalf("completeUpload: %+v err=%v", done, err)
	}
	// 测试进程里没有后台 worker,手动驱动状态机定稿
	if processed, err := e.uploads.ProcessNextAgentTransfer(ctx); err != nil || !processed {
		t.Fatalf("定稿任务未处理:processed=%v err=%v", processed, err)
	}
	_, st, err := s.getUploadStatus(ctxA, nil, UploadPartsInput{UploadID: up.UploadID})
	if err != nil || st.Status != "verifying" || st.SHA256 == nil || st.Node == nil {
		t.Fatalf("getUploadStatus(定稿后): %+v err=%v", st, err)
	}
	if *st.SHA256 != sha256Hex(data) {
		t.Fatalf("服务端 sha 不符:%s", *st.SHA256)
	}
	nodeID := st.Node.ID
	// 模拟病毒扫描/校验完成 → ready
	blob, err := e.q.GetBlobBySha256(ctx, *st.SHA256)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.q.MarkBlobVerified(ctx, blob.ID); err != nil {
		t.Fatal(err)
	}
	if err := e.q.MarkTransferReadyBySha(ctx, st.SHA256); err != nil {
		t.Fatal(err)
	}
	if _, st, err = s.getUploadStatus(ctxA, nil, UploadPartsInput{UploadID: up.UploadID}); err != nil || st.Status != "ready" {
		t.Fatalf("校验后应 ready:%+v err=%v", st, err)
	}

	// —— 秒传:同 sha 再传 → instant ——
	sha := sha256Hex(data)
	_, instant, err := s.prepareUpload(ctxA, nil, PrepareUploadInput{
		Name: "note-copy.txt", Size: int64(len(data)), SHA256: &sha,
	})
	if err != nil || instant.Mode != "instant" || instant.Status != "ready" || instant.Node == nil {
		t.Fatalf("秒传不符:%+v err=%v", instant, err)
	}

	// —— read_text_file:全量、偏移、越界 EOF、超限 max_bytes、目录、越权 ——
	_, txt, err := s.readTextFile(ctxA, nil, ReadTextInput{NodeID: nodeID})
	if err != nil || txt.Text != string(data) || !txt.EOF || txt.NextOffset != int64(len(data)) {
		t.Fatalf("readTextFile 全量:%+v err=%v", txt, err)
	}
	_, part, err := s.readTextFile(ctxA, nil, ReadTextInput{NodeID: nodeID, Offset: 5, MaxBytes: 4})
	if err != nil || part.Text != string(data[5:9]) || part.EOF || part.Offset != 5 || part.NextOffset != 9 {
		t.Fatalf("readTextFile 偏移:%+v err=%v", part, err)
	}
	if _, eof, err := s.readTextFile(ctxA, nil, ReadTextInput{NodeID: nodeID, Offset: int64(len(data)) + 10}); err != nil || !eof.EOF || eof.Text != "" {
		t.Fatalf("偏移越界应空 EOF:%+v err=%v", eof, err)
	}
	_, _, err = s.readTextFile(ctxA, nil, ReadTextInput{NodeID: nodeID, MaxBytes: 65537})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.readTextFile(ctxA, nil, ReadTextInput{NodeID: "zzz"})
	wantCode(t, err, "INVALID_INPUT")
	folder, err := e.nodes.CreateFolder(ctx, e.alice, nil, "dir")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = s.readTextFile(ctxA, nil, ReadTextInput{NodeID: folder.ID.String()}); err != service.ErrNotFound {
		t.Fatalf("读目录应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.readTextFile(ctxB, nil, ReadTextInput{NodeID: nodeID}); err != service.ErrNotFound {
		t.Fatalf("越权读文本应 NOT_FOUND,got %v", err)
	}

	// —— multipart:分片签名、缺片 complete、abort ——
	big := int64(6 << 20) // 5MiB partSize → 2 片
	_, mp, err := s.prepareUpload(ctxA, nil, PrepareUploadInput{
		Name: "big.bin", Size: big, Transport: "multipart",
	})
	if err != nil || mp.Mode != "multipart" || mp.TotalParts != 2 || len(mp.PartURLs) != 2 {
		t.Fatalf("prepareUpload(multipart): %+v err=%v", mp, err)
	}
	_, parts, err := s.getUploadParts(ctxA, nil, UploadPartsInput{UploadID: mp.UploadID, FirstPart: 2, Limit: 1})
	if err != nil || len(parts.PartURLs) != 1 || parts.PartURLs[0].PartNumber != 2 {
		t.Fatalf("getUploadParts 应只签第 2 片:%+v err=%v", parts, err)
	}
	_, _, err = s.completeUpload(ctxA, nil, UploadPartsInput{UploadID: mp.UploadID})
	wantCode(t, err, "MISSING_PART")
	_, aborted, err := s.abortUpload(ctxA, nil, UploadPartsInput{UploadID: mp.UploadID})
	if err != nil || !aborted.Success {
		t.Fatalf("abortUpload: %+v err=%v", aborted, err)
	}
	if _, st, err := s.getUploadStatus(ctxA, nil, UploadPartsInput{UploadID: mp.UploadID}); err != nil || st.Status != "aborted" {
		t.Fatalf("abort 后应 aborted:%+v err=%v", st, err)
	}
	// abort 幂等:非 uploading 状态下再次 abort 直接成功
	if _, again, err := s.abortUpload(ctxA, nil, UploadPartsInput{UploadID: mp.UploadID}); err != nil || !again.Success {
		t.Fatalf("重复 abort 应幂等成功:%+v err=%v", again, err)
	}

	// —— 错误与越权 ——
	_, _, err = s.getUploadStatus(ctxA, nil, UploadPartsInput{UploadID: "zzz"})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.getUploadParts(ctxA, nil, UploadPartsInput{UploadID: "zzz"})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.completeUpload(ctxA, nil, UploadPartsInput{UploadID: "zzz"})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.abortUpload(ctxA, nil, UploadPartsInput{UploadID: "zzz"})
	wantCode(t, err, "INVALID_INPUT")
	if _, _, err = s.getUploadStatus(ctxA, nil, UploadPartsInput{UploadID: uuid.NewString()}); err != service.ErrNotFound {
		t.Fatalf("不存在的上传应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.getUploadStatus(ctxB, nil, UploadPartsInput{UploadID: up.UploadID}); err != service.ErrNotFound {
		t.Fatalf("越权查上传应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.abortUpload(ctxB, nil, UploadPartsInput{UploadID: up.UploadID}); err != service.ErrNotFound {
		t.Fatalf("越权 abort 应 NOT_FOUND,got %v", err)
	}
}

// TestMCPPrepareDownload 覆盖单文件直链与文件夹/多选 ZIP 票据两条路。
func TestMCPPrepareDownload(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	ctxA, ctxB := ctxFor(e.alice), ctxFor(e.bob)
	s := e.s

	// 直写 blob + 节点:pack/{a.txt},根下 b.txt
	mkblob := func(content []byte) uuid.UUID {
		sha := sha256Hex(content)
		if err := e.obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader(content), int64(len(content)), "text/plain"); err != nil {
			t.Fatal(err)
		}
		b, err := e.q.UpsertBlob(ctx, store.UpsertBlobParams{
			ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: int64(len(content)), Mime: "text/plain",
		})
		if err != nil {
			t.Fatal(err)
		}
		return b.ID
	}
	mknode := func(parent *uuid.UUID, name, kind string, blobID *uuid.UUID) store.Node {
		n, err := e.q.CreateNode(ctx, store.CreateNodeParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: e.alice, ParentID: parent, Name: name, Kind: kind, BlobID: blobID,
		})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	aData, bData := []byte("pack-file-a"), []byte("standalone-b 内容")
	aBlob, bBlob := mkblob(aData), mkblob(bData)
	pack := mknode(nil, "pack", "folder", nil)
	mknode(&pack.ID, "a.txt", "file", &aBlob)
	bFile := mknode(nil, "b.txt", "file", &bBlob)

	// 单文件 → 预签名直链,字节能取回
	one := bFile.ID.String()
	_, dl, err := s.prepareDownload(ctxA, nil, PrepareDownloadInput{NodeID: &one})
	if err != nil || dl.Method != "GET" || dl.Archive || dl.Filename != "b.txt" ||
		dl.Size != int64(len(bData)) || dl.ContentType != "text/plain" || dl.ExpiresIn != 15*60 {
		t.Fatalf("prepareDownload(单文件): %+v err=%v", dl, err)
	}
	resp, err := http.Get(dl.URL)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !bytes.Equal(got, bData) {
		t.Fatalf("直链下载不符:code=%d body=%q", resp.StatusCode, got)
	}

	// 文件夹 → ZIP 票据:URL 挂在请求 baseURL 下,票据限定用户与节点
	_, zipOut, err := s.prepareDownload(ctxA, nil, PrepareDownloadInput{NodeIDs: []string{pack.ID.String()}})
	if err != nil || !zipOut.Archive || zipOut.ContentType != "application/zip" ||
		zipOut.Filename != "pack.zip" || zipOut.Size != int64(len(aData)) || zipOut.ExpiresIn != 10*60 {
		t.Fatalf("prepareDownload(文件夹): %+v err=%v", zipOut, err)
	}
	prefix := testBaseURL + "/mcp-download/"
	if !strings.HasPrefix(zipOut.URL, prefix) {
		t.Fatalf("ZIP URL 应挂在 baseURL 下:%q", zipOut.URL)
	}
	raw, err := url.PathUnescape(strings.TrimPrefix(zipOut.URL, prefix))
	if err != nil {
		t.Fatal(err)
	}
	ident, ids, err := e.tickets.Parse(raw)
	if err != nil || ident.UserID != e.alice || len(ids) != 1 || ids[0] != pack.ID {
		t.Fatalf("票据内容不符:ident=%+v ids=%v err=%v", ident, ids, err)
	}

	// 多节点(单文件 + 文件夹混合)也走 ZIP
	_, multi, err := s.prepareDownload(ctxA, nil, PrepareDownloadInput{
		NodeIDs: []string{pack.ID.String(), bFile.ID.String()},
	})
	if err != nil || !multi.Archive || multi.Size != int64(len(aData)+len(bData)) {
		t.Fatalf("prepareDownload(多选): %+v err=%v", multi, err)
	}

	// 错误路径:空输入、非法 ID、不存在
	_, _, err = s.prepareDownload(ctxA, nil, PrepareDownloadInput{})
	wantCode(t, err, "INVALID_INPUT")
	_, _, err = s.prepareDownload(ctxA, nil, PrepareDownloadInput{NodeIDs: []string{"zzz"}})
	wantCode(t, err, "INVALID_INPUT")
	if _, _, err = s.prepareDownload(ctxA, nil, PrepareDownloadInput{NodeIDs: []string{uuid.NewString()}}); err != service.ErrNotFound {
		t.Fatalf("不存在的节点应 NOT_FOUND,got %v", err)
	}

	// 越权:bob 拿不到 alice 的直链和 ZIP 票据
	if _, _, err = s.prepareDownload(ctxB, nil, PrepareDownloadInput{NodeID: &one}); err != service.ErrNotFound {
		t.Fatalf("越权单文件下载应 NOT_FOUND,got %v", err)
	}
	if _, _, err = s.prepareDownload(ctxB, nil, PrepareDownloadInput{NodeIDs: []string{pack.ID.String()}}); err != service.ErrNotFound {
		t.Fatalf("越权 ZIP 打包应 NOT_FOUND,got %v", err)
	}
}
