package httpx

import (
	"errors"
	"html"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/mdrender"
	"github.com/yophon/gopan/server/internal/service"
)

// markdownCSP 渲染出来的页面与 /x/ 的 SVG 一样放进沙箱:不能跑脚本、没有本站的源;
// 外链能在新窗口打开(并且新窗口不受沙箱限制);只许本站页面把它嵌进 iframe。
const markdownCSP = "sandbox allow-popups allow-popups-to-escape-sandbox; default-src 'none'; img-src * data:; " +
	"style-src 'unsafe-inline'; frame-ancestors 'self'"

// MarkdownHandler Markdown 渲染(doc/23 五.2):
//
//	GET /md/{node}?token=<access>[&theme=light|dark][&pt=<px>][&pb=<px>]  渲染成一页 HTML;pt / pb:正文前后多留的空白
//	                                                       (App 的阅读页铺满整屏,顶栏浮在上面、底部导航浮在下面,要让出它们)
//	GET /md/{node}/f?p=<相对路径>&token=<access>           那篇里的相对路径:图片 / 文件 302 到签名地址,.md 跳到它的渲染页
//
// 相对路径放在查询参数里而不是路径里:路径里的 ../ 会被浏览器和路由先规范化掉。
//
// token 可以是登录用户的,也可以是分享访客的(节点须在分享范围内);也接受 Authorization 头。
func MarkdownHandler(auth *service.Auth, md *service.Markdown, shares *service.Shares) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		token := q.Get("token")
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			token = strings.TrimPrefix(h, "Bearer ")
		}
		ident, err := auth.ParseAccessContext(r.Context(), token)
		if err != nil {
			mdError(w, http.StatusUnauthorized, "登录已过期,关掉重新打开")
			return
		}
		nodeID, err := uuid.Parse(r.PathValue("node"))
		if err != nil {
			mdError(w, http.StatusBadRequest, "地址不对")
			return
		}
		if err := shares.Authorize(r.Context(), ident, nodeID); err != nil {
			mdServiceErr(w, err)
			return
		}
		theme := q.Get("theme")
		if theme != "light" && theme != "dark" {
			theme = ""
		}
		padTop, padBottom := mdPad(q.Get("pt")), mdPad(q.Get("pb"))
		suffix := "?token=" + url.QueryEscape(token)
		if theme != "" {
			suffix += "&theme=" + theme
		}
		if padTop > 0 {
			suffix += "&pt=" + strconv.Itoa(padTop)
		}
		if padBottom > 0 {
			suffix += "&pb=" + strconv.Itoa(padBottom)
		}

		if strings.HasSuffix(r.URL.Path, "/f") {
			rel := q.Get("p")
			if rel == "" {
				mdError(w, http.StatusBadRequest, "地址不对")
				return
			}
			row, err := md.Node(r.Context(), ident.UserID, nodeID) // 只要它所在的文件夹,不读内容
			if err != nil {
				mdServiceErr(w, err)
				return
			}
			target, err := md.Resolve(r.Context(), ident.UserID, row.ParentID, rel)
			if err == nil {
				err = shares.Authorize(r.Context(), ident, target.ID)
			}
			if err != nil {
				mdServiceErr(w, err)
				return
			}
			if service.IsMarkdownName(target.Name) {
				http.Redirect(w, r, "/md/"+target.ID.String()+suffix, http.StatusFound)
				return
			}
			u, err := md.AssetURL(r.Context(), target)
			if err != nil {
				mdServiceErr(w, err)
				return
			}
			w.Header().Set("Cache-Control", "private, max-age=600")
			http.Redirect(w, r, u, http.StatusFound)
			return
		}

		row, src, err := md.Load(r.Context(), ident.UserID, nodeID)
		if err != nil {
			mdServiceErr(w, err)
			return
		}
		base := "/md/" + nodeID.String() + "/f?p="
		tail := "&" + strings.TrimPrefix(suffix, "?")
		out, err := mdrender.Render(src, mdrender.Options{Theme: theme, PadTop: padTop, PadBottom: padBottom, Title: row.Name, Resolve: func(rel string, _ bool) string {
			return base + url.QueryEscape(strings.TrimPrefix(rel, "./")) + tail
		}})
		if err != nil {
			slog.Warn("render markdown", "node", nodeID, "err", err)
			mdError(w, http.StatusUnprocessableEntity, "渲染失败,请看源文件")
			return
		}
		h := w.Header()
		h.Set("Content-Type", "text/html; charset=utf-8")
		h.Set("Content-Security-Policy", markdownCSP)
		h.Set("X-Frame-Options", "SAMEORIGIN")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "private, no-store")
		_, _ = w.Write(out)
	})
}

func mdServiceErr(w http.ResponseWriter, err error) {
	var se *service.Error
	if !errors.As(err, &se) {
		slog.Error("markdown", "err", err)
		mdError(w, http.StatusInternalServerError, "内部错误")
		return
	}
	status := map[string]int{
		"NOT_FOUND": http.StatusNotFound, "FORBIDDEN": http.StatusForbidden, "SHARE_EXPIRED": http.StatusGone,
		"UNSUPPORTED_TYPE": http.StatusUnsupportedMediaType, "TOO_LARGE": http.StatusRequestEntityTooLarge,
	}[se.Code]
	if status == 0 {
		status = http.StatusBadRequest
	}
	msg := se.Message
	if se.Code == "NOT_FOUND" {
		msg = "找不到这个文件(可能已删除或移动了)"
	}
	mdError(w, status, msg)
}

// mdError 错误也回一页 HTML(在 iframe 里显示),同样放进沙箱。
func mdError(w http.ResponseWriter, status int, msg string) {
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Content-Security-Policy", markdownCSP)
	h.Set("X-Frame-Options", "SAMEORIGIN")
	h.Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`<!doctype html><meta charset="utf-8"><body style="font:15px/1.6 sans-serif;color:#888;text-align:center;padding:48px 16px">` +
		html.EscapeString(msg) + `</body>`))
}

// mdPad 前后留白的参数:0–400 px,不认识的当 0
func mdPad(v string) int {
	n, _ := strconv.Atoi(v)
	return min(max(n, 0), 400)
}
