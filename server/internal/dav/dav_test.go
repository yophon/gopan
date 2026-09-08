package dav_test

// WebDAV 端到端集成测试:真 Postgres + 真 MinIO + httptest,
// 用裸 HTTP 打协议方法(与真实客户端同一路径)。两个环境变量都设了才跑:
//   TEST_DB_URL=... TEST_S3_ENDPOINT=127.0.0.1:9000 go test ./internal/dav/

import (
	"context"
	"database/sql"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	gdb "github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/dav"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type env struct {
	pool     *pgxpool.Pool
	srv      *httptest.Server
	username string
	password string // 应用密码明文
	mainPass string // 主密码(必须进不来)
	appPass  *service.AppPasswords
	passID   uuid.UUID
	owner    uuid.UUID
}

func setup(t *testing.T) *env {
	t.Helper()
	dbURL := os.Getenv("TEST_DB_URL")
	ep := os.Getenv("TEST_S3_ENDPOINT")
	if dbURL == "" || ep == "" {
		t.Skip("TEST_DB_URL / TEST_S3_ENDPOINT 未设置,跳过 WebDAV 集成测试")
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

	ctx := context.Background()
	q := store.New(pool)
	auth := service.NewAuth(q, []byte("test-secret-test-secret-test-secret"), 15*time.Minute, 14*24*time.Hour, true, 1<<30)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, 48*time.Hour)
	ap := service.NewAppPasswords(q)
	ap.SetRateLimit(time.Millisecond, 10000) // 测试不测限速,放开

	res, err := auth.Register(ctx, "davuser", "mainpassword123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	plain, row, err := ap.Create(ctx, res.User.ID, "test-device")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(dav.Handler(ap, dav.NewBackend(q, obj, nodes, uploads)))
	t.Cleanup(srv.Close)
	return &env{
		pool: pool, srv: srv,
		username: "davuser", password: plain, mainPass: "mainpassword123",
		appPass: ap, passID: row.ID, owner: res.User.ID,
	}
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// req 发起 WebDAV 请求,返回状态码与响应体。
func (e *env) req(t *testing.T, method, path, body string, hdr map[string]string, user, pass string) (int, string) {
	t.Helper()
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	r, err := http.NewRequest(method, e.srv.URL+"/dav"+path, rd)
	if err != nil {
		t.Fatal(err)
	}
	r.SetBasicAuth(user, pass)
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func (e *env) do(t *testing.T, method, path, body string, hdr map[string]string) (int, string) {
	return e.req(t, method, path, body, hdr, e.username, e.password)
}

func TestWebDAV(t *testing.T) {
	e := setup(t)
	ctx := context.Background()

	// 认证边界:错密码 401、主密码 401(应用密码专用通道)、无认证 401
	if code, _ := e.req(t, "PROPFIND", "/", "", map[string]string{"Depth": "1"}, e.username, "gopan_wrong"); code != 401 {
		t.Fatalf("错密码应 401,got %d", code)
	}
	if code, _ := e.req(t, "PROPFIND", "/", "", map[string]string{"Depth": "1"}, e.username, e.mainPass); code != 401 {
		t.Fatalf("主密码走 WebDAV 应 401,got %d", code)
	}

	// MKCOL
	if code, _ := e.do(t, "MKCOL", "/docs", "", nil); code != 201 {
		t.Fatalf("MKCOL 应 201,got %d", code)
	}
	if code, _ := e.do(t, "MKCOL", "/docs", "", nil); code != 405 {
		t.Fatalf("重复 MKCOL 应 405,got %d", code)
	}

	// PUT + GET 往返
	content := "hello webdav, 你好"
	if code, _ := e.do(t, "PUT", "/docs/hello.txt", content, nil); code != 201 {
		t.Fatalf("PUT 应 201,got %d", code)
	}
	if code, body := e.do(t, "GET", "/docs/hello.txt", "", nil); code != 200 || body != content {
		t.Fatalf("GET: code=%d body=%q", code, body)
	}
	// Windows sends PROPPATCH after PUT; metadata updates must not truncate data.
	patch := `<D:propertyupdate xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:"><D:set><D:prop><Z:Win32LastModifiedTime>Tue, 08 Sep 2026 06:00:00 GMT</Z:Win32LastModifiedTime></D:prop></D:set></D:propertyupdate>`
	if code, _ := e.do(t, "PROPPATCH", "/docs/hello.txt", patch, map[string]string{"Content-Type": "application/xml"}); code != 207 {
		t.Fatalf("PROPPATCH: got %d", code)
	}
	if code, body := e.do(t, "GET", "/docs/hello.txt", "", nil); code != 200 || body != content {
		t.Fatalf("PROPPATCH changed contents: code=%d body=%q", code, body)
	}
	// Range(预览/断点下载路径)
	if code, body := e.do(t, "GET", "/docs/hello.txt", "", map[string]string{"Range": "bytes=0-4"}); code != 206 || body != "hello" {
		t.Fatalf("Range GET: code=%d body=%q", code, body)
	}
	// PROPFIND 列出
	if code, body := e.do(t, "PROPFIND", "/docs", "", map[string]string{"Depth": "1"}); code != 207 || !strings.Contains(body, "hello.txt") {
		t.Fatalf("PROPFIND: code=%d 应含 hello.txt", code)
	}

	// 配额记账
	var used int64
	if err := e.pool.QueryRow(ctx, `SELECT used_bytes FROM users WHERE id = $1`, e.owner).Scan(&used); err != nil {
		t.Fatal(err)
	}
	if used != int64(len(content)) {
		t.Fatalf("used_bytes 应 %d,got %d", len(content), used)
	}

	// 覆盖 PUT:换内容不进回收站,配额按差额
	content2 := "v2"
	if code, _ := e.do(t, "PUT", "/docs/hello.txt", content2, nil); code >= 300 {
		t.Fatalf("覆盖 PUT 失败:%d", code)
	}
	if _, body := e.do(t, "GET", "/docs/hello.txt", "", nil); body != content2 {
		t.Fatalf("覆盖后应读到新内容,got %q", body)
	}
	var trashCount int
	if err := e.pool.QueryRow(ctx, `SELECT count(*) FROM nodes WHERE owner_id = $1 AND deleted_at IS NOT NULL`, e.owner).Scan(&trashCount); err != nil {
		t.Fatal(err)
	}
	if trashCount != 0 {
		t.Fatalf("覆盖不应产生回收站副本,got %d", trashCount)
	}
	if err := e.pool.QueryRow(ctx, `SELECT used_bytes FROM users WHERE id = $1`, e.owner).Scan(&used); err != nil {
		t.Fatal(err)
	}
	if used != int64(len(content2)) {
		t.Fatalf("覆盖后 used_bytes 应 %d,got %d", len(content2), used)
	}

	// 内容寻址去重:同内容第二个文件,blob 只一行、ref=2
	if code, _ := e.do(t, "PUT", "/docs/copy.txt", content2, nil); code != 201 {
		t.Fatalf("PUT copy 应 201,got %d", code)
	}
	var blobCount, refCount int
	if err := e.pool.QueryRow(ctx, `SELECT count(*), max(ref_count) FROM blobs WHERE size = $1`, len(content2)).Scan(&blobCount, &refCount); err != nil {
		t.Fatal(err)
	}
	if blobCount != 1 || refCount != 2 {
		t.Fatalf("去重:blob=%d ref=%d,want 1/2", blobCount, refCount)
	}

	// MOVE(改名)
	if code, _ := e.do(t, "MOVE", "/docs/copy.txt", "", map[string]string{
		"Destination": e.srv.URL + "/dav/docs/renamed.txt",
	}); code != 201 && code != 204 {
		t.Fatalf("MOVE 应成功,got %d", code)
	}
	if code, _ := e.do(t, "GET", "/docs/copy.txt", "", nil); code != 404 {
		t.Fatalf("旧名应 404,got %d", code)
	}
	if code, _ := e.do(t, "GET", "/docs/renamed.txt", "", nil); code != 200 {
		t.Fatalf("新名应 200,got %d", code)
	}

	// DELETE = 软删进回收站
	if code, _ := e.do(t, "DELETE", "/docs/renamed.txt", "", nil); code != 204 {
		t.Fatalf("DELETE 应 204,got %d", code)
	}
	if err := e.pool.QueryRow(ctx, `SELECT count(*) FROM nodes WHERE owner_id = $1 AND deleted_at IS NOT NULL`, e.owner).Scan(&trashCount); err != nil {
		t.Fatal(err)
	}
	if trashCount != 1 {
		t.Fatalf("DELETE 后回收站应 1 项,got %d", trashCount)
	}

	// 目标是文件夹的 PUT 拒绝
	if code, _ := e.do(t, "PUT", "/docs", "x", nil); code < 400 {
		t.Fatalf("PUT 到文件夹应失败,got %d", code)
	}

	// 吊销后 401
	if err := e.appPass.Revoke(ctx, e.owner, e.passID); err != nil {
		t.Fatal(err)
	}
	if code, _ := e.do(t, "PROPFIND", "/", "", map[string]string{"Depth": "1"}); code != 401 {
		t.Fatalf("吊销后应 401,got %d", code)
	}
}

func TestAppPasswordLimit(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	// 已有 1 条,补到 20 条上限,第 21 条拒
	for i := 0; i < 19; i++ {
		if _, _, err := e.appPass.Create(ctx, e.owner, fmt.Sprintf("dev-%d", i)); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := e.appPass.Create(ctx, e.owner, "overflow"); err == nil {
		t.Fatal("超过上限应拒")
	}
}
