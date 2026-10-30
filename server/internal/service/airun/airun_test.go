package airun

// 真 qjs(GOPAN_TEST_QJS 指向 quickjs-ng 的 qjs,没设就跳过)+ 真 runtime.js,对着假的 gopan 跑一轮:
// GraphQL、MCP(建文件夹要确认)、经转发的模型(第一轮要建文件夹,第二轮说完成)。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeGopan struct {
	modelCalls atomic.Int32
	toolCalls  atomic.Int32
	appended   atomic.Int32
	mu         sync.Mutex
	authHeads  []string
	firstModel string
}

func (f *fakeGopan) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	f.mu.Lock()
	f.authHeads = append(f.authHeads, r.URL.Path+" "+r.Header.Get("Authorization"))
	f.mu.Unlock()
	writeJSON := func(v string) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, v)
	}
	switch {
	case r.URL.Path == "/query":
		var req struct {
			Query     string                     `json:"query"`
			Variables map[string]json.RawMessage `json:"variables"`
		}
		_ = json.Unmarshal(body, &req)
		conv := `{"id":"c1","title":"建个文件夹","providerId":"p1","model":"m","entries":%d,"createdAt":"2026-10-29T00:00:00Z","updatedAt":"2026-10-29T00:00:00Z"}`
		switch {
		case strings.Contains(req.Query, "aiConversation("):
			writeJSON(`{"data":{"aiConversation":` + fmt.Sprintf(conv, 0) + `}}`)
		case strings.Contains(req.Query, "aiEntries"):
			writeJSON(`{"data":{"aiEntries":[]}}`)
		case strings.Contains(req.Query, "assistantAccess"):
			writeJSON(`{"data":{"assistantAccess":{"disabled":false,"groups":[{"key":"files:read","scopes":["files:read"],"permission":"ALLOW"},{"key":"files:write","scopes":["files:write"],"permission":"ASK"}]}}}`)
		case strings.Contains(req.Query, "appendAiEntries"):
			var from int
			var entries []string
			_ = json.Unmarshal(req.Variables["f"], &from)
			_ = json.Unmarshal(req.Variables["e"], &entries)
			f.appended.Add(1)
			writeJSON(`{"data":{"appendAiEntries":` + fmt.Sprintf(conv, from+len(entries)) + `}}`)
		default:
			writeJSON(`{"errors":[{"message":"unexpected"}]}`)
		}
	case r.URL.Path == "/mcp":
		var m struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
		}
		_ = json.Unmarshal(body, &m)
		if len(m.ID) == 0 {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		result := `{}`
		switch m.Method {
		case "initialize":
			result = `{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"gopan","version":"t"}}`
		case "tools/list":
			result = `{"tools":[{"name":"create_folder","description":"mk","inputSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},"annotations":{"destructiveHint":false},"_meta":{"gopan/scopes":["files:write"]}}]}`
		case "tools/call":
			f.toolCalls.Add(1)
			result = `{"content":[{"type":"text","text":"ok"}]}`
		}
		writeJSON(`{"jsonrpc":"2.0","id":` + string(m.ID) + `,"result":` + result + `}`)
	case strings.HasPrefix(r.URL.Path, "/api/ai/p1/chat/completions"):
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		sse := func(s string) { _, _ = io.WriteString(w, "data: "+s+"\n\n"); fl.Flush() }
		if f.modelCalls.Add(1) == 1 {
			f.mu.Lock()
			f.firstModel = string(body)
			f.mu.Unlock()
			sse(`{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"mcp__gopan__create_folder","arguments":"{\"name\":\"报销\"}"}}]},"finish_reason":"tool_calls"}]}`)
		} else {
			sse(`{"choices":[{"delta":{"content":"建好了"},"finish_reason":null}]}`)
			sse(`{"choices":[{"delta":{},"finish_reason":"stop"}]}`)
		}
		_, _ = io.WriteString(w, "data: [DONE]\n\n")
	default:
		http.NotFound(w, r)
	}
}

type fakeHost struct {
	answer   func() string
	mu       sync.Mutex
	events   []string
	approved chan json.RawMessage
}

func (h *fakeHost) Fetch(ctx context.Context, req Request) (*http.Response, error) {
	var body io.Reader
	if req.Body != nil {
		body = strings.NewReader(*req.Body)
	}
	r, err := http.NewRequestWithContext(ctx, req.Method, req.URL, body)
	if err != nil {
		return nil, err
	}
	for k, v := range req.Headers {
		r.Header.Set(k, v)
	}
	r.Header.Set("Authorization", "Bearer run-token")
	return http.DefaultClient.Do(r)
}

func (h *fakeHost) Emit(e json.RawMessage) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.events = append(h.events, string(e))
}

func (h *fakeHost) Approve(ctx context.Context, view json.RawMessage) (string, error) {
	if h.approved != nil {
		h.approved <- view
	}
	if h.answer == nil {
		<-ctx.Done()
		return "", ctx.Err()
	}
	return h.answer(), nil
}

func (h *fakeHost) lastItems(t *testing.T) []map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	for i := len(h.events) - 1; i >= 0; i-- {
		var e struct {
			Type  string           `json:"type"`
			Items []map[string]any `json:"items"`
		}
		if json.Unmarshal([]byte(h.events[i]), &e) == nil && e.Type == "items" {
			return e.Items
		}
	}
	t.Fatalf("no items event in %v", h.events)
	return nil
}

func setup(t *testing.T) (string, *fakeGopan, Options) {
	t.Helper()
	qjs := os.Getenv("GOPAN_TEST_QJS")
	if qjs == "" {
		t.Skip("GOPAN_TEST_QJS 未设置(quickjs-ng 的 qjs 可执行文件),跳过")
	}
	f := &fakeGopan{}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	args, _ := json.Marshal(map[string]any{
		"text": "在根目录建个叫报销的文件夹", "context": "用户正在看:/", "model": "m", "mode": "proxy",
		"provider": map[string]any{"id": "p1", "name": "DeepSeek", "kind": "OPENAI", "baseUrl": "https://x/v1", "site": true},
	})
	return qjs, f, Options{QJS: qjs, Origin: srv.URL, ConversationID: "c1", Args: args, MaxActive: time.Minute}
}

func TestRunWithApproval(t *testing.T) {
	_, f, opt := setup(t)
	views := make(chan json.RawMessage, 1)
	host := &fakeHost{answer: func() string { return "once" }, approved: views}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	res, err := Run(ctx, opt, host, nil)
	if err != nil {
		t.Fatalf("run: %v (stderr %s)", err, res.Stderr)
	}
	if res.Error != "" || res.Stopped {
		t.Fatalf("result: %+v", res)
	}
	view := <-views
	if !strings.Contains(string(view), "在根目录新建文件夹「报销」") {
		t.Fatalf("approval view: %s", view)
	}
	items := host.lastItems(t)
	if len(items) != 3 || items[1]["status"] != "ok" || items[2]["text"] != "建好了" {
		t.Fatalf("items: %v", items)
	}
	if f.toolCalls.Load() != 1 || f.modelCalls.Load() != 2 || f.appended.Load() == 0 {
		t.Fatalf("tool=%d model=%d appended=%d", f.toolCalls.Load(), f.modelCalls.Load(), f.appended.Load())
	}
	// 新对话的第一句带着「你在看什么」(请求体是 JSON,换行是 \n)
	if !strings.Contains(f.firstModel, `"<gopan-context>\n用户正在看:/\n</gopan-context>\n在根目录建个叫报销的文件夹"`) {
		t.Fatalf("first model request: %s", f.firstModel)
	}
	for _, h := range f.authHeads {
		if !strings.HasSuffix(h, "Bearer run-token") {
			t.Fatalf("request without run token: %s", h)
		}
	}
}

func TestRunDenied(t *testing.T) {
	_, f, opt := setup(t)
	host := &fakeHost{answer: func() string { return "deny" }}
	res, err := Run(context.Background(), opt, host, nil)
	if err != nil || res.Error != "" {
		t.Fatalf("run: %v %+v", err, res)
	}
	if f.toolCalls.Load() != 0 {
		t.Fatal("denied tool was called")
	}
	if items := host.lastItems(t); items[1]["status"] != "denied" {
		t.Fatalf("items: %v", items)
	}
}

func TestStopWhileWaitingForApproval(t *testing.T) {
	_, _, opt := setup(t)
	views := make(chan json.RawMessage, 1)
	host := &fakeHost{approved: views} // 一直不答复
	stop := make(chan struct{})
	go func() {
		<-views
		close(stop)
	}()
	start := time.Now()
	res, err := Run(context.Background(), opt, host, stop)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if !res.Stopped {
		t.Fatalf("not stopped: %+v", res)
	}
	if time.Since(start) > 20*time.Second {
		t.Fatalf("stop took %v", time.Since(start))
	}
}

func TestMissingQJS(t *testing.T) {
	if _, err := Run(context.Background(), Options{QJS: "/nope/qjs"}, &fakeHost{}, nil); err == nil {
		t.Fatal("expected error without qjs")
	}
}
