package service

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"

	"github.com/yophon/gopan/server/internal/store"
)

const ScopeUser = "user"

type Identity struct {
	UserID uuid.UUID
	Scope  string // "user" 或 "share:{shareId}"
}

type Auth struct {
	q            *store.Queries
	secret       []byte
	accessTTL    time.Duration
	refreshTTL   time.Duration
	registerOpen bool
	defaultQuota int64

	// 限速:每 key(IP)每分钟 10 次
	limiter *keyedLimiter
}

func NewAuth(q *store.Queries, secret []byte, accessTTL, refreshTTL time.Duration, registerOpen bool, defaultQuota int64) *Auth {
	return &Auth{
		q: q, secret: secret,
		accessTTL: accessTTL, refreshTTL: refreshTTL,
		registerOpen: registerOpen, defaultQuota: defaultQuota,
		limiter: newKeyedLimiter(6*time.Second, 10),
	}
}

// SetRateLimit 调整登录/注册限速(每 interval 回填一个令牌,突发上限 burst),测试注入用。
func (a *Auth) SetRateLimit(interval time.Duration, burst int) {
	a.limiter.SetRate(interval, burst)
}

func (a *Auth) allow(key string) bool {
	return a.limiter.Allow(key)
}

// ---- access token(JWT)----

type claims struct {
	Scope string `json:"scope"`
	jwt.RegisteredClaims
}

func (a *Auth) IssueAccess(userID uuid.UUID, scope string) (string, error) {
	return a.IssueAccessFor(userID, scope, a.accessTTL)
}

// IssueAccessFor 指定 TTL 签发(访客 token 用 30 分钟,与用户 access 不同)。
func (a *Auth) IssueAccessFor(userID uuid.UUID, scope string, ttl time.Duration) (string, error) {
	now := time.Now()
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims{
		Scope: scope,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID.String(),
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
		},
	})
	return t.SignedString(a.secret)
}

func (a *Auth) ParseAccess(token string) (*Identity, error) {
	var c claims
	_, err := jwt.ParseWithClaims(token, &c, func(t *jwt.Token) (any, error) {
		if t.Method != jwt.SigningMethodHS256 {
			return nil, errors.New("unexpected signing method")
		}
		return a.secret, nil
	})
	if err != nil {
		return nil, ErrUnauthenticated
	}
	uid, err := uuid.Parse(c.Subject)
	if err != nil {
		return nil, ErrUnauthenticated
	}
	return &Identity{UserID: uid, Scope: c.Scope}, nil
}

// ---- 注册 / 登录 ----

type AuthResult struct {
	User         store.User
	AccessToken  string
	RefreshToken string // 明文,仅此一次出现,由 HTTP 层写进 cookie
}

func (a *Auth) Register(ctx context.Context, username, password, ip string) (*AuthResult, error) {
	if !a.registerOpen {
		return nil, errf("REGISTER_CLOSED", "注册已关闭")
	}
	if !a.allow("reg:" + ip) {
		return nil, ErrRateLimited
	}
	username = strings.TrimSpace(username)
	if len(username) < 2 || len(username) > 32 {
		return nil, errf("INVALID_INPUT", "用户名长度需在 2~32 之间")
	}
	if len(password) < 8 || len(password) > 72 { // bcrypt 上限 72 字节
		return nil, errf("INVALID_INPUT", "密码长度需在 8~72 之间")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return nil, err
	}
	u, err := a.q.CreateUser(ctx, store.CreateUserParams{
		ID: uuid.Must(uuid.NewV7()), Username: username,
		PasswordHash: string(hash), QuotaBytes: a.defaultQuota,
	})
	if err != nil {
		if isUniqueViolation(err) {
			return nil, errf("USERNAME_TAKEN", "用户名已被占用")
		}
		return nil, err
	}
	return a.issuePair(ctx, u)
}

func (a *Auth) Login(ctx context.Context, username, password, ip string) (*AuthResult, error) {
	if !a.allow("login:" + ip) {
		return nil, ErrRateLimited
	}
	u, err := a.q.GetUserByUsername(ctx, strings.TrimSpace(username))
	if err != nil {
		// 用户不存在也走一次 bcrypt,防时间侧信道探测用户名
		_ = bcrypt.CompareHashAndPassword(
			[]byte("$2a$12$C6UzMDM.H6dfI/f/IKcEeO7ccuNM97xf1nRZDqCVYyk1uUkFTB0P6"), []byte(password))
		return nil, errf("BAD_CREDENTIALS", "用户名或密码错误")
	}
	if bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil {
		return nil, errf("BAD_CREDENTIALS", "用户名或密码错误")
	}
	if u.DisabledAt.Valid {
		return nil, errf("ACCOUNT_DISABLED", "账号已被禁用")
	}
	return a.issuePair(ctx, u)
}

func (a *Auth) issuePair(ctx context.Context, u store.User) (*AuthResult, error) {
	access, err := a.IssueAccess(u.ID, ScopeUser)
	if err != nil {
		return nil, err
	}
	plain, err := a.newRefresh(ctx, u.ID, uuid.Must(uuid.NewV7()))
	if err != nil {
		return nil, err
	}
	return &AuthResult{User: u, AccessToken: access, RefreshToken: plain}, nil
}

// ---- refresh:旋转 + 重用检测 ----

func (a *Auth) newRefresh(ctx context.Context, userID, familyID uuid.UUID) (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	plain := base64.RawURLEncoding.EncodeToString(raw)
	_, err := a.q.CreateRefreshToken(ctx, store.CreateRefreshTokenParams{
		ID: uuid.Must(uuid.NewV7()), UserID: userID,
		TokenHash: hashToken(plain), FamilyID: familyID,
		ExpiresAt: tstz(time.Now().Add(a.refreshTTL)),
	})
	if err != nil {
		return "", err
	}
	return plain, nil
}

func (a *Auth) Refresh(ctx context.Context, plain string) (*AuthResult, error) {
	if plain == "" {
		return nil, ErrUnauthenticated
	}
	rt, err := a.q.GetRefreshTokenByHash(ctx, hashToken(plain))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrUnauthenticated
		}
		return nil, err
	}
	switch {
	case rt.RevokedAt.Valid, rt.ExpiresAt.Time.Before(time.Now()):
		return nil, ErrUnauthenticated
	case rt.UsedAt.Valid:
		// 已旋转过的 token 再次出现 = 泄露,吊销整族
		_ = a.q.RevokeRefreshFamily(ctx, rt.FamilyID)
		return nil, ErrUnauthenticated
	}
	if err := a.q.MarkRefreshTokenUsed(ctx, rt.ID); err != nil {
		return nil, err
	}
	u, err := a.q.GetUserByID(ctx, rt.UserID)
	if err != nil {
		return nil, err
	}
	if u.DisabledAt.Valid {
		return nil, ErrUnauthenticated
	}
	access, err := a.IssueAccess(u.ID, ScopeUser)
	if err != nil {
		return nil, err
	}
	next, err := a.newRefresh(ctx, u.ID, rt.FamilyID)
	if err != nil {
		return nil, err
	}
	return &AuthResult{User: u, AccessToken: access, RefreshToken: next}, nil
}

func (a *Auth) Logout(ctx context.Context, plain string) error {
	if plain == "" {
		return nil
	}
	rt, err := a.q.GetRefreshTokenByHash(ctx, hashToken(plain))
	if err != nil {
		return nil // 找不到就当已登出
	}
	return a.q.RevokeRefreshFamily(ctx, rt.FamilyID)
}

// ChangePassword 验旧密码后换新,吊销全部 refresh family(其它设备下线),
// 当场重新签发一对 token 让当前会话无感续命。
func (a *Auth) ChangePassword(ctx context.Context, userID uuid.UUID, oldPw, newPw, ip string) (*AuthResult, error) {
	if !a.allow("chpw:" + ip) {
		return nil, ErrRateLimited
	}
	u, err := a.q.GetUserByID(ctx, userID)
	if err != nil {
		return nil, ErrUnauthenticated
	}
	if bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(oldPw)) != nil {
		return nil, errf("BAD_CREDENTIALS", "旧密码错误")
	}
	if len(newPw) < 8 || len(newPw) > 72 {
		return nil, errf("INVALID_INPUT", "密码长度需在 8~72 之间")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(newPw), 12)
	if err != nil {
		return nil, err
	}
	if err := a.q.UpdateUserPassword(ctx, store.UpdateUserPasswordParams{ID: userID, PasswordHash: string(hash)}); err != nil {
		return nil, err
	}
	if err := a.q.RevokeAllUserFamilies(ctx, userID); err != nil {
		return nil, err
	}
	return a.issuePair(ctx, u)
}

func (a *Auth) GetUser(ctx context.Context, id uuid.UUID) (store.User, error) {
	u, err := a.q.GetUserByID(ctx, id)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.User{}, ErrUnauthenticated
		}
		return store.User{}, err
	}
	return u, nil
}

func hashToken(plain string) string {
	h := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(h[:])
}
