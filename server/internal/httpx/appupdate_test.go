package httpx

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeRelease(t *testing.T, dir, latest string, apk []byte) {
	t.Helper()
	if latest != "" {
		if err := os.WriteFile(filepath.Join(dir, "latest.json"), []byte(latest), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if apk != nil {
		if err := os.WriteFile(filepath.Join(dir, "gopan-3.0.0.apk"), apk, 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func get(h http.Handler, path string, header map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	for k, v := range header {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

const goodLatest = `{"versionCode":30000,"versionName":"3.0.0","apk":"gopan-3.0.0.apk","sha256":"84D89877F0D4041EFB6BF91A16F0248F2FD573E6AF05C19F96BEDB9F882F7882","size":10,"notes":"正式版","publishedAt":"2026-10-14T12:00:00Z"}`

const sha64 = "84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882"

func TestAndroidReleaseNotConfigured(t *testing.T) {
	rec := get(AndroidRelease(""), "/api/app/android/latest", nil)
	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "not_configured") {
		t.Fatalf("未配置时应 404 not_configured,得到 %d %s", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("应是 JSON(不能落到 SPA 的 HTML):%s", ct)
	}
}

func TestAndroidReleaseLatestAndDownload(t *testing.T) {
	dir := t.TempDir()
	writeRelease(t, dir, goodLatest, []byte("0123456789"))
	h := AndroidRelease(dir)

	rec := get(h, "/api/app/android/latest", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("latest: %d %s", rec.Code, rec.Body.String())
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["versionCode"].(float64) != 30000 || got["url"] != "/api/app/android/gopan-3.0.0.apk" || got["notes"] != "正式版" {
		t.Fatalf("latest 内容不对:%v", got)
	}
	if rec.Header().Get("Cache-Control") != "no-cache" {
		t.Fatalf("latest 不该被缓存")
	}

	rec = get(h, "/api/app/android/gopan-3.0.0.apk", nil)
	if rec.Code != http.StatusOK || rec.Body.String() != "0123456789" {
		t.Fatalf("下载:%d %q", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/vnd.android.package-archive" {
		t.Fatalf("Content-Type: %s", ct)
	}

	rec = get(h, "/api/app/android/gopan-3.0.0.apk", map[string]string{"Range": "bytes=4-"})
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "456789" {
		t.Fatalf("Range: %d %q", rec.Code, rec.Body.String())
	}

	// 首次安装用的固定链接跳到当前安装包
	rec = get(h, "/api/app/android/latest.apk", nil)
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != "/api/app/android/gopan-3.0.0.apk" {
		t.Fatalf("latest.apk: %d %q", rec.Code, rec.Header().Get("Location"))
	}
	if rec.Header().Get("Cache-Control") != "no-cache" {
		t.Fatalf("latest.apk 的跳转不该被缓存")
	}
}

func TestAndroidReleaseOnlyServesTheDeclaredApk(t *testing.T) {
	dir := t.TempDir()
	writeRelease(t, dir, goodLatest, []byte("x"))
	if err := os.WriteFile(filepath.Join(dir, "secret.apk"), []byte("s"), 0o644); err != nil {
		t.Fatal(err)
	}
	h := AndroidRelease(dir)
	for _, p := range []string{"/api/app/android/secret.apk", "/api/app/android/latest.json", "/api/app/android/../config", "/api/app/android/"} {
		if rec := get(h, p, nil); rec.Code != http.StatusNotFound {
			t.Errorf("%s 应 404,得到 %d", p, rec.Code)
		}
	}
}

func TestAndroidReleaseRejectsBadManifest(t *testing.T) {
	for name, latest := range map[string]string{
		"跳出目录":         `{"versionCode":1,"versionName":"1","apk":"../x.apk","sha256":"` + sha64 + `"}`,
		"不是 apk":       `{"versionCode":1,"versionName":"1","apk":"x.zip","sha256":"` + sha64 + `"}`,
		"缺版本号":         `{"versionName":"1","apk":"x.apk","sha256":"` + sha64 + `"}`,
		"缺 sha256":     `{"versionCode":1,"versionName":"1","apk":"x.apk"}`,
		"sha256 太短":    `{"versionCode":1,"versionName":"1","apk":"x.apk","sha256":"ab"}`,
		"sha256 非十六进制": `{"versionCode":1,"versionName":"1","apk":"x.apk","sha256":"` + strings.Repeat("g", 64) + `"}`,
		"坏 JSON":       `{`,
	} {
		dir := t.TempDir()
		writeRelease(t, dir, latest, nil)
		rec := get(AndroidRelease(dir), "/api/app/android/latest", nil)
		if rec.Code != http.StatusInternalServerError {
			t.Errorf("%s:应 500 bad_release,得到 %d", name, rec.Code)
		}
	}
	// 目录配了但还没发版
	rec := get(AndroidRelease(t.TempDir()), "/api/app/android/latest", nil)
	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "no_release") {
		t.Fatalf("没有 latest.json 应 404 no_release:%d %s", rec.Code, rec.Body.String())
	}
}

func TestAndroidReleaseMethod(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/app/android/latest", nil)
	rec := httptest.NewRecorder()
	AndroidRelease(t.TempDir()).ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST 应 405,得到 %d", rec.Code)
	}
}
