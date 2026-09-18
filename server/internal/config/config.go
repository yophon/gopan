package config

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Listen       string
	PprofListen  string
	DBURL        string
	JWTSecret    []byte
	RegisterOpen bool
	DefaultQuota int64
	AccessTTL    time.Duration
	RefreshTTL   time.Duration
	TrashTTL     time.Duration // 回收站保留期,过期自动彻删
	DevMode      bool // 关闭 cookie Secure、开 introspection
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
	S3Region         string // 显式 region,免去签名前的 GetBucketLocation 探测
	S3UseSSL         bool // 内部地址是否走 TLS
	S3PublicUseSSL   bool // 外部地址是否走 TLS(生产经反代通常 true,内网 false)
	PartSize         int64         // 分片大小,S3 规定除末片外 ≥5MiB
	SessionTTL       time.Duration // 上传会话有效期
	PresignPutTTL    time.Duration
	PresignGetTTL    time.Duration

	GotenbergURL string // 置空 = 本次部署不提供 Office 预览(小内存机器不部署 Gotenberg)
	FFmpegPath   string
	FFprobePath  string
}

func Load() (*Config, error) {
	c := &Config{
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

		GotenbergURL: env("GOTENBERG_URL", "http://127.0.0.1:3100"),
		FFmpegPath:   env("FFMPEG_PATH", "ffmpeg"),
		FFprobePath:  env("FFPROBE_PATH", "ffprobe"),
	}
	tp, err := parseCIDRs(env("TRUSTED_PROXIES", ""))
	if err != nil {
		return nil, fmt.Errorf("GOPAN_TRUSTED_PROXIES: %w", err)
	}
	c.TrustedProxies = tp

	if c.DBURL == "" {
		return nil, fmt.Errorf("GOPAN_DB_URL is required")
	}
	if c.S3PublicEndpoint == "" {
		c.S3PublicEndpoint = c.S3Endpoint // 单机同网默认
	}
	c.S3PublicUseSSL = envBool("S3_PUBLIC_USE_SSL", c.S3UseSSL)
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
