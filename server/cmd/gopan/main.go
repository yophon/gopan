package main

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	_ "time/tzdata" // 运行镜像不一定带时区库,GOPAN_TZ 要能解析

	"github.com/google/uuid"

	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib" // goose 走 database/sql,需要 pgx 的 stdlib 驱动
	"github.com/pressly/goose/v3"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/dav"
	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/graph"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/mcpserver"
	"github.com/yophon/gopan/server/internal/oauthserver"
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

// adminCmd 处理 admin 子命令(只连库,不起服务):
//
//	gopan admin promote <username>   把已注册用户提升为管理员(首个管理员只能从这里产生)
//	gopan admin notify --title T [--body B] [--level info|warn|alert]   给所有管理员发一条通知(备份脚本失败时用)
//	gopan admin notify --heartbeat backup   报平安:超过 36 小时没报就提醒管理员(doc/22 三.3)
func adminCmd(args []string) error {
	dbURL := os.Getenv("GOPAN_DB_URL")
	if len(args) >= 1 && args[0] == "notify" {
		if dbURL == "" {
			return fmt.Errorf("需要设置 GOPAN_DB_URL")
		}
		return notifyCmd(dbURL, args[1:])
	}
	if len(args) != 2 || args[0] != "promote" {
		return fmt.Errorf("用法:gopan admin promote <username> | gopan admin notify --title T [--body B] [--level warn] | gopan admin notify --heartbeat backup(需要 GOPAN_DB_URL)")
	}
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

// notifyCmd 只写库:推送由运行中的服务的投递循环发出去(30 秒内)。
func notifyCmd(dbURL string, args []string) error {
	fs := flag.NewFlagSet("notify", flag.ContinueOnError)
	title := fs.String("title", "", "标题")
	body := fs.String("body", "", "正文")
	level := fs.String("level", service.LvAlert, "info / warn / alert")
	heartbeat := fs.String("heartbeat", "", "报平安的名字(目前只有 backup)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	n := service.NewNotifier(pool, fetcher.Guard{}, strings.TrimRight(os.Getenv("GOPAN_PUBLIC_URL"), "/"))
	if *heartbeat != "" {
		return n.Heartbeat(ctx, *heartbeat)
	}
	if *title == "" {
		return fmt.Errorf("要给 --title")
	}
	if *level != service.LvInfo && *level != service.LvWarn && *level != service.LvAlert {
		return fmt.Errorf("--level 只能是 info / warn / alert")
	}
	n.NotifyAdmins(ctx, service.Event{
		Kind: service.EvBackup, Level: *level, Title: *title, Body: *body, Target: &service.Target{Kind: service.TgAdmin},
	})
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
	chats := service.NewChats(pool, nodes)
	uploads := service.NewUploads(pool, obj, nodes, cfg.PartSize, cfg.SessionTTL)

	previews := service.NewPreviews(pool, obj)
	previews.SetOfficeEnabled(cfg.GotenbergURL != "") // 未配 Gotenberg = 本次部署不提供 Office 预览
	remuxer := service.NewRemuxer(service.RemuxConfig{
		Dir: cfg.RemuxDir, CacheSize: cfg.RemuxCache, TTL: cfg.RemuxTTL, FFmpeg: cfg.FFmpegPath, FFprobe: cfg.FFprobePath,
	}, obj)
	previews.SetRemuxer(remuxer)
	shares := service.NewShares(q, auth)
	collects := service.NewCollects(pool, auth, uploads)
	shareUploads := service.NewShareUploads(pool, shares, uploads)
	content := service.NewContentSearch(pool, cfg.ContentIndex)
	admin := service.NewAdmin(q, cfg.DefaultQuota)
	admin.SetContentIndex(cfg.ContentIndex)
	appPass := service.NewAppPasswords(q)
	mcpTokens := service.NewMCPTokens(q)
	oauth := service.NewOAuth(q)
	packer := service.NewPacker(q, obj, shares)
	packTickets := service.NewPackTickets(cfg.JWTSecret, 10*time.Minute)
	wk := worker.New(pool, obj, nodes, cfg.TrashTTL, cfg.FFmpegPath, cfg.FFprobePath, cfg.GotenbergURL)
	wk.SetUploads(uploads)
	wk.SetCollects(collects)
	wk.SetTextIndex(cfg.ContentIndex, lookPathOrEmpty(cfg.PdftotextPath))
	uploads.SetEnqueue(wk.Enqueue)
	previews.SetEnqueue(wk.Enqueue)
	// 离线下载与压缩包(doc/21):后台任务有自己的 runner,不占 worker
	archives := service.NewArchives(pool, obj, uploads)
	archives.SetEnqueue(wk.Enqueue)
	wk.SetArchives(archives)
	jobs := service.NewJobs(pool, obj, uploads, service.FetchConfig{
		Enabled: cfg.Fetch, MaxSize: cfg.FetchMaxSize, Rate: cfg.FetchRate,
		Guard:     fetcher.Guard{AllowPrivate: cfg.FetchAllowPrivate, Deny: cfg.FetchDeny},
		UserAgent: "Mozilla/5.0 (compatible; gopan-offline-download)",
	})
	jobs.SetArchives(archives)
	// 影音、通知、直链与图床(doc/22)
	media := service.NewMedia(pool, obj)
	notifier := service.NewNotifier(pool, fetcher.Guard{AllowPrivate: cfg.NotifyAllowPrivate, Deny: cfg.FetchDeny}, cfg.PublicURL)
	links := service.NewLinks(pool, obj, uploads, notifier, service.LinkConfig{
		Enabled: cfg.Links, DailyBytes: cfg.LinkDailyBytes, MonthlyBytes: cfg.LinkMonthlyBytes, ImgMaxSize: cfg.ImgMaxSize,
		PublicURL: cfg.PublicURL, Loc: cfg.Location,
	})
	auth.SetNotifier(notifier)
	uploads.SetNotifier(notifier)
	nodes.SetNotifier(notifier)
	jobs.SetNotifier(notifier)
	mcpTokens.SetNotifier(notifier)
	appPass.SetNotifier(notifier)
	oauth.SetNotifier(notifier)
	wk.SetNotify(notifier, media)
	// 动态、空间、订阅、剪藏(doc/23)
	activities := service.NewActivities(pool, nodes)
	space := service.NewSpace(pool)
	markdown := service.NewMarkdown(pool, obj)
	fetchGuard := fetcher.Guard{AllowPrivate: cfg.FetchAllowPrivate, Deny: cfg.FetchDeny}
	clips := service.NewClips(pool, obj, jobs, uploads, fetchGuard, "Mozilla/5.0 (compatible; gopan-clip)")
	jobs.SetClips(clips)
	feeds := service.NewFeeds(pool, jobs, clips, nodes, uploads, notifier, fetchGuard, "gopan-feed (+https://github.com/yophon/gopan)")
	wk.SetV35(activities, feeds)
	// 收藏、最近与历史版本(doc/24)
	versions := service.NewVersions(pool, obj, service.VersionPolicy{Keep: cfg.VersionKeep, TTL: cfg.VersionTTL})
	uploads.SetVersionKeep(cfg.VersionKeep)
	activities.SetVersions(versions)
	wk.SetVersions(versions)
	go wk.Run(ctx, cfg.Workers)
	if remuxer != nil {
		go remuxer.Run(ctx)
	}
	go jobs.Run(ctx, cfg.JobWorkers)
	go notifier.Run(ctx)
	// 助手(doc/33):模型服务与 key、对话记录、助手的权限
	ai := service.NewAI(pool, admin, cfg.JWTSecret)
	aiChats := service.NewAIChats(pool)
	assistant := service.NewAssistant(pool)
	changes := service.NewChangeHub(pool) // 自动刷新(doc/32 二)
	go changes.Run(ctx)
	// 助手在服务器上运行(doc/34):每一轮起一个 qjs 进程,请求经本机回环地址回到这里
	aiRuns := service.NewAIRuns(pool, aiChats, auth, changes, notifier, service.AIRunsConfig{
		QJS: envOr("GOPAN_QJS", "/usr/local/bin/qjs"), Origin: loopbackURL(cfg.Listen), PublicURL: cfg.PublicURL,
		Max: envIntOr("GOPAN_AI_RUNS", 2),
	})
	go aiRuns.Run(ctx)
	linksDone := make(chan struct{})
	go func() { links.Run(ctx); close(linksDone) }()

	es := graph.NewExecutableSchema(graph.Config{Resolvers: &graph.Resolver{
		Cfg: cfg, Auth: auth, Nodes: nodes, Uploads: uploads, Previews: previews, Shares: shares, Admin: admin, AppPass: appPass, MCPTokens: mcpTokens, OAuth: oauth, Chats: chats, Collects: collects, ShareUploads: shareUploads, Content: content, Jobs: jobs, Archives: archives,
		Media: media, Notify: notifier, Links: links,
		Activities: activities, Space: space, Feeds: feeds, Clips: clips,
		Versions: versions, AI: ai, AIChats: aiChats, Assistant: assistant, AIRuns: aiRuns,
	}})

	mux := http.NewServeMux()
	if cfg.IDIssuer != "" {
		userID, err := uuid.Parse(cfg.IDUserID)
		if err != nil {
			return fmt.Errorf("invalid GOPAN_ID_USER_ID")
		}
		provider, err := service.NewIDProvider(service.IDConfig{Issuer: cfg.IDIssuer, Origin: cfg.IDOrigin, ClientID: cfg.IDClientID, Secret: cfg.IDSecret, Subject: cfg.IDSubject, UserID: userID, Development: cfg.DevMode}, pool, auth)
		if err != nil {
			return err
		}
		mux.Handle("/api/auth/id/", provider.Handler(func(r *http.Request) string { return httpx.ClientIPFrom(r, cfg.TrustedProxies) }))
	} else {
		mux.HandleFunc("GET /api/auth/id/config", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"issuer":""}`))
		})
	}
	mux.Handle("POST /query", httpx.WithAuth(httpx.NewGraphQLHandler(es, cfg.DevMode), auth, cfg.TrustedProxies))
	mux.Handle("GET /api/events", httpx.EventsHandler(auth, changes))
	// 自己的模型服务转发时不许连内网(站点提供的不限)
	mux.Handle("POST /api/ai/{provider}/{path...}", httpx.AIProxyHandler(auth, ai, fetcher.Guard{Deny: cfg.FetchDeny}))
	oauthHTTP := oauthserver.New(oauth)
	mux.HandleFunc("GET /.well-known/oauth-authorization-server", oauthHTTP.AuthorizationMetadata)
	mux.HandleFunc("GET /.well-known/oauth-protected-resource", oauthHTTP.ProtectedResourceMetadata)
	mux.HandleFunc("GET /.well-known/oauth-protected-resource/mcp", oauthHTTP.ProtectedResourceMetadata)
	mux.HandleFunc("GET /.well-known/oauth-protected-resource/mcp/admin", oauthHTTP.ProtectedResourceMetadata)
	mux.HandleFunc("GET /oauth/authorize", oauthHTTP.Authorize)
	mux.HandleFunc("POST /oauth/token", oauthHTTP.Token)
	mux.HandleFunc("POST /oauth/register", oauthHTTP.Register)
	mux.Handle("/mcp", mcpserver.NewHandler(nodes, uploads, mcpTokens, oauth, packer, packTickets, chats, content, jobs, archives, links, activities, clips, mcpserver.WithAssistant(auth, assistant)))
	mux.Handle("/mcp/admin", mcpserver.NewAdminHandler(nodes, mcpTokens, oauth, cfg.DefaultQuota))
	mux.Handle("GET /mcp-download/{ticket}", httpx.MCPPackHandler(packTickets, packer))
	mux.Handle("GET /pack", httpx.PackHandler(auth, packer, shares, cfg.TrustedProxies))
	mux.Handle("GET /arc/{node}/{idx}", httpx.ArchiveEntryHandler(auth, archives, shares))
	mux.Handle("GET /sub/{node}", httpx.SubtitleHandler(auth, media, shares))
	mux.Handle("GET /remux/{sha}/{file}", httpx.RemuxHandler(remuxer))
	mux.Handle("GET /lrc/{node}", httpx.LyricsHandler(auth, media, shares))
	mdHandler := httpx.MarkdownHandler(auth, markdown, shares)
	mux.Handle("GET /md/{node}", mdHandler)
	mux.Handle("GET /md/{node}/f", mdHandler)
	mux.Handle("POST /md/{node}/preview", mdHandler)
	linkHandler := httpx.LinkHandler(links, obj, cfg.TrustedProxies)
	mux.Handle("GET /x/{token}", linkHandler)
	mux.Handle("GET /x/{token}/{name...}", linkHandler)
	mux.Handle("POST /api/img/upload", httpx.ImageUploadHandler(auth, mcpTokens, links, cfg.TrustedProxies))
	// WebDAV:应用密码 Basic 认证,/dav 与 /dav/ 都接(Finder/资源管理器会裸打前缀)
	davHandler := dav.Handler(appPass, dav.NewBackend(q, obj, nodes, uploads), cfg.TrustedProxies)
	mux.Handle("/dav", davHandler)
	mux.Handle("/dav/", davHandler)
	// 安卓 App 的检查更新与安装包(未配置时也挂上,回 404 JSON,别让 SPA 拿 index.html 顶替)
	mux.Handle(httpx.AndroidReleasePath, httpx.AndroidRelease(cfg.AppReleaseDir))
	// 桌面端(Windows,doc/29)的更新清单与安装包:放在发布目录的 desktop/ 子目录
	desktopReleaseDir := ""
	if cfg.AppReleaseDir != "" {
		desktopReleaseDir = filepath.Join(cfg.AppReleaseDir, "desktop")
	}
	mux.Handle(httpx.DesktopReleasePath, httpx.DesktopRelease(desktopReleaseDir))
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
	mux.Handle("GET /s/{token}/og", httpx.ShareOGImage(shares, previews))
	mux.Handle("GET /c/{token}", httpx.CollectLanding(dist, collects))
	// 发布页(功能介绍、安卓安装包、更新日志):SPA 页面,服务端只补 og 标签
	mux.Handle("GET /app", httpx.AppLanding(dist, cfg.AppReleaseDir))
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
		Handler:           httpx.WithLogging(httpx.WithSecurityHeaders(httpx.WithForwardedTrust(mux, cfg.TrustedProxies), cfg.DevMode)),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	srv.RegisterOnShutdown(changes.Close) // 推送连接是长连接,不关掉 Shutdown 要等满超时

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
		<-linksDone // 直链计数冲进库再退
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

// lookPathOrEmpty 找不到可执行文件时返回空串:内容索引照开,只是 PDF 记为不支持。
func lookPathOrEmpty(name string) string {
	if name == "" {
		return ""
	}
	p, err := exec.LookPath(name)
	if err != nil {
		slog.Warn("pdftotext not found, PDF text will not be indexed", "path", name)
		return ""
	}
	return p
}

// loopbackURL 本机访问自己的地址(服务器上跑的助手用):":8080" → http://127.0.0.1:8080
func loopbackURL(listen string) string {
	host, port, err := net.SplitHostPort(listen)
	if err != nil {
		return "http://127.0.0.1:8080"
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return "http://" + net.JoinHostPort(host, port)
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envIntOr(key string, def int) int {
	if n, err := strconv.Atoi(os.Getenv(key)); err == nil && n > 0 {
		return n
	}
	return def
}
