package httpx

import (
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// AndroidReleasePath 是安卓 App 检查更新与下载安装包的前缀。
const AndroidReleasePath = "/api/app/android/"

// androidRelease 是 latest.json 的内容(发版时与 apk 一起放进 GOPAN_APP_RELEASE_DIR)。
type androidRelease struct {
	VersionCode int64  `json:"versionCode"`
	VersionName string `json:"versionName"`
	// 目录里的安装包文件名(只认这一个,不提供目录浏览)
	APK         string `json:"apk"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	Notes       string `json:"notes"`
	PublishedAt string `json:"publishedAt"`
}

// androidReleaseResponse 在 latest.json 之外给出本站的下载地址(相对路径,App 按自己的基址拼)。
type androidReleaseResponse struct {
	androidRelease
	URL string `json:"url"`
}

// AndroidRelease 服务安卓 App 的更新信息与安装包。仓库是私有的,App 不能匿名读 GitHub Releases,
// 所以由站点自己发:
//
//	GET /api/app/android/latest      → latest.json(no-cache)+ url
//	GET /api/app/android/<apk 名>     → 安装包(支持 Range;只认 latest.json 里声明的文件名)
//	GET /api/app/android/latest.apk  → 302 到当前安装包(给首次安装用的固定链接)
//
// dir 为空 = 本站没配,两条都回 404 JSON(不能落到 SPA 的 index.html,App 会把 HTML 当 JSON 解析)。
// 不需要登录:安装包不是秘密,未登录时也该能检查更新。
func AndroidRelease(dir string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			writeJSONError(w, http.StatusMethodNotAllowed, "method_not_allowed")
			return
		}
		name := strings.TrimPrefix(r.URL.Path, AndroidReleasePath)
		if dir == "" {
			writeJSONError(w, http.StatusNotFound, "not_configured")
			return
		}
		rel, err := readAndroidRelease(dir)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				writeJSONError(w, http.StatusNotFound, "no_release")
				return
			}
			writeJSONError(w, http.StatusInternalServerError, "bad_release")
			return
		}
		if name == "latest" {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Header().Set("Cache-Control", "no-cache")
			_ = json.NewEncoder(w).Encode(androidReleaseResponse{androidRelease: rel, URL: AndroidReleasePath + rel.APK})
			return
		}
		if name != rel.APK {
			if name == "latest.apk" {
				w.Header().Set("Cache-Control", "no-cache")
				http.Redirect(w, r, AndroidReleasePath+rel.APK, http.StatusFound)
				return
			}
			writeJSONError(w, http.StatusNotFound, "not_found")
			return
		}
		f, err := os.Open(filepath.Join(dir, rel.APK))
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
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="`+rel.APK+`"`)
		// 文件名带版本号,内容不会变
		w.Header().Set("Cache-Control", "public, max-age=86400")
		http.ServeContent(w, r, rel.APK, st.ModTime(), f)
	})
}

// readAndroidRelease 读并校验 latest.json:文件名只能是同目录下的 .apk,版本号必须为正,
// 必须带安装包的 SHA-256(App 下载后据此校验,没有校验值的发布不给出去)。
func readAndroidRelease(dir string) (androidRelease, error) {
	var rel androidRelease
	b, err := os.ReadFile(filepath.Join(dir, "latest.json"))
	if err != nil {
		return rel, err
	}
	if err := json.Unmarshal(b, &rel); err != nil {
		return rel, err
	}
	if rel.VersionCode <= 0 || rel.VersionName == "" || !strings.HasSuffix(rel.APK, ".apk") ||
		rel.APK != filepath.Base(rel.APK) || strings.ContainsAny(rel.APK, `/\"`) || strings.HasPrefix(rel.APK, ".") {
		return rel, errors.New("latest.json 不完整或 apk 文件名非法")
	}
	if !isHexSHA256(rel.SHA256) {
		return rel, errors.New("latest.json 的 sha256 不是 64 位十六进制")
	}
	if rel.PublishedAt != "" {
		if _, err := time.Parse(time.RFC3339, rel.PublishedAt); err != nil {
			return rel, errors.New("latest.json 的 publishedAt 不是 RFC3339")
		}
	}
	return rel, nil
}

func isHexSHA256(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}

func writeJSONError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code})
}

// AppLanding 对发布页 /app 直出带 og 标签的 index.html(与 ShareLanding 同一个做法):
// 把链接发进微信 / Telegram 时卡片上有标题与安装包版本,浏览器随后照常加载 SPA。
// 没发安卓包或 latest.json 不合法时只是少写版本,页面照样出。
func AppLanding(dist fs.FS, releaseDir string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		idx, err := fs.ReadFile(dist, "index.html")
		if err != nil {
			http.Error(w, "gopan: 前端未构建,先跑 make build", http.StatusNotFound)
			return
		}
		title := "gopan 云盘 · 安卓 App 与网页版"
		desc := "网页版打开即用。"
		if releaseDir != "" {
			if rel, err := readAndroidRelease(releaseDir); err == nil {
				desc = fmt.Sprintf("安卓 App %s(%.1f MB),网页版打开即用。",
					rel.VersionName, float64(rel.Size)/(1<<20))
			}
		}
		meta := fmt.Sprintf(
			`<meta property="og:title" content="%s"><meta property="og:description" content="%s"><meta property="og:type" content="website"><meta name="description" content="%s">`,
			html.EscapeString(title), html.EscapeString(desc), html.EscapeString(desc))
		page := strings.Replace(string(idx), "<head>", "<head>"+meta, 1)
		page = strings.Replace(page, "<title>gopan 云盘</title>", "<title>gopan 云盘 · 安卓 App 与更新日志</title>", 1)
		// 与 SPAHandler 的 index.html 一样不缓存:发版后旧页面引用的 /assets/ 指纹文件已经不在了
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(page))
	})
}
