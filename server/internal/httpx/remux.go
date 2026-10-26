package httpx

import (
	"net/http"
	"strings"

	"github.com/yophon/gopan/server/internal/service"
)

// RemuxHandler 转成 mp4 的视频与内嵌字幕:GET /remux/{sha}/{file}?e=&s=(doc/31 一)。
// 地址由 node(id).preview.remux 签发,本身带签名和有效期,不再看登录(<video> 带不了 Authorization 头)。
func RemuxHandler(rm *service.Remuxer) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if rm == nil {
			http.NotFound(w, r)
			return
		}
		file := r.PathValue("file")
		f, err := rm.Open(r.PathValue("sha"), file, r.URL.Query().Get("e"), r.URL.Query().Get("s"))
		if err != nil {
			writeServiceErr(w, err)
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			http.Error(w, "读取失败", http.StatusInternalServerError)
			return
		}
		h := w.Header()
		if strings.HasSuffix(file, ".vtt") {
			h.Set("Content-Type", "text/vtt; charset=utf-8")
			h.Set("Content-Security-Policy", archiveCSP)
		} else {
			h.Set("Content-Type", "video/mp4")
		}
		h.Set("Cache-Control", "private, max-age=3600")
		http.ServeContent(w, r, file, st.ModTime(), f)
	})
}
