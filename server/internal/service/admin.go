package service

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"strings"

	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"

	"github.com/yophon/gopan/server/internal/store"
)

// Admin 管理端操作。每个方法第一步 require:调用者必须是未禁用的 admin,
// 否则一律 FORBIDDEN——不区分"不是 admin"和"目标不存在",不暴露信息。
type Admin struct {
	q            *store.Queries
	defaultQuota int64
}

func NewAdmin(q *store.Queries, defaultQuota int64) *Admin {
	return &Admin{q: q, defaultQuota: defaultQuota}
}

func (a *Admin) require(ctx context.Context, callerID uuid.UUID) error {
	u, err := a.q.GetUserByID(ctx, callerID)
	if err != nil || !u.IsAdmin || u.DisabledAt.Valid {
		return ErrForbidden
	}
	return nil
}

func (a *Admin) ListUsers(ctx context.Context, callerID uuid.UUID) ([]store.User, error) {
	if err := a.require(ctx, callerID); err != nil {
		return nil, err
	}
	return a.q.AdminListUsers(ctx)
}

// CreateUser 管理员建号,不受注册开关限制。quota 为 nil 时用实例默认配额。
func (a *Admin) CreateUser(ctx context.Context, callerID uuid.UUID, username, password string, quota *int64) (store.User, error) {
	if err := a.require(ctx, callerID); err != nil {
		return store.User{}, err
	}
	username = strings.TrimSpace(username)
	if len(username) < 2 || len(username) > 32 {
		return store.User{}, errf("INVALID_INPUT", "用户名长度需在 2~32 之间")
	}
	if len(password) < 8 || len(password) > 72 {
		return store.User{}, errf("INVALID_INPUT", "密码长度需在 8~72 之间")
	}
	q := a.defaultQuota
	if quota != nil {
		if *quota < 0 {
			return store.User{}, errf("INVALID_INPUT", "配额不能为负")
		}
		q = *quota
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return store.User{}, err
	}
	u, err := a.q.CreateUser(ctx, store.CreateUserParams{
		ID: uuid.Must(uuid.NewV7()), Username: username,
		PasswordHash: string(hash), QuotaBytes: q,
	})
	if err != nil {
		if isUniqueViolation(err) {
			return store.User{}, errf("USERNAME_TAKEN", "用户名已被占用")
		}
		return store.User{}, err
	}
	return u, nil
}

func (a *Admin) SetQuota(ctx context.Context, callerID, target uuid.UUID, quota int64) (store.User, error) {
	if err := a.require(ctx, callerID); err != nil {
		return store.User{}, err
	}
	if quota < 0 {
		return store.User{}, errf("INVALID_INPUT", "配额不能为负")
	}
	u, err := a.q.AdminSetUserQuota(ctx, store.AdminSetUserQuotaParams{ID: target, QuotaBytes: quota})
	if err != nil {
		return store.User{}, ErrNotFound
	}
	return u, nil
}

// SetDisabled 禁用即吊销全部会话;分享连带失效在 share 校验层按 disabled_at 判断。
// 不允许禁用自己:单管理员实例一旦自锁,只剩上服务器改库一条路。
func (a *Admin) SetDisabled(ctx context.Context, callerID, target uuid.UUID, disabled bool) (store.User, error) {
	if err := a.require(ctx, callerID); err != nil {
		return store.User{}, err
	}
	if disabled && callerID == target {
		return store.User{}, errf("INVALID_INPUT", "不能禁用自己")
	}
	u, err := a.q.AdminSetUserDisabled(ctx, store.AdminSetUserDisabledParams{ID: target, Disabled: disabled})
	if err != nil {
		return store.User{}, ErrNotFound
	}
	if disabled {
		if err := a.q.RevokeAllUserFamilies(ctx, target); err != nil {
			return store.User{}, err
		}
	}
	return u, nil
}

// ResetPassword 生成随机新密码,明文仅此一次返回;吊销该用户全部会话。
func (a *Admin) ResetPassword(ctx context.Context, callerID, target uuid.UUID) (string, error) {
	if err := a.require(ctx, callerID); err != nil {
		return "", err
	}
	raw := make([]byte, 12)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	plain := base64.RawURLEncoding.EncodeToString(raw) // 16 字符
	hash, err := bcrypt.GenerateFromPassword([]byte(plain), 12)
	if err != nil {
		return "", err
	}
	if err := a.q.UpdateUserPassword(ctx, store.UpdateUserPasswordParams{ID: target, PasswordHash: string(hash)}); err != nil {
		return "", err
	}
	if err := a.q.RevokeAllUserFamilies(ctx, target); err != nil {
		return "", err
	}
	return plain, nil
}

type OverviewView struct {
	UserCount      int64
	TotalUsedBytes int64
	BlobCount      int64
	BlobBytes      int64
	TaskCounts     []store.CountTasksByStatusRow
}

func (a *Admin) Overview(ctx context.Context, callerID uuid.UUID) (*OverviewView, error) {
	if err := a.require(ctx, callerID); err != nil {
		return nil, err
	}
	us, err := a.q.AdminOverviewUsers(ctx)
	if err != nil {
		return nil, err
	}
	bs, err := a.q.AdminOverviewBlobs(ctx)
	if err != nil {
		return nil, err
	}
	ts, err := a.q.CountTasksByStatus(ctx)
	if err != nil {
		return nil, err
	}
	return &OverviewView{
		UserCount: us.UserCount, TotalUsedBytes: us.TotalUsed,
		BlobCount: bs.BlobCount, BlobBytes: bs.BlobBytes,
		TaskCounts: ts,
	}, nil
}

// RetryFailedTasks 失败任务清零重排。worker 有 30 秒兜底轮询,无需显式唤醒。
func (a *Admin) RetryFailedTasks(ctx context.Context, callerID uuid.UUID) (int64, error) {
	if err := a.require(ctx, callerID); err != nil {
		return 0, err
	}
	return a.q.RetryFailedTasks(ctx)
}
