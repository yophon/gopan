package store

import (
	"context"

	"github.com/google/uuid"
)

// FamilyActive 判断一个登录会话(refresh token 族)还能不能用:族里还有没吊销、没过期的 refresh token,
// 账号也没被停用。移除设备、改密码、重放检测、管理员停用都会吊销整族,这里随即变成 false。
// 手写而不走 sqlc:和 identity.go 一样,只是一句 EXISTS。
func (q *Queries) FamilyActive(ctx context.Context, family, user uuid.UUID) (bool, error) {
	var active bool
	err := q.db.QueryRow(ctx, `SELECT EXISTS(
		SELECT 1 FROM refresh_tokens r JOIN users u ON u.id = r.user_id
		WHERE r.family_id = $1 AND r.user_id = $2
		  AND r.revoked_at IS NULL AND r.expires_at > now() AND u.disabled_at IS NULL)`, family, user).Scan(&active)
	return active, err
}
