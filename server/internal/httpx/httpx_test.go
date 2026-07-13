package httpx

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

func TestNormalizePath(t *testing.T) {
	cases := map[string]string{
		"/query":                      "/query",
		"/healthz":                    "/healthz",
		"/pack":                       "/pack",
		"/s/AbC123xyz9":               "/s/:token",
		"/mcp":                        "/mcp",
		"/mcp-download/eyJhbGciOi...": "/mcp-download/:ticket",
		"/oauth/token":                "/oauth/:endpoint",
		"/.well-known/oauth-authorization-server": "/.well-known/oauth-*",
		"/dav":         "/dav",
		"/dav/docs/a":  "/dav",
		"/":            "/spa",
		"/drive/xxx":   "/spa",
		"/assets/x.js": "/spa",
	}
	for in, want := range cases {
		if got := normalizePath(in); got != want {
			t.Errorf("normalizePath(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSecurityHeaders(t *testing.T) {
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	for _, tc := range []struct {
		dev     bool
		wantCSP bool
	}{{false, true}, {true, false}} {
		rec := httptest.NewRecorder()
		WithSecurityHeaders(inner, tc.dev).ServeHTTP(rec, httptest.NewRequest("GET", "/", nil))
		h := rec.Header()
		if h.Get("X-Content-Type-Options") != "nosniff" || h.Get("X-Frame-Options") != "DENY" ||
			h.Get("Referrer-Policy") != "same-origin" {
			t.Fatalf("dev=%v 基础安全头缺失:%v", tc.dev, h)
		}
		if got := h.Get("Content-Security-Policy") != ""; got != tc.wantCSP {
			t.Fatalf("dev=%v CSP 应为 %v", tc.dev, tc.wantCSP)
		}
	}
}

// newAuthNoDB:ParseAccess/IssueAccess 是纯 JWT 计算,不碰库,q 传 nil 安全。
func newAuthNoDB() *service.Auth {
	return service.NewAuth(nil, []byte("test-secret-test-secret-test-secret"),
		15*time.Minute, 14*24*time.Hour, true, 1<<30)
}

func TestWithAuthBearer(t *testing.T) {
	auth := newAuthNoDB()
	uid := uuid.Must(uuid.NewV7())
	token, err := auth.IssueAccess(uid, service.ScopeUser)
	if err != nil {
		t.Fatal(err)
	}

	var gotIdent *service.Identity
	var gotErr error
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotIdent, gotErr = IdentityFrom(r.Context())
	})
	h := WithAuth(inner, auth)

	// 合法 Bearer → 身份进 ctx
	req := httptest.NewRequest("POST", "/query", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	h.ServeHTTP(httptest.NewRecorder(), req)
	if gotErr != nil || gotIdent == nil || gotIdent.UserID != uid || gotIdent.Scope != service.ScopeUser {
		t.Fatalf("合法 token 应解析出身份:%+v err=%v", gotIdent, gotErr)
	}

	// 坏 token → 不拦截但 ctx 无身份(由 resolver 决定是否要求登录)
	req = httptest.NewRequest("POST", "/query", nil)
	req.Header.Set("Authorization", "Bearer not-a-jwt")
	h.ServeHTTP(httptest.NewRecorder(), req)
	if gotErr == nil {
		t.Fatal("坏 token 不应产出身份")
	}

	// 无头 → 同样放行且无身份
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/query", nil))
	if gotErr == nil {
		t.Fatal("无 Authorization 不应产出身份")
	}
}

func TestUserFromRejectsGuestScope(t *testing.T) {
	auth := newAuthNoDB()
	uid := uuid.Must(uuid.NewV7())
	guestTok, _ := auth.IssueAccessFor(uid, "share:"+uuid.Must(uuid.NewV7()).String(), 30*time.Minute)

	var err error
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, err = UserFrom(r.Context())
	})
	req := httptest.NewRequest("POST", "/query", nil)
	req.Header.Set("Authorization", "Bearer "+guestTok)
	WithAuth(inner, auth).ServeHTTP(httptest.NewRecorder(), req)
	if err != service.ErrForbidden {
		t.Fatalf("访客 scope 走 UserFrom 应 FORBIDDEN,got %v", err)
	}
}

func TestRefreshCookieRoundtrip(t *testing.T) {
	auth := newAuthNoDB()
	var readBack string
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		SetRefreshCookie(r.Context(), "the-refresh-token", 3600, true)
		readBack = RefreshTokenFrom(r.Context())
	})
	req := httptest.NewRequest("POST", "/query", nil)
	req.AddCookie(&http.Cookie{Name: "gopan_rt", Value: "incoming-token"})
	rec := httptest.NewRecorder()
	WithAuth(inner, auth).ServeHTTP(rec, req)

	if readBack != "incoming-token" {
		t.Fatalf("应读到请求 cookie,got %q", readBack)
	}
	sc := rec.Header().Get("Set-Cookie")
	for _, want := range []string{"gopan_rt=the-refresh-token", "HttpOnly", "Path=/query", "SameSite=Lax", "Secure"} {
		if !strings.Contains(sc, want) {
			t.Fatalf("Set-Cookie 缺 %q:%s", want, sc)
		}
	}
}

func TestPackHandlerAuth(t *testing.T) {
	auth := newAuthNoDB()
	h := PackHandler(auth, nil) // 401 路径不触达 packer

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/pack?nodes=x", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("无 token 应 401,got %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/pack?nodes=x&token=garbage", nil)
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("坏 token 应 401,got %d", rec.Code)
	}
}
