package mcpserver

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/yophon/gopan/server/internal/service"
)

func TestHandlerRegistersToolsAndRequiresBearer(t *testing.T) {
	// NewHandler 会立即推导并校验所有工具 schema；能构造成功即覆盖 schema 回归。
	h := NewHandler(nil, nil, service.NewMCPTokens(nil), service.NewOAuth(nil), nil, nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/mcp", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("无 Bearer 应 401,got %d", rec.Code)
	}
}

func TestRequestBaseURL(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "http://internal/mcp", nil)
	req.Host = "internal:8080"
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "pan.example.com, proxy.internal")
	if got := requestBaseURL(req); got != "https://pan.example.com" {
		t.Fatalf("requestBaseURL=%q", got)
	}
}

func TestPrincipalRequiresRequestedScope(t *testing.T) {
	p := &service.MCPPrincipal{Scopes: map[string]struct{}{"files:read": {}}}
	ctx := context.WithValue(context.Background(), principalKey{}, p)
	if _, err := principal(ctx, "files:read"); err != nil {
		t.Fatalf("已有 scope 应放行: %v", err)
	}
	if _, err := principal(ctx, "files:upload"); err != service.ErrForbidden {
		t.Fatalf("缺少 scope 应拒绝,got %v", err)
	}
}
