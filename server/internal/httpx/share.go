package httpx

import (
	"errors"
	"fmt"
	"html"
	"io/fs"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

// ShareLanding 对 /s/{token} 服务端直出带 og 标签的 index.html,
// 微信/群聊爬虫拿到卡片预览,浏览器随后照常加载 SPA。
func ShareLanding(dist fs.FS, shares *service.Shares) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		idx, err := fs.ReadFile(dist, "index.html")
		if err != nil {
			http.Error(w, "gopan: 前端未构建,先跑 make build", http.StatusNotFound)
			return
		}
		page := string(idx)
		if info, err := shares.Info(r.Context(), r.PathValue("token")); err == nil {
			title := info.Name
			desc := "通过 gopan 云盘分享的文件"
			if info.Kind == "folder" {
				desc = "通过 gopan 云盘分享的文件夹"
			}
			if info.Expired {
				title, desc = "分享已失效", "该分享链接已过期或被取消"
			}
			meta := fmt.Sprintf(
				`<meta property="og:title" content="%s"><meta property="og:description" content="%s"><meta property="og:type" content="website">`,
				html.EscapeString(title), html.EscapeString(desc))
			page = strings.Replace(page, "<head>", "<head>"+meta, 1)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(page))
	})
}

// PackHandler 文件夹/多选打包下载:流式 zip,唯一经过 Go 的字节流。
// GET /pack?nodes=id1,id2&token=<access>(下载链接没法带 Authorization 头,token 走查询串)。
func PackHandler(auth *service.Auth, packer *service.Packer) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := r.URL.Query().Get("token")
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			token = strings.TrimPrefix(h, "Bearer ")
		}
		ident, err := auth.ParseAccess(token)
		if err != nil {
			http.Error(w, "未登录或凭证已过期", http.StatusUnauthorized)
			return
		}

		var ids []uuid.UUID
		for _, raw := range strings.Split(r.URL.Query().Get("nodes"), ",") {
			raw = strings.TrimSpace(raw)
			if raw == "" {
				continue
			}
			id, err := uuid.Parse(raw)
			if err != nil {
				http.Error(w, "非法节点 ID", http.StatusBadRequest)
				return
			}
			ids = append(ids, id)
		}
		servePack(w, r, packer, ident, ids)
	})
}

// MCPPackHandler 接受仅能下载指定节点的短期票据。票据不是登录 JWT，不能访问
// GraphQL 或 MCP；适合 Agent 拿到 URL 后交给自己的 HTTP 工具直接下载。
func MCPPackHandler(tickets *service.PackTickets, packer *service.Packer) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ident, ids, err := tickets.Parse(r.PathValue("ticket"))
		if err != nil {
			http.Error(w, "下载票据无效或已过期", http.StatusUnauthorized)
			return
		}
		servePack(w, r, packer, ident, ids)
	})
}

func servePack(w http.ResponseWriter, r *http.Request, packer *service.Packer, ident *service.Identity, ids []uuid.UUID) {
	// 打包是唯一过 Go 的字节流:限频 + 全局并发上限
	release, err := packer.Gate(ident)
	if err != nil {
		w.Header().Set("Retry-After", "30")
		http.Error(w, "打包请求过于频繁,稍后再试", http.StatusTooManyRequests)
		return
	}
	defer release()

	// 打包是分钟级流式响应,豁免 Server 级 60s WriteTimeout(过闸后才放开,
	// 名额被并发闸限死)。M9 接 WebDAV 时发现的 v1 潜伏问题:全局超时会掐断长下载。
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})

	name, entries, err := packer.Collect(r.Context(), ident, ids)
	if err != nil {
		status := http.StatusInternalServerError
		msg := "打包失败"
		var se *service.Error
		if errors.As(err, &se) {
			msg = se.Message
			switch se.Code {
			case "NOT_FOUND":
				status = http.StatusNotFound
			case "FORBIDDEN", "UNAUTHENTICATED":
				status = http.StatusForbidden
			case "SHARE_EXPIRED":
				status = http.StatusGone
			case "PACK_TOO_LARGE":
				status = http.StatusRequestEntityTooLarge
			case "INVALID_INPUT":
				status = http.StatusBadRequest
			}
		} else {
			slog.ErrorContext(r.Context(), "pack collect", "err", err)
		}
		http.Error(w, msg, status)
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf(`attachment; filename*=UTF-8''%s`, url.PathEscape(name)))
	if err := packer.Stream(r.Context(), entries, w); err != nil {
		// 响应头已发出,只能记日志断流,浏览器会看到下载失败
		slog.ErrorContext(r.Context(), "pack stream", "err", err)
	}
}
