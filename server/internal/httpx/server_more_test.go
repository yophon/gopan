package httpx

// 纯单测:ClientIP 信任边界、ClearRefreshCookie、SPAHandler(含路径穿越)、
// WithLogging 状态码捕获。不碰 DB/S3。

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"
)

// clientIPOf 让请求过一遍 WithAuth(它负责把 IP 塞进 ctx),再读 ClientIP。
func clientIPOf(t *testing.T, mutate func(r *http.Request)) string {
	t.Helper()
	var got string
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = ClientIP(r.Context())
	})
	req := httptest.NewRequest("POST", "/query", nil)
	mutate(req)
	WithAuth(inner, newAuthNoDB()).ServeHTTP(httptest.NewRecorder(), req)
	return got
}

func TestClientIP(t *testing.T) {
	// 无 http 载体的裸 ctx → "unknown"
	if got := ClientIP(t.Context()); got != "unknown" {
		t.Fatalf(`裸 ctx 应返回 "unknown",got %q`, got)
	}

	// 常规 IPv4:取 RemoteAddr 的 host 部分
	if got := clientIPOf(t, func(r *http.Request) {
		r.RemoteAddr = "203.0.113.7:4567"
	}); got != "203.0.113.7" {
		t.Fatalf("IPv4 RemoteAddr 应取 host,got %q", got)
	}

	// IPv6 带端口:方括号剥掉
	if got := clientIPOf(t, func(r *http.Request) {
		r.RemoteAddr = "[2001:db8::1]:443"
	}); got != "2001:db8::1" {
		t.Fatalf("IPv6 RemoteAddr 应取 host,got %q", got)
	}

	// 信任边界:X-Forwarded-For / X-Real-IP 是客户端可伪造的头,
	// 当前实现只信 RemoteAddr,伪造头不得改变结果(防限速被绕)。
	if got := clientIPOf(t, func(r *http.Request) {
		r.RemoteAddr = "203.0.113.7:4567"
		r.Header.Set("X-Forwarded-For", "10.0.0.1, 198.51.100.9")
		r.Header.Set("X-Real-IP", "10.0.0.2")
	}); got != "203.0.113.7" {
		t.Fatalf("伪造 XFF/X-Real-IP 不应被信任,got %q", got)
	}

	// 畸形 RemoteAddr(无端口):SplitHostPort 失败,当前实现记为空串。
	// 这里锁定现状:至少不能把畸形串原样透传或 panic。
	if got := clientIPOf(t, func(r *http.Request) {
		r.RemoteAddr = "203.0.113.7"
	}); got != "" {
		t.Fatalf("无端口 RemoteAddr 当前应得空串,got %q", got)
	}
}

func TestClearRefreshCookie(t *testing.T) {
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ClearRefreshCookie(r.Context(), false)
	})
	rec := httptest.NewRecorder()
	WithAuth(inner, newAuthNoDB()).ServeHTTP(rec, httptest.NewRequest("POST", "/query", nil))

	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("应恰好写一个 Set-Cookie,got %d", len(cookies))
	}
	ck := cookies[0]
	if ck.Name != "gopan_rt" || ck.Value != "" {
		t.Fatalf("清除 cookie 应置空值:%+v", ck)
	}
	if ck.MaxAge >= 0 && !strings.Contains(rec.Header().Get("Set-Cookie"), "Max-Age=0") {
		t.Fatalf("清除 cookie 应立即过期:%s", rec.Header().Get("Set-Cookie"))
	}
	if ck.Path != "/query" || !ck.HttpOnly {
		t.Fatalf("清除 cookie 的 Path/HttpOnly 应与设置时一致:%+v", ck)
	}
	if ck.Secure {
		t.Fatalf("secure=false 时不应带 Secure:%+v", ck)
	}

	// 无 http 载体时静默 no-op,不 panic
	ClearRefreshCookie(t.Context(), true)
}

func TestSPAHandler(t *testing.T) {
	dist := fstest.MapFS{
		"index.html":    &fstest.MapFile{Data: []byte("<html><head></head><body>gopan-spa</body></html>")},
		"assets/app.js": &fstest.MapFile{Data: []byte("console.log('gopan')")},
	}
	h := SPAHandler(dist)

	get := func(path string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("GET", "/", nil)
		req.URL.Path = path // 绕过 URL 解析的清洗,直接控制 handler 看到的 path
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}

	// 静态文件命中
	if rec := get("/assets/app.js"); rec.Code != 200 || !strings.Contains(rec.Body.String(), "console.log") {
		t.Fatalf("静态文件应命中:code=%d body=%q", rec.Code, rec.Body.String())
	}
	// 根路径 → index.html
	if rec := get("/"); rec.Code != 200 || !strings.Contains(rec.Body.String(), "gopan-spa") {
		t.Fatalf("/ 应回 index.html:code=%d", rec.Code)
	}
	// 未知前端路由 → 回落 index.html,Content-Type 正确
	rec := get("/drive/some/deep/route")
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "gopan-spa") {
		t.Fatalf("SPA 路由应回落 index.html:code=%d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Fatalf("回落响应 Content-Type 应为 html,got %q", ct)
	}

	// 前端未构建(空 FS)→ 404 + 提示
	rec = httptest.NewRecorder()
	SPAHandler(fstest.MapFS{}).ServeHTTP(rec, httptest.NewRequest("GET", "/anything", nil))
	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "前端未构建") {
		t.Fatalf("缺 index.html 应 404:code=%d body=%q", rec.Code, rec.Body.String())
	}
}

func TestSPAHandlerPathTraversal(t *testing.T) {
	// 真实目录布局:dist 外面放一个"机密"文件,../ 不得逃出 dist
	tmp := t.TempDir()
	distDir := filepath.Join(tmp, "dist")
	if err := os.MkdirAll(distDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(distDir, "index.html"), []byte("<html>safe-index</html>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(tmp, "secret.txt"), []byte("TOP-SECRET"), 0o600); err != nil {
		t.Fatal(err)
	}

	h := SPAHandler(os.DirFS(distDir))
	for _, p := range []string{"/../secret.txt", "/..%2fsecret.txt", "/./../secret.txt"} {
		req := httptest.NewRequest("GET", "/", nil)
		req.URL.Path = p
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if strings.Contains(rec.Body.String(), "TOP-SECRET") {
			t.Fatalf("路径穿越泄露文件:path=%q", p)
		}
	}
}

func TestWithLoggingStatusCapture(t *testing.T) {
	// 显式 WriteHeader:statusRecorder 捕获并原样透传给底层 writer
	var captured int
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
		w.Write([]byte("teapot"))
		if sr, ok := w.(*statusRecorder); ok {
			captured = sr.status
		}
	})
	rec := httptest.NewRecorder()
	WithLogging(inner).ServeHTTP(rec, httptest.NewRequest("GET", "/drive/x", nil))
	if rec.Code != http.StatusTeapot || rec.Body.String() != "teapot" {
		t.Fatalf("状态码/响应体应透传:code=%d body=%q", rec.Code, rec.Body.String())
	}
	if captured != http.StatusTeapot {
		t.Fatalf("statusRecorder 应捕获 418,got %d", captured)
	}

	// 不调 WriteHeader:默认按 200 记
	captured = 0
	rec = httptest.NewRecorder()
	WithLogging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
		if sr, ok := w.(*statusRecorder); ok {
			captured = sr.status
		}
	})).ServeHTTP(rec, httptest.NewRequest("GET", "/healthz", nil))
	if rec.Code != 200 || captured != 200 {
		t.Fatalf("默认应按 200 记:code=%d captured=%d", rec.Code, captured)
	}
}
