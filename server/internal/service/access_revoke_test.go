package service_test

import (
	"context"
	"errors"
	"testing"

	"github.com/yophon/gopan/server/internal/service"
)

// access token 有效期长(默认 4 小时),吊销不能等它过期:移除设备之后,那台设备手上的 token 当场作废,
// 本进程(缓存已清)与别的进程(没有缓存)都一样;没被吊销的会话照常。
func TestRevokedDeviceTokenStopsWorkingAtOnce(t *testing.T) {
	pool := setup(t)
	auth := newAuth(pool)
	ctx := context.Background()

	reg, err := auth.Register(ctx, "revokeuser", "password123", "203.0.113.7")
	if err != nil {
		t.Fatal(err)
	}
	phone := loginWithUA(t, auth, "revokeuser", "phone-UA")
	desk, err := auth.ParseAccessContext(ctx, reg.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	ph, err := auth.ParseAccessContext(ctx, phone.AccessToken)
	if err != nil {
		t.Fatal(err)
	}

	if err := auth.RevokeSession(ctx, reg.User.ID, desk.FamilyID, ph.FamilyID); err != nil {
		t.Fatal(err)
	}
	if _, err := auth.ParseAccessContext(ctx, phone.AccessToken); !errors.Is(err, service.ErrUnauthenticated) {
		t.Fatalf("移除的设备的 token 应立即作废,得到 %v", err)
	}
	if _, err := newAuth(pool).ParseAccessContext(ctx, phone.AccessToken); !errors.Is(err, service.ErrUnauthenticated) {
		t.Fatalf("别的进程也应拒绝,得到 %v", err)
	}
	if _, err := auth.ParseAccessContext(ctx, reg.AccessToken); err != nil {
		t.Fatalf("没被移除的会话应照常可用: %v", err)
	}
}

// 改密码踢掉其它设备、管理员停用账号:旧 token 同样当场作废。
func TestPasswordChangeAndDisableInvalidateOldTokens(t *testing.T) {
	pool := setup(t)
	auth := newAuth(pool)
	ctx := context.Background()

	reg, err := auth.Register(ctx, "pwchangeuser", "password123", "203.0.113.7")
	if err != nil {
		t.Fatal(err)
	}
	other := loginWithUA(t, auth, "pwchangeuser", "other-UA")
	if _, err := auth.ParseAccessContext(ctx, other.AccessToken); err != nil {
		t.Fatal(err)
	}

	fresh, err := auth.ChangePassword(ctx, reg.User.ID, "password123", "newpassword123", "203.0.113.7")
	if err != nil {
		t.Fatal(err)
	}
	for _, tok := range []string{reg.AccessToken, other.AccessToken} {
		if _, err := auth.ParseAccessContext(ctx, tok); !errors.Is(err, service.ErrUnauthenticated) {
			t.Fatalf("改密码前签发的 token 应作废,得到 %v", err)
		}
	}
	if _, err := auth.ParseAccessContext(ctx, fresh.AccessToken); err != nil {
		t.Fatalf("改密码后重发的 token 应可用: %v", err)
	}

	// 停用账号(只改 disabled_at,不吊销 refresh token,也要挡住)
	if _, err := pool.Exec(ctx, "UPDATE users SET disabled_at = now() WHERE id = $1", reg.User.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := newAuth(pool).ParseAccessContext(ctx, fresh.AccessToken); !errors.Is(err, service.ErrUnauthenticated) {
		t.Fatalf("停用账号后 token 应作废,得到 %v", err)
	}
}
