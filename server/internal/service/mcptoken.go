package service

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"sort"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/store"
)

const maxMCPTokens = 20

var validMCPScopes = map[string]struct{}{
	"files:read":     {},
	"files:write":    {},
	"files:delete":   {},
	"files:upload":   {},
	"files:download": {},
	"shares:read":    {},
	"shares:write":   {},
	"audit:read":     {},
	"admin:read":     {},
	"admin:users":    {},
	"admin:tasks":    {},
	"admin:purge":    {},
}

var MCPScopes = []string{
	"files:read",
	"files:download",
	"files:upload",
	"files:write",
	"files:delete",
	"shares:read", "shares:write", "audit:read",
}

var MCPAdminScopes = []string{"admin:read", "admin:users", "admin:tasks", "admin:purge"}

func requireScopeAccount(ctx context.Context, q *store.Queries, user uuid.UUID, scopes []string) error {
	for _, scope := range scopes {
		if strings.HasPrefix(scope, "admin:") {
			return NewAdmin(q, 0).require(ctx, user)
		}
	}
	return nil
}

type MCPPrincipal struct {
	TokenID        uuid.UUID
	CredentialID   uuid.UUID
	RootID         *uuid.UUID
	UserID         uuid.UUID
	Username       string
	CredentialType string
	Scopes         map[string]struct{}
}

func (p *MCPPrincipal) HasScope(scope string) bool {
	_, ok := p.Scopes[scope]
	return ok
}

type MCPTokens struct {
	q *store.Queries
}

func NewMCPTokens(q *store.Queries) *MCPTokens {
	return &MCPTokens{q: q}
}

func (m *MCPTokens) Create(ctx context.Context, userID uuid.UUID, name string, scopes []string) (string, store.McpToken, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 64 {
		return "", store.McpToken{}, errf("INVALID_INPUT", "名称不能为空且不超过 64 字节")
	}
	existing, err := m.q.ListMCPTokens(ctx, userID)
	if err != nil {
		return "", store.McpToken{}, err
	}
	if len(existing) >= maxMCPTokens {
		return "", store.McpToken{}, errf("INVALID_INPUT", "MCP API Key 最多 %d 条,请先吊销不用的", maxMCPTokens)
	}
	scopes, err = normalizeMCPScopes(scopes)
	if err != nil {
		return "", store.McpToken{}, err
	}
	if err := requireScopeAccount(ctx, m.q, userID, scopes); err != nil {
		return "", store.McpToken{}, err
	}
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return "", store.McpToken{}, err
	}
	plain := "gopan_key_" + base64.RawURLEncoding.EncodeToString(raw)
	row, err := m.q.CreateMCPToken(ctx, store.CreateMCPTokenParams{
		ID: uuid.Must(uuid.NewV7()), UserID: userID, Name: name,
		TokenHash: hashToken(plain), Scopes: scopes,
	})
	if err != nil {
		return "", store.McpToken{}, err
	}
	return plain, row, nil
}

func normalizeMCPScopes(scopes []string) ([]string, error) {
	if len(scopes) == 0 {
		scopes = []string{"files:read"}
	}
	seen := make(map[string]struct{}, len(scopes))
	for _, scope := range scopes {
		scope = strings.TrimSpace(scope)
		if _, ok := validMCPScopes[scope]; !ok {
			return nil, errf("INVALID_INPUT", "不支持的 MCP scope: %s", scope)
		}
		seen[scope] = struct{}{}
	}
	out := make([]string, 0, len(seen))
	admin, file := false, false
	for scope := range seen {
		if strings.HasPrefix(scope, "admin:") {
			admin = true
		} else {
			file = true
		}
		out = append(out, scope)
	}
	if admin && file {
		return nil, errf("INVALID_INPUT", "管理员权限需使用单独凭据，不能与文件权限混合")
	}
	sort.Strings(out)
	return out, nil
}

func (m *MCPTokens) List(ctx context.Context, userID uuid.UUID) ([]store.McpToken, error) {
	return m.q.ListMCPTokens(ctx, userID)
}

func (m *MCPTokens) Revoke(ctx context.Context, userID, id uuid.UUID) error {
	n, err := m.q.RevokeMCPToken(ctx, store.RevokeMCPTokenParams{ID: id, UserID: userID})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

func (m *MCPTokens) Authenticate(ctx context.Context, token string) (*MCPPrincipal, error) {
	if !strings.HasPrefix(token, "gopan_key_") && !strings.HasPrefix(token, "gopan_mcp_") {
		return nil, ErrUnauthenticated
	}
	row, err := m.q.GetMCPTokenByHash(ctx, hashToken(token))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrUnauthenticated
		}
		return nil, err
	}
	if row.OwnerDisabled {
		return nil, ErrUnauthenticated
	}
	scopes := make(map[string]struct{}, len(row.Scopes))
	for _, scope := range row.Scopes {
		scopes[scope] = struct{}{}
	}
	_ = m.q.TouchMCPToken(ctx, row.ID)
	return &MCPPrincipal{
		TokenID: row.ID, CredentialID: row.ID, UserID: row.UserID, Username: row.Username,
		CredentialType: "api_key", Scopes: scopes,
	}, nil
}
