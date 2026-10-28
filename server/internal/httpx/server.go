package httpx

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/99designs/gqlgen/graphql"
	gqlhandler "github.com/99designs/gqlgen/graphql/handler"
	"github.com/99designs/gqlgen/graphql/handler/extension"
	"github.com/99designs/gqlgen/graphql/handler/lru"
	"github.com/99designs/gqlgen/graphql/handler/transport"
	"github.com/vektah/gqlparser/v2/ast"
	"github.com/vektah/gqlparser/v2/gqlerror"

	"github.com/yophon/gopan/server/internal/metrics"
	"github.com/yophon/gopan/server/internal/service"
)

const maxQueryDepth = 8

// NewGraphQLHandler 组装 gqlgen server:错误转 extensions.code、深度/复杂度限制。
func NewGraphQLHandler(es graphql.ExecutableSchema, devMode bool) http.Handler {
	srv := gqlhandler.New(es)
	srv.AddTransport(transport.POST{})
	srv.SetQueryCache(lru.New[*ast.QueryDocument](256))
	srv.Use(extension.FixedComplexityLimit(300))
	// gqlgen 只自带复杂度限制,深度限制自己算(fragment 展开跟进,防循环)
	srv.AroundOperations(func(ctx context.Context, next graphql.OperationHandler) graphql.ResponseHandler {
		oc := graphql.GetOperationContext(ctx)
		frags := make(map[string]*ast.FragmentDefinition, len(oc.Doc.Fragments))
		for _, f := range oc.Doc.Fragments {
			frags[f.Name] = f
		}
		if d := queryDepth(oc.Operation.SelectionSet, frags, map[string]bool{}); d > maxQueryDepth {
			return func(ctx context.Context) *graphql.Response {
				return &graphql.Response{Errors: gqlerror.List{{
					Message:    "查询嵌套过深",
					Extensions: map[string]any{"code": "QUERY_TOO_DEEP"},
				}}}
			}
		}
		return next(ctx)
	})
	if devMode {
		srv.Use(extension.Introspection{})
	}
	srv.SetErrorPresenter(presentError)
	return srv
}

// gqlgen 在 resolver 之前就产出的错误:语法错误、字段/参数校验错误。这些来自客户端
// 写错了查询,原文对调用方有用、对服务端无泄露,放行并只记 info。
var clientQueryErrorCodes = map[string]bool{
	"GRAPHQL_PARSE_FAILED":      true,
	"GRAPHQL_VALIDATION_FAILED": true,
}

// presentError 把错误翻译成 extensions.code:
//   - service.Error:业务错误,code / message 原样透出;
//   - gqlgen 的语法/校验错误:原样透出(含 code),info 日志;
//   - 其余(panic 恢复、DB/对象存储故障、未预期的 Go error):统一 INTERNAL/"内部错误",
//     error 日志带原始错误。对外不泄露内部细节是底线,所以兜底一定是打码。
func presentError(ctx context.Context, err error) *gqlerror.Error {
	ge := graphql.DefaultErrorPresenter(ctx, err)
	var se *service.Error
	if errors.As(err, &se) {
		ge.Message = se.Message
		ge.Extensions = map[string]any{"code": se.Code}
		return ge
	}
	var qe *gqlerror.Error
	if errors.As(err, &qe) {
		if code, _ := qe.Extensions["code"].(string); clientQueryErrorCodes[code] {
			slog.InfoContext(ctx, "graphql query rejected", "code", code, "err", qe.Message)
			return ge
		}
	}
	slog.ErrorContext(ctx, "internal error", "err", err)
	ge.Message = "内部错误"
	ge.Extensions = map[string]any{"code": "INTERNAL"}
	return ge
}

// queryDepth 计算 SelectionSet 的字段嵌套深度。Field 加一层;inline fragment
// 不加层;命名 fragment 按定义展开,seen 防循环引用打成死递归。
func queryDepth(sel ast.SelectionSet, frags map[string]*ast.FragmentDefinition, seen map[string]bool) int {
	max := 0
	for _, s := range sel {
		d := 0
		switch v := s.(type) {
		case *ast.Field:
			d = 1 + queryDepth(v.SelectionSet, frags, seen)
		case *ast.InlineFragment:
			d = queryDepth(v.SelectionSet, frags, seen)
		case *ast.FragmentSpread:
			if f, ok := frags[v.Name]; ok && !seen[v.Name] {
				seen[v.Name] = true
				d = queryDepth(f.SelectionSet, frags, seen)
				delete(seen, v.Name)
			}
		}
		if d > max {
			max = d
		}
	}
	return max
}

// ClientIPFrom 算客户端地址。只有 RemoteAddr 落在 trustedProxies 里时才认
// X-Forwarded-For —— 那个头客户端能随便伪造,RemoteAddr 伪造不了。
//
// 取 XFF 里**最右一个不属于可信代理**的地址:nginx 的 $proxy_add_x_forwarded_for
// 是把上游已有的值追加在后面,所以最右那段才是直连反代的真实客户端,别人往左边
// 塞多少假地址都不影响结果。
//
// trustedProxies 为空时行为与历史一致:直接用 RemoteAddr(server_more_test.go 里
// "伪造 XFF 不被信任"的断言正是钉这个默认值)。
func ClientIPFrom(r *http.Request, trustedProxies []*net.IPNet) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !ipInAny(host, trustedProxies) {
		return host
	}
	parts := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
	for i := len(parts) - 1; i >= 0; i-- {
		if cand := strings.TrimSpace(parts[i]); cand != "" && !ipInAny(cand, trustedProxies) {
			return cand
		}
	}
	return host
}

// WithForwardedTrust 在请求不是来自可信反代时删掉 X-Forwarded-Proto / X-Forwarded-Host。
// OAuth 与 MCP 用这两个头拼对外地址(metadata 里的 issuer、各 endpoint);绕过反代直连端口的客户端
// 若能伪造它们,就能污染返回给别人的 metadata。判定与 ClientIPFrom 同一套 trustedProxies,
// 所以经反代部署必须配 GOPAN_TRUSTED_PROXIES(生产 compose 已给 docker 私有网段的默认值)。
func WithForwardedTrust(next http.Handler, trustedProxies []*net.IPNet) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Forwarded-Proto") != "" || r.Header.Get("X-Forwarded-Host") != "" {
			host, _, err := net.SplitHostPort(r.RemoteAddr)
			if err != nil {
				host = r.RemoteAddr
			}
			if !ipInAny(host, trustedProxies) {
				r = r.Clone(r.Context())
				r.Header.Del("X-Forwarded-Proto")
				r.Header.Del("X-Forwarded-Host")
			}
		}
		next.ServeHTTP(w, r)
	})
}

func ipInAny(host string, nets []*net.IPNet) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	for _, n := range nets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// Middleware:注入 http 载体 + 解析 Bearer(解析失败不拦截,由 resolver 决定是否需要身份)。
func WithAuth(next http.Handler, auth *service.Auth, trustedProxies []*net.IPNet) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := ClientIPFrom(r, trustedProxies)
		ctx := context.WithValue(r.Context(), keyHTTP, &httpCarrier{w: w, r: r, ip: ip})
		// UA 经 context 往 service 层带,省得为它给一串方法签名加参数。
		ctx = service.WithClientMeta(ctx, service.ClientMeta{IP: ip, UA: r.UserAgent()})
		// 桌面端发进传输助手的消息记成 desktop(doc/29)
		if service.IsDesktopUA(r.UserAgent()) {
			ctx = service.WithChatSource(ctx, service.ChatSourceDesktop)
		}
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			if id, err := auth.ParseAccessContext(ctx, strings.TrimPrefix(h, "Bearer ")); err == nil {
				ctx = context.WithValue(ctx, keyIdentity, id)
				auth.TouchSession(ctx, id.FamilyID) // 推进 last_seen,节流在 Auth 内部
				if id.Scope == service.ScopeUser {
					// 动态(doc/23 二):来源是这台设备,一次请求里的改动算一批
					ctx = service.WithBatch(service.WithActor(ctx, service.SessionActor(id.FamilyID, r.UserAgent())))
				}
			}
		}
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// WithSecurityHeaders 全站安全响应头。CSP 是宽松基线:img/media/connect 放开
// http/https 是给"预签名走独立子域"的部署留路(默认同域反代其实 'self' 就够);
// style unsafe-inline 是 Element Plus 动态样式的现实;dev 跳过 CSP(vite 注入脚本会撞)。
// hash-wasm 在上传 Worker 内编译 WebAssembly;仅放行 wasm,不放行 JS eval。
//
// v2.x 的安卓壳(Capacitor 远程模式)把线上页面装进 WebView 时会往 HTML 里注入一段内联的
// bridge 脚本,被 script-src 'self' 挡住插件就全哑了。壳的 UA 带 "GopanApp/" 标记,
// 只对它的请求给 script-src 加 'unsafe-inline';普通浏览器的 CSP 不变。UA 可伪造,但伪造者
// 只能放宽自己那次响应的 CSP,拿不到别人的。v3 起由原生 App 取代,壳的源码已从仓库删除;
// 这条放行留给还没升级的旧壳,确认没人再用后连同 AppUserAgentMark 一起删。
func WithSecurityHeaders(next http.Handler, devMode bool) http.Handler {
	const cspBase = "default-src 'self'; img-src 'self' data: blob: http: https:; " +
		"media-src 'self' blob: http: https:; connect-src 'self' http: https:; " +
		"style-src 'self' 'unsafe-inline'; worker-src 'self' blob:; frame-ancestors 'none'; "
	const csp = cspBase + "script-src 'self' 'wasm-unsafe-eval'"
	const cspApp = cspBase + "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'"
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		if !devMode {
			if IsAppWebView(r) {
				h.Set("Content-Security-Policy", cspApp)
			} else {
				h.Set("Content-Security-Policy", csp)
			}
		}
		next.ServeHTTP(w, r)
	})
}

// AppUserAgentMark 是 v2.x 安卓壳 WebView 的 UA 标记(capacitor.config 的 appendUserAgent)。
const AppUserAgentMark = "GopanApp/"

// IsAppWebView 判断请求是否来自安卓壳。
func IsAppWebView(r *http.Request) bool {
	return strings.Contains(r.UserAgent(), AppUserAgentMark)
}

// statusRecorder 捕获响应码给指标用。
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// Unwrap 让 http.NewResponseController 够到底下的连接:打包、WebDAV、直链这些长响应靠它放开
// Server 级的 60s WriteTimeout,自动刷新的推送连接靠它 Flush。少了它两样都悄悄失效。
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// normalizePath 归一指标的 path 标签,防 /s/{token} 这类动态段打爆基数。
func normalizePath(p string) string {
	switch {
	case p == "/query", p == "/healthz", p == "/pack", p == "/mcp", p == "/app", p == "/api/events":
		return p
	case strings.HasPrefix(p, "/oauth/"):
		return "/oauth/:endpoint"
	case strings.HasPrefix(p, "/.well-known/oauth-"):
		return "/.well-known/oauth-*"
	case strings.HasPrefix(p, "/s/"):
		return "/s/:token"
	case strings.HasPrefix(p, "/c/"):
		return "/c/:token"
	case strings.HasPrefix(p, "/mcp-download/"):
		return "/mcp-download/:ticket"
	case strings.HasPrefix(p, "/arc/"):
		return "/arc/:node/:idx"
	case strings.HasPrefix(p, "/x/"):
		return "/x/:token"
	case strings.HasPrefix(p, "/sub/"):
		return "/sub/:node"
	case strings.HasPrefix(p, "/remux/"):
		return "/remux/:sha"
	case strings.HasPrefix(p, "/lrc/"):
		return "/lrc/:node"
	case strings.HasPrefix(p, "/md/"):
		return "/md/:node"
	case p == "/api/img/upload":
		return p
	case p == "/dav" || strings.HasPrefix(p, "/dav/"):
		return "/dav"
	case strings.HasPrefix(p, AndroidReleasePath):
		return AndroidReleasePath + ":file"
	default:
		return "/spa"
	}
}

func WithLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		dur := time.Since(start)
		np := normalizePath(r.URL.Path)
		slog.Info("http", "method", r.Method, "path", np, "status", rec.status, "dur_ms", dur.Milliseconds())
		metrics.HTTPRequests.WithLabelValues(np, strconv.Itoa(rec.status)).Inc()
		metrics.HTTPDuration.WithLabelValues(np).Observe(dur.Seconds())
	})
}

// SPAHandler 服务 embed 的前端产物。已知静态文件直接给;带扩展名的未知路径
// 返回 404 而不是回落 index.html(否则 /sw.js 缺失时会拿到 HTML,MIME 报错还难查);
// 其余路径回落 index.html 交给前端路由。
func SPAHandler(dist fs.FS) http.Handler {
	fileServer := http.FileServerFS(dist)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(r.URL.Path, "/")
		if p != "" {
			if f, err := dist.Open(p); err == nil {
				f.Close()
				switch {
				case strings.HasPrefix(p, "assets/"):
					// 指纹产物:内容不变,钉死缓存
					w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
				case p == "sw.js" || p == "manifest.webmanifest":
					// SW 与清单必须每次真实请求,发版才生效
					w.Header().Set("Cache-Control", "no-cache")
				}
				if strings.HasSuffix(p, ".webmanifest") {
					// Go 内建 mime 表不一定认识 .webmanifest,nosniff 下猜错就是拒载
					w.Header().Set("Content-Type", "application/manifest+json")
				}
				fileServer.ServeHTTP(w, r)
				return
			}
			if path.Ext(p) != "" {
				http.NotFound(w, r)
				return
			}
		}
		idx, err := fs.ReadFile(dist, "index.html")
		if err != nil {
			http.Error(w, "gopan: 前端未构建,先跑 make build", http.StatusNotFound)
			return
		}
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(idx)
	})
}
