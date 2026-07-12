package httpx

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
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
	srv.SetErrorPresenter(func(ctx context.Context, err error) *gqlerror.Error {
		ge := graphql.DefaultErrorPresenter(ctx, err)
		var se *service.Error
		if errors.As(err, &se) {
			ge.Message = se.Message
			ge.Extensions = map[string]any{"code": se.Code}
		} else {
			slog.ErrorContext(ctx, "internal error", "err", err)
			ge.Message = "内部错误"
			ge.Extensions = map[string]any{"code": "INTERNAL"}
		}
		return ge
	})
	return srv
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

// Middleware:注入 http 载体 + 解析 Bearer(解析失败不拦截,由 resolver 决定是否需要身份)。
func WithAuth(next http.Handler, auth *service.Auth) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		ctx := context.WithValue(r.Context(), keyHTTP, &httpCarrier{w: w, r: r, ip: ip})
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			if id, err := auth.ParseAccess(strings.TrimPrefix(h, "Bearer ")); err == nil {
				ctx = context.WithValue(ctx, keyIdentity, id)
			}
		}
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// WithSecurityHeaders 全站安全响应头。CSP 是宽松基线:img/media/connect 放开
// http/https 是给"预签名走独立子域"的部署留路(默认同域反代其实 'self' 就够);
// style unsafe-inline 是 Element Plus 动态样式的现实;dev 跳过 CSP(vite 注入脚本会撞)。
func WithSecurityHeaders(next http.Handler, devMode bool) http.Handler {
	const csp = "default-src 'self'; img-src 'self' data: blob: http: https:; " +
		"media-src 'self' blob: http: https:; connect-src 'self' http: https:; " +
		"style-src 'self' 'unsafe-inline'; script-src 'self'; worker-src 'self' blob:; " +
		"frame-ancestors 'none'"
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		if !devMode {
			h.Set("Content-Security-Policy", csp)
		}
		next.ServeHTTP(w, r)
	})
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

// normalizePath 归一指标的 path 标签,防 /s/{token} 这类动态段打爆基数。
func normalizePath(p string) string {
	switch {
	case p == "/query", p == "/healthz", p == "/pack":
		return p
	case strings.HasPrefix(p, "/s/"):
		return "/s/:token"
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
		slog.Info("http", "method", r.Method, "path", r.URL.Path, "status", rec.status, "dur_ms", dur.Milliseconds())
		np := normalizePath(r.URL.Path)
		metrics.HTTPRequests.WithLabelValues(np, strconv.Itoa(rec.status)).Inc()
		metrics.HTTPDuration.WithLabelValues(np).Observe(dur.Seconds())
	})
}

// SPAHandler 服务 embed 的前端产物,任意未知路径回落到 index.html。
func SPAHandler(dist fs.FS) http.Handler {
	fileServer := http.FileServerFS(dist)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(r.URL.Path, "/")
		if p != "" {
			if f, err := dist.Open(p); err == nil {
				f.Close()
				fileServer.ServeHTTP(w, r)
				return
			}
		}
		idx, err := fs.ReadFile(dist, "index.html")
		if err != nil {
			http.Error(w, "gopan: 前端未构建,先跑 make build", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(idx)
	})
}
