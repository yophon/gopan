package httpx_test

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// 真转发:站点提供的服务(本机假上游)换上真 key、去掉客户端的认证、流式回写并记账;
// 自己的服务指向本机被挡;看不到别人的服务。
func TestAIProxyRoundTrip(t *testing.T) {
	pool := setupPool(t)
	ctx := t.Context()
	q := store.New(pool)
	secret := []byte("test-secret-test-secret-test-secret")
	auth := service.NewAuth(q, secret, 15*time.Minute, 14*24*time.Hour, true, 1<<30)
	admin := service.NewAdmin(q, 1<<30)
	ai := service.NewAI(pool, admin, secret)
	boss, err := auth.Register(ctx, "boss", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE users SET is_admin = true WHERE id = $1`, boss.User.ID); err != nil {
		t.Fatal(err)
	}
	kid, err := auth.Register(ctx, "kid", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}

	var gotAuth, gotPath, gotBody string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotPath = r.Header.Get("Authorization"), r.URL.Path
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		_, _ = io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
		fl.Flush()
		_, _ = io.WriteString(w, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n")
	}))
	t.Cleanup(upstream.Close)

	key := "sk-real"
	site, err := ai.CreateProvider(ctx, boss.User.ID, true, service.AIProviderInput{Name: "站点", Kind: "openai", BaseURL: upstream.URL + "/v1", Models: []string{"m"}, Key: &key})
	if err != nil {
		t.Fatal(err)
	}
	mine, err := ai.CreateProvider(ctx, kid.User.ID, false, service.AIProviderInput{Name: "我的", Kind: "openai", BaseURL: "https://127.0.0.1:1", Models: []string{"m"}, Key: &key})
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.Handle("POST /api/ai/{provider}/{path...}", httpx.AIProxyHandler(auth, ai, fetcher.Guard{}))
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	post := func(token, provider, path string) *http.Response {
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/ai/"+provider+"/"+path, strings.NewReader(`{"model":"m","stream":true,"messages":[]}`))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { resp.Body.Close() })
		return resp
	}

	resp := post(kid.AccessToken, site.ID.String(), "chat/completions")
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.Contains(string(body), "你好") || resp.Header.Get("X-Accel-Buffering") != "no" {
		t.Fatalf("proxy: %d %s", resp.StatusCode, body)
	}
	if gotAuth != "Bearer sk-real" || gotPath != "/v1/chat/completions" || !strings.Contains(gotBody, `"include_usage":true`) {
		t.Fatalf("upstream saw auth=%q path=%q body=%s", gotAuth, gotPath, gotBody)
	}
	usage, err := ai.Usage(ctx, kid.User.ID)
	if err != nil || len(usage) != 1 || usage[0].InputTokens != 10 || usage[0].OutputTokens != 5 {
		t.Fatalf("usage: %+v %v", usage, err)
	}

	if r := post(kid.AccessToken, site.ID.String(), "models"); r.StatusCode != http.StatusNotFound {
		t.Fatalf("non-chat path: %d", r.StatusCode)
	}
	if r := post(kid.AccessToken, mine.ID.String(), "chat/completions"); r.StatusCode != http.StatusBadGateway {
		t.Fatalf("own provider to loopback: %d", r.StatusCode)
	}
	if r := post(boss.AccessToken, mine.ID.String(), "chat/completions"); r.StatusCode != http.StatusNotFound {
		t.Fatalf("other's provider: %d", r.StatusCode)
	}
	if r := post("bogus", site.ID.String(), "chat/completions"); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("no login: %d", r.StatusCode)
	}
}
