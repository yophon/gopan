package objstore

import (
	"context"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/yophon/gopan/server/internal/config"
)

// 真 MinIO 上确认响应头改写生效:对象按 text/html / image/svg+xml 存进去,
// 内联预签名拿回来的必须是纯文本 / 带 attachment。需要 TEST_S3_ENDPOINT,缺了跳过。
func TestPresignGetInlineServesSafeTypes(t *testing.T) {
	ep := os.Getenv("TEST_S3_ENDPOINT")
	if ep == "" {
		t.Skip("TEST_S3_ENDPOINT 未设置,跳过")
	}
	key, secret := os.Getenv("TEST_S3_KEY"), os.Getenv("TEST_S3_SECRET")
	if key == "" {
		key = "gopan"
	}
	if secret == "" {
		secret = "gopan-minio-dev"
	}
	s, err := New(&config.Config{
		S3Endpoint: ep, S3PublicEndpoint: ep, S3Key: key, S3Secret: secret,
		S3Bucket: "gopan-test", S3Region: "us-east-1",
		PresignPutTTL: time.Hour, PresignGetTTL: 15 * time.Minute,
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if err := s.EnsureBucket(ctx); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		key, stored, body, wantType, wantDisp string
	}{
		{"inline-test/x.html", "text/html", "<script>alert(1)</script>", "text/plain; charset=utf-8", ""},
		{"inline-test/x.svg", "image/svg+xml", `<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`, "image/svg+xml", "attachment"},
		{"inline-test/x.png", "image/png", "not really a png", "image/png", ""},
	}
	for _, c := range cases {
		if err := s.Put(ctx, c.key, strings.NewReader(c.body), int64(len(c.body)), c.stored); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = s.Remove(context.Background(), c.key) })

		u, err := s.PresignGetInline(ctx, c.key, c.stored)
		if err != nil {
			t.Fatal(err)
		}
		resp, err := http.Get(u)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK || string(body) != c.body {
			t.Fatalf("%s: status %d body %q", c.key, resp.StatusCode, body)
		}
		if got := resp.Header.Get("Content-Type"); got != c.wantType {
			t.Errorf("%s: Content-Type = %q, want %q", c.key, got, c.wantType)
		}
		if got := resp.Header.Get("Content-Disposition"); got != c.wantDisp {
			t.Errorf("%s: Content-Disposition = %q, want %q", c.key, got, c.wantDisp)
		}
	}
}
