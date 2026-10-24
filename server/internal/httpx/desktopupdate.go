package httpx

import (
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// DesktopReleasePath 是桌面端(Windows,doc/29)检查更新与下载安装包的前缀。
const DesktopReleasePath = "/api/app/desktop/"

// desktopRelease 是 GOPAN_APP_RELEASE_DIR/desktop/latest.json 的内容:发版时与安装包一起放进去。
type desktopRelease struct {
	Version string `json:"version"`
	// 目录里的安装包文件名(只认这一个)
	Installer string `json:"installer"`
	SHA256    string `json:"sha256"`
	Size      int64  `json:"size"`
	// Tauri updater 的签名(安装包的 .sig 文件内容,minisign);程序更新前据此校验,不是 Windows 代码签名
	Signature   string `json:"signature"`
	Notes       string `json:"notes"`
	PublishedAt string `json:"publishedAt"`
}

// tauriPlatform / tauriManifest 是 Tauri updater 认的静态清单(v2)。
type tauriPlatform struct {
	Signature string `json:"signature"`
	URL       string `json:"url"`
}

type tauriManifest struct {
	Version   string                   `json:"version"`
	Notes     string                   `json:"notes"`
	PubDate   string                   `json:"pub_date,omitempty"`
	Platforms map[string]tauriPlatform `json:"platforms"`
}

// desktopInfo 给发布页:版本、大小、下载地址(相对路径)。
type desktopInfo struct {
	Version     string `json:"version"`
	Size        int64  `json:"size"`
	Notes       string `json:"notes"`
	PublishedAt string `json:"publishedAt"`
	URL         string `json:"url"`
}

var semverRe = regexp.MustCompile(`^\d+\.\d+\.\d+$`)

// DesktopRelease 服务桌面端的更新清单与安装包(与安卓的 [AndroidRelease] 同一个做法,不要登录):
//
//	GET /api/app/desktop/latest      → Tauri updater 清单(no-cache),下载地址按请求的来源拼成绝对地址
//	GET /api/app/desktop/info        → 发布页用的版本、大小、相对下载地址
//	GET /api/app/desktop/<安装包名>    → 安装包(支持 Range;只认 latest.json 里声明的文件名)
//	GET /api/app/desktop/latest.exe  → 302 到当前安装包(固定下载链接)
//
// dir 为空 = 本站没配,都回 404 JSON。
func DesktopRelease(dir string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			writeJSONError(w, http.StatusMethodNotAllowed, "method_not_allowed")
			return
		}
		name := strings.TrimPrefix(r.URL.Path, DesktopReleasePath)
		if dir == "" {
			writeJSONError(w, http.StatusNotFound, "not_configured")
			return
		}
		rel, err := readDesktopRelease(dir)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				writeJSONError(w, http.StatusNotFound, "no_release")
				return
			}
			writeJSONError(w, http.StatusInternalServerError, "bad_release")
			return
		}
		switch name {
		case "latest":
			m := tauriManifest{
				Version: rel.Version,
				Notes:   rel.Notes,
				PubDate: rel.PublishedAt,
				Platforms: map[string]tauriPlatform{
					"windows-x86_64": {Signature: rel.Signature, URL: requestOrigin(r) + DesktopReleasePath + url.PathEscape(rel.Installer)},
				},
			}
			writeNoCacheJSON(w, m)
			return
		case "info":
			writeNoCacheJSON(w, desktopInfo{Version: rel.Version, Size: rel.Size, Notes: rel.Notes, PublishedAt: rel.PublishedAt, URL: DesktopReleasePath + url.PathEscape(rel.Installer)})
			return
		case "latest.exe":
			w.Header().Set("Cache-Control", "no-cache")
			http.Redirect(w, r, DesktopReleasePath+url.PathEscape(rel.Installer), http.StatusFound)
			return
		}
		if name != rel.Installer {
			writeJSONError(w, http.StatusNotFound, "not_found")
			return
		}
		f, err := os.Open(filepath.Join(dir, rel.Installer))
		if err != nil {
			writeJSONError(w, http.StatusNotFound, "not_found")
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil || st.IsDir() {
			writeJSONError(w, http.StatusNotFound, "not_found")
			return
		}
		w.Header().Set("Content-Type", "application/vnd.microsoft.portable-executable")
		w.Header().Set("Content-Disposition", `attachment; filename="`+rel.Installer+`"`)
		// 文件名带版本号,内容不会变
		w.Header().Set("Cache-Control", "public, max-age=86400")
		http.ServeContent(w, r, rel.Installer, st.ModTime(), f)
	})
}

func writeNoCacheJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_ = json.NewEncoder(w).Encode(v)
}

// readDesktopRelease 读并校验 desktop/latest.json:版本号是 x.y.z,安装包是同目录下的 .exe,
// 必须带 SHA-256 与 updater 签名(没有签名的发布程序会拒绝安装,干脆不给出去)。
func readDesktopRelease(dir string) (desktopRelease, error) {
	var rel desktopRelease
	b, err := os.ReadFile(filepath.Join(dir, "latest.json"))
	if err != nil {
		return rel, err
	}
	if err := json.Unmarshal(b, &rel); err != nil {
		return rel, err
	}
	if !semverRe.MatchString(rel.Version) || !strings.HasSuffix(rel.Installer, ".exe") ||
		rel.Installer != filepath.Base(rel.Installer) || strings.ContainsAny(rel.Installer, `/\"`) || strings.HasPrefix(rel.Installer, ".") {
		return rel, errors.New("desktop/latest.json 不完整或安装包文件名非法")
	}
	if !isHexSHA256(rel.SHA256) || strings.TrimSpace(rel.Signature) == "" {
		return rel, errors.New("desktop/latest.json 缺 sha256 或签名")
	}
	if rel.PublishedAt != "" {
		if _, err := time.Parse(time.RFC3339, rel.PublishedAt); err != nil {
			return rel, errors.New("desktop/latest.json 的 publishedAt 不是 RFC3339")
		}
	}
	return rel, nil
}
