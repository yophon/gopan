package service

// 助手的模型服务(doc/33 三):站点提供的(owner_id 为空,管理员配)与个人自己的,key 加密存库。
//
// key 只写不读:列表里从不带 key;直连时 ProviderKey 只把自己的 key 给自己,站点的一律不给(给了家里每个人
// 都能把管理员的 key 拿走),站点的只能经服务器转发(ProxyTarget)。转发的用量按天记进 ai_usage,
// 站点额度按它判。

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	maxAIProviders = 20 // 每人自己的模型服务
	maxAIModels    = 50
)

var (
	errAIKeyBroken = &Error{Code: "AI_KEY_BROKEN", Message: "这个模型服务的 key 解不开了(服务器密钥换过),请重填"}
	errAIQuota     = &Error{Code: "AI_QUOTA", Message: "今天的站点额度用完了,可以换自己的 key"}
	errAISiteKey   = &Error{Code: "FORBIDDEN", Message: "站点提供的模型服务只能经服务器转发"}
)

var aiKinds = map[string]bool{"openai": true, "anthropic": true, "google": true, "openai-responses": true}

type AIProvider struct {
	ID          uuid.UUID
	OwnerID     *uuid.UUID
	Name        string
	Kind        string
	BaseURL     string
	Models      []string
	HasKey      bool
	DailyTokens int64
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

func (p *AIProvider) Site() bool { return p.OwnerID == nil }

type AIProviderInput struct {
	Name        string
	Kind        string
	BaseURL     string
	Models      []string
	Key         *string // nil = 不变;"" = 清掉
	DailyTokens int64
}

type AIUsageItem struct {
	ProviderID   uuid.UUID
	Requests     int32
	InputTokens  int64
	OutputTokens int64
}

// AIProxyTarget 转发一次请求要的东西:真地址、真 key、额度。
type AIProxyTarget struct {
	Provider AIProvider
	Key      string
}

type AI struct {
	pool  *pgxpool.Pool
	admin *Admin
	aead  cipher.AEAD
}

func NewAI(pool *pgxpool.Pool, admin *Admin, jwtSecret []byte) *AI {
	key, err := hkdf.Key(sha256.New, jwtSecret, nil, "gopan-ai-key", 32)
	if err != nil {
		panic(err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	return &AI{pool: pool, admin: admin, aead: aead}
}

func (a *AI) seal(plain string) []byte {
	nonce := make([]byte, a.aead.NonceSize())
	_, _ = rand.Read(nonce)
	return a.aead.Seal(nonce, nonce, []byte(plain), []byte("ai_providers.key"))
}

func (a *AI) open(sealed []byte) (string, error) {
	n := a.aead.NonceSize()
	if len(sealed) < n {
		return "", errAIKeyBroken
	}
	plain, err := a.aead.Open(nil, sealed[:n], sealed[n:], []byte("ai_providers.key"))
	if err != nil {
		return "", errAIKeyBroken
	}
	return string(plain), nil
}

const aiProviderCols = `id, owner_id, name, kind, base_url, models, key_enc IS NOT NULL, daily_tokens, created_at, updated_at`

func scanAIProvider(row pgx.Row) (AIProvider, error) {
	var p AIProvider
	err := row.Scan(&p.ID, &p.OwnerID, &p.Name, &p.Kind, &p.BaseURL, &p.Models, &p.HasKey, &p.DailyTokens, &p.CreatedAt, &p.UpdatedAt)
	return p, err
}

// Providers 自己的 + 站点提供的;站点的排前面。
func (a *AI) Providers(ctx context.Context, user uuid.UUID) ([]AIProvider, error) {
	rows, err := a.pool.Query(ctx, `SELECT `+aiProviderCols+` FROM ai_providers
		WHERE owner_id = $1 OR owner_id IS NULL ORDER BY owner_id NULLS FIRST, created_at`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AIProvider
	for rows.Next() {
		p, err := scanAIProvider(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// SiteProviders 管理员看的站点提供列表。
func (a *AI) SiteProviders(ctx context.Context, caller uuid.UUID) ([]AIProvider, error) {
	if err := a.admin.Require(ctx, caller); err != nil {
		return nil, err
	}
	all, err := a.Providers(ctx, caller)
	if err != nil {
		return nil, err
	}
	var out []AIProvider
	for _, p := range all {
		if p.Site() {
			out = append(out, p)
		}
	}
	return out, nil
}

func normalizeAIInput(in *AIProviderInput, site bool) error {
	in.Name = strings.TrimSpace(in.Name)
	in.BaseURL = strings.TrimRight(strings.TrimSpace(in.BaseURL), "/")
	if in.Name == "" || len([]rune(in.Name)) > 60 {
		return errf("INVALID_INPUT", "名字要 1–60 个字")
	}
	if !aiKinds[in.Kind] {
		return errf("INVALID_INPUT", "不认识的服务类型")
	}
	u, err := url.Parse(in.BaseURL)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return errf("INVALID_INPUT", "地址要是 http(s):// 开头的完整地址")
	}
	// 自己的服务经服务器转发时只走 https(连接时还会挡内网地址);站点的由管理员配,可以接本机的服务
	if !site && u.Scheme != "https" {
		return errf("INVALID_INPUT", "自己的模型服务地址要用 https")
	}
	models := make([]string, 0, len(in.Models))
	seen := map[string]bool{}
	for _, m := range in.Models {
		m = strings.TrimSpace(m)
		if m == "" || seen[m] {
			continue
		}
		if len(m) > 200 {
			return errf("INVALID_INPUT", "模型名太长")
		}
		seen[m] = true
		models = append(models, m)
	}
	if len(models) == 0 {
		return errf("INVALID_INPUT", "至少填一个模型")
	}
	if len(models) > maxAIModels {
		return errf("INVALID_INPUT", "模型最多 50 个")
	}
	in.Models = models
	if in.Key != nil {
		k := strings.TrimSpace(*in.Key)
		if len(k) > 4096 {
			return errf("INVALID_INPUT", "key 太长")
		}
		in.Key = &k
	}
	if in.DailyTokens < 0 {
		return errf("INVALID_INPUT", "每日额度不能是负数")
	}
	if !site {
		in.DailyTokens = 0
	}
	return nil
}

func (a *AI) keyArg(key *string) any {
	if key == nil || *key == "" {
		return nil
	}
	return a.seal(*key)
}

// CreateProvider site 为 true 时建站点提供的(要管理员)。
func (a *AI) CreateProvider(ctx context.Context, caller uuid.UUID, site bool, in AIProviderInput) (AIProvider, error) {
	if site {
		if err := a.admin.Require(ctx, caller); err != nil {
			return AIProvider{}, err
		}
	}
	if err := normalizeAIInput(&in, site); err != nil {
		return AIProvider{}, err
	}
	var owner *uuid.UUID
	if !site {
		owner = &caller
		var n int
		if err := a.pool.QueryRow(ctx, `SELECT count(*) FROM ai_providers WHERE owner_id = $1`, caller).Scan(&n); err != nil {
			return AIProvider{}, err
		}
		if n >= maxAIProviders {
			return AIProvider{}, errf("INVALID_INPUT", "最多 20 个模型服务")
		}
	}
	return scanAIProvider(a.pool.QueryRow(ctx, `INSERT INTO ai_providers (id, owner_id, name, kind, base_url, models, key_enc, daily_tokens)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING `+aiProviderCols,
		uuid.Must(uuid.NewV7()), owner, in.Name, in.Kind, in.BaseURL, in.Models, a.keyArg(in.Key), in.DailyTokens))
}

// editable 拿到 caller 能改的那一行:自己的,或者 caller 是管理员时站点的。
func (a *AI) editable(ctx context.Context, caller, id uuid.UUID) (AIProvider, error) {
	p, err := scanAIProvider(a.pool.QueryRow(ctx, `SELECT `+aiProviderCols+` FROM ai_providers WHERE id = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return p, ErrNotFound
	}
	if err != nil {
		return p, err
	}
	if p.Site() {
		if err := a.admin.Require(ctx, caller); err != nil {
			return p, err
		}
	} else if *p.OwnerID != caller {
		return p, ErrNotFound
	}
	return p, nil
}

func (a *AI) UpdateProvider(ctx context.Context, caller, id uuid.UUID, in AIProviderInput) (AIProvider, error) {
	cur, err := a.editable(ctx, caller, id)
	if err != nil {
		return cur, err
	}
	if err := normalizeAIInput(&in, cur.Site()); err != nil {
		return cur, err
	}
	// key 留空(nil)就是不变
	return scanAIProvider(a.pool.QueryRow(ctx, `UPDATE ai_providers SET name = $2, kind = $3, base_url = $4, models = $5,
		key_enc = CASE WHEN $6 THEN $7 ELSE key_enc END, daily_tokens = $8, updated_at = now()
		WHERE id = $1 RETURNING `+aiProviderCols,
		id, in.Name, in.Kind, in.BaseURL, in.Models, in.Key != nil, a.keyArg(in.Key), in.DailyTokens))
}

func (a *AI) DeleteProvider(ctx context.Context, caller, id uuid.UUID) error {
	if _, err := a.editable(ctx, caller, id); err != nil {
		return err
	}
	_, err := a.pool.Exec(ctx, `DELETE FROM ai_providers WHERE id = $1`, id)
	return err
}

// ProviderKey 直连用:只给自己的 key。
func (a *AI) ProviderKey(ctx context.Context, user, id uuid.UUID) (string, error) {
	var owner *uuid.UUID
	var sealed []byte
	err := a.pool.QueryRow(ctx, `SELECT owner_id, key_enc FROM ai_providers WHERE id = $1`, id).Scan(&owner, &sealed)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	if owner == nil {
		return "", errAISiteKey
	}
	if *owner != user {
		return "", ErrNotFound
	}
	if sealed == nil {
		return "", nil
	}
	return a.open(sealed)
}

// ProxyTarget 转发前:服务对这个用户可见、key 解得开、站点额度没用完。
func (a *AI) ProxyTarget(ctx context.Context, user, id uuid.UUID) (*AIProxyTarget, error) {
	var sealed []byte
	p, err := scanAIProvider(a.pool.QueryRow(ctx, `SELECT `+aiProviderCols+` FROM ai_providers
		WHERE id = $1 AND (owner_id = $2 OR owner_id IS NULL)`, id, user))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := a.pool.QueryRow(ctx, `SELECT key_enc FROM ai_providers WHERE id = $1`, id).Scan(&sealed); err != nil {
		return nil, err
	}
	t := &AIProxyTarget{Provider: p}
	if sealed != nil {
		if t.Key, err = a.open(sealed); err != nil {
			return nil, err
		}
	}
	if p.Site() && p.DailyTokens > 0 {
		var used int64
		if err := a.pool.QueryRow(ctx, `SELECT COALESCE(sum(input_tokens + output_tokens), 0) FROM ai_usage
			WHERE user_id = $1 AND provider_id = $2 AND day = current_date`, user, id).Scan(&used); err != nil {
			return nil, err
		}
		if used >= p.DailyTokens {
			return nil, errAIQuota
		}
	}
	return t, nil
}

// RecordUsage 一次转发结束后记账;请求没拿到用量时也记一次请求数。
func (a *AI) RecordUsage(ctx context.Context, user, provider uuid.UUID, input, output int64) error {
	_, err := a.pool.Exec(ctx, `INSERT INTO ai_usage (user_id, provider_id, day, requests, input_tokens, output_tokens)
		VALUES ($1, $2, current_date, 1, $3, $4)
		ON CONFLICT (user_id, provider_id, day) DO UPDATE SET requests = ai_usage.requests + 1,
			input_tokens = ai_usage.input_tokens + EXCLUDED.input_tokens,
			output_tokens = ai_usage.output_tokens + EXCLUDED.output_tokens`, user, provider, input, output)
	return err
}

// Usage 今天的用量,按服务。
func (a *AI) Usage(ctx context.Context, user uuid.UUID) ([]AIUsageItem, error) {
	rows, err := a.pool.Query(ctx, `SELECT provider_id, requests, input_tokens, output_tokens FROM ai_usage
		WHERE user_id = $1 AND day = current_date ORDER BY provider_id`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AIUsageItem
	for rows.Next() {
		var u AIUsageItem
		if err := rows.Scan(&u.ProviderID, &u.Requests, &u.InputTokens, &u.OutputTokens); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// UsageByUser 管理员看:每个用户今天经站点服务用了多少 token。
func (a *AI) UsageByUser(ctx context.Context, caller uuid.UUID) (map[uuid.UUID]int64, error) {
	if err := a.admin.Require(ctx, caller); err != nil {
		return nil, err
	}
	rows, err := a.pool.Query(ctx, `SELECT u.user_id, sum(u.input_tokens + u.output_tokens) FROM ai_usage u
		JOIN ai_providers p ON p.id = u.provider_id AND p.owner_id IS NULL
		WHERE u.day = current_date GROUP BY u.user_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID]int64{}
	for rows.Next() {
		var id uuid.UUID
		var n int64
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}
