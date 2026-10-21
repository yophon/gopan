package config

import (
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	IDIssuer, IDOrigin, IDClientID, IDSecret, IDSubject, IDUserID string
	Listen                                                        string
	PprofListen                                                   string
	DBURL                                                         string
	JWTSecret                                                     []byte
	RegisterOpen                                                  bool
	DefaultQuota                                                  int64
	AccessTTL                                                     time.Duration
	RefreshTTL                                                    time.Duration
	TrashTTL                                                      time.Duration // 回收站保留期,过期自动彻删
	DevMode                                                       bool          // 关闭 cookie Secure、开 introspection
	// 信任其转发头的反代来源(IP 或 CIDR,逗号分隔)。空 = 谁都不信,一律用 RemoteAddr。
	// **生产在 nginx / docker 端口发布之后必须配**:否则后端看到的永远是网关地址,
	// 登录限速会退化成"全站共用一个桶",设备列表里的 IP 也没有意义。
	TrustedProxies []*net.IPNet

	// 对象存储
	S3Endpoint       string // 服务端内部访问地址
	S3PublicEndpoint string // 预签名 URL 用的外部地址(浏览器可达)
	S3Key            string
	S3Secret         string
	S3Bucket         string
	S3Region         string        // 显式 region,免去签名前的 GetBucketLocation 探测
	S3UseSSL         bool          // 内部地址是否走 TLS
	S3PublicUseSSL   bool          // 外部地址是否走 TLS(生产经反代通常 true,内网 false)
	PartSize         int64         // 分片大小,S3 规定除末片外 ≥5MiB
	SessionTTL       time.Duration // 上传会话有效期
	PresignPutTTL    time.Duration
	PresignGetTTL    time.Duration

	GotenbergURL string // 置空 = 本次部署不提供 Office 预览(小内存机器不部署 Gotenberg)
	// 安卓 App 的发版目录(latest.json + apk),App 内「检查更新」从这里读;置空 = 本站不提供
	AppReleaseDir string
	FFmpegPath    string
	FFprobePath   string
	Workers       int // 后台任务并发(hash 校验、缩略图、封面、转换),1–16
	// 内容搜索:false 关掉文字提取,内容搜索返回不可用;pdftotext 找不到时 PDF 不提(doc/20)
	ContentIndex  bool
	PdftotextPath string

	// 离线下载(doc/21 三):服务器替用户从链接拉文件。挡内网的规则见 fetcher.Guard
	Fetch             bool
	FetchMaxSize      int64          // 单文件上限
	FetchRate         int64          // 每个任务每秒最多读多少字节,0 = 不限
	FetchDeny         []netip.Prefix // 额外拒绝的网段(部署时把 VPS 自己的公网 IP 加进来)
	FetchAllowPrivate bool           // 放开私有网段(从局域网 NAS 拉文件)
	JobWorkers        int            // 后台任务(离线下载、解压)并发,1–8
}

func Load() (*Config, error) {
	c := &Config{
		IDIssuer: env("ID_ISSUER", ""), IDOrigin: env("ID_ORIGIN", ""), IDClientID: env("ID_CLIENT_ID", "gopan"), IDSecret: env("ID_SECRET", ""), IDSubject: env("ID_SUBJECT", ""), IDUserID: env("ID_USER_ID", ""),
		Listen:       env("LISTEN", ":8080"),
		PprofListen:  env("PPROF_LISTEN", "127.0.0.1:6060"),
		DBURL:        env("DB_URL", ""),
		RegisterOpen: envBool("REGISTER_OPEN", true),
		DefaultQuota: envInt64("DEFAULT_QUOTA", 100<<30),
		AccessTTL:    15 * time.Minute,
		RefreshTTL:   14 * 24 * time.Hour,
		TrashTTL:     envDuration("TRASH_TTL", 30*24*time.Hour),
		DevMode:      envBool("DEV_MODE", false),

		S3Endpoint:       env("S3_ENDPOINT", "127.0.0.1:9000"),
		S3PublicEndpoint: env("S3_PUBLIC_ENDPOINT", ""),
		S3Key:            env("S3_KEY", "gopan"),
		S3Secret:         env("S3_SECRET", "gopan-minio-dev"),
		S3Bucket:         env("S3_BUCKET", "gopan"),
		S3Region:         env("S3_REGION", "us-east-1"), // MinIO 默认 region
		S3UseSSL:         envBool("S3_USE_SSL", false),
		PartSize:         envInt64("PART_SIZE", 16<<20),
		SessionTTL:       48 * time.Hour,
		PresignPutTTL:    time.Hour,
		PresignGetTTL:    15 * time.Minute,

		GotenbergURL:  env("GOTENBERG_URL", "http://127.0.0.1:3100"),
		AppReleaseDir: env("APP_RELEASE_DIR", ""),
		FFmpegPath:    env("FFMPEG_PATH", "ffmpeg"),
		FFprobePath:   env("FFPROBE_PATH", "ffprobe"),
		Workers:       int(envInt64("WORKERS", 2)),
		ContentIndex:  envBool("CONTENT_INDEX", true),
		PdftotextPath: env("PDFTOTEXT_PATH", "pdftotext"),

		Fetch:             envBool("FETCH", true),
		FetchMaxSize:      envInt64("FETCH_MAX_SIZE", 20<<30),
		FetchRate:         envInt64("FETCH_RATE", 0),
		FetchAllowPrivate: envBool("FETCH_ALLOW_PRIVATE", false),
		JobWorkers:        int(envInt64("JOB_WORKERS", 1)),
	}
	tp, err := parseCIDRs(env("TRUSTED_PROXIES", ""))
	if err != nil {
		return nil, fmt.Errorf("GOPAN_TRUSTED_PROXIES: %w", err)
	}
	c.TrustedProxies = tp
	deny, err := parseCIDRs(env("FETCH_DENY", ""))
	if err != nil {
		return nil, fmt.Errorf("GOPAN_FETCH_DENY: %w", err)
	}
	for _, n := range deny {
		c.FetchDeny = append(c.FetchDeny, netip.MustParsePrefix(n.String()))
	}
	if c.JobWorkers < 1 || c.JobWorkers > 8 {
		return nil, fmt.Errorf("GOPAN_JOB_WORKERS 必须在 1 到 8 之间")
	}
	if c.FetchMaxSize <= 0 || c.FetchRate < 0 {
		return nil, fmt.Errorf("GOPAN_FETCH_MAX_SIZE 必须大于 0,GOPAN_FETCH_RATE 不能为负")
	}

	if c.DBURL == "" {
		return nil, fmt.Errorf("GOPAN_DB_URL is required")
	}
	if c.S3PublicEndpoint == "" {
		c.S3PublicEndpoint = c.S3Endpoint // 单机同网默认
	}
	c.S3PublicUseSSL = envBool("S3_PUBLIC_USE_SSL", c.S3UseSSL)
	if c.Workers < 1 || c.Workers > 16 {
		return nil, fmt.Errorf("GOPAN_WORKERS 必须在 1 到 16 之间")
	}
	if c.PartSize < 5<<20 {
		return nil, fmt.Errorf("GOPAN_PART_SIZE 不能小于 5MiB(S3 multipart 规定)")
	}

	secret := env("JWT_SECRET", "")
	if f := env("JWT_SECRET_FILE", ""); f != "" {
		b, err := os.ReadFile(f)
		if err != nil {
			return nil, fmt.Errorf("read GOPAN_JWT_SECRET_FILE: %w", err)
		}
		secret = strings.TrimSpace(string(b))
	}
	if len(secret) < 32 {
		return nil, fmt.Errorf("GOPAN_JWT_SECRET must be at least 32 bytes")
	}
	c.JWTSecret = []byte(secret)
	return c, nil
}

func env(key, def string) string {
	if v, ok := os.LookupEnv("GOPAN_" + key); ok {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	if v, ok := os.LookupEnv("GOPAN_" + key); ok {
		b, err := strconv.ParseBool(v)
		if err == nil {
			return b
		}
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if v, ok := os.LookupEnv("GOPAN_" + key); ok {
		d, err := time.ParseDuration(v)
		if err == nil {
			return d
		}
	}
	return def
}

func envInt64(key string, def int64) int64 {
	if v, ok := os.LookupEnv("GOPAN_" + key); ok {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil {
			return n
		}
	}
	return def
}

// parseCIDRs 解析逗号分隔的 IP/CIDR 列表;裸 IP 按单机掩码补齐(/32 或 /128)。
// 非法输入直接报错而不是静默忽略 —— 配错了就会静默退化成"谁都不信",
// 而这种情况的表现是"IP 全是网关地址",不报错很难发现。
func parseCIDRs(s string) ([]*net.IPNet, error) {
	var out []*net.IPNet
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if !strings.Contains(part, "/") {
			ip := net.ParseIP(part)
			if ip == nil {
				return nil, fmt.Errorf("非法地址 %q", part)
			}
			if ip.To4() != nil {
				part += "/32"
			} else {
				part += "/128"
			}
		}
		_, n, err := net.ParseCIDR(part)
		if err != nil {
			return nil, fmt.Errorf("非法 CIDR %q: %w", part, err)
		}
		out = append(out, n)
	}
	return out, nil
}
