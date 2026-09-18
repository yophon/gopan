package service_test

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func loginWithUA(t *testing.T, auth *service.Auth, username, ua string) *service.AuthResult {
	t.Helper()
	ctx := service.WithClientMeta(context.Background(), service.ClientMeta{UA: ua})
	res, err := auth.Login(ctx, username, "password123", "203.0.113.7")
	if err != nil {
		t.Fatalf("登录失败: %v", err)
	}
	return res
}

func TestSessionsLifecycle(t *testing.T) {
	pool := setup(t)
	auth := newAuth(pool)
	ctx := context.Background()

	// 注册也算一次登录(桌面 UA)
	regCtx := service.WithClientMeta(ctx, service.ClientMeta{UA: "desktop-UA"})
	reg, err := auth.Register(regCtx, "sessuser", "password123", "203.0.113.7")
	if err != nil {
		t.Fatal(err)
	}
	// 再登录一次(手机 UA)
	phone := loginWithUA(t, auth, "sessuser", "phone-UA")

	ident, err := auth.ParseAccess(phone.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if ident.FamilyID == uuid.Nil {
		t.Fatal("access token 应带 fid claim")
	}

	rows, err := auth.ListSessions(ctx, reg.User.ID, ident.FamilyID)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("应有 2 个会话,得到 %d", len(rows))
	}
	current, desktop := 0, uuid.Nil
	for _, r := range rows {
		if r.Current {
			current++
			if r.UserAgent != "phone-UA" {
				t.Fatalf("当前会话应是手机 UA,得到 %q", r.UserAgent)
			}
		} else {
			desktop = r.FamilyID
			if r.UserAgent != "desktop-UA" {
				t.Fatalf("另一条应是桌面 UA,得到 %q", r.UserAgent)
			}
		}
		if !r.Active {
			t.Fatalf("刚建的会话都该 active: %+v", r)
		}
	}
	if current != 1 || desktop == uuid.Nil {
		t.Fatalf("当前会话应恰好一个,得到 %d", current)
	}

	// 吊销当前会话必须被拒 —— 那是 logout 的路
	if err := auth.RevokeSession(ctx, reg.User.ID, ident.FamilyID, ident.FamilyID); err == nil {
		t.Fatal("吊销当前会话应被拒绝")
	}

	if err := auth.RevokeSession(ctx, reg.User.ID, ident.FamilyID, desktop); err != nil {
		t.Fatalf("吊销其它会话失败: %v", err)
	}
	rows, err = auth.ListSessions(ctx, reg.User.ID, ident.FamilyID)
	if err != nil {
		t.Fatal(err)
	}
	active := 0
	for _, r := range rows {
		if r.Active {
			active++
		}
		if r.FamilyID == desktop && r.RevokedAt == nil {
			t.Fatal("被吊销的会话应带 revokedAt")
		}
	}
	if active != 1 {
		t.Fatalf("吊销后应只剩 1 个活跃会话,得到 %d", active)
	}
}

func TestRevokeSessionCrossUser(t *testing.T) {
	pool := setup(t)
	auth := newAuth(pool)
	ctx := context.Background()

	a, err := auth.Register(ctx, "usera", "password123", "203.0.113.1")
	if err != nil {
		t.Fatal(err)
	}
	b, err := auth.Register(ctx, "userb", "password123", "203.0.113.2")
	if err != nil {
		t.Fatal(err)
	}
	identA, err := auth.ParseAccess(a.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	identB, err := auth.ParseAccess(b.AccessToken)
	if err != nil {
		t.Fatal(err)
	}

	// B 吊销 A 的会话 → 一律 NotFound,不泄露"这个 family 存不存在"
	if err := auth.RevokeSession(ctx, b.User.ID, identB.FamilyID, identA.FamilyID); err == nil {
		t.Fatal("跨用户吊销应失败")
	}
}

func TestTouchSessionDoesNotWriteWithinWindow(t *testing.T) {
	pool := setup(t)
	auth := newAuth(pool)
	ctx := context.Background()

	res, err := auth.Register(ctx, "touchuser", "password123", "203.0.113.9")
	if err != nil {
		t.Fatal(err)
	}
	ident, err := auth.ParseAccess(res.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if ident.FamilyID == uuid.Nil {
		t.Fatal("注册签发的 token 也该带 fid")
	}

	q := store.New(pool)
	before, err := q.ListSessions(ctx, res.User.ID)
	if err != nil || len(before) != 1 {
		t.Fatalf("应有 1 行,err=%v n=%d", err, len(before))
	}
	stamp := before[0].LastSeenAt.Time

	// 刚建的会话 last_seen 就是 now,SQL 里"5 分钟前"的条件不成立 ⇒ 不该写
	auth.TouchSession(ctx, ident.FamilyID)

	after, err := q.ListSessions(ctx, res.User.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !after[0].LastSeenAt.Time.Equal(stamp) {
		t.Fatalf("五分钟内的 TouchSession 不该改动 last_seen_at:%v -> %v",
			stamp, after[0].LastSeenAt.Time)
	}
}
