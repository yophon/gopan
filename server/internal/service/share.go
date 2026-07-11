package service

import (
	"context"
	"crypto/rand"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"golang.org/x/crypto/bcrypt"

	"github.com/yophon/gopan/server/internal/store"
)

// base58:去掉 0 O I l,10 位 ≈ 4×10^17,防枚举足够
const shareAlphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
const shareTokenLen = 10

// GuestTTL 访客 token 有效期。无 refresh,过期让访客重验。
const GuestTTL = 30 * time.Minute

// ShareScopeID 从 "share:{id}" 里解出分享 ID。
func ShareScopeID(scope string) (uuid.UUID, bool) {
	raw, ok := strings.CutPrefix(scope, "share:")
	if !ok {
		return uuid.Nil, false
	}
	id, err := uuid.Parse(raw)
	if err != nil {
		return uuid.Nil, false
	}
	return id, true
}

type Shares struct {
	q       *store.Queries
	auth    *Auth
	limiter *keyedLimiter // 验密按 IP 限速
}

func NewShares(q *store.Queries, auth *Auth) *Shares {
	return &Shares{q: q, auth: auth, limiter: newKeyedLimiter(6*time.Second, 10)}
}

// SetRateLimit 调整验密限速,测试注入用。
func (s *Shares) SetRateLimit(interval time.Duration, burst int) {
	s.limiter.SetRate(interval, burst)
}

func (s *Shares) allow(key string) bool {
	return s.limiter.Allow(key)
}

// ---- 属主操作 ----

func (s *Shares) Create(ctx context.Context, owner, nodeID uuid.UUID, password *string, expiresAt *time.Time) (store.Share, error) {
	if _, err := s.q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: nodeID, OwnerID: owner}); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.Share{}, ErrNotFound
		}
		return store.Share{}, err
	}
	if expiresAt != nil && !expiresAt.After(time.Now()) {
		return store.Share{}, errf("INVALID_INPUT", "有效期必须是将来的时间")
	}
	var pwHash *string
	if password != nil && *password != "" {
		if len(*password) > 72 {
			return store.Share{}, errf("INVALID_INPUT", "分享密码过长")
		}
		h, err := bcrypt.GenerateFromPassword([]byte(*password), 12)
		if err != nil {
			return store.Share{}, err
		}
		hs := string(h)
		pwHash = &hs
	}
	var exp pgtype.Timestamptz
	if expiresAt != nil {
		exp = tstz(*expiresAt)
	}
	// token 唯一冲突概率极小,兜底重试几次
	for range 3 {
		token, err := shareToken()
		if err != nil {
			return store.Share{}, err
		}
		sh, err := s.q.CreateShare(ctx, store.CreateShareParams{
			ID: uuid.Must(uuid.NewV7()), Token: token, NodeID: nodeID,
			CreatedBy: owner, PasswordHash: pwHash, ExpiresAt: exp,
		})
		if isUniqueViolation(err) {
			continue
		}
		return sh, err
	}
	return store.Share{}, errf("INTERNAL", "生成分享 token 失败")
}

func (s *Shares) Revoke(ctx context.Context, owner, id uuid.UUID) error {
	n, err := s.q.RevokeShare(ctx, store.RevokeShareParams{ID: id, CreatedBy: owner})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Shares) List(ctx context.Context, owner uuid.UUID) ([]store.ListMySharesRow, error) {
	return s.q.ListMyShares(ctx, owner)
}

// ---- 公开查询 / 访客 ----

type ShareInfoView struct {
	Token        string
	Name         string
	Kind         string
	NeedPassword bool
	Expired      bool
}

func (s *Shares) Info(ctx context.Context, token string) (*ShareInfoView, error) {
	sh, err := s.q.GetShareByToken(ctx, token)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &ShareInfoView{
		Token:        sh.Token,
		Name:         sh.NodeName,
		Kind:         sh.NodeKind,
		NeedPassword: sh.PasswordHash != nil,
		Expired:      shareDead(sh.RevokedAt.Valid, sh.ExpiresAt, sh.NodeDeletedAt.Valid),
	}, nil
}

// Access 验密并签发访客 token(无密码分享传空密码即可)。
// 访客 JWT 的 sub 是分享属主,scope=share:{id}:后续查询天然复用属主视角,
// 权限收窄靠子树校验。
func (s *Shares) Access(ctx context.Context, token, password, ip string) (string, error) {
	if !s.allow("share:" + ip) {
		return "", ErrRateLimited
	}
	sh, err := s.q.GetShareByToken(ctx, token)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", ErrNotFound
		}
		return "", err
	}
	if shareDead(sh.RevokedAt.Valid, sh.ExpiresAt, sh.NodeDeletedAt.Valid) {
		return "", errShareExpired
	}
	if sh.PasswordHash != nil {
		if password == "" {
			return "", errf("SHARE_PASSWORD_REQUIRED", "该分享需要密码")
		}
		if bcrypt.CompareHashAndPassword([]byte(*sh.PasswordHash), []byte(password)) != nil {
			return "", errf("BAD_SHARE_PASSWORD", "分享密码错误")
		}
	}
	return s.auth.IssueAccessFor(sh.CreatedBy, "share:"+sh.ID.String(), GuestTTL)
}

// Validate 校验访客 token 指向的分享仍有效,返回分享行。
func (s *Shares) Validate(ctx context.Context, shareID uuid.UUID) (store.GetShareByIDRow, error) {
	sh, err := s.q.GetShareByID(ctx, shareID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.GetShareByIDRow{}, errShareExpired
		}
		return store.GetShareByIDRow{}, err
	}
	if shareDead(sh.RevokedAt.Valid, sh.ExpiresAt, sh.NodeDeletedAt.Valid) {
		return store.GetShareByIDRow{}, errShareExpired
	}
	return sh, nil
}

// ValidateScope 从身份里解出分享并校验(必须是 share scope)。
func (s *Shares) ValidateScope(ctx context.Context, ident *Identity) (store.GetShareByIDRow, error) {
	shareID, ok := ShareScopeID(ident.Scope)
	if !ok {
		return store.GetShareByIDRow{}, ErrForbidden
	}
	sh, err := s.Validate(ctx, shareID)
	if err != nil {
		return store.GetShareByIDRow{}, err
	}
	if sh.CreatedBy != ident.UserID {
		return store.GetShareByIDRow{}, ErrForbidden
	}
	return sh, nil
}

// Authorize 统一鉴权入口:user scope 校验属主,share scope 校验分享有效 + 目标在子树内。
// 目标必须是未删除节点。
func (s *Shares) Authorize(ctx context.Context, ident *Identity, nodeID uuid.UUID) error {
	if ident.Scope == ScopeUser {
		if _, err := s.q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: nodeID, OwnerID: ident.UserID}); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		return nil
	}
	sh, err := s.ValidateScope(ctx, ident)
	if err != nil {
		return err
	}
	if _, err := s.q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: nodeID, OwnerID: ident.UserID}); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	in, err := s.q.IsDescendant(ctx, store.IsDescendantParams{ID: sh.NodeID, ID_2: nodeID})
	if err != nil {
		return err
	}
	if !in {
		return ErrNotFound // 不暴露"存在但无权",与属主语义一致
	}
	return nil
}

var errShareExpired = &Error{Code: "SHARE_EXPIRED", Message: "分享已失效"}

func shareDead(revoked bool, expiresAt pgtype.Timestamptz, nodeDeleted bool) bool {
	if revoked || nodeDeleted {
		return true
	}
	return expiresAt.Valid && expiresAt.Time.Before(time.Now())
}

func shareToken() (string, error) {
	raw := make([]byte, shareTokenLen)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	out := make([]byte, shareTokenLen)
	for i, b := range raw {
		out[i] = shareAlphabet[int(b)%len(shareAlphabet)]
	}
	return string(out), nil
}
