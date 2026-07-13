package main

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib" // goose 走 database/sql,需要 pgx 的 stdlib 驱动
	"github.com/pressly/goose/v3"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/graph"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
	"github.com/yophon/gopan/server/internal/worker"
)

//go:embed all:dist
var distFS embed.FS

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	// 子命令模式:gopan admin promote <username>。只连库,不起服务。
	if len(os.Args) > 1 && os.Args[1] == "admin" {
		if err := adminCmd(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if err := run(); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

// adminCmd 处理 admin 子命令。目前只有 promote:把已注册用户提升为管理员。
// 首个管理员只能从这里产生(不受注册开关与 API 鉴权约束,属主凭服务器权限自举)。
func adminCmd(args []string) error {
	if len(args) != 2 || args[0] != "promote" {
		return fmt.Errorf("用法:gopan admin promote <username>(需要 GOPAN_DB_URL)")
	}
	dbURL := os.Getenv("GOPAN_DB_URL")
	if dbURL == "" {
		return fmt.Errorf("需要设置 GOPAN_DB_URL")
	}
	if err := migrate(dbURL); err != nil {
		return err
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	n, err := store.New(pool).PromoteAdminByUsername(ctx, args[1])
	if err != nil {
		return err
	}
	if n == 0 {
		return fmt.Errorf("用户 %q 不存在(请先注册该账号)", args[1])
	}
	fmt.Printf("已将 %s 提升为管理员\n", args[1])
	return nil
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// 迁移(单机,启动时自动跑)
	if err := migrate(cfg.DBURL); err != nil {
		return err
	}

	pool, err := pgxpool.New(ctx, cfg.DBURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	if err := pool.Ping(ctx); err != nil {
		return err
	}

	obj, err := objstore.New(cfg)
	if err != nil {
		return err
	}
	if err := obj.EnsureBucket(ctx); err != nil {
		return err
	}

	q := store.New(pool)
	auth := service.NewAuth(q, cfg.JWTSecret, cfg.AccessTTL, cfg.RefreshTTL, cfg.RegisterOpen, cfg.DefaultQuota)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, cfg.PartSize, cfg.SessionTTL)

	previews := service.NewPreviews(pool, obj)
	previews.SetOfficeEnabled(cfg.GotenbergURL != "") // 未配 Gotenberg = 本次部署不提供 Office 预览
	shares := service.NewShares(q, auth)
	admin := service.NewAdmin(q, cfg.DefaultQuota)
	packer := service.NewPacker(q, obj, shares)
	wk := worker.New(pool, obj, nodes, cfg.TrashTTL, cfg.FFmpegPath, cfg.FFprobePath, cfg.GotenbergURL)
	uploads.SetEnqueue(wk.Enqueue)
	previews.SetEnqueue(wk.Enqueue)
	go wk.Run(ctx, 2)

	es := graph.NewExecutableSchema(graph.Config{Resolvers: &graph.Resolver{
		Cfg: cfg, Auth: auth, Nodes: nodes, Uploads: uploads, Previews: previews, Shares: shares, Admin: admin,
	}})

	mux := http.NewServeMux()
	mux.Handle("POST /query", httpx.WithAuth(httpx.NewGraphQLHandler(es, cfg.DevMode), auth))
	mux.Handle("GET /pack", httpx.PackHandler(auth, packer))
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if err := pool.Ping(r.Context()); err != nil {
			http.Error(w, "db: "+err.Error(), http.StatusServiceUnavailable)
			return
		}
		if err := obj.Ping(r.Context()); err != nil {
			http.Error(w, "objstore: "+err.Error(), http.StatusServiceUnavailable)
			return
		}
		w.Write([]byte("ok"))
	})
	dist, err := fs.Sub(distFS, "dist")
	if err != nil {
		return err
	}
	mux.Handle("GET /s/{token}", httpx.ShareLanding(dist, shares))
	mux.Handle("/", httpx.SPAHandler(dist))

	// pprof + /metrics 同挂内网端口(反代永不转发,与 pprof 同一安全边界)
	http.Handle("/metrics", promhttp.Handler())
	go func() {
		slog.Info("pprof/metrics listening", "addr", cfg.PprofListen)
		if err := http.ListenAndServe(cfg.PprofListen, nil); err != nil {
			slog.Warn("pprof server stopped", "err", err)
		}
	}()

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           httpx.WithLogging(httpx.WithSecurityHeaders(mux, cfg.DevMode)),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		slog.Info("gopan listening", "addr", cfg.Listen, "dev", cfg.DevMode)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		slog.Info("shutting down")
		shutCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	}
}

func migrate(dbURL string) error {
	sqlDB, err := sql.Open("pgx", dbURL)
	if err != nil {
		return err
	}
	defer sqlDB.Close()
	goose.SetBaseFS(db.Migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("postgres"); err != nil {
		return err
	}
	return goose.Up(sqlDB, "migrations")
}
