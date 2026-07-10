package config

import (
	"fmt"
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
	DevMode      bool // 关闭 cookie Secure、开 introspection

	// 对象存储
	S3Endpoint       string // 服务端内部访问地址
	S3PublicEndpoint string // 预签名 URL 用的外部地址(浏览器可达)
	S3Key            string
	S3Secret         string
	S3Bucket         string
	S3UseSSL         bool
	PartSize         int64         // 分片大小,S3 规定除末片外 ≥5MiB
	SessionTTL       time.Duration // 上传会话有效期
	PresignPutTTL    time.Duration
	PresignGetTTL    time.Duration

	GotenbergURL string
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
		DevMode:      envBool("DEV_MODE", false),

		S3Endpoint:       env("S3_ENDPOINT", "127.0.0.1:9000"),
		S3PublicEndpoint: env("S3_PUBLIC_ENDPOINT", ""),
		S3Key:            env("S3_KEY", "gopan"),
		S3Secret:         env("S3_SECRET", "gopan-minio-dev"),
		S3Bucket:         env("S3_BUCKET", "gopan"),
		S3UseSSL:         envBool("S3_USE_SSL", false),
		PartSize:         envInt64("PART_SIZE", 16<<20),
		SessionTTL:       48 * time.Hour,
		PresignPutTTL:    time.Hour,
		PresignGetTTL:    15 * time.Minute,

		GotenbergURL: env("GOTENBERG_URL", "http://127.0.0.1:3100"),
		FFmpegPath:   env("FFMPEG_PATH", "ffmpeg"),
		FFprobePath:  env("FFPROBE_PATH", "ffprobe"),
	}
	if c.DBURL == "" {
		return nil, fmt.Errorf("GOPAN_DB_URL is required")
	}
	if c.S3PublicEndpoint == "" {
		c.S3PublicEndpoint = c.S3Endpoint // 单机同网默认
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

func envInt64(key string, def int64) int64 {
	if v, ok := os.LookupEnv("GOPAN_" + key); ok {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil {
			return n
		}
	}
	return def
}
