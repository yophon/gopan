package service

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type IDConfig struct {
	Issuer, Origin, ClientID, Secret, Subject string
	UserID                                    uuid.UUID
	Development                               bool
}
type IDProvider struct {
	Config IDConfig
	pool   *pgxpool.Pool
	auth   *Auth
	client *http.Client
	seal   cipher.AEAD
}
type idTokens struct {
	Access  string `json:"access_token"`
	Refresh string `json:"refresh_token"`
	ID      string `json:"id_token"`
	Expires int    `json:"expires_in"`
}
type idClaims struct {
	SID   string `json:"sid"`
	Nonce string `json:"nonce"`
	jwt.RegisteredClaims
}

var ErrIDUnavailable = errors.New("account service unavailable")

func NewIDProvider(cfg IDConfig, pool *pgxpool.Pool, auth *Auth) (*IDProvider, error) {
	for _, origin := range []string{cfg.Issuer, cfg.Origin} {
		u, e := url.Parse(origin)
		if e != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" || u.Host == "" || (u.Scheme != "https" && !(cfg.Development && u.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost"))) {
			return nil, errors.New("identity requires an HTTPS origin")
		}
	}
	if len(cfg.Secret) < 32 || cfg.Subject == "" || cfg.ClientID == "" || cfg.UserID == uuid.Nil {
		return nil, errors.New("incomplete identity binding")
	}
	key := sha256.Sum256([]byte(cfg.Secret))
	block, _ := aes.NewCipher(key[:])
	seal, _ := cipher.NewGCM(block)
	p := &IDProvider{Config: cfg, pool: pool, auth: auth, seal: seal, client: &http.Client{Timeout: 5 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("redirect denied") }}}
	auth.identity = p
	return p, nil
}
func idRandom() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func idHash(v string) string {
	b := sha256.Sum256([]byte(v))
	return base64.RawURLEncoding.EncodeToString(b[:])
}
func idReturn(v string) string {
	if !strings.HasPrefix(v, "/") || strings.HasPrefix(v, "//") || strings.ContainsAny(v, "\\\r\n") || strings.HasPrefix(v, "/api/") || strings.HasPrefix(v, "/query") {
		return "/drive"
	}
	return v
}
func (p *IDProvider) post(ctx context.Context, path string, values url.Values, out any) error {
	values.Set("client_id", p.Config.ClientID)
	values.Set("client_secret", p.Config.Secret)
	r, _ := http.NewRequestWithContext(ctx, "POST", p.Config.Issuer+path, strings.NewReader(values.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := p.client.Do(r)
	if err != nil {
		return ErrIDUnavailable
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 500 {
		return ErrIDUnavailable
	}
	if resp.StatusCode != 200 {
		return ErrUnauthenticated
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 65536)).Decode(out) != nil {
		return ErrIDUnavailable
	}
	return nil
}
func (p *IDProvider) introspect(ctx context.Context, t idTokens) (string, error) {
	var v struct {
		Active bool   `json:"active"`
		Sub    string `json:"sub"`
		SID    string `json:"sid"`
	}
	if err := p.post(ctx, "/oauth/introspect", url.Values{"token": {t.Access}}, &v); err != nil {
		return "", err
	}
	if !v.Active || v.Sub != p.Config.Subject || v.SID == "" {
		return "", ErrUnauthenticated
	}
	return v.SID, nil
}
func (p *IDProvider) validate(ctx context.Context, t idTokens, nonce string) error {
	if t.Access == "" || t.Refresh == "" || t.Expires <= 0 || t.Expires > 3600 {
		return ErrUnauthenticated
	}
	r, _ := http.NewRequestWithContext(ctx, "GET", p.Config.Issuer+"/jwks", nil)
	resp, err := p.client.Do(r)
	if err != nil {
		return ErrIDUnavailable
	}
	defer resp.Body.Close()
	var jwks struct {
		Keys []struct{ Kty, Use, Alg, Kid, N, E string }
	}
	if resp.StatusCode != 200 || json.NewDecoder(io.LimitReader(resp.Body, 65536)).Decode(&jwks) != nil {
		return ErrIDUnavailable
	}
	var c idClaims
	_, err = jwt.ParseWithClaims(t.ID, &c, func(token *jwt.Token) (any, error) {
		for _, key := range jwks.Keys {
			if key.Kid == token.Header["kid"] && key.Kty == "RSA" && key.Alg == "RS256" {
				n, e1 := base64.RawURLEncoding.DecodeString(key.N)
				e, e2 := base64.RawURLEncoding.DecodeString(key.E)
				if e1 != nil || e2 != nil || len(e) > 4 || len(n) < 256 {
					return nil, ErrUnauthenticated
				}
				var exponent int
				for _, b := range e {
					exponent = exponent*256 + int(b)
				}
				if exponent < 3 {
					return nil, ErrUnauthenticated
				}
				return &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: exponent}, nil
			}
		}
		return nil, ErrUnauthenticated
	}, jwt.WithValidMethods([]string{"RS256"}), jwt.WithIssuer(p.Config.Issuer), jwt.WithAudience(p.Config.ClientID), jwt.WithExpirationRequired())
	if err != nil || c.Subject != p.Config.Subject || c.SID == "" || (nonce != "" && c.Nonce != nonce) {
		return ErrUnauthenticated
	}
	sid, err := p.introspect(ctx, t)
	if err != nil {
		return err
	}
	if sid != c.SID {
		return ErrUnauthenticated
	}
	return nil
}
func (p *IDProvider) encrypt(t idTokens) []byte {
	v, _ := json.Marshal(t)
	iv := make([]byte, p.seal.NonceSize())
	if _, e := rand.Read(iv); e != nil {
		panic(e)
	}
	return p.seal.Seal(iv, iv, v, nil)
}
func (p *IDProvider) decrypt(b []byte) (idTokens, error) {
	var t idTokens
	if len(b) < p.seal.NonceSize() {
		return t, ErrUnauthenticated
	}
	v, e := p.seal.Open(nil, b[:p.seal.NonceSize()], b[p.seal.NonceSize():], nil)
	if e != nil {
		return t, e
	}
	e = json.Unmarshal(v, &t)
	return t, e
}

// PostgreSQL row locking serializes refresh across requests and processes.
func (p *IDProvider) Check(ctx context.Context, user, family uuid.UUID) error {
	if user != p.Config.UserID {
		return nil
	}
	if family == uuid.Nil {
		return ErrUnauthenticated
	}
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return ErrIDUnavailable
	}
	defer tx.Rollback(ctx)
	var encrypted []byte
	var expires time.Time
	err = tx.QueryRow(ctx, `SELECT i.tokens,i.expires_at FROM id_sessions i JOIN sessions s USING(family_id) JOIN users u ON u.id=s.user_id WHERE i.family_id=$1 AND s.user_id=$2 AND s.revoked_at IS NULL AND u.disabled_at IS NULL AND EXISTS(SELECT 1 FROM refresh_tokens r WHERE r.family_id=s.family_id AND r.revoked_at IS NULL AND r.expires_at>now()) FOR UPDATE OF i`, family, user).Scan(&encrypted, &expires)
	if err != nil {
		return ErrUnauthenticated
	}
	t, err := p.decrypt(encrypted)
	if err != nil {
		return ErrUnauthenticated
	}
	if expires.Before(time.Now().Add(30 * time.Second)) {
		var next idTokens
		if err = p.post(ctx, "/oauth/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {t.Refresh}}, &next); err != nil {
			return err
		}
		if err = p.validate(ctx, next, ""); err != nil {
			return err
		}
		t = next
		if _, err = tx.Exec(ctx, "UPDATE id_sessions SET tokens=$1,expires_at=$2 WHERE family_id=$3", p.encrypt(t), time.Now().Add(time.Duration(t.Expires)*time.Second), family); err != nil {
			return ErrIDUnavailable
		}
	}
	if _, err = p.introspect(ctx, t); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
func (p *IDProvider) establish(ctx context.Context, t idTokens, nonce, ip string) (*AuthResult, error) {
	if err := p.validate(ctx, t, nonce); err != nil {
		return nil, err
	}
	u, err := p.auth.q.GetUserByID(ctx, p.Config.UserID)
	if err != nil || u.DisabledAt.Valid {
		return nil, ErrUnauthenticated
	}
	result, err := p.auth.issuePair(ctx, u, ip)
	if err != nil {
		return nil, err
	}
	claims, err := p.auth.parseAccess(result.AccessToken)
	if err != nil {
		return nil, err
	}
	if _, err = p.pool.Exec(ctx, "INSERT INTO id_sessions VALUES($1,$2,$3)", claims.FamilyID, p.encrypt(t), time.Now().Add(time.Duration(t.Expires)*time.Second)); err != nil {
		_ = p.auth.q.RevokeRefreshFamily(ctx, claims.FamilyID)
		return nil, err
	}
	return result, nil
}
func (p *IDProvider) cookie(w http.ResponseWriter, name, value, path string, seconds int) {
	http.SetCookie(w, &http.Cookie{Name: name, Value: value, Path: path, MaxAge: seconds, HttpOnly: true, Secure: !p.Config.Development, SameSite: http.SameSiteLaxMode})
}
func (p *IDProvider) Handler(ip func(*http.Request) string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		ctx := WithClientMeta(r.Context(), ClientMeta{IP: ip(r), UA: r.UserAgent()})
		if r.URL.Path == "/api/auth/id/config" && r.Method == "GET" {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]string{"issuer": p.Config.Issuer})
			return
		}
		if r.URL.Path == "/api/auth/id/start" && r.Method == "GET" {
			state, verifier, nonce := idRandom(), idRandom(), idRandom()
			_, _ = p.pool.Exec(ctx, "DELETE FROM id_logins WHERE expires_at<=now()")
			_, err := p.pool.Exec(ctx, "INSERT INTO id_logins VALUES($1,$2,$3,$4,$5)", idHash(state), verifier, nonce, idReturn(r.URL.Query().Get("return")), time.Now().Add(5*time.Minute))
			if err != nil {
				http.Error(w, "暂时无法登录", 503)
				return
			}
			p.cookie(w, "gopan_oidc", state, "/api/auth/id", 300)
			q := url.Values{"client_id": {p.Config.ClientID}, "redirect_uri": {p.Config.Origin + "/api/auth/id/callback"}, "response_type": {"code"}, "scope": {"openid profile offline_access"}, "state": {state}, "nonce": {nonce}, "code_challenge": {idHash(verifier)}, "code_challenge_method": {"S256"}}
			http.Redirect(w, r, p.Config.Issuer+"/authorize?"+q.Encode(), 302)
			return
		}
		var t idTokens
		var nonce, returnTo string
		var err error
		switch {
		case r.URL.Path == "/api/auth/id/callback" && r.Method == "GET":
			q := r.URL.Query()
			state := q.Get("state")
			ck, e := r.Cookie("gopan_oidc")
			if e != nil || state == "" || len(q["state"]) != 1 || len(q["code"]) != 1 || subtle.ConstantTimeCompare([]byte(state), []byte(ck.Value)) != 1 {
				http.Error(w, "登录已过期，请重试", 403)
				return
			}
			var verifier string
			err = p.pool.QueryRow(ctx, "DELETE FROM id_logins WHERE state=$1 AND expires_at>now() RETURNING verifier,nonce,return_to", idHash(state)).Scan(&verifier, &nonce, &returnTo)
			if err == nil {
				err = p.post(ctx, "/oauth/token", url.Values{"grant_type": {"authorization_code"}, "code": {q.Get("code")}, "code_verifier": {verifier}, "redirect_uri": {p.Config.Origin + "/api/auth/id/callback"}}, &t)
			}
		case r.URL.Path == "/api/auth/id/mobile" && r.Method == "POST":
			r.Body = http.MaxBytesReader(w, r.Body, 8192)
			if r.ParseForm() != nil {
				http.Error(w, "请求无效", 400)
				return
			}
			returnTo = r.PostForm.Get("return")
			err = p.post(ctx, "/oauth/mobile/exchange", url.Values{"ticket": {r.PostForm.Get("ticket")}, "verifier": {r.PostForm.Get("verifier")}}, &t)
		default:
			http.NotFound(w, r)
			return
		}
		var result *AuthResult
		if err == nil {
			result, err = p.establish(ctx, t, nonce, ip(r))
		}
		if err != nil {
			status := 403
			if errors.Is(err, ErrIDUnavailable) {
				status = 503
			}
			http.Error(w, "账号未授权或登录暂不可用，请返回云盘重试", status)
			return
		}
		p.cookie(w, "gopan_rt", result.RefreshToken, "/query", int(p.auth.refreshTTL.Seconds()))
		p.cookie(w, "gopan_oidc", "", "/api/auth/id", -1)
		http.Redirect(w, r, p.Config.Origin+idReturn(returnTo), 303)
	})
}
