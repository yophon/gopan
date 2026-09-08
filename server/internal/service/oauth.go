package service

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/store"
)

const (
	oauthCodeTTL    = 5 * time.Minute
	oauthAccessTTL  = time.Hour
	oauthRefreshTTL = 30 * 24 * time.Hour
)

var pkceValue = regexp.MustCompile(`^[A-Za-z0-9._~-]{43,128}$`)

type OAuth struct {
	q *store.Queries
}

func NewOAuth(q *store.Queries) *OAuth {
	return &OAuth{q: q}
}

type OAuthAuthorizationInput struct {
	ClientID            string
	RedirectURI         string
	ResponseType        string
	Scopes              []string
	State               string
	CodeChallenge       string
	CodeChallengeMethod string
}

type OAuthAuthorizationView struct {
	ClientName string
	Scopes     []string
}

type OAuthTokenResult struct {
	AccessToken  string
	RefreshToken string
	ExpiresIn    int64
	Scopes       []string
}

func randomCredential(prefix string, size int) (string, error) {
	raw := make([]byte, size)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return prefix + base64.RawURLEncoding.EncodeToString(raw), nil
}

func ValidateOAuthRedirectURI(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || !u.IsAbs() || u.Fragment != "" || u.User != nil {
		return errf("OAUTH_INVALID_REQUEST", "redirect_uri 必须是无 fragment 的绝对 URL")
	}
	if u.Scheme == "https" && u.Hostname() != "" {
		return nil
	}
	if u.Scheme != "http" {
		return errf("OAUTH_INVALID_REQUEST", "redirect_uri 必须使用 HTTPS 或 HTTP loopback")
	}
	host := strings.Trim(strings.ToLower(u.Hostname()), "[]")
	if host == "localhost" {
		return nil
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errf("OAUTH_INVALID_REQUEST", "HTTP redirect_uri 仅允许 loopback 地址")
	}
	return nil
}

func (o *OAuth) RegisterClient(ctx context.Context, name string, redirectURIs []string) (store.OauthClient, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 80 {
		return store.OauthClient{}, errf("OAUTH_INVALID_REQUEST", "client_name 不能为空且不超过 80 字节")
	}
	if len(redirectURIs) == 0 || len(redirectURIs) > 10 {
		return store.OauthClient{}, errf("OAUTH_INVALID_REQUEST", "redirect_uris 数量必须在 1 到 10 之间")
	}
	seen := make(map[string]struct{}, len(redirectURIs))
	clean := make([]string, 0, len(redirectURIs))
	for _, redirectURI := range redirectURIs {
		redirectURI = strings.TrimSpace(redirectURI)
		if err := ValidateOAuthRedirectURI(redirectURI); err != nil {
			return store.OauthClient{}, err
		}
		if _, ok := seen[redirectURI]; ok {
			continue
		}
		seen[redirectURI] = struct{}{}
		clean = append(clean, redirectURI)
	}
	clientID, err := randomCredential("gopan_client_", 18)
	if err != nil {
		return store.OauthClient{}, err
	}
	return o.q.CreateOAuthClient(ctx, store.CreateOAuthClientParams{
		ClientID: clientID, ClientName: name, RedirectUris: clean,
	})
}

func (o *OAuth) InspectAuthorization(ctx context.Context, in OAuthAuthorizationInput) (*OAuthAuthorizationView, error) {
	client, scopes, err := o.validateAuthorization(ctx, in)
	if err != nil {
		return nil, err
	}
	return &OAuthAuthorizationView{ClientName: client.ClientName, Scopes: scopes}, nil
}

func (o *OAuth) validateAuthorization(ctx context.Context, in OAuthAuthorizationInput) (store.OauthClient, []string, error) {
	if in.ResponseType != "code" {
		return store.OauthClient{}, nil, errf("OAUTH_INVALID_REQUEST", "仅支持 response_type=code")
	}
	client, err := o.q.GetOAuthClient(ctx, in.ClientID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.OauthClient{}, nil, errf("OAUTH_INVALID_CLIENT", "未知 OAuth client")
		}
		return store.OauthClient{}, nil, err
	}
	if !containsString(client.RedirectUris, in.RedirectURI) {
		return store.OauthClient{}, nil, errf("OAUTH_INVALID_REQUEST", "redirect_uri 未注册")
	}
	if in.CodeChallengeMethod != "S256" || !pkceValue.MatchString(in.CodeChallenge) {
		return store.OauthClient{}, nil, errf("OAUTH_INVALID_REQUEST", "必须使用有效的 PKCE S256 code_challenge")
	}
	scopes := in.Scopes
	if len(scopes) == 0 {
		scopes = []string{"files:read"}
	}
	scopes, err = normalizeMCPScopes(scopes)
	if err != nil {
		return store.OauthClient{}, nil, err
	}
	return client, scopes, nil
}

func (o *OAuth) Authorize(ctx context.Context, userID uuid.UUID, in OAuthAuthorizationInput, approved bool) (string, error) {
	client, scopes, err := o.validateAuthorization(ctx, in)
	if err != nil {
		return "", err
	}
	if err := requireScopeAccount(ctx, o.q, userID, scopes); err != nil {
		return "", err
	}
	redirect, _ := url.Parse(in.RedirectURI)
	query := redirect.Query()
	if in.State != "" {
		query.Set("state", in.State)
	}
	if !approved {
		query.Set("error", "access_denied")
		query.Set("error_description", "The user denied the authorization request")
		redirect.RawQuery = query.Encode()
		return redirect.String(), nil
	}
	grant, err := o.q.UpsertOAuthGrant(ctx, store.UpsertOAuthGrantParams{
		ID: uuid.Must(uuid.NewV7()), UserID: userID, ClientID: client.ClientID, Scopes: scopes,
	})
	if err != nil {
		return "", err
	}
	code, err := randomCredential("gopan_code_", 32)
	if err != nil {
		return "", err
	}
	if err := o.q.CreateOAuthAuthorizationCode(ctx, store.CreateOAuthAuthorizationCodeParams{
		CodeHash: hashToken(code), GrantID: grant.ID, ClientID: client.ClientID,
		RedirectUri: in.RedirectURI, Scopes: scopes, CodeChallenge: in.CodeChallenge,
		ExpiresAt: tstz(time.Now().Add(oauthCodeTTL)),
	}); err != nil {
		return "", err
	}
	query.Set("code", code)
	redirect.RawQuery = query.Encode()
	return redirect.String(), nil
}

func (o *OAuth) ExchangeAuthorizationCode(ctx context.Context, code, clientID, redirectURI, verifier string) (*OAuthTokenResult, error) {
	if !pkceValue.MatchString(verifier) {
		return nil, errf("OAUTH_INVALID_GRANT", "无效的 code_verifier")
	}
	row, err := o.q.ConsumeOAuthAuthorizationCode(ctx, hashToken(code))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errf("OAUTH_INVALID_GRANT", "授权码无效或已过期")
		}
		return nil, err
	}
	challenge := sha256.Sum256([]byte(verifier))
	actual := base64.RawURLEncoding.EncodeToString(challenge[:])
	if row.ClientID != clientID || row.RedirectUri != redirectURI || subtle.ConstantTimeCompare([]byte(actual), []byte(row.CodeChallenge)) != 1 {
		return nil, errf("OAUTH_INVALID_GRANT", "授权码校验失败")
	}
	return o.issueTokens(ctx, row.GrantID, row.Scopes)
}

func (o *OAuth) Refresh(ctx context.Context, refreshToken, clientID string) (*OAuthTokenResult, error) {
	row, err := o.q.ConsumeOAuthRefreshToken(ctx, hashToken(refreshToken))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errf("OAUTH_INVALID_GRANT", "refresh_token 无效或已过期")
		}
		return nil, err
	}
	grant, err := o.q.GetOAuthGrant(ctx, row.GrantID)
	if err != nil || grant.RevokedAt.Valid || grant.OwnerDisabled || grant.ClientID != clientID {
		return nil, errf("OAUTH_INVALID_GRANT", "OAuth 授权已失效")
	}
	return o.issueTokens(ctx, row.GrantID, intersectScopes(row.Scopes, grant.Scopes))
}

func (o *OAuth) issueTokens(ctx context.Context, grantID uuid.UUID, scopes []string) (*OAuthTokenResult, error) {
	access, err := randomCredential("gopan_oauth_", 32)
	if err != nil {
		return nil, err
	}
	refresh, err := randomCredential("gopan_refresh_", 32)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	if _, err := o.q.CreateOAuthAccessToken(ctx, store.CreateOAuthAccessTokenParams{
		ID: uuid.Must(uuid.NewV7()), GrantID: grantID, TokenHash: hashToken(access),
		Scopes: scopes, ExpiresAt: tstz(now.Add(oauthAccessTTL)),
	}); err != nil {
		return nil, err
	}
	if _, err := o.q.CreateOAuthRefreshToken(ctx, store.CreateOAuthRefreshTokenParams{
		ID: uuid.Must(uuid.NewV7()), GrantID: grantID, TokenHash: hashToken(refresh),
		Scopes: scopes, ExpiresAt: tstz(now.Add(oauthRefreshTTL)),
	}); err != nil {
		return nil, err
	}
	return &OAuthTokenResult{
		AccessToken: access, RefreshToken: refresh,
		ExpiresIn: int64(oauthAccessTTL.Seconds()), Scopes: scopes,
	}, nil
}

func (o *OAuth) Authenticate(ctx context.Context, token string) (*MCPPrincipal, error) {
	if !strings.HasPrefix(token, "gopan_oauth_") {
		return nil, ErrUnauthenticated
	}
	row, err := o.q.GetOAuthAccessTokenByHash(ctx, hashToken(token))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrUnauthenticated
		}
		return nil, err
	}
	if row.OwnerDisabled || row.GrantRevokedAt.Valid {
		return nil, ErrUnauthenticated
	}
	_ = o.q.TouchOAuthAccessToken(ctx, row.ID)
	return &MCPPrincipal{
		TokenID: row.ID, CredentialID: row.GrantID, UserID: row.UserID, Username: row.Username,
		CredentialType: "oauth", Scopes: scopeSet(intersectScopes(row.Scopes, row.GrantScopes)),
	}, nil
}

func (o *OAuth) ListGrants(ctx context.Context, userID uuid.UUID) ([]store.ListOAuthGrantsRow, error) {
	return o.q.ListOAuthGrants(ctx, userID)
}

func (o *OAuth) RevokeGrant(ctx context.Context, userID, id uuid.UUID) error {
	n, err := o.q.RevokeOAuthGrant(ctx, store.RevokeOAuthGrantParams{ID: id, UserID: userID})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

func containsString(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

func intersectScopes(left, right []string) []string {
	allowed := scopeSet(right)
	out := make([]string, 0, len(left))
	for _, scope := range left {
		if _, ok := allowed[scope]; ok {
			out = append(out, scope)
		}
	}
	return out
}

func scopeSet(scopes []string) map[string]struct{} {
	out := make(map[string]struct{}, len(scopes))
	for _, scope := range scopes {
		out[scope] = struct{}{}
	}
	return out
}
