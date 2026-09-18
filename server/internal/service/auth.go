package service

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"log/slog"
	"strings"
	"sync"
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
	// FamilyID 是当前 access token 所属的登录会话(来自 fid claim)。访客 token
	// 与此前版本签发的 token 都是零值 —— 那种情况下"本机"标记不出来,不会出错。
	FamilyID uuid.UUID
}

type Auth struct {
	identity     *IDProvider
	q            *store.Queries
	secret       []byte
	accessTTL    time.Duration
	refreshTTL   time.Duration
	registerOpen bool
	defaultQuota int64

	// 限速:每 key(IP)每分钟 10 次
	limiter *keyedLimiter

	// last_seen 的进程内节流:同一个 family 5 分钟内不再打库。
	// SQL 里还有一层 WHERE 兜底(多实例/重启后也有用),这里是省掉那次必然 no-op 的写。
	seenMu sync.Mutex
	seen   map[uuid.UUID]time.Time
}

func NewAuth(q *store.Queries, secret []byte, accessTTL, refreshTTL time.Duration, registerOpen bool, defaultQuota int64) *Auth {
	return &Auth{
		q: q, secret: secret,
		accessTTL: accessTTL, refreshTTL: refreshTTL,
		registerOpen: registerOpen, defaultQuota: defaultQuota,
		limiter: newKeyedLimiter(6*time.Second, 10),
		seen:    make(map[uuid.UUID]time.Time),
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
	// FID 是登录会话 id。omitempty + ParseAccess 忽略未知 claim,保证访客 token
	// 和升级前签发的 token 都不受影响。
	FID string `json:"fid,omitempty"`
	jwt.RegisteredClaims
}

func (a *Auth) IssueAccess(userID uuid.UUID, scope string, familyID uuid.UUID) (string, error) {
	return a.IssueAccessFor(userID, scope, familyID, a.accessTTL)
}

// IssueAccessFor 指定 TTL 签发(访客 token 用 30 分钟,与用户 access 不同)。
// familyID 写进 fid claim,让请求能定位到"这是哪个登录会话";访客 token 传 uuid.Nil。
func (a *Auth) IssueAccessFor(userID uuid.UUID, scope string, familyID uuid.UUID, ttl time.Duration) (string, error) {
	now := time.Now()
	c := claims{Scope: scope}
	if familyID != uuid.Nil {
		c.FID = familyID.String()
	}
	c.Subject = userID.String()
	c.IssuedAt = jwt.NewNumericDate(now)
	c.ExpiresAt = jwt.NewNumericDate(now.Add(ttl))
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, c)
	return t.SignedString(a.secret)
}

func (a *Auth) ParseAccess(token string) (*Identity, error) {
	return a.ParseAccessContext(context.Background(), token)
}

func (a *Auth) ParseAccessContext(ctx context.Context, token string) (*Identity, error) {
	id, err := a.parseAccess(token)
	if err != nil {
		return nil, err
	}
	if id.Scope == ScopeUser {
		if err = a.checkIdentity(ctx, id.UserID, id.FamilyID); err != nil {
			return nil, err
		}
	}
	return id, nil
}

func (a *Auth) checkIdentity(ctx context.Context, user, family uuid.UUID) error {
	if a.identity != nil && user == a.identity.Config.UserID {
		return a.identity.Check(ctx, user, family)
	}
	if a.q != nil && family != uuid.Nil {
		linked, err := a.q.HasIdentitySession(ctx, family)
		if err != nil {
			return err
		}
		if linked {
			return ErrUnauthenticated
		}
	}
	return nil
}

func (a *Auth) parseAccess(token string) (*Identity, error) {
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
	var fid uuid.UUID
	if c.FID != "" {
		if parsed, err := uuid.Parse(c.FID); err == nil {
			fid = parsed
		}
	}
	return &Identity{UserID: uid, Scope: c.Scope, FamilyID: fid}, nil
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
	return a.issuePair(ctx, u, ip)
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
	if a.identity != nil && u.ID == a.identity.Config.UserID {
		return nil, errf("ID_REQUIRED", "请使用 Yophon ID 登录")
	}
	if bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil {
		return nil, errf("BAD_CREDENTIALS", "用户名或密码错误")
	}
	if u.DisabledAt.Valid {
		return nil, errf("ACCOUNT_DISABLED", "账号已被禁用")
	}
	return a.issuePair(ctx, u, ip)
}

func (a *Auth) issuePair(ctx context.Context, u store.User, ip string) (*AuthResult, error) {
	// 先定 family 再签 access:access 里的 fid 必须指向这个新会话。
	familyID := uuid.Must(uuid.NewV7())
	access, err := a.IssueAccess(u.ID, ScopeUser, familyID)
	if err != nil {
		return nil, err
	}
	plain, err := a.newRefresh(ctx, u.ID, familyID)
	if err != nil {
		return nil, err
	}
	a.createSession(ctx, familyID, u.ID, ip)
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
	if err := a.checkIdentity(ctx, rt.UserID, rt.FamilyID); err != nil {
		return nil, err
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
	access, err := a.IssueAccess(u.ID, ScopeUser, rt.FamilyID)
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
	if a.identity != nil && userID == a.identity.Config.UserID {
		return nil, errf("ID_REQUIRED", "请在 Yophon ID 修改密码")
	}
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
	// 设备列表也要标失效,否则页面上还显示着"在线",实际 token 已经不能用了。
	if err := a.q.RevokeAllSessions(ctx, userID); err != nil {
		return nil, err
	}
	return a.issuePair(ctx, u, ip)
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

// ---- 登录会话(设备管理)----

// sessionMaxUA 截断 UA。它是客户端完全可控的字符串,不该让它把行撑爆。
const sessionMaxUA = 512

// createSession 记一条登录设备。**尽力而为**:设备列表少一行,好过用户登不进来。
func (a *Auth) createSession(ctx context.Context, familyID, userID uuid.UUID, ip string) {
	ua := ClientMetaFrom(ctx).UA
	if len(ua) > sessionMaxUA {
		ua = ua[:sessionMaxUA]
	}
	if err := a.q.CreateSession(ctx, store.CreateSessionParams{
		FamilyID: familyID, UserID: userID, UserAgent: ua, Ip: ip,
	}); err != nil {
		slog.Warn("create session", "err", err)
	}
}

// AuthSession 是设备列表的一行。
type AuthSession struct {
	FamilyID   uuid.UUID
	UserAgent  string
	IP         string
	CreatedAt  time.Time
	LastSeenAt time.Time
	RevokedAt  *time.Time
	// Active 表示这一族的 refresh token 还能用(没被吊销、没过期)。
	Active bool
	// Current 是当前请求所在的会话(来自 access token 的 fid claim)。
	Current bool
}

func (a *Auth) ListSessions(ctx context.Context, userID, currentFamily uuid.UUID) ([]AuthSession, error) {
	rows, err := a.q.ListSessions(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := make([]AuthSession, 0, len(rows))
	for _, r := range rows {
		v := AuthSession{
			FamilyID: r.FamilyID, UserAgent: r.UserAgent, IP: r.Ip,
			CreatedAt: r.CreatedAt.Time, LastSeenAt: r.LastSeenAt.Time,
			Active: r.Active, Current: r.FamilyID == currentFamily,
		}
		if r.RevokedAt.Valid {
			t := r.RevokedAt.Time
			v.RevokedAt = &t
		}
		out = append(out, v)
	}
	return out, nil
}

// RevokeSession 吊销一个会话:标记 sessions 行 + 吊销整族 refresh token。
// 两件事必须一起做 —— 只标记前者的话,那个设备的 token 还能继续用。
//
// 拒绝吊销当前会话:那是「退出登录」该走的路,分开两条路径才不会被误操作把自己踢下线。
func (a *Auth) RevokeSession(ctx context.Context, userID, currentFamily, familyID uuid.UUID) error {
	if familyID == currentFamily {
		return errf("CANNOT_REVOKE_CURRENT", "当前设备请用「退出登录」")
	}
	n, err := a.q.RevokeSession(ctx, store.RevokeSessionParams{
		FamilyID: familyID, UserID: userID,
	})
	if err != nil {
		return err
	}
	if n == 0 {
		// 不存在 / 已吊销 / 不属于这个用户 —— 一律 NotFound,不泄露存在性
		return ErrNotFound
	}
	return a.q.RevokeRefreshFamily(ctx, familyID)
}

// TouchSession 推进 last_seen。两层节流:进程内 map 先挡掉绝大多数调用,
// SQL 的 WHERE 再兜一层(重启或多实例时). 尽力而为,失败只记日志。
func (a *Auth) TouchSession(ctx context.Context, familyID uuid.UUID) {
	if familyID == uuid.Nil {
		return
	}
	now := time.Now()
	a.seenMu.Lock()
	if last, ok := a.seen[familyID]; ok && now.Sub(last) < 5*time.Minute {
		a.seenMu.Unlock()
		return
	}
	a.seen[familyID] = now
	// 容量保护:顺手清掉一小时前的条目,免得 map 随登录次数无限长
	if len(a.seen) > 4096 {
		for k, t := range a.seen {
			if now.Sub(t) > time.Hour {
				delete(a.seen, k)
			}
		}
	}
	a.seenMu.Unlock()

	if err := a.q.TouchSession(ctx, familyID); err != nil {
		slog.Warn("touch session", "err", err)
	}
}

func hashToken(plain string) string {
	h := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(h[:])
}
