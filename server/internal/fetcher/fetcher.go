// Package fetcher 是离线下载的出网 HTTP(doc/21 三):按真正要连的 IP 挡住内网(防 SSRF)、
// 断线时用 Range 接着读、从响应头推文件名。不碰数据库和对象存储,单测不需要外部依赖。
package fetcher

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/time/rate"

	"github.com/yophon/gopan/server/internal/fulltext"
)

var (
	// ErrBlocked 目标地址落在不允许访问的网段(内网、本机、云元数据……)。
	ErrBlocked = errors.New("目标地址不允许访问")
	// ErrBadURL 不是 http / https 链接,或者格式不对。
	ErrBadURL = errors.New("链接格式不对,只支持 http / https")
	// ErrChanged 续传时源站内容变了(或不再支持 Range),只能从头再下。
	ErrChanged = errors.New("源站文件已变化,需要重新下载")
	// ErrTooLong 源站发来的字节比它声明的长度多。
	ErrTooLong = errors.New("源站发来的内容比声明的长")
	// ErrTooManyRedirects 重定向超过 MaxRedirects 次。
	ErrTooManyRedirects = errors.New("重定向次数过多")
)

// HTTPError 源站返回了非 2xx。
type HTTPError struct{ Status int }

func (e *HTTPError) Error() string { return fmt.Sprintf("源站返回 HTTP %d", e.Status) }

const (
	MaxRedirects = 5
	MaxURLLen    = 4096
)

// ---- 地址检查 ----

// Guard 决定哪些 IP 能连。零值只允许公网地址。
type Guard struct {
	// AllowPrivate 放开私有网段(RFC 1918、IPv6 ULA、CGNAT、fake-ip 代理用的 198.18/15):
	// 自部署在家里、要从局域网 NAS 拉文件,或主机上跑着 fake-ip 代理时用。
	// 回环、链路本地(含云元数据 169.254.169.254)、组播、未指定与保留地址无论如何都不放。
	AllowPrivate bool
	// Deny 额外拒绝的网段,比如 VPS 自己的公网 IP:容器访问宿主机的公网 IP 可能绕过只防外部的防火墙。
	Deny []netip.Prefix
	// AllowLoopback 只给测试用(httptest 服务器在 127.0.0.1)。
	AllowLoopback bool
}

var (
	// 与私有网段同等对待(AllowPrivate 时放行):CGNAT,以及 198.18.0.0/15 ——
	// 它本是测试保留段,但 Clash 一类 fake-ip 代理把所有域名都解析到这里,主机上跑着这种代理时得能放开
	privateLike = []netip.Prefix{netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("198.18.0.0/15")}
	reserved    = []netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/8"),
		netip.MustParsePrefix("192.0.0.0/24"),
		netip.MustParsePrefix("192.0.2.0/24"),
		netip.MustParsePrefix("198.51.100.0/24"),
		netip.MustParsePrefix("203.0.113.0/24"),
		netip.MustParsePrefix("240.0.0.0/4"), // 含广播 255.255.255.255
		netip.MustParsePrefix("::/96"),       // 含 ::、::1 与已废弃的 IPv4 兼容地址
		netip.MustParsePrefix("64:ff9b::/96"),
		netip.MustParsePrefix("64:ff9b:1::/48"), // NAT64:后半截可能是内网 IPv4
		netip.MustParsePrefix("100::/64"),
		netip.MustParsePrefix("2001:db8::/32"),
	}
)

// Check 返回 nil 表示可以连。
func (g Guard) Check(ip netip.Addr) error {
	ip = ip.Unmap()
	switch {
	case !ip.IsValid(), ip.IsUnspecified(), ip.IsMulticast(), ip.IsLinkLocalUnicast(),
		ip.IsLinkLocalMulticast(), ip.IsInterfaceLocalMulticast():
		return ErrBlocked
	case ip.IsLoopback():
		if !g.AllowLoopback {
			return ErrBlocked
		}
		return nil
	case ip.IsPrivate() || inAny(privateLike, ip):
		if !g.AllowPrivate {
			return ErrBlocked
		}
	}
	for _, p := range reserved {
		if p.Contains(ip) {
			return ErrBlocked
		}
	}
	for _, p := range g.Deny {
		if p.Contains(ip) {
			return ErrBlocked
		}
	}
	return nil
}

func inAny(ps []netip.Prefix, ip netip.Addr) bool {
	for _, p := range ps {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}

// control 在真正 connect 之前检查地址:解析阶段检查挡不住 DNS rebinding(检查时一个 IP、连接时另一个)。
func (g Guard) control(_, address string, _ syscall.RawConn) error {
	ap, err := netip.ParseAddrPort(address)
	if err != nil {
		return ErrBlocked
	}
	if err := g.Check(ap.Addr()); err != nil {
		return fmt.Errorf("%w: %s", err, ap.Addr())
	}
	return nil
}

// Dialer 每次拨号都过 Guard 的拨号器,给别处自己拼 Transport 用(助手的模型转发,doc/33 三)。
func (g Guard) Dialer() *net.Dialer {
	return &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second, Control: g.control}
}

// Client 出网用的 http.Client:每次拨号都过 Guard,不走系统代理(经代理时 IP 检查就失效了),
// 不自动解 gzip(否则存下的字节与源文件不一致),重定向最多 MaxRedirects 次且只能是 http / https。
// 不设整体超时:大文件要下很久,断线由 Stream 的读空闲计时器判断。
func (g Guard) Client() *http.Client {
	d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second, Control: g.control}
	tr := &http.Transport{
		Proxy:                 nil,
		DialContext:           d.DialContext,
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		DisableCompression:    true,
		MaxIdleConns:          4,
		IdleConnTimeout:       30 * time.Second,
	}
	return &http.Client{Transport: tr, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= MaxRedirects {
			return ErrTooManyRedirects
		}
		if req.URL.Scheme != "http" && req.URL.Scheme != "https" {
			return ErrBadURL
		}
		return nil
	}}
}

// ParseURL 校验用户给的链接:只收 http / https,要有主机名,不超过 MaxURLLen。
func ParseURL(raw string) (*url.URL, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > MaxURLLen {
		return nil, ErrBadURL
	}
	u, err := url.Parse(raw)
	if err != nil {
		return nil, ErrBadURL
	}
	u.Scheme = strings.ToLower(u.Scheme)
	if (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" {
		return nil, ErrBadURL
	}
	u.Fragment = ""
	return u, nil
}

// ---- 下载流 ----

// Options 下载参数。零值可用。
type Options struct {
	UserAgent string
	Rate      int64         // 每秒最多读多少字节,0 = 不限
	Idle      time.Duration // 多久读不到字节算断线,默认 60 秒
	Retries   int           // 断线后最多续几次,默认 5
}

func (o Options) withDefaults() Options {
	if o.Idle <= 0 {
		o.Idle = 60 * time.Second
	}
	if o.Retries <= 0 {
		o.Retries = 5
	}
	if o.UserAgent == "" {
		o.UserAgent = "gopan-offline-download"
	}
	return o
}

// Stream 一次下载的响应体。Read 遇到断线时,源站支持的话自动带 Range 续上,调用方感觉不到。
type Stream struct {
	Name        string   // 推出来的文件名(已清洗)
	Size        int64    // 声明的长度,未知为 -1
	ContentType string   //
	URL         *url.URL // 跟完重定向后的地址

	ctx       context.Context
	client    *http.Client
	opt       Options
	limiter   *rate.Limiter
	validator string // If-Range 用:强 ETag 或 Last-Modified
	resumable bool

	body    io.ReadCloser
	stop    context.CancelFunc
	timer   *time.Timer
	off     int64
	Resumes int // 已续传的次数
}

// Open 发出 GET,拿到 2xx 响应头后返回;调用方读完要 Close。
func Open(ctx context.Context, c *http.Client, rawURL string, opt Options) (*Stream, error) {
	u, err := ParseURL(rawURL)
	if err != nil {
		return nil, err
	}
	s := &Stream{ctx: ctx, client: c, opt: opt.withDefaults()}
	if opt.Rate > 0 {
		burst := int(min(opt.Rate, 256<<10))
		s.limiter = rate.NewLimiter(rate.Limit(opt.Rate), burst)
	}
	resp, err := s.get(u.String(), "")
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		resp.Body.Close()
		s.endAttempt()
		return nil, &HTTPError{Status: resp.StatusCode}
	}
	s.URL = resp.Request.URL
	s.Size = resp.ContentLength
	s.ContentType = strings.TrimSpace(resp.Header.Get("Content-Type"))
	s.Name = FileName(resp.Header.Get("Content-Disposition"), s.URL, s.ContentType)
	if et := resp.Header.Get("ETag"); et != "" && !strings.HasPrefix(et, "W/") {
		s.validator = et
	} else if lm := resp.Header.Get("Last-Modified"); lm != "" {
		s.validator = lm
	}
	s.resumable = s.Size > 0 && s.validator != "" &&
		strings.EqualFold(strings.TrimSpace(resp.Header.Get("Accept-Ranges")), "bytes")
	s.body = resp.Body
	return s, nil
}

// Resumable 断线后能否续传(源站声明了长度、支持 Range、给了 ETag 或 Last-Modified)。
func (s *Stream) Resumable() bool { return s.resumable }

// get 发一次请求。每次请求有自己的 context,读空闲计时器到点就取消它,Read 随之报错、走续传。
func (s *Stream) get(u, rangeHdr string) (*http.Response, error) {
	actx, cancel := context.WithCancel(s.ctx)
	req, err := http.NewRequestWithContext(actx, http.MethodGet, u, nil)
	if err != nil {
		cancel()
		return nil, ErrBadURL
	}
	req.Header.Set("User-Agent", s.opt.UserAgent)
	req.Header.Set("Accept-Encoding", "identity")
	if rangeHdr != "" {
		req.Header.Set("Range", rangeHdr)
		req.Header.Set("If-Range", s.validator)
	}
	timer := time.AfterFunc(s.opt.Idle, cancel)
	resp, err := s.client.Do(req)
	if err != nil {
		timer.Stop()
		cancel()
		return nil, err
	}
	s.stop, s.timer = cancel, timer
	return resp, nil
}

func (s *Stream) endAttempt() {
	if s.timer != nil {
		s.timer.Stop()
	}
	if s.stop != nil {
		s.stop()
	}
}

func (s *Stream) closeBody() {
	if s.body != nil {
		s.body.Close()
		s.body = nil
	}
	s.endAttempt()
}

// Close 释放连接。
func (s *Stream) Close() error {
	s.closeBody()
	return nil
}

// Offset 已经读到的字节数。
func (s *Stream) Offset() int64 { return s.off }

func (s *Stream) Read(p []byte) (int, error) {
	if s.limiter != nil && len(p) > s.limiter.Burst() {
		p = p[:s.limiter.Burst()]
	}
	for {
		if s.body == nil {
			return 0, io.ErrClosedPipe
		}
		n, err := s.body.Read(p)
		if n > 0 {
			s.timer.Reset(s.opt.Idle)
			s.off += int64(n)
			if s.Size >= 0 && s.off > s.Size {
				return n, ErrTooLong
			}
			if s.limiter != nil {
				if werr := s.limiter.WaitN(s.ctx, n); werr != nil {
					return n, werr
				}
			}
		}
		if err == nil {
			return n, nil
		}
		if err == io.EOF && (s.Size < 0 || s.off == s.Size) {
			return n, io.EOF
		}
		if s.ctx.Err() != nil {
			return n, s.ctx.Err()
		}
		if err == io.EOF {
			err = io.ErrUnexpectedEOF // 比声明的短就结束了
		}
		if rerr := s.resume(err); rerr != nil {
			return n, rerr
		}
		if n > 0 {
			return n, nil
		}
	}
}

// resume 断线后带 Range + If-Range 从已读位置接着要。源站回 200(内容变了、或不认 Range)
// 或 Content-Range 对不上,只能从头再下(ErrChanged)。
func (s *Stream) resume(cause error) error {
	s.closeBody()
	if !s.resumable {
		return fmt.Errorf("下载中断: %w", cause)
	}
	for s.Resumes < s.opt.Retries {
		s.Resumes++
		select {
		case <-time.After(time.Duration(s.Resumes) * time.Second):
		case <-s.ctx.Done():
			return s.ctx.Err()
		}
		resp, err := s.get(s.URL.String(), "bytes="+strconv.FormatInt(s.off, 10)+"-")
		if err != nil {
			if errors.Is(err, ErrBlocked) || s.ctx.Err() != nil {
				return err
			}
			cause = err
			continue
		}
		if resp.StatusCode != http.StatusPartialContent || !rangeStartsAt(resp.Header.Get("Content-Range"), s.off, s.Size) {
			resp.Body.Close()
			s.endAttempt()
			return ErrChanged
		}
		s.body = resp.Body
		return nil
	}
	return fmt.Errorf("下载中断,已重连 %d 次: %w", s.Resumes, cause)
}

// rangeStartsAt 检查 "bytes 100-999/1000" 的起点与总长。
func rangeStartsAt(cr string, off, size int64) bool {
	cr = strings.TrimSpace(cr)
	if !strings.HasPrefix(cr, "bytes ") {
		return false
	}
	spec, total, ok := strings.Cut(cr[len("bytes "):], "/")
	if !ok {
		return false
	}
	start, _, ok := strings.Cut(spec, "-")
	if !ok {
		return false
	}
	n, err := strconv.ParseInt(strings.TrimSpace(start), 10, 64)
	if err != nil || n != off {
		return false
	}
	if total = strings.TrimSpace(total); total != "*" {
		t, err := strconv.ParseInt(total, 10, 64)
		if err != nil || t != size {
			return false
		}
	}
	return true
}

// ---- 文件名 ----

var rawFilenameRe = regexp.MustCompile(`(?i)filename\s*=\s*(?:"([^"]*)"|([^;]+))`)

// FileName 依次取 Content-Disposition 的文件名(含 filename*;非 UTF-8 按 GB18030 解;
// 有的站点把百分号编码直接塞进 filename)、URL 最后一段、主机名;没有扩展名就按 Content-Type 补。
func FileName(disposition string, u *url.URL, contentType string) string {
	name := ""
	if disposition != "" {
		if _, params, err := mime.ParseMediaType(disposition); err == nil {
			name = params["filename"]
		}
		if name == "" {
			if m := rawFilenameRe.FindStringSubmatch(disposition); m != nil {
				name = m[1] + m[2]
			}
		}
		if strings.Contains(name, "%") {
			if un, err := url.PathUnescape(name); err == nil {
				name = un
			}
		}
	}
	if name == "" && u != nil {
		if seg := path.Base(u.Path); seg != "/" && seg != "." {
			name = seg
		}
	}
	name = Clean(fulltext.DecodeName([]byte(name)))
	fallback := name == ""
	if fallback && u != nil {
		name = Clean(u.Hostname())
	}
	if name == "" {
		name = "download"
	}
	// 主机名自带"扩展名"(.com、.org),兜底名一律按类型补
	if fallback || path.Ext(name) == "" {
		if ext := extByType(contentType); ext != "" {
			name += ext
		}
	}
	return name
}

// Clean 把任意字符串收拾成合法的节点名:去掉控制字符,斜杠换成下划线,
// 去掉首尾空白和末尾的点,不超过 255 字节(截断时保住扩展名)。
func Clean(name string) string {
	name = strings.Map(func(r rune) rune {
		switch {
		case r == '/' || r == '\\':
			return '_'
		case unicode.IsControl(r), r == utf8.RuneError:
			return -1
		}
		return r
	}, name)
	name = strings.TrimRight(strings.TrimSpace(name), ". ")
	if name == "." || name == ".." {
		return ""
	}
	if len(name) > 255 {
		ext := path.Ext(name)
		if len(ext) > 20 {
			ext = ""
		}
		base := name[:255-len(ext)]
		for !utf8.ValidString(base) {
			base = base[:len(base)-1]
		}
		name = base + ext
	}
	return name
}

var typeExts = map[string]string{
	"application/pdf":                         ".pdf",
	"application/zip":                         ".zip",
	"application/x-zip-compressed":            ".zip",
	"application/x-7z-compressed":             ".7z",
	"application/vnd.rar":                     ".rar",
	"application/x-rar-compressed":            ".rar",
	"application/gzip":                        ".gz",
	"application/x-gzip":                      ".gz",
	"application/x-tar":                       ".tar",
	"application/x-xz":                        ".xz",
	"application/json":                        ".json",
	"application/vnd.android.package-archive": ".apk",
	"application/x-msdownload":                ".exe",
	"image/jpeg":                              ".jpg",
	"image/png":                               ".png",
	"image/gif":                               ".gif",
	"image/webp":                              ".webp",
	"image/svg+xml":                           ".svg",
	"video/mp4":                               ".mp4",
	"video/webm":                              ".webm",
	"audio/mpeg":                              ".mp3",
	"text/plain":                              ".txt",
	"text/html":                               ".html",
	"text/csv":                                ".csv",
}

func extByType(ct string) string {
	t, _, err := mime.ParseMediaType(ct)
	if err != nil {
		return ""
	}
	return typeExts[strings.ToLower(t)]
}

// IsHTML 响应是网页(常见于源站把机房 IP 拦到验证码页、或者链接本来就是网页)。
func IsHTML(contentType string) bool {
	t, _, err := mime.ParseMediaType(contentType)
	return err == nil && (t == "text/html" || t == "application/xhtml+xml")
}

// Host 日志与审计里只记主机名:下载链接常带签名,路径和查询串不落日志。
func Host(raw string) string {
	if u, err := url.Parse(strings.TrimSpace(raw)); err == nil && u.Hostname() != "" {
		return u.Hostname()
	}
	return ""
}
