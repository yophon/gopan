package oauthserver

// 集成测试:需要一个可用的 Postgres,通过 TEST_DB_URL 传入,例如
//   TEST_DB_URL='postgres://gopan:gopan@127.0.0.1:5433/gopan_test?sslmode=disable' go test ./internal/oauthserver/
// 未设置时整包跳过。每次运行前重建 schema,不依赖 S3。
//
// 覆盖 OAuth 2.1 授权服务器的 HTTP 语义:
//   - 动态客户端注册(RFC 7591)的合法/非法请求
//   - /oauth/authorize 的入参校验(PKCE S256 强制、redirect_uri 白名单、response_type)
//   - /oauth/token 的授权码兑换(单次使用、client_id/redirect_uri/code_verifier 绑定)
//   - refresh_token 旋转与重放拒绝
//   - 受保护资源元数据端点

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	"github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// 与 service_test 一致的 PKCE 素材:verifier 固定,challenge = BASE64URL(SHA256(verifier))
const testVerifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"

func testChallenge() string {
	digest := sha256.Sum256([]byte(testVerifier))
	return base64.RawURLEncoding.EncodeToString(digest[:])
}

// setupDB 重建 schema 并跑 goose 迁移,返回连接池;未设置 TEST_DB_URL 时跳过
func setupDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dbURL := os.Getenv("TEST_DB_URL")
	if dbURL == "" {
		t.Skip("TEST_DB_URL 未设置,跳过集成测试")
	}
	sqlDB, err := sql.Open("pgx", dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		t.Fatal(err)
	}
	goose.SetBaseFS(db.Migrations)
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

// setupHTTP 按 cmd/gopan/main.go 的路由挂法起一个真实 httptest 服务
func setupHTTP(t *testing.T, pool *pgxpool.Pool) (*httptest.Server, *service.OAuth) {
	t.Helper()
	oauth := service.NewOAuth(store.New(pool))
	srv := New(oauth)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /.well-known/oauth-authorization-server", srv.AuthorizationMetadata)
	mux.HandleFunc("GET /.well-known/oauth-protected-resource", srv.ProtectedResourceMetadata)
	mux.HandleFunc("GET /oauth/authorize", srv.Authorize)
	mux.HandleFunc("POST /oauth/token", srv.Token)
	mux.HandleFunc("POST /oauth/register", srv.Register)
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return ts, oauth
}

// noRedirectClient 不跟随 302,方便断言 Location
func noRedirectClient() *http.Client {
	return &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
}

func decodeJSONBody(t *testing.T, resp *http.Response) map[string]any {
	t.Helper()
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("非法 JSON 响应: %v body=%q", err, raw)
	}
	return body
}

// postJSON 向注册端点发 JSON
func postJSON(t *testing.T, ts *httptest.Server, path, payload string) *http.Response {
	t.Helper()
	resp, err := http.Post(ts.URL+path, "application/json", strings.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

// registerTestClient 通过 HTTP 端点注册一个合法公共客户端,返回 client_id
func registerTestClient(t *testing.T, ts *httptest.Server, redirectURI string) string {
	t.Helper()
	resp := postJSON(t, ts, "/oauth/register",
		`{"client_name":"Codex CLI","redirect_uris":["`+redirectURI+`"]}`)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("注册客户端失败 status=%d", resp.StatusCode)
	}
	body := decodeJSONBody(t, resp)
	clientID, _ := body["client_id"].(string)
	if clientID == "" {
		t.Fatalf("注册响应缺 client_id: %+v", body)
	}
	return clientID
}

var testUserSeq atomic.Int64

// issueCode 走用户同意流程(GraphQL 侧的 service.OAuth.Authorize)签发授权码。
// oauthserver 包只暴露到 /oauth/consent 的跳转,code 签发在 consent 提交后,
// 所以这里直接调 service 层拿 code,再用 HTTP /oauth/token 兑换。
func issueCode(t *testing.T, pool *pgxpool.Pool, oauth *service.OAuth, clientID, redirectURI string, scopes []string) string {
	t.Helper()
	ctx := context.Background()
	auth := service.NewAuth(store.New(pool), []byte("test-secret-test-secret-test-secret"),
		15*time.Minute, 14*24*time.Hour, true, 1<<30)
	registered, err := auth.Register(ctx,
		"oauth_http_user_"+strconv.FormatInt(testUserSeq.Add(1), 10), "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	redirect, err := oauth.Authorize(ctx, registered.User.ID, service.OAuthAuthorizationInput{
		ClientID: clientID, RedirectURI: redirectURI, ResponseType: "code",
		Scopes: scopes, State: "st", CodeChallenge: testChallenge(), CodeChallengeMethod: "S256",
	}, true)
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(redirect)
	if err != nil || u.Query().Get("code") == "" {
		t.Fatalf("授权回调缺 code: %q err=%v", redirect, err)
	}
	return u.Query().Get("code")
}

// postToken 发 token 请求并返回状态码 + JSON body
func postToken(t *testing.T, ts *httptest.Server, form url.Values) (int, map[string]any) {
	t.Helper()
	resp, err := http.PostForm(ts.URL+"/oauth/token", form)
	if err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode, decodeJSONBody(t, resp)
}

// ---------------------------------------------------------------------------
// 元数据端点(无需 DB)
// ---------------------------------------------------------------------------

func TestProtectedResourceMetadata(t *testing.T) {
	server := New(service.NewOAuth(nil))
	req := httptest.NewRequest(http.MethodGet, "http://internal/.well-known/oauth-protected-resource", nil)
	req.Header.Set("X-Forwarded-Proto", "https, http") // 多级代理:只取第一个
	req.Header.Set("X-Forwarded-Host", "pan.example.com")
	rec := httptest.NewRecorder()
	server.ProtectedResourceMetadata(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d", rec.Code)
	}
	var body struct {
		Resource               string   `json:"resource"`
		AuthorizationServers   []string `json:"authorization_servers"`
		BearerMethodsSupported []string `json:"bearer_methods_supported"`
		ScopesSupported        []string `json:"scopes_supported"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Resource != "https://pan.example.com/mcp" {
		t.Fatalf("resource 不符: %q", body.Resource)
	}
	if len(body.AuthorizationServers) != 1 || body.AuthorizationServers[0] != "https://pan.example.com" {
		t.Fatalf("authorization_servers 不符: %v", body.AuthorizationServers)
	}
	if len(body.BearerMethodsSupported) != 1 || body.BearerMethodsSupported[0] != "header" {
		t.Fatalf("bearer_methods_supported 不符: %v", body.BearerMethodsSupported)
	}
	if len(body.ScopesSupported) != len(service.MCPScopes) {
		t.Fatalf("scopes_supported 不符: %v", body.ScopesSupported)
	}
}

func TestProtectedResourceMetadataDefaultsToRequestHost(t *testing.T) {
	// 无转发头时应回落到请求本身的 scheme/host
	server := New(service.NewOAuth(nil))
	req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8080/.well-known/oauth-protected-resource", nil)
	rec := httptest.NewRecorder()
	server.ProtectedResourceMetadata(rec, req)
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["resource"] != "http://127.0.0.1:8080/mcp" {
		t.Fatalf("resource 应用请求 host: %v", body["resource"])
	}
}

func TestProtectedResourceMetadataDirectTLS(t *testing.T) {
	// 直连 TLS(无反代转发头)时 scheme 应为 https
	server := New(service.NewOAuth(nil))
	req := httptest.NewRequest(http.MethodGet, "https://pan.example.com/.well-known/oauth-protected-resource", nil)
	rec := httptest.NewRecorder()
	server.ProtectedResourceMetadata(rec, req)
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["resource"] != "https://pan.example.com/mcp" {
		t.Fatalf("直连 TLS 应产出 https base: %v", body["resource"])
	}
}

// ---------------------------------------------------------------------------
// 动态客户端注册 POST /oauth/register
// ---------------------------------------------------------------------------

func TestRegisterEndpointHTTP(t *testing.T) {
	pool := setupDB(t)
	ts, _ := setupHTTP(t, pool)

	t.Run("合法注册返回 201 且元数据完整", func(t *testing.T) {
		resp := postJSON(t, ts, "/oauth/register",
			`{"client_name":"Codex","redirect_uris":["http://127.0.0.1:49152/cb","https://agent.example/cb","http://127.0.0.1:49152/cb"],"token_endpoint_auth_method":"none"}`)
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("status=%d", resp.StatusCode)
		}
		body := decodeJSONBody(t, resp)
		clientID, _ := body["client_id"].(string)
		if !strings.HasPrefix(clientID, "gopan_client_") {
			t.Fatalf("client_id 前缀不符: %q", clientID)
		}
		if body["client_name"] != "Codex" || body["token_endpoint_auth_method"] != "none" {
			t.Fatalf("客户端元数据不符: %+v", body)
		}
		// 重复的 redirect_uri 应去重
		uris, _ := body["redirect_uris"].([]any)
		if len(uris) != 2 {
			t.Fatalf("redirect_uris 应去重为 2 个: %v", uris)
		}
		if _, ok := body["client_id_issued_at"].(float64); !ok {
			t.Fatalf("缺 client_id_issued_at: %+v", body)
		}
		grants, _ := body["grant_types"].([]any)
		if len(grants) != 2 || grants[0] != "authorization_code" || grants[1] != "refresh_token" {
			t.Fatalf("grant_types 不符: %v", grants)
		}
	})

	t.Run("非法请求一律 400 invalid_client_metadata", func(t *testing.T) {
		cases := []struct {
			name    string
			payload string
		}{
			{"非法 JSON", `{not json`},
			{"要求机密客户端认证", `{"client_name":"x","redirect_uris":["https://a.example/cb"],"token_endpoint_auth_method":"client_secret_basic"}`},
			{"client_name 为空", `{"client_name":"  ","redirect_uris":["https://a.example/cb"]}`},
			{"client_name 超 80 字节", `{"client_name":"` + strings.Repeat("a", 81) + `","redirect_uris":["https://a.example/cb"]}`},
			{"缺 redirect_uris", `{"client_name":"x","redirect_uris":[]}`},
			{"redirect_uris 超过 10 个", `{"client_name":"x","redirect_uris":["https://a.example/1","https://a.example/2","https://a.example/3","https://a.example/4","https://a.example/5","https://a.example/6","https://a.example/7","https://a.example/8","https://a.example/9","https://a.example/10","https://a.example/11"]}`},
			{"HTTP 非 loopback 回调", `{"client_name":"x","redirect_uris":["http://evil.example/cb"]}`},
			{"带 fragment 的回调", `{"client_name":"x","redirect_uris":["https://a.example/cb#frag"]}`},
			{"javascript 伪协议", `{"client_name":"x","redirect_uris":["javascript:alert(1)"]}`},
			{"相对路径回调", `{"client_name":"x","redirect_uris":["/cb"]}`},
		}
		for _, tc := range cases {
			t.Run(tc.name, func(t *testing.T) {
				resp := postJSON(t, ts, "/oauth/register", tc.payload)
				body := decodeJSONBody(t, resp)
				if resp.StatusCode != http.StatusBadRequest || body["error"] != "invalid_client_metadata" {
					t.Fatalf("status=%d body=%+v", resp.StatusCode, body)
				}
				if desc, _ := body["error_description"].(string); desc == "" {
					t.Fatalf("缺 error_description: %+v", body)
				}
			})
		}
	})

	t.Run("超过 64KB 的请求体被拒", func(t *testing.T) {
		huge := `{"client_name":"x","redirect_uris":["https://a.example/cb"],"pad":"` + strings.Repeat("p", 70<<10) + `"}`
		resp := postJSON(t, ts, "/oauth/register", huge)
		body := decodeJSONBody(t, resp)
		if resp.StatusCode != http.StatusBadRequest || body["error"] != "invalid_client_metadata" {
			t.Fatalf("status=%d body=%+v", resp.StatusCode, body)
		}
	})
}

// ---------------------------------------------------------------------------
// 授权端点 GET /oauth/authorize
// ---------------------------------------------------------------------------

func TestAuthorizeEndpointHTTP(t *testing.T) {
	pool := setupDB(t)
	ts, _ := setupHTTP(t, pool)
	redirectURI := "http://127.0.0.1:49152/cb"
	clientID := registerTestClient(t, ts, redirectURI)
	client := noRedirectClient()

	valid := url.Values{
		"response_type":         {"code"},
		"client_id":             {clientID},
		"redirect_uri":          {redirectURI},
		"scope":                 {"files:read files:download"},
		"state":                 {"xyz"},
		"code_challenge":        {testChallenge()},
		"code_challenge_method": {"S256"},
	}

	get := func(t *testing.T, q url.Values) *http.Response {
		t.Helper()
		resp, err := client.Get(ts.URL + "/oauth/authorize?" + q.Encode())
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}

	t.Run("合法请求 302 跳转到 consent 并原样带全部参数", func(t *testing.T) {
		resp := get(t, valid)
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusFound {
			t.Fatalf("status=%d", resp.StatusCode)
		}
		loc, err := url.Parse(resp.Header.Get("Location"))
		if err != nil || loc.Path != "/oauth/consent" {
			t.Fatalf("Location 不符: %q err=%v", resp.Header.Get("Location"), err)
		}
		q := loc.Query()
		if q.Get("client_id") != clientID || q.Get("state") != "xyz" ||
			q.Get("code_challenge") != testChallenge() || q.Get("redirect_uri") != redirectURI {
			t.Fatalf("consent 查询串丢参数: %q", loc.RawQuery)
		}
	})

	t.Run("省略 scope 用默认 scope 仍放行", func(t *testing.T) {
		q := cloneValues(valid)
		q.Del("scope")
		resp := get(t, q)
		resp.Body.Close()
		if resp.StatusCode != http.StatusFound {
			t.Fatalf("status=%d", resp.StatusCode)
		}
	})

	t.Run("非法请求", func(t *testing.T) {
		cases := []struct {
			name       string
			mutate     func(url.Values)
			wantStatus int
			wantError  string
		}{
			{"未知 client_id 401", func(q url.Values) { q.Set("client_id", "gopan_client_nope") }, 401, "invalid_client"},
			{"response_type 非 code", func(q url.Values) { q.Set("response_type", "token") }, 400, "invalid_request"},
			{"缺 response_type", func(q url.Values) { q.Del("response_type") }, 400, "invalid_request"},
			{"未注册的 redirect_uri", func(q url.Values) { q.Set("redirect_uri", "http://127.0.0.1:49153/cb") }, 400, "invalid_request"},
			{"缺 code_challenge(PKCE 必选)", func(q url.Values) { q.Del("code_challenge") }, 400, "invalid_request"},
			{"code_challenge_method=plain 被拒", func(q url.Values) { q.Set("code_challenge_method", "plain") }, 400, "invalid_request"},
			{"code_challenge 太短", func(q url.Values) { q.Set("code_challenge", "short") }, 400, "invalid_request"},
			{"未知 scope 被拒", func(q url.Values) { q.Set("scope", "admin:*") }, 400, "invalid_request"},
		}
		for _, tc := range cases {
			t.Run(tc.name, func(t *testing.T) {
				q := cloneValues(valid)
				tc.mutate(q)
				resp := get(t, q)
				body := decodeJSONBody(t, resp)
				if resp.StatusCode != tc.wantStatus || body["error"] != tc.wantError {
					t.Fatalf("status=%d body=%+v", resp.StatusCode, body)
				}
				// 校验失败时绝不能 302 回 redirect_uri(开放跳转防护)
				if resp.Header.Get("Location") != "" {
					t.Fatalf("失败请求不应跳转: %q", resp.Header.Get("Location"))
				}
			})
		}
	})
}

func containsField(fields []string, target string) bool {
	for _, f := range fields {
		if f == target {
			return true
		}
	}
	return false
}

func cloneValues(in url.Values) url.Values {
	out := make(url.Values, len(in))
	for k, v := range in {
		out[k] = append([]string(nil), v...)
	}
	return out
}

// 数据库故障时 authorize 应回落为 invalid_request + "internal error",不泄露内部细节
func TestAuthorizeEndpointInternalError(t *testing.T) {
	pool := setupDB(t)
	ts, _ := setupHTTP(t, pool)
	pool.Close() // 模拟 DB 故障(pgxpool.Close 幂等,Cleanup 再关无害)
	client := noRedirectClient()
	resp, err := client.Get(ts.URL + "/oauth/authorize?response_type=code&client_id=x")
	if err != nil {
		t.Fatal(err)
	}
	body := decodeJSONBody(t, resp)
	if resp.StatusCode != http.StatusBadRequest || body["error"] != "invalid_request" ||
		body["error_description"] != "internal error" {
		t.Fatalf("内部错误应脱敏: status=%d body=%+v", resp.StatusCode, body)
	}
}

// ---------------------------------------------------------------------------
// 令牌端点 POST /oauth/token
// ---------------------------------------------------------------------------

func TestTokenEndpointAuthorizationCodeFlow(t *testing.T) {
	pool := setupDB(t)
	ts, oauth := setupHTTP(t, pool)
	redirectURI := "http://127.0.0.1:49152/cb"
	clientID := registerTestClient(t, ts, redirectURI)
	scopes := []string{"files:read", "files:download"}

	baseForm := func(code string) url.Values {
		return url.Values{
			"grant_type":    {"authorization_code"},
			"code":          {code},
			"client_id":     {clientID},
			"redirect_uri":  {redirectURI},
			"code_verifier": {testVerifier},
		}
	}

	code := issueCode(t, pool, oauth, clientID, redirectURI, scopes)

	var refreshToken string
	t.Run("授权码兑换成功", func(t *testing.T) {
		resp, err := http.PostForm(ts.URL+"/oauth/token", baseForm(code))
		if err != nil {
			t.Fatal(err)
		}
		if cc := resp.Header.Get("Cache-Control"); cc != "no-store" {
			t.Fatalf("token 响应必须 no-store,got %q", cc)
		}
		if resp.Header.Get("Pragma") != "no-cache" {
			t.Fatal("token 响应缺 Pragma: no-cache")
		}
		body := decodeJSONBody(t, resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status=%d body=%+v", resp.StatusCode, body)
		}
		access, _ := body["access_token"].(string)
		refreshToken, _ = body["refresh_token"].(string)
		if !strings.HasPrefix(access, "gopan_oauth_") || !strings.HasPrefix(refreshToken, "gopan_refresh_") {
			t.Fatalf("token 前缀不符: %+v", body)
		}
		if body["token_type"] != "Bearer" || body["expires_in"] != float64(3600) {
			t.Fatalf("token_type/expires_in 不符: %+v", body)
		}
		// normalizeMCPScopes 会排序去重,这里按集合断言
		got, _ := body["scope"].(string)
		fields := strings.Fields(got)
		if len(fields) != 2 || !containsField(fields, "files:read") || !containsField(fields, "files:download") {
			t.Fatalf("scope 不符: %v", body["scope"])
		}
	})

	t.Run("授权码只能兑换一次", func(t *testing.T) {
		status, body := postToken(t, ts, baseForm(code))
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("重放授权码应 invalid_grant: status=%d body=%+v", status, body)
		}
	})

	t.Run("错误 code_verifier 拒绝且消耗该码", func(t *testing.T) {
		fresh := issueCode(t, pool, oauth, clientID, redirectURI, scopes)
		form := baseForm(fresh)
		form.Set("code_verifier", strings.Repeat("Z", 64)) // 格式合法但值不对
		status, body := postToken(t, ts, form)
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("错误 verifier 应 invalid_grant: status=%d body=%+v", status, body)
		}
		// OAuth 2.1:失败尝试后授权码应作废,不能再用正确 verifier 换取
		status, body = postToken(t, ts, baseForm(fresh))
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("失败尝试后授权码应已作废: status=%d body=%+v", status, body)
		}
	})

	t.Run("client_id 不匹配被拒", func(t *testing.T) {
		fresh := issueCode(t, pool, oauth, clientID, redirectURI, scopes)
		form := baseForm(fresh)
		form.Set("client_id", "gopan_client_other")
		status, body := postToken(t, ts, form)
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("status=%d body=%+v", status, body)
		}
	})

	t.Run("redirect_uri 不匹配被拒", func(t *testing.T) {
		fresh := issueCode(t, pool, oauth, clientID, redirectURI, scopes)
		form := baseForm(fresh)
		form.Set("redirect_uri", "http://127.0.0.1:49153/cb")
		status, body := postToken(t, ts, form)
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("status=%d body=%+v", status, body)
		}
	})

	t.Run("verifier 格式非法直接拒绝", func(t *testing.T) {
		form := baseForm("gopan_code_whatever")
		form.Set("code_verifier", "too-short")
		status, body := postToken(t, ts, form)
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("status=%d body=%+v", status, body)
		}
	})

	t.Run("伪造授权码被拒", func(t *testing.T) {
		status, body := postToken(t, ts, baseForm("gopan_code_forged"))
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("status=%d body=%+v", status, body)
		}
	})

	t.Run("refresh_token 旋转与重放拒绝", func(t *testing.T) {
		if refreshToken == "" {
			t.Skip("前置兑换未成功")
		}
		form := url.Values{
			"grant_type":    {"refresh_token"},
			"refresh_token": {refreshToken},
			"client_id":     {clientID},
		}
		status, body := postToken(t, ts, form)
		if status != http.StatusOK {
			t.Fatalf("刷新失败: status=%d body=%+v", status, body)
		}
		rotated, _ := body["refresh_token"].(string)
		if rotated == "" || rotated == refreshToken {
			t.Fatalf("refresh_token 应旋转: %+v", body)
		}
		// 旧 refresh_token 重放应被拒
		status, body = postToken(t, ts, form)
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("重放旧 refresh_token 应 invalid_grant: status=%d body=%+v", status, body)
		}
		// 新 refresh_token 配错 client_id 也应被拒
		form.Set("refresh_token", rotated)
		form.Set("client_id", "gopan_client_other")
		status, body = postToken(t, ts, form)
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("错 client_id 刷新应 invalid_grant: status=%d body=%+v", status, body)
		}
	})

	t.Run("伪造 refresh_token 被拒", func(t *testing.T) {
		status, body := postToken(t, ts, url.Values{
			"grant_type": {"refresh_token"}, "refresh_token": {"gopan_refresh_forged"}, "client_id": {clientID},
		})
		if status != http.StatusBadRequest || body["error"] != "invalid_grant" {
			t.Fatalf("status=%d body=%+v", status, body)
		}
	})

	t.Run("不支持的 grant_type", func(t *testing.T) {
		status, body := postToken(t, ts, url.Values{"grant_type": {"password"}})
		if status != http.StatusBadRequest || body["error"] != "unsupported_grant_type" {
			t.Fatalf("status=%d body=%+v", status, body)
		}
	})

	t.Run("非法表单编码", func(t *testing.T) {
		resp, err := http.Post(ts.URL+"/oauth/token", "application/x-www-form-urlencoded",
			strings.NewReader("grant_type=%zz"))
		if err != nil {
			t.Fatal(err)
		}
		body := decodeJSONBody(t, resp)
		if resp.StatusCode != http.StatusBadRequest || body["error"] != "invalid_request" {
			t.Fatalf("status=%d body=%+v", resp.StatusCode, body)
		}
	})
}
