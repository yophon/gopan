package fetcher

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/text/encoding/simplifiedchinese"
)

func TestGuard(t *testing.T) {
	deny := Guard{Deny: []netip.Prefix{netip.MustParsePrefix("1.1.1.1/32")}}
	cases := []struct {
		ip           string
		pub, private bool // 默认 / AllowPrivate 是否放行
	}{
		{"8.8.8.8", true, true},
		{"2606:4700:4700::1111", true, true},
		{"127.0.0.1", false, false},
		{"::1", false, false},
		{"::ffff:127.0.0.1", false, false},
		{"10.1.2.3", false, true},
		{"172.18.0.5", false, true}, // docker 网桥上的 minio / postgres
		{"192.168.1.10", false, true},
		{"100.64.0.1", false, true},
		{"fd00::1", false, true},
		{"169.254.169.254", false, false}, // 云元数据
		{"fe80::1", false, false},
		{"0.0.0.0", false, false},
		{"0.1.2.3", false, false},
		{"224.0.0.1", false, false},
		{"255.255.255.255", false, false},
		{"::", false, false},
		{"::a00:1", false, false},        // IPv4 兼容地址
		{"64:ff9b::a00:1", false, false}, // NAT64 包着 10.0.0.1
		{"::ffff:10.0.0.1", false, true}, // IPv4 映射按 IPv4 判断
		{"198.18.0.1", false, true},      // fake-ip 代理的地址段
		{"1.1.1.1", true, true},     // 不在 Deny 时是公网
	}
	for _, c := range cases {
		ip := netip.MustParseAddr(c.ip)
		if got := (Guard{}).Check(ip) == nil; got != c.pub {
			t.Errorf("默认 %s: 放行=%v,应为 %v", c.ip, got, c.pub)
		}
		if got := (Guard{AllowPrivate: true}).Check(ip) == nil; got != c.private {
			t.Errorf("AllowPrivate %s: 放行=%v,应为 %v", c.ip, got, c.private)
		}
	}
	if deny.Check(netip.MustParseAddr("1.1.1.1")) == nil {
		t.Fatal("Deny 里的地址应被拒")
	}
}

func TestParseURL(t *testing.T) {
	for _, bad := range []string{"", "ftp://a/b", "file:///etc/passwd", "http://", "javascript:alert(1)", "/relative", "http://" + strings.Repeat("a", MaxURLLen)} {
		if _, err := ParseURL(bad); !errors.Is(err, ErrBadURL) {
			t.Errorf("%q 应被拒,got %v", bad, err)
		}
	}
	u, err := ParseURL("  HTTPS://example.com/a.zip#frag ")
	if err != nil || u.String() != "https://example.com/a.zip" {
		t.Fatalf("got %v %v", u, err)
	}
}

// 默认 Guard 连不上 127.0.0.1;重定向到内网同样被拒(拨号时检查,不看域名)。
func TestClientBlocks(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/hop" {
			http.Redirect(w, r, "http://10.0.0.1/secret", http.StatusFound)
			return
		}
		w.Write([]byte("hi"))
	}))
	defer srv.Close()
	ctx := context.Background()
	if _, err := Open(ctx, (Guard{}).Client(), srv.URL+"/x", Options{}); !errors.Is(err, ErrBlocked) {
		t.Fatalf("回环应被拒,got %v", err)
	}
	g := Guard{AllowLoopback: true}
	if _, err := Open(ctx, g.Client(), srv.URL+"/hop", Options{}); !errors.Is(err, ErrBlocked) {
		t.Fatalf("重定向到 10.0.0.1 应被拒,got %v", err)
	}
	// 用域名指向回环(localhost)一样拦在拨号阶段
	lh := strings.Replace(srv.URL, "127.0.0.1", "localhost", 1)
	if _, err := Open(ctx, (Guard{}).Client(), lh, Options{}); !errors.Is(err, ErrBlocked) {
		t.Fatalf("localhost 应被拒,got %v", err)
	}
}

func TestRedirectLimitAndScheme(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ftp":
			http.Redirect(w, r, "ftp://example.com/x", http.StatusFound)
		default:
			n, _ := strconv.Atoi(strings.TrimPrefix(r.URL.Path, "/"))
			http.Redirect(w, r, srv.URL+"/"+strconv.Itoa(n+1), http.StatusFound)
		}
	}))
	defer srv.Close()
	c := Guard{AllowLoopback: true}.Client()
	if _, err := Open(context.Background(), c, srv.URL+"/0", Options{}); !errors.Is(err, ErrTooManyRedirects) {
		t.Fatalf("应报重定向过多,got %v", err)
	}
	if _, err := Open(context.Background(), c, srv.URL+"/ftp", Options{}); !errors.Is(err, ErrBadURL) {
		t.Fatalf("重定向到 ftp 应被拒,got %v", err)
	}
}

func TestHTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "nope", http.StatusForbidden)
	}))
	defer srv.Close()
	_, err := Open(context.Background(), Guard{AllowLoopback: true}.Client(), srv.URL, Options{})
	var he *HTTPError
	if !errors.As(err, &he) || he.Status != 403 {
		t.Fatalf("got %v", err)
	}
}

// cutServer 第一次完整请求只发 cut 字节就掐断连接;带 Range 的请求按 ServeContent 正常回。
func cutServer(t *testing.T, data []byte, cut int, etag string, ignoreRange bool) (*httptest.Server, *atomic.Int32) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := hits.Add(1)
		if etag != "" {
			w.Header().Set("ETag", etag)
		}
		if r.Header.Get("Range") == "" || ignoreRange {
			if n > 1 && ignoreRange {
				w.Header().Set("Content-Length", strconv.Itoa(len(data)))
				w.Write(data)
				return
			}
			w.Header().Set("Accept-Ranges", "bytes")
			w.Header().Set("Content-Length", strconv.Itoa(len(data)))
			w.WriteHeader(http.StatusOK)
			w.Write(data[:cut])
			w.(http.Flusher).Flush()
			panic(http.ErrAbortHandler) // 掐断连接
		}
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
	}))
	t.Cleanup(srv.Close)
	return srv, &hits
}

func payload(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i*7 + i/251)
	}
	return b
}

func TestResume(t *testing.T) {
	data := payload(1 << 20)
	srv, hits := cutServer(t, data, 300<<10, `"v1"`, false)
	s, err := Open(context.Background(), Guard{AllowLoopback: true}.Client(), srv.URL+"/f.bin", Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if !s.Resumable() || s.Size != int64(len(data)) || s.Name != "f.bin" {
		t.Fatalf("resumable=%v size=%d name=%q", s.Resumable(), s.Size, s.Name)
	}
	got, err := io.ReadAll(s)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, data) {
		t.Fatalf("续传后内容不一致:%d 字节", len(got))
	}
	if s.Resumes != 1 || hits.Load() != 2 {
		t.Fatalf("resumes=%d hits=%d", s.Resumes, hits.Load())
	}
}

func TestResumeChanged(t *testing.T) {
	data := payload(200 << 10)
	srv, _ := cutServer(t, data, 50<<10, `"v1"`, true)
	s, err := Open(context.Background(), Guard{AllowLoopback: true}.Client(), srv.URL, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := io.ReadAll(s); !errors.Is(err, ErrChanged) {
		t.Fatalf("源站不认 Range 时应 ErrChanged,got %v", err)
	}
}

func TestNotResumable(t *testing.T) {
	data := payload(200 << 10)
	srv, _ := cutServer(t, data, 50<<10, "", false) // 没有 ETag / Last-Modified
	s, err := Open(context.Background(), Guard{AllowLoopback: true}.Client(), srv.URL, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.Resumable() {
		t.Fatal("没有校验头时不该续传")
	}
	if _, err := io.ReadAll(s); err == nil || errors.Is(err, ErrChanged) {
		t.Fatalf("应报下载中断,got %v", err)
	}
}

// 读空闲超时:源站发了一半就不动了,计时器到点后续传。
func TestIdleResume(t *testing.T) {
	data := payload(100 << 10)
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", `"v1"`)
		if hits.Add(1) == 1 {
			w.Header().Set("Accept-Ranges", "bytes")
			w.Header().Set("Content-Length", strconv.Itoa(len(data)))
			w.WriteHeader(http.StatusOK)
			w.Write(data[:10<<10])
			w.(http.Flusher).Flush()
			select {
			case <-r.Context().Done():
			case <-time.After(5 * time.Second):
			}
			return
		}
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
	}))
	defer srv.Close()
	s, err := Open(context.Background(), Guard{AllowLoopback: true}.Client(), srv.URL, Options{Idle: 300 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	got, err := io.ReadAll(s)
	if err != nil || !bytes.Equal(got, data) || s.Resumes != 1 {
		t.Fatalf("err=%v len=%d resumes=%d", err, len(got), s.Resumes)
	}
}

func TestCancel(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1000000")
		w.WriteHeader(http.StatusOK)
		w.Write(make([]byte, 1000))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer srv.Close()
	ctx, cancel := context.WithCancel(context.Background())
	s, err := Open(ctx, Guard{AllowLoopback: true}.Client(), srv.URL, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	go func() { time.Sleep(100 * time.Millisecond); cancel() }()
	if _, err := io.ReadAll(s); !errors.Is(err, context.Canceled) {
		t.Fatalf("取消后应报 context.Canceled,got %v", err)
	}
}

func TestRate(t *testing.T) {
	data := payload(300 << 10)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(data)
	}))
	defer srv.Close()
	s, err := Open(context.Background(), Guard{AllowLoopback: true}.Client(), srv.URL, Options{Rate: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	start := time.Now()
	got, err := io.ReadAll(s)
	if err != nil || len(got) != len(data) {
		t.Fatal(err)
	}
	// 1 MB/s 下 300 KB(首个 256 KB 是突发额度)至少要几十毫秒
	if time.Since(start) < 30*time.Millisecond {
		t.Fatalf("限速没生效:%v", time.Since(start))
	}
}

func TestFileName(t *testing.T) {
	gbk, _ := simplifiedchinese.GBK.NewEncoder().String("报告.pdf")
	mustURL := func(s string) *url.URL { u, _ := url.Parse(s); return u }
	cases := []struct {
		cd, u, ct, want string
	}{
		{`attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf`, "https://x.com/dl?id=1", "", "中文.pdf"},
		{`attachment; filename="a b.zip"`, "https://x.com/dl", "", "a b.zip"},
		{`attachment; filename="` + gbk + `"`, "https://x.com/dl", "", "报告.pdf"},
		{`attachment; filename=%E5%90%88%E5%90%8C.docx`, "https://x.com/dl", "", "合同.docx"},
		{``, "https://x.com/files/%E5%8F%91%E7%A5%A8.pdf?sig=abc", "", "发票.pdf"},
		{``, "https://x.com/download", "application/pdf; charset=binary", "download.pdf"},
		{``, "https://cdn.example.org/", "text/html; charset=utf-8", "cdn.example.org.html"},
		{`attachment; filename="../../etc/passwd"`, "https://x.com/", "", ".._.._etc_passwd"},
		{`attachment; filename="  name.txt.. "`, "https://x.com/", "", "name.txt"},
		{``, "https://x.com/a.tar.gz", "application/gzip", "a.tar.gz"},
	}
	for _, c := range cases {
		if got := FileName(c.cd, mustURL(c.u), c.ct); got != c.want {
			t.Errorf("FileName(%q, %q) = %q,应为 %q", c.cd, c.u, got, c.want)
		}
	}
	long := strings.Repeat("长", 120) + ".pdf"
	if got := Clean(long); len(got) > 255 || !strings.HasSuffix(got, ".pdf") {
		t.Fatalf("长名截断:%d 字节 %q", len(got), got[len(got)-8:])
	}
	if Clean("..") != "" || Clean("a\x00b\x1fc") != "abc" {
		t.Fatal("Clean 处理不对")
	}
}

func TestHost(t *testing.T) {
	if Host("https://user:pw@files.example.com:8443/a?sig=secret") != "files.example.com" {
		t.Fatal(Host("https://user:pw@files.example.com:8443/a?sig=secret"))
	}
	if !IsHTML("text/html; charset=utf-8") || IsHTML("application/pdf") {
		t.Fatal("IsHTML")
	}
}
