package oauthserver

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/yophon/gopan/server/internal/service"
)

func TestAuthorizationMetadataUsesForwardedOrigin(t *testing.T) {
	server := New(service.NewOAuth(nil))
	req := httptest.NewRequest(http.MethodGet, "http://internal/.well-known/oauth-authorization-server", nil)
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "pan.example.com")
	rec := httptest.NewRecorder()
	server.AuthorizationMetadata(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d", rec.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["issuer"] != "https://pan.example.com" || body["token_endpoint"] != "https://pan.example.com/oauth/token" {
		t.Fatalf("metadata origin 不符: %+v", body)
	}
}
