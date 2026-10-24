package httpx

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

const goodDesktop = `{"version":"3.14.0","installer":"gopan-desktop-3.14.0-setup.exe","sha256":"` + sha64 + `","size":7,"signature":"dW50cnVzdGVkIGNvbW1lbnQ=","notes":"桌面端","publishedAt":"2026-10-24T12:00:00Z"}`

func writeDesktop(t *testing.T, dir, latest string, exe []byte) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, "latest.json"), []byte(latest), 0o644); err != nil {
		t.Fatal(err)
	}
	if exe != nil {
		if err := os.WriteFile(filepath.Join(dir, "gopan-desktop-3.14.0-setup.exe"), exe, 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDesktopReleaseServesTauriManifestAndInstaller(t *testing.T) {
	dir := t.TempDir()
	writeDesktop(t, dir, goodDesktop, []byte("MZ-exe!"))
	h := DesktopRelease(dir)

	// 清单的下载地址按请求来源拼成绝对地址(反代过来的按 X-Forwarded-*)
	rec := get(h, "/api/app/desktop/latest", map[string]string{"X-Forwarded-Proto": "https", "X-Forwarded-Host": "drive.example.com"})
	if rec.Code != http.StatusOK || rec.Header().Get("Cache-Control") != "no-cache" {
		t.Fatalf("latest: %d %v", rec.Code, rec.Header())
	}
	var m tauriManifest
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	p := m.Platforms["windows-x86_64"]
	if m.Version != "3.14.0" || m.PubDate != "2026-10-24T12:00:00Z" || p.Signature != "dW50cnVzdGVkIGNvbW1lbnQ=" ||
		p.URL != "https://drive.example.com/api/app/desktop/gopan-desktop-3.14.0-setup.exe" {
		t.Fatalf("manifest: %+v", m)
	}

	rec = get(h, "/api/app/desktop/info", nil)
	var info desktopInfo
	if err := json.Unmarshal(rec.Body.Bytes(), &info); err != nil || info.Size != 7 || info.URL != "/api/app/desktop/gopan-desktop-3.14.0-setup.exe" {
		t.Fatalf("info: %s %v", rec.Body.String(), err)
	}

	rec = get(h, "/api/app/desktop/gopan-desktop-3.14.0-setup.exe", map[string]string{"Range": "bytes=3-"})
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "exe!" {
		t.Fatalf("installer range: %d %q", rec.Code, rec.Body.String())
	}
	rec = get(h, "/api/app/desktop/latest.exe", nil)
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != "/api/app/desktop/gopan-desktop-3.14.0-setup.exe" {
		t.Fatalf("latest.exe: %d %v", rec.Code, rec.Header())
	}
	if rec = get(h, "/api/app/desktop/other.exe", nil); rec.Code != http.StatusNotFound {
		t.Fatalf("other file: %d", rec.Code)
	}
}

func TestDesktopReleaseRejectsMissingOrBadReleases(t *testing.T) {
	if rec := get(DesktopRelease(""), "/api/app/desktop/latest", nil); rec.Code != http.StatusNotFound {
		t.Fatalf("not configured: %d", rec.Code)
	}
	dir := t.TempDir()
	if rec := get(DesktopRelease(dir), "/api/app/desktop/latest", nil); rec.Code != http.StatusNotFound {
		t.Fatalf("no release: %d", rec.Code)
	}
	for _, bad := range []string{
		`{"version":"3.14","installer":"a.exe","sha256":"` + sha64 + `","signature":"s"}`,
		`{"version":"3.14.0","installer":"../a.exe","sha256":"` + sha64 + `","signature":"s"}`,
		`{"version":"3.14.0","installer":"a.msi","sha256":"` + sha64 + `","signature":"s"}`,
		`{"version":"3.14.0","installer":"a.exe","sha256":"` + sha64 + `","signature":" "}`,
		`{"version":"3.14.0","installer":"a.exe","sha256":"zz","signature":"s"}`,
	} {
		writeDesktop(t, dir, bad, nil)
		if rec := get(DesktopRelease(dir), "/api/app/desktop/latest", nil); rec.Code != http.StatusInternalServerError {
			t.Fatalf("%s → %d", bad, rec.Code)
		}
	}
}
