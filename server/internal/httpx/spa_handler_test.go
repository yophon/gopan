package httpx

// SPAHandler 的静态文件语义:unknown-with-ext 不回落 HTML、manifest 的 MIME、
// 缓存头(指纹产物 immutable / sw.js 与 index.html no-cache)。
// PWA 依赖这三件事:sw.js 拿到 HTML 会以 MIME 报错静默失败,缓存头不对则发版不生效。

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"testing/fstest"
)

func spaFS() fstest.MapFS {
	return fstest.MapFS{
		"index.html":           &fstest.MapFile{Data: []byte("<html>spa</html>")},
		"sw.js":                &fstest.MapFile{Data: []byte("// sw")},
		"manifest.webmanifest": &fstest.MapFile{Data: []byte("{}")},
		"assets/index-abc.js":  &fstest.MapFile{Data: []byte("console.log(1)")},
	}
}

func TestSPAHandlerPWAHeaders(t *testing.T) {
	server := httptest.NewServer(SPAHandler(spaFS()))
	t.Cleanup(server.Close)

	get := func(path string) *http.Response {
		t.Helper()
		res, err := http.Get(server.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		return res
	}

	t.Run("根路径回落 index.html 且 no-cache", func(t *testing.T) {
		res := get("/")
		defer res.Body.Close()
		if res.StatusCode != http.StatusOK {
			t.Fatalf("status=%d", res.StatusCode)
		}
		if res.Header.Get("Cache-Control") != "no-cache" {
			t.Fatalf("index.html 必须 no-cache,got %q", res.Header.Get("Cache-Control"))
		}
	})

	t.Run("前端路由(无扩展名)回落 index.html", func(t *testing.T) {
		res := get("/drive/01a0ad00-0000-0000-0000-000000000000")
		defer res.Body.Close()
		if res.StatusCode != http.StatusOK {
			t.Fatalf("status=%d", res.StatusCode)
		}
	})

	t.Run("assets 指纹产物钉死缓存", func(t *testing.T) {
		res := get("/assets/index-abc.js")
		defer res.Body.Close()
		if res.StatusCode != http.StatusOK {
			t.Fatalf("status=%d", res.StatusCode)
		}
		if got := res.Header.Get("Cache-Control"); got != "public, max-age=31536000, immutable" {
			t.Fatalf("Cache-Control=%q", got)
		}
	})

	t.Run("manifest 的 MIME 与 no-cache", func(t *testing.T) {
		res := get("/manifest.webmanifest")
		defer res.Body.Close()
		if res.StatusCode != http.StatusOK {
			t.Fatalf("status=%d", res.StatusCode)
		}
		if got := res.Header.Get("Content-Type"); got != "application/manifest+json" {
			t.Fatalf("Content-Type=%q", got)
		}
		if got := res.Header.Get("Cache-Control"); got != "no-cache" {
			t.Fatalf("Cache-Control=%q", got)
		}
	})

	t.Run("sw.js no-cache", func(t *testing.T) {
		res := get("/sw.js")
		defer res.Body.Close()
		if res.StatusCode != http.StatusOK {
			t.Fatalf("status=%d", res.StatusCode)
		}
		if got := res.Header.Get("Cache-Control"); got != "no-cache" {
			t.Fatalf("Cache-Control=%q", got)
		}
	})

	t.Run("带扩展名的未知路径 404,不回落 HTML", func(t *testing.T) {
		res := get("/nope.js")
		defer res.Body.Close()
		if res.StatusCode != http.StatusNotFound {
			t.Fatalf("status=%d", res.StatusCode)
		}
	})
}
