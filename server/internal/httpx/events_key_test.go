package httpx

import (
	"bufio"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

// doc/39 五:MCP 的 API Key 也能连推送,只收到不带文件夹 id 的文件改动

func keyAuth(user uuid.UUID, scopes ...string) KeyAuthenticator {
	return func(_ context.Context, token string) (*service.MCPPrincipal, error) {
		if token != "gopan_key_ok" {
			return nil, service.ErrUnauthenticated
		}
		s := map[string]struct{}{}
		for _, sc := range scopes {
			s[sc] = struct{}{}
		}
		return &service.MCPPrincipal{UserID: user, Scopes: s, CredentialType: "api_key"}, nil
	}
}

func openEvents(t *testing.T, srv *httptest.Server, token string) *http.Response {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func TestEventsAPIKeyGetsBareFileChanges(t *testing.T) {
	hub := service.NewChangeHub(nil)
	user := uuid.New()
	srv := httptest.NewServer(EventsHandler(nil, hub, keyAuth(user, "files:read")))
	defer srv.Close()

	resp := openEvents(t, srv, "gopan_key_ok")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	rd := bufio.NewReader(resp.Body)
	// 先读到开头的 ": ok",说明已经订阅上了
	for {
		line, err := rd.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if strings.HasPrefix(line, ": ok") {
			break
		}
	}
	hub.Publish(user, service.Change{Topic: "chat"})
	hub.Publish(user, service.Change{Topic: "files", Folders: []string{"别处的文件夹"}, Trash: true})
	for {
		line, err := rd.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if strings.HasPrefix(line, "data:") {
			if got := strings.TrimSpace(line); got != `data: {"t":"files"}` {
				t.Fatalf("got %q, want bare files change (chat skipped, folders stripped)", got)
			}
			return
		}
	}
}

func TestEventsAPIKeyRejected(t *testing.T) {
	hub := service.NewChangeHub(nil)
	user := uuid.New()

	bad := httptest.NewServer(EventsHandler(nil, hub, keyAuth(user, "files:read")))
	defer bad.Close()
	if resp := openEvents(t, bad, "gopan_key_wrong"); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("bad key: status = %d, want 401", resp.StatusCode)
	}

	noRead := httptest.NewServer(EventsHandler(nil, hub, keyAuth(user, "files:write")))
	defer noRead.Close()
	if resp := openEvents(t, noRead, "gopan_key_ok"); resp.StatusCode != http.StatusForbidden {
		t.Fatalf("no files:read: status = %d, want 403", resp.StatusCode)
	}
}
