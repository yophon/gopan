package httpx

// 助手的模型转发(doc/33 三):POST /api/ai/{provider}/{path...}。
//
// 客户端的 tau 把 baseUrl 设成 /api/ai/{provider},原样拼上各家的对话接口路径;这里只放行对话接口,
// 去掉客户端带来的认证头,换上存在服务端的真 key,请求体原样转发,响应流式原样回写。
// 边转边从流里抠用量记进 ai_usage,站点额度按它判。
//
// 自己的服务连接时挡内网 / 回环 / 链路本地地址(拨号时检查,防 DNS 换绑),不跟重定向;
// 站点提供的由管理员配,不限(可以接本机的 Ollama)。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/service"
)

const (
	aiProxyMaxBody    = 32 << 20
	aiProxyPerUser    = 4
	aiProxyMaxRequest = 10 * time.Minute
)

var googleChatPath = regexp.MustCompile(`^(v1beta/|v1/)?models/[^/]+:(stream)?[gG]enerateContent$`)

// aiChatPath 这类服务的对话接口;别的路径一律不转。
func aiChatPath(kind, path string) bool {
	switch kind {
	case "openai":
		return path == "chat/completions"
	case "openai-responses":
		return path == "responses"
	case "anthropic":
		return path == "v1/messages"
	case "google":
		return googleChatPath.MatchString(path)
	}
	return false
}

// 客户端带来的头里只转这些;认证头一律换成服务端的
var aiForwardHeaders = []string{"Content-Type", "Accept", "Anthropic-Version", "Anthropic-Beta"}

type aiProxy struct {
	auth   *service.Auth
	ai     *service.AI
	open   *http.Client // 站点提供的
	guard  *http.Client // 自己的:拨号过 Guard
	mu     sync.Mutex
	active map[uuid.UUID]int
}

func AIProxyHandler(auth *service.Auth, ai *service.AI, guard fetcher.Guard) http.Handler {
	transport := func(dial func(ctx context.Context, network, addr string) (net.Conn, error), proxy func(*http.Request) (*url.URL, error)) *http.Transport {
		return &http.Transport{
			Proxy: proxy, DialContext: dial, ForceAttemptHTTP2: true,
			TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 5 * time.Minute,
			MaxIdleConns: 16, IdleConnTimeout: 60 * time.Second,
		}
	}
	noRedirect := func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	plain := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return &aiProxy{
		auth: auth, ai: ai, active: map[uuid.UUID]int{},
		open:  &http.Client{Transport: transport(plain.DialContext, http.ProxyFromEnvironment), CheckRedirect: noRedirect},
		guard: &http.Client{Transport: transport(guard.Dialer().DialContext, nil), CheckRedirect: noRedirect},
	}
}

func (p *aiProxy) acquire(user uuid.UUID) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active[user] >= aiProxyPerUser {
		return false
	}
	p.active[user]++
	return true
}

func (p *aiProxy) release(user uuid.UUID) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active[user]--; p.active[user] <= 0 {
		delete(p.active, user)
	}
}

func (p *aiProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		writeArchiveErr(w, &service.Error{Code: "UNAUTHENTICATED", Message: "未登录或凭证已过期"}, http.StatusUnauthorized)
		return
	}
	ident, err := p.auth.ParseAccessContext(r.Context(), strings.TrimPrefix(h, "Bearer "))
	if err != nil {
		writeArchiveErr(w, &service.Error{Code: "UNAUTHENTICATED", Message: "未登录或凭证已过期"}, http.StatusUnauthorized)
		return
	}
	if ident.Scope != service.ScopeUser {
		writeServiceErr(w, service.ErrForbidden)
		return
	}
	providerID, err := uuid.Parse(r.PathValue("provider"))
	if err != nil {
		writeServiceErr(w, service.ErrNotFound)
		return
	}
	target, err := p.ai.ProxyTarget(r.Context(), ident.UserID, providerID)
	if err != nil {
		var se *service.Error
		if errors.As(err, &se) && se.Code == "AI_QUOTA" {
			writeArchiveErr(w, se, http.StatusTooManyRequests)
			return
		}
		writeServiceErr(w, err)
		return
	}
	path := r.PathValue("path")
	if !aiChatPath(target.Provider.Kind, path) {
		writeServiceErr(w, service.ErrNotFound)
		return
	}
	if !p.acquire(ident.UserID) {
		w.Header().Set("Retry-After", "5")
		writeArchiveErr(w, &service.Error{Code: "RATE_LIMITED", Message: "同时进行的请求太多"}, http.StatusTooManyRequests)
		return
	}
	defer p.release(ident.UserID)

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, aiProxyMaxBody))
	if err != nil {
		writeArchiveErr(w, &service.Error{Code: "INVALID_INPUT", Message: "请求太大"}, http.StatusRequestEntityTooLarge)
		return
	}
	if target.Provider.Kind == "openai" {
		body = withIncludeUsage(body)
	}

	ctx, cancel := context.WithTimeout(r.Context(), aiProxyMaxRequest)
	defer cancel()
	upstreamURL := target.Provider.BaseURL + "/" + path
	if q := stripKeyParam(r.URL.Query()); q != "" {
		upstreamURL += "?" + q
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, upstreamURL, bytes.NewReader(body))
	if err != nil {
		writeServiceErr(w, err)
		return
	}
	for _, k := range aiForwardHeaders {
		if v := r.Header.Get(k); v != "" {
			req.Header.Set(k, v)
		}
	}
	setAIKey(req, target.Provider.Kind, target.Key)

	client := p.guard
	if target.Provider.Site() {
		client = p.open
	}
	resp, err := client.Do(req)
	if err != nil {
		slog.Warn("ai proxy", "provider", providerID, "err", err)
		msg := "连不上模型服务"
		if errors.Is(err, fetcher.ErrBlocked) {
			msg = "模型服务的地址不允许访问(内网地址)"
		}
		writeArchiveErr(w, &service.Error{Code: "AI_UPSTREAM", Message: msg}, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	rc := http.NewResponseController(w)
	_ = rc.SetWriteDeadline(time.Time{}) // 长回答豁免 Server 级 60s WriteTimeout
	hd := w.Header()
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		hd.Set("Content-Type", ct)
	}
	hd.Set("Cache-Control", "no-cache, no-transform")
	hd.Set("X-Accel-Buffering", "no")
	w.WriteHeader(resp.StatusCode)

	meter := &usageMeter{}
	buf := make([]byte, 16<<10)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			meter.Write(buf[:n])
			if _, werr := w.Write(buf[:n]); werr != nil {
				break
			}
			_ = rc.Flush()
		}
		if rerr != nil {
			break
		}
	}
	meter.finish()
	if resp.StatusCode < 300 {
		recCtx, recCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer recCancel()
		if err := p.ai.RecordUsage(recCtx, ident.UserID, providerID, meter.input, meter.output); err != nil {
			slog.Warn("ai usage", "err", err)
		}
	}
}

func setAIKey(req *http.Request, kind, key string) {
	if key == "" {
		return
	}
	switch kind {
	case "anthropic":
		req.Header.Set("x-api-key", key)
		if req.Header.Get("Anthropic-Version") == "" {
			req.Header.Set("Anthropic-Version", "2023-06-01")
		}
	case "google":
		req.Header.Set("x-goog-api-key", key)
	default:
		req.Header.Set("Authorization", "Bearer "+key)
	}
}

// stripKeyParam 客户端不该在地址里带 key(google 的 ?key=),带了也去掉;其余参数(?alt=sse)原样。
func stripKeyParam(q map[string][]string) string {
	delete(q, "key")
	if len(q) == 0 {
		return ""
	}
	return url.Values(q).Encode()
}

// withIncludeUsage OpenAI 兼容的流式请求补上 stream_options.include_usage,最后一块才带用量。
func withIncludeUsage(body []byte) []byte {
	var m map[string]json.RawMessage
	if json.Unmarshal(body, &m) != nil {
		return body
	}
	if string(m["stream"]) != "true" {
		return body
	}
	opts := map[string]any{}
	if raw, ok := m["stream_options"]; ok {
		_ = json.Unmarshal(raw, &opts)
	}
	if _, ok := opts["include_usage"]; ok {
		return body
	}
	opts["include_usage"] = true
	raw, _ := json.Marshal(opts)
	m["stream_options"] = raw
	out, err := json.Marshal(m)
	if err != nil {
		return body
	}
	return out
}

// usageMeter 从回写的字节里抠用量:SSE 的每条 data: 或整段 JSON。几家的写法不同,取各字段见过的最大值
// (Anthropic 的输入在 message_start、输出在 message_delta 里累计;Google 每块都带累计值;OpenAI 最后一块)。
type usageMeter struct {
	line          bytes.Buffer
	whole         bytes.Buffer
	sse           bool
	input, output int64
}

const usageMeterMaxLine = 1 << 20

func (m *usageMeter) Write(b []byte) {
	if m.whole.Len() < 4<<20 {
		m.whole.Write(b)
	}
	for len(b) > 0 {
		i := bytes.IndexByte(b, '\n')
		if i < 0 {
			if m.line.Len() < usageMeterMaxLine {
				m.line.Write(b)
			}
			return
		}
		if m.line.Len() < usageMeterMaxLine {
			m.line.Write(b[:i])
		}
		m.onLine(m.line.Bytes())
		m.line.Reset()
		b = b[i+1:]
	}
}

func (m *usageMeter) onLine(l []byte) {
	l = bytes.TrimRight(l, "\r")
	if !bytes.HasPrefix(l, []byte("data:")) {
		return
	}
	m.sse = true
	m.scan(bytes.TrimSpace(l[5:]))
}

func (m *usageMeter) finish() {
	if m.line.Len() > 0 {
		m.onLine(m.line.Bytes())
	}
	if !m.sse {
		m.scan(m.whole.Bytes())
	}
}

func (m *usageMeter) scan(raw []byte) {
	if len(raw) == 0 || raw[0] != '{' {
		return
	}
	var v map[string]any
	if json.Unmarshal(raw, &v) != nil {
		return
	}
	for _, u := range []any{v["usage"], v["usageMetadata"], dig(v, "message", "usage"), dig(v, "response", "usage")} {
		obj, ok := u.(map[string]any)
		if !ok {
			continue
		}
		m.input = max(m.input, num(obj, "prompt_tokens", "input_tokens", "promptTokenCount"))
		m.output = max(m.output, num(obj, "completion_tokens", "output_tokens", "candidatesTokenCount"))
	}
}

func dig(v map[string]any, keys ...string) any {
	var cur any = v
	for _, k := range keys {
		obj, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = obj[k]
	}
	return cur
}

func num(obj map[string]any, keys ...string) int64 {
	for _, k := range keys {
		if f, ok := obj[k].(float64); ok {
			return int64(f)
		}
	}
	return 0
}
