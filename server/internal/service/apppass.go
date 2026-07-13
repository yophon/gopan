package service

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/store"
)

// AppPasswords WebDAV 应用密码:每设备一条、明文只出现一次、可单独吊销。
// 高熵随机 token,认证走 sha256 精确查表(与 refresh token 同构),不需要 bcrypt。
type AppPasswords struct {
	q       *store.Queries
	limiter *keyedLimiter // 认证失败按 IP 限速,防爆破
}

func NewAppPasswords(q *store.Queries) *AppPasswords {
	return &AppPasswords{q: q, limiter: newKeyedLimiter(6*time.Second, 10)}
}

// SetRateLimit 测试注入用。
func (a *AppPasswords) SetRateLimit(interval time.Duration, burst int) {
	a.limiter.SetRate(interval, burst)
}

const maxAppPasswords = 20

// Create 生成新应用密码,返回明文(仅此一次)。
func (a *AppPasswords) Create(ctx context.Context, userID uuid.UUID, name string) (string, store.AppPassword, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 64 {
		return "", store.AppPassword{}, errf("INVALID_INPUT", "名称不能为空且不超过 64 字节")
	}
	existing, err := a.q.ListAppPasswords(ctx, userID)
	if err != nil {
		return "", store.AppPassword{}, err
	}
	if len(existing) >= maxAppPasswords {
		return "", store.AppPassword{}, errf("INVALID_INPUT", "应用密码最多 %d 条,请先吊销不用的", maxAppPasswords)
	}
	raw := make([]byte, 18)
	if _, err := rand.Read(raw); err != nil {
		return "", store.AppPassword{}, err
	}
	plain := "gopan_" + base64.RawURLEncoding.EncodeToString(raw) // 前缀便于识别与泄露扫描
	row, err := a.q.CreateAppPassword(ctx, store.CreateAppPasswordParams{
		ID: uuid.Must(uuid.NewV7()), UserID: userID, Name: name, TokenHash: hashToken(plain),
	})
	if err != nil {
		return "", store.AppPassword{}, err
	}
	return plain, row, nil
}

func (a *AppPasswords) List(ctx context.Context, userID uuid.UUID) ([]store.AppPassword, error) {
	return a.q.ListAppPasswords(ctx, userID)
}

func (a *AppPasswords) Revoke(ctx context.Context, userID, id uuid.UUID) error {
	n, err := a.q.DeleteAppPassword(ctx, store.DeleteAppPasswordParams{ID: id, UserID: userID})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// Authenticate 校验 Basic 凭据。username 必须与密码属主一致(防拿别人的密码配自己的名);
// 属主被禁用一律拒。主账号密码天然进不来——它不在这张表里。
// 限速只记失败:WebDAV 客户端每个请求都带 Basic 重新认证,rclone 一秒几十个请求
// 是正常水位,按"每次认证"扣令牌会把合法客户端打成 429;要防的是爆破,即失败尝试。
func (a *AppPasswords) Authenticate(ctx context.Context, username, password, ip string) (uuid.UUID, error) {
	fail := func() (uuid.UUID, error) {
		if !a.limiter.Allow("dav:" + ip) {
			return uuid.Nil, ErrRateLimited
		}
		return uuid.Nil, ErrUnauthenticated
	}
	row, err := a.q.GetAppPasswordByHash(ctx, hashToken(password))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return fail()
		}
		return uuid.Nil, err
	}
	if row.Username != username || row.OwnerDisabled {
		return fail()
	}
	_ = a.q.TouchAppPassword(ctx, row.ID) // 尽力而为,失败不影响认证
	return row.UserID, nil
}
