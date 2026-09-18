package service_test

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/yophon/gopan/server/internal/service"
)

func TestIdentityBindingAndRevocation(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	old, err := auth.Register(ctx, "existing_owner", "original-password", "local")
	if err != nil {
		t.Fatal(err)
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	var active atomic.Bool
	active.Store(true)
	var refreshes atomic.Int32
	var expectedNonce, challenge, subject, issuer string
	subject = "owner-sub"
	token := func(nonce string) map[string]any {
		c := jwt.MapClaims{"iss": issuer, "aud": "gopan", "sub": subject, "sid": "device-one", "nonce": nonce, "exp": time.Now().Add(5 * time.Minute).Unix(), "iat": time.Now().Unix()}
		signed := jwt.NewWithClaims(jwt.SigningMethodRS256, c)
		signed.Header["kid"] = "test-key"
		idToken, e := signed.SignedString(key)
		if e != nil {
			t.Fatal(e)
		}
		return map[string]any{"access_token": "access", "refresh_token": "refresh", "id_token": idToken, "expires_in": 300}
	}
	center := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = r.ParseForm()
		switch r.URL.Path {
		case "/jwks":
			json.NewEncoder(w).Encode(map[string]any{"keys": []any{map[string]string{"kty": "RSA", "alg": "RS256", "use": "sig", "kid": "test-key", "n": base64.RawURLEncoding.EncodeToString(key.N.Bytes()), "e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes())}}})
		case "/oauth/token":
			if r.Form.Get("client_secret") != strings.Repeat("s", 40) {
				w.WriteHeader(401)
				return
			}
			if r.Form.Get("grant_type") == "authorization_code" {
				h := sha256.Sum256([]byte(r.Form.Get("code_verifier")))
				if base64.RawURLEncoding.EncodeToString(h[:]) != challenge {
					w.WriteHeader(400)
					return
				}
			} else {
				refreshes.Add(1)
			}
			json.NewEncoder(w).Encode(token(expectedNonce))
		case "/oauth/introspect":
			json.NewEncoder(w).Encode(map[string]any{"active": active.Load(), "sub": subject, "sid": "device-one"})
		case "/oauth/mobile/exchange":
			json.NewEncoder(w).Encode(token(""))
		default:
			w.WriteHeader(404)
		}
	}))
	defer center.Close()
	issuer = center.URL
	provider, err := service.NewIDProvider(service.IDConfig{Issuer: issuer, Origin: "http://127.0.0.1:8080", ClientID: "gopan", Secret: strings.Repeat("s", 40), Subject: subject, UserID: old.User.ID, Development: true}, pool, auth)
	if err != nil {
		t.Fatal(err)
	}
	handler := provider.Handler(func(*http.Request) string { return "test" })
	if _, err = auth.Login(ctx, "existing_owner", "original-password", "test"); err == nil {
		t.Fatal("bound owner must not bypass ID using old password")
	}
	if _, err = auth.ParseAccess(old.AccessToken); err == nil {
		t.Fatal("old access must stop working after binding")
	}
	if _, err = auth.Refresh(ctx, old.RefreshToken); err == nil {
		t.Fatal("old refresh must stop working after binding")
	}
	start := httptest.NewRecorder()
	handler.ServeHTTP(start, httptest.NewRequest("GET", "http://127.0.0.1:8080/api/auth/id/start?return=%2Fsearch", nil))
	if start.Code != 302 {
		t.Fatalf("start %d", start.Code)
	}
	authorize, _ := url.Parse(start.Header().Get("Location"))
	q := authorize.Query()
	expectedNonce = q.Get("nonce")
	challenge = q.Get("code_challenge")
	callback := "http://127.0.0.1:8080/api/auth/id/callback?code=one&state=" + q.Get("state")
	rejected := httptest.NewRecorder()
	handler.ServeHTTP(rejected, httptest.NewRequest("GET", callback, nil))
	if rejected.Code != 403 {
		t.Fatal("state cookie required")
	}
	request := httptest.NewRequest("GET", callback, nil)
	request.AddCookie(start.Result().Cookies()[0])
	done := httptest.NewRecorder()
	handler.ServeHTTP(done, request)
	if done.Code != 303 || done.Header().Get("Location") != "http://127.0.0.1:8080/search" {
		t.Fatalf("callback %d", done.Code)
	}
	var refresh string
	for _, cookie := range done.Result().Cookies() {
		if cookie.Name == "gopan_rt" {
			refresh = cookie.Value
			if cookie.Path != "/query" || !cookie.HttpOnly {
				t.Fatal("bad cookie")
			}
		}
	}
	logged, err := auth.Refresh(ctx, refresh)
	if err != nil {
		t.Fatal(err)
	}
	if logged.User.ID != old.User.ID || logged.User.QuotaBytes != old.User.QuotaBytes {
		t.Fatal("must preserve user and quota")
	}
	identity, err := auth.ParseAccess(logged.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, "UPDATE id_sessions SET expires_at=now() WHERE family_id=$1", identity.FamilyID); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	errs := make(chan error, 2)
	for range 2 {
		wg.Add(1)
		go func() { defer wg.Done(); _, e := auth.ParseAccess(logged.AccessToken); errs <- e }()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	if refreshes.Load() != 1 {
		t.Fatal("concurrent refresh must serialize")
	}
	rollback := newAuth(pool)
	if _, err = rollback.ParseAccess(logged.AccessToken); err == nil {
		t.Fatal("disabling provider must not promote ID session to password auth")
	}
	active.Store(false)
	if _, err = auth.ParseAccess(logged.AccessToken); err == nil {
		t.Fatal("revoked center session must reject access")
	}
	if _, err = auth.Refresh(ctx, logged.RefreshToken); err == nil {
		t.Fatal("revoked center session must reject refresh")
	}
	active.Store(true)
	subject = "other-account"
	native := httptest.NewRecorder()
	r := httptest.NewRequest("POST", "http://127.0.0.1:8080/api/auth/id/mobile", strings.NewReader("ticket=x&verifier=x"))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	handler.ServeHTTP(native, r)
	if native.Code != 403 {
		t.Fatal("unmapped subject must not access files")
	}
	center.Close()
	if _, err = auth.ParseAccess(logged.AccessToken); err == nil {
		t.Fatal("offline provider must fail closed")
	}
}
