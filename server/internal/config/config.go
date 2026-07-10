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
	}
	if c.DBURL == "" {
		return nil, fmt.Errorf("GOPAN_DB_URL is required")
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
