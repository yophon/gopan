package httpx_test

// ShareLanding / PackHandler / MCPPackHandler 集成测试:真 Postgres + 真 MinIO,
// 环境变量未设置时跳过(与 internal/dav、internal/service 同一套约定):
//   TEST_DB_URL='postgres://gopan:gopan@127.0.0.1:5433/gopan_test?sslmode=disable' \
//   TEST_S3_ENDPOINT=127.0.0.1:9000 go test ./internal/httpx/

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"sort"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	gdb "github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// setupPool 重建 schema 并跑迁移,TEST_DB_URL 未设置时跳过。
func setupPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dbURL := os.Getenv("TEST_DB_URL")
	if dbURL == "" {
		t.Skip("TEST_DB_URL 未设置,跳过 httpx 集成测试")
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
	return pool
}

// setupObj 连真 MinIO,TEST_S3_ENDPOINT 未设置时跳过。
func setupObj(t *testing.T) *objstore.Store {
	t.Helper()
	ep := os.Getenv("TEST_S3_ENDPOINT")
	if ep == "" {
		t.Skip("TEST_S3_ENDPOINT 未设置,跳过 MinIO 集成测试")
	}
	obj, err := objstore.New(&config.Config{
		S3Endpoint: ep, S3PublicEndpoint: ep,
		S3Key: envOr("TEST_S3_KEY", "gopan"), S3Secret: envOr("TEST_S3_SECRET", "gopan-minio-dev"),
		S3Bucket: "gopan-test", S3Region: "us-east-1",
		PresignPutTTL: time.Hour, PresignGetTTL: 15 * time.Minute,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := obj.EnsureBucket(context.Background()); err != nil {
		t.Fatal(err)
	}
	return obj
}

const testSecret = "test-secret-test-secret-test-secret"

func newTestAuth(pool *pgxpool.Pool) *service.Auth {
	return service.NewAuth(store.New(pool), []byte(testSecret),
		15*time.Minute, 14*24*time.Hour, true, 1<<30)
}

// spaIndex 最小可注入 og 标签的 index.html。
var spaIndex = fstest.MapFS{
	"index.html": &fstest.MapFile{Data: []byte("<html><head></head><body>gopan-spa</body></html>")},
}

func TestShareLanding(t *testing.T) {
	pool := setupPool(t)
	ctx := context.Background()
	q := store.New(pool)
	auth := newTestAuth(pool)
	shares := service.NewShares(q, auth)

	res, err := auth.Register(ctx, "landing_user", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	mknode := func(name, kind string) store.Node {
		n, err := q.CreateNode(ctx, store.CreateNodeParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: nil, Name: name, Kind: kind,
		})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	// 名字带 <> 和引号,顺带验证 og 标签的 HTML 转义
	file := mknode(`报告<v1>&"final".pdf`, "file")
	folder := mknode("photos", "folder")

	fileShare, err := shares.Create(ctx, owner, file.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	folderShare, err := shares.Create(ctx, owner, folder.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	deadShare, err := shares.Create(ctx, owner, file.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := shares.Revoke(ctx, owner, deadShare.ID); err != nil {
		t.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.Handle("GET /s/{token}", httpx.ShareLanding(spaIndex, shares))
	get := func(token string) (int, string, string) {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest("GET", "/s/"+token, nil))
		return rec.Code, rec.Body.String(), rec.Header().Get("Content-Type")
	}

	// 文件分享:og:title 是转义后的文件名,描述是"文件"
	code, body, ct := get(fileShare.Token)
	if code != 200 || !strings.Contains(ct, "text/html") {
		t.Fatalf("落地页应 200 html:code=%d ct=%q", code, ct)
	}
	wantTitle := `content="报告&lt;v1&gt;&amp;&#34;final&#34;.pdf"`
	if !strings.Contains(body, "og:title") || !strings.Contains(body, wantTitle) {
		t.Fatalf("og:title 应为转义后的文件名,body=%q", body)
	}
	if !strings.Contains(body, "分享的文件<") && !strings.Contains(body, "分享的文件\"") {
		t.Fatalf("文件分享描述应含“分享的文件”,body=%q", body)
	}
	if !strings.Contains(body, "gopan-spa") {
		t.Fatal("注入 og 后应保留原 index 内容,浏览器照常加载 SPA")
	}

	// 文件夹分享:描述换成"文件夹"
	if _, body, _ := get(folderShare.Token); !strings.Contains(body, "分享的文件夹") {
		t.Fatalf("文件夹分享描述不符,body=%q", body)
	}

	// 已撤销分享:标题/描述换成失效文案(仍 200,前端再渲染细节)
	if _, body, _ := get(deadShare.Token); !strings.Contains(body, "分享已失效") || !strings.Contains(body, "已过期或被取消") {
		t.Fatalf("失效分享文案不符,body=%q", body)
	}

	// 不存在的 token:原样出 index,不注入 og 标签
	if _, body, _ := get("zzzzzzzzzz"); strings.Contains(body, "og:title") || !strings.Contains(body, "gopan-spa") {
		t.Fatalf("未知 token 不应注入 og 标签,body=%q", body)
	}

	// 前端未构建:404 + 提示
	rec := httptest.NewRecorder()
	mux2 := http.NewServeMux()
	mux2.Handle("GET /s/{token}", httpx.ShareLanding(fstest.MapFS{}, shares))
	mux2.ServeHTTP(rec, httptest.NewRequest("GET", "/s/"+fileShare.Token, nil))
	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "前端未构建") {
		t.Fatalf("缺 index.html 应 404:code=%d body=%q", rec.Code, rec.Body.String())
	}
}

// packEnv 打包测试共用夹具:用户 + root/{a.txt, sub/b.txt} 树,字节已进 MinIO。
type packEnv struct {
	q      *store.Queries
	auth   *service.Auth
	shares *service.Shares
	packer *service.Packer
	owner  uuid.UUID
	token  string // 属主 access token
	root   store.Node
	aFile  store.Node
	aData  []byte
	bData  []byte
}

func newPackEnv(t *testing.T) *packEnv {
	t.Helper()
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := context.Background()
	q := store.New(pool)
	auth := newTestAuth(pool)
	shares := service.NewShares(q, auth)
	packer := service.NewPacker(q, obj, shares)
	packer.SetRateLimit(time.Millisecond, 100000) // 限速单独用新 packer 测

	res, err := auth.Register(ctx, "pack_user", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	token, err := auth.IssueAccess(owner, service.ScopeUser)
	if err != nil {
		t.Fatal(err)
	}

	mkblob := func(content []byte) uuid.UUID {
		h := sha256.Sum256(content)
		sha := hex.EncodeToString(h[:])
		if err := obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader(content), int64(len(content)), "text/plain"); err != nil {
			t.Fatal(err)
		}
		b, err := q.UpsertBlob(ctx, store.UpsertBlobParams{
			ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: int64(len(content)), Mime: "text/plain",
		})
		if err != nil {
			t.Fatal(err)
		}
		return b.ID
	}
	mknode := func(parent *uuid.UUID, name, kind string, blobID *uuid.UUID) store.Node {
		n, err := q.CreateNode(ctx, store.CreateNodeParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent, Name: name, Kind: kind, BlobID: blobID,
		})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	aData, bData := []byte("pack-content-a"), []byte("pack-content-bb")
	aBlob, bBlob := mkblob(aData), mkblob(bData)
	root := mknode(nil, "root", "folder", nil)
	aFile := mknode(&root.ID, "a.txt", "file", &aBlob)
	sub := mknode(&root.ID, "sub", "folder", nil)
	mknode(&sub.ID, "b.txt", "file", &bBlob)

	return &packEnv{
		q: q, auth: auth, shares: shares, packer: packer,
		owner: owner, token: token, root: root, aFile: aFile, aData: aData, bData: bData,
	}
}

// unzipNames 解 zip 并返回排序后的条目名。
func unzipNames(t *testing.T, data []byte) ([]string, *zip.Reader) {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("响应不是合法 zip:%v", err)
	}
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
	}
	sort.Strings(names)
	return names, zr
}

func TestPackHandlerIntegration(t *testing.T) {
	e := newPackEnv(t)
	h := httpx.PackHandler(e.auth, e.packer)
	do := func(target string, hdr map[string]string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("GET", target, nil)
		for k, v := range hdr {
			req.Header.Set(k, v)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}

	// 打包整个文件夹(token 走查询串):zip 名、结构、字节全对
	rec := do("/pack?nodes="+e.root.ID.String()+"&token="+e.token, nil)
	if rec.Code != 200 {
		t.Fatalf("打包应 200,got %d body=%q", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/zip" {
		t.Fatalf("Content-Type 应为 zip,got %q", ct)
	}
	if cd := rec.Header().Get("Content-Disposition"); !strings.Contains(cd, "attachment") || !strings.Contains(cd, "root.zip") {
		t.Fatalf("Content-Disposition 不符:%q", cd)
	}
	names, zr := unzipNames(t, rec.Body.Bytes())
	want := []string{"root/", "root/a.txt", "root/sub/", "root/sub/b.txt"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Fatalf("zip 结构 %v want %v", names, want)
	}
	rc, err := zr.Open("root/sub/b.txt")
	if err != nil {
		t.Fatal(err)
	}
	gotB, _ := io.ReadAll(rc)
	rc.Close()
	if !bytes.Equal(gotB, e.bData) {
		t.Fatal("zip 内文件字节不符")
	}

	// 单文件打包 + token 走 Authorization 头(且头优先于查询串的坏 token)
	rec = do("/pack?nodes="+e.aFile.ID.String()+"&token=garbage",
		map[string]string{"Authorization": "Bearer " + e.token})
	if rec.Code != 200 {
		t.Fatalf("Bearer 头应可用且优先,got %d body=%q", rec.Code, rec.Body.String())
	}
	if names, _ := unzipNames(t, rec.Body.Bytes()); strings.Join(names, ",") != "a.txt" {
		t.Fatalf("单文件 zip 应只含 a.txt,got %v", names)
	}
	if cd := rec.Header().Get("Content-Disposition"); !strings.Contains(cd, "a.txt.zip") {
		t.Fatalf("单文件 zip 名应取自文件名:%q", cd)
	}

	// 非法节点 ID → 400
	if rec := do("/pack?nodes=not-a-uuid&token="+e.token, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 ID 应 400,got %d", rec.Code)
	}
	// 空 nodes → 400(INVALID_INPUT)
	if rec := do("/pack?nodes=&token="+e.token, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("空 nodes 应 400,got %d body=%q", rec.Code, rec.Body.String())
	}
	// 不存在的节点 → 404
	if rec := do("/pack?nodes="+uuid.Must(uuid.NewV7()).String()+"&token="+e.token, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("不存在节点应 404,got %d", rec.Code)
	}

	// 访客 token 指向已撤销的分享 → 410 SHARE_EXPIRED
	ctx := context.Background()
	sh, err := e.shares.Create(ctx, e.owner, e.root.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	guestTok, err := e.auth.IssueAccessFor(e.owner, "share:"+sh.ID.String(), 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.shares.Revoke(ctx, e.owner, sh.ID); err != nil {
		t.Fatal(err)
	}
	if rec := do("/pack?nodes="+e.root.ID.String()+"&token="+guestTok, nil); rec.Code != http.StatusGone {
		t.Fatalf("失效分享打包应 410,got %d body=%q", rec.Code, rec.Body.String())
	}

	// 非法 scope(既不是 user 也不是 share:{id})→ 403 FORBIDDEN
	weirdTok, err := e.auth.IssueAccessFor(e.owner, "bogus-scope", 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if rec := do("/pack?nodes="+e.root.ID.String()+"&token="+weirdTok, nil); rec.Code != http.StatusForbidden {
		t.Fatalf("非法 scope 应 403,got %d body=%q", rec.Code, rec.Body.String())
	}
}

func TestPackHandlerRateLimit(t *testing.T) {
	e := newPackEnv(t)
	e.packer.SetRateLimit(time.Hour, 1) // 一小时才回填一个,突发 1
	h := httpx.PackHandler(e.auth, e.packer)

	req := httptest.NewRequest("GET", "/pack?nodes="+e.aFile.ID.String()+"&token="+e.token, nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 200 {
		t.Fatalf("第一次打包应 200,got %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/pack?nodes="+e.aFile.ID.String()+"&token="+e.token, nil))
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("超限应 429,got %d", rec.Code)
	}
	if rec.Header().Get("Retry-After") != "30" {
		t.Fatalf("429 应带 Retry-After: 30,got %q", rec.Header().Get("Retry-After"))
	}
}

func TestMCPPackHandler(t *testing.T) {
	e := newPackEnv(t)
	tickets := service.NewPackTickets([]byte(testSecret), 10*time.Minute)
	mux := http.NewServeMux()
	mux.Handle("GET /mcp-download/{ticket}", httpx.MCPPackHandler(tickets, e.packer))
	do := func(ticket string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest("GET", "/mcp-download/"+ticket, nil))
		return rec
	}

	// 合法票据:下载指定节点的 zip
	ticket, err := tickets.Issue(e.owner, []uuid.UUID{e.aFile.ID})
	if err != nil {
		t.Fatal(err)
	}
	rec := do(ticket)
	if rec.Code != 200 || rec.Header().Get("Content-Type") != "application/zip" {
		t.Fatalf("合法票据应 200 zip,got %d body=%q", rec.Code, rec.Body.String())
	}
	names, zr := unzipNames(t, rec.Body.Bytes())
	if strings.Join(names, ",") != "a.txt" {
		t.Fatalf("票据 zip 应只含 a.txt,got %v", names)
	}
	rc, _ := zr.Open("a.txt")
	gotA, _ := io.ReadAll(rc)
	rc.Close()
	if !bytes.Equal(gotA, e.aData) {
		t.Fatal("票据下载字节不符")
	}

	// 垃圾票据 → 401
	if rec := do("garbage-ticket"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("垃圾票据应 401,got %d", rec.Code)
	}
	// 过期票据 → 401
	expired, err := service.NewPackTickets([]byte(testSecret), -time.Minute).Issue(e.owner, []uuid.UUID{e.aFile.ID})
	if err != nil {
		t.Fatal(err)
	}
	if rec := do(expired); rec.Code != http.StatusUnauthorized {
		t.Fatalf("过期票据应 401,got %d", rec.Code)
	}
	// 换密钥签的票据 → 401(票据与登录 JWT 隔离,不能互换)
	forged, err := service.NewPackTickets([]byte("another-secret-another-secret-32"), 10*time.Minute).Issue(e.owner, []uuid.UUID{e.aFile.ID})
	if err != nil {
		t.Fatal(err)
	}
	if rec := do(forged); rec.Code != http.StatusUnauthorized {
		t.Fatalf("伪造票据应 401,got %d", rec.Code)
	}
}
