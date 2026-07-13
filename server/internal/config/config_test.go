package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// base 设齐必填项,子用例在其上覆盖。
func base(t *testing.T) {
	t.Helper()
	t.Setenv("GOPAN_DB_URL", "postgres://x")
	t.Setenv("GOPAN_JWT_SECRET", strings.Repeat("s", 32))
}

func TestLoadDefaults(t *testing.T) {
	base(t)
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":8080" || c.PartSize != 16<<20 || c.DefaultQuota != 100<<30 ||
		c.AccessTTL != 15*time.Minute || c.TrashTTL != 30*24*time.Hour || !c.RegisterOpen {
		t.Fatalf("默认值不符:%+v", c)
	}
	// 未配公网 endpoint 时回落内网地址
	if c.S3PublicEndpoint != c.S3Endpoint {
		t.Fatal("S3PublicEndpoint 应回落 S3Endpoint")
	}
}

func TestLoadValidation(t *testing.T) {
	t.Run("缺 DB_URL", func(t *testing.T) {
		t.Setenv("GOPAN_JWT_SECRET", strings.Repeat("s", 32))
		t.Setenv("GOPAN_DB_URL", "")
		if _, err := Load(); err == nil {
			t.Fatal("缺 DB_URL 应报错")
		}
	})
	t.Run("JWT 太短", func(t *testing.T) {
		base(t)
		t.Setenv("GOPAN_JWT_SECRET", "short")
		if _, err := Load(); err == nil {
			t.Fatal("短 secret 应报错")
		}
	})
	t.Run("分片小于 5MiB", func(t *testing.T) {
		base(t)
		t.Setenv("GOPAN_PART_SIZE", "1048576")
		if _, err := Load(); err == nil {
			t.Fatal("S3 multipart 下限应拦截")
		}
	})
}

func TestJWTSecretFile(t *testing.T) {
	base(t)
	f := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(f, []byte("  "+strings.Repeat("f", 40)+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("GOPAN_JWT_SECRET", "") // 文件优先
	t.Setenv("GOPAN_JWT_SECRET_FILE", f)
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if string(c.JWTSecret) != strings.Repeat("f", 40) {
		t.Fatal("secret 文件应去除首尾空白后生效")
	}
	t.Setenv("GOPAN_JWT_SECRET_FILE", filepath.Join(t.TempDir(), "missing"))
	if _, err := Load(); err == nil {
		t.Fatal("secret 文件不存在应报错")
	}
}

func TestEnvParsers(t *testing.T) {
	base(t)
	t.Setenv("GOPAN_REGISTER_OPEN", "false")
	t.Setenv("GOPAN_DEFAULT_QUOTA", "1024")
	t.Setenv("GOPAN_TRASH_TTL", "48h")
	t.Setenv("GOPAN_S3_USE_SSL", "true")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.RegisterOpen || c.DefaultQuota != 1024 || c.TrashTTL != 48*time.Hour || !c.S3UseSSL {
		t.Fatalf("env 解析不符:%+v", c)
	}
	// 非法值回默认,不炸
	t.Setenv("GOPAN_REGISTER_OPEN", "not-a-bool")
	t.Setenv("GOPAN_DEFAULT_QUOTA", "not-a-number")
	t.Setenv("GOPAN_TRASH_TTL", "not-a-duration")
	c, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if !c.RegisterOpen || c.DefaultQuota != 100<<30 || c.TrashTTL != 30*24*time.Hour {
		t.Fatalf("非法 env 应回默认:%+v", c)
	}
}
