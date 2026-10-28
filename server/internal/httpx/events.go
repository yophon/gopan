package httpx

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/yophon/gopan/server/internal/service"
)

// 自动刷新的推送连接(doc/32 二):GET /api/events,Server-Sent Events。
//
// 只认 Authorization: Bearer(不收 ?token=,免得凭据进访问日志),网页用 fetch 读流,App 用 OkHttp。
// 每条是 data: {"t":"files","f":["<文件夹 id>",""],"trash":true} 这样的 JSON,客户端据此重拉。
// 连接最长 5 分钟就由服务端断开,客户端带着新凭据重连:移除设备、改密码最迟 5 分钟后连不上。

const (
	eventsPing   = 25 * time.Second // 反代(nginx 300s、Caddy)不至于当成闲置连接掐掉
	eventsMaxAge = 5 * time.Minute
)

func EventsHandler(auth *service.Auth, hub *service.ChangeHub) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			writeArchiveErr(w, &service.Error{Code: "UNAUTHENTICATED", Message: "未登录或凭证已过期"}, http.StatusUnauthorized)
			return
		}
		ident, err := auth.ParseAccessContext(r.Context(), strings.TrimPrefix(h, "Bearer "))
		if err != nil {
			writeArchiveErr(w, &service.Error{Code: "UNAUTHENTICATED", Message: "未登录或凭证已过期"}, http.StatusUnauthorized)
			return
		}
		if ident.Scope != service.ScopeUser {
			writeServiceErr(w, service.ErrForbidden)
			return
		}
		ch, cancel, ok := hub.Subscribe(ident.UserID)
		if !ok {
			w.Header().Set("Retry-After", "30")
			http.Error(w, "连接太多", http.StatusTooManyRequests)
			return
		}
		defer cancel()

		rc := http.NewResponseController(w)
		_ = rc.SetWriteDeadline(time.Time{}) // 豁免 Server 级 60s WriteTimeout
		hd := w.Header()
		hd.Set("Content-Type", "text/event-stream")
		// no-transform:Caddy 的 encode gzip 不会把它攒起来压缩;X-Accel-Buffering:nginx 不缓冲
		hd.Set("Cache-Control", "no-cache, no-transform")
		hd.Set("X-Accel-Buffering", "no")
		w.WriteHeader(http.StatusOK)
		if _, err := fmt.Fprint(w, "retry: 3000\n: ok\n\n"); err != nil {
			return
		}
		if rc.Flush() != nil {
			return
		}

		ping := time.NewTicker(eventsPing)
		defer ping.Stop()
		maxAge := time.NewTimer(eventsMaxAge)
		defer maxAge.Stop()
		for {
			select {
			case <-r.Context().Done():
				return
			case <-maxAge.C:
				return
			case <-ping.C:
				if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
					return
				}
			case c, live := <-ch:
				if !live {
					return // 关机
				}
				b, _ := json.Marshal(c)
				if _, err := fmt.Fprintf(w, "data: %s\n\n", b); err != nil {
					return
				}
			}
			if rc.Flush() != nil {
				return
			}
		}
	})
}
