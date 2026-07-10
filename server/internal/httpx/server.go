package httpx

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/99designs/gqlgen/graphql"
	gqlhandler "github.com/99designs/gqlgen/graphql/handler"
	"github.com/99designs/gqlgen/graphql/handler/extension"
	"github.com/99designs/gqlgen/graphql/handler/lru"
	"github.com/99designs/gqlgen/graphql/handler/transport"
	"github.com/vektah/gqlparser/v2/ast"
	"github.com/vektah/gqlparser/v2/gqlerror"

	"github.com/yophon/gopan/server/internal/service"
)

// NewGraphQLHandler 组装 gqlgen server:错误转 extensions.code、复杂度限制。
func NewGraphQLHandler(es graphql.ExecutableSchema, devMode bool) http.Handler {
	srv := gqlhandler.New(es)
	srv.AddTransport(transport.POST{})
	srv.SetQueryCache(lru.New[*ast.QueryDocument](256))
	srv.Use(extension.FixedComplexityLimit(300))
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

func WithLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		slog.Info("http", "method", r.Method, "path", r.URL.Path, "dur_ms", time.Since(start).Milliseconds())
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
