package httpx_test

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/yophon/gopan/server/internal/httpx"
)

// 安卓壳 WebView(UA 带 GopanApp/)拿到放行内联脚本的 CSP;普通浏览器不变;dev 模式不发 CSP。
func TestSecurityHeadersAppWebView(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) })

	get := func(h http.Handler, ua string) http.Header {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/chat", nil)
		if ua != "" {
			req.Header.Set("User-Agent", ua)
		}
		h.ServeHTTP(rec, req)
		return rec.Header()
	}

	prod := httpx.WithSecurityHeaders(ok, false)
	browser := get(prod, "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile")
	app := get(prod, "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile GopanApp/2.6.0")

	bcsp := browser.Get("Content-Security-Policy")
	acsp := app.Get("Content-Security-Policy")
	if bcsp == "" || acsp == "" {
		t.Fatalf("生产模式两种 UA 都应有 CSP:browser=%q app=%q", bcsp, acsp)
	}
	if strings.Contains(bcsp, "'unsafe-inline' 'wasm-unsafe-eval'") || !strings.Contains(bcsp, "script-src 'self' 'wasm-unsafe-eval'") {
		t.Fatalf("普通浏览器的 script-src 不应放行内联:%q", bcsp)
	}
	if !strings.Contains(acsp, "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'") {
		t.Fatalf("壳 WebView 的 script-src 应放行内联:%q", acsp)
	}
	// 其余指令两边一致(只差 script-src)
	strip := func(s string) string {
		i := strings.Index(s, "script-src")
		return s[:i]
	}
	if strip(bcsp) != strip(acsp) {
		t.Fatalf("除 script-src 外应一致:\n%q\n%q", bcsp, acsp)
	}
	for _, h := range []http.Header{browser, app} {
		if h.Get("X-Frame-Options") != "DENY" || h.Get("X-Content-Type-Options") != "nosniff" {
			t.Fatalf("其余安全头应原样:%v", h)
		}
	}

	dev := httpx.WithSecurityHeaders(ok, true)
	if get(dev, "GopanApp/2.6.0").Get("Content-Security-Policy") != "" {
		t.Fatal("dev 模式不应发 CSP")
	}
}
