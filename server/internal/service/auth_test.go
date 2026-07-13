package service_test

// 注册输入校验 / 开关 / 限速与 GetUser 的集成测试。
// 登录、refresh 旋转、改密等在 service_test.go 里已覆盖。

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestRegisterValidationAndGetUser(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)

	// 输入校验:用户名长度、密码长度(bcrypt 上限 72)
	if _, err := auth.Register(ctx, "a", "password123", "1.1.1.1"); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("用户名过短应 INVALID_INPUT,got %v", err)
	}
	if _, err := auth.Register(ctx, strings.Repeat("n", 33), "password123", "1.1.1.1"); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("用户名过长应 INVALID_INPUT,got %v", err)
	}
	if _, err := auth.Register(ctx, "gooduser", "short", "1.1.1.1"); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("密码过短应 INVALID_INPUT,got %v", err)
	}
	if _, err := auth.Register(ctx, "gooduser", strings.Repeat("p", 73), "1.1.1.1"); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("密码过长应 INVALID_INPUT,got %v", err)
	}

	// 用户名前后空白应被裁剪
	res, err := auth.Register(ctx, "  gooduser  ", "password123", "1.1.1.1")
	if err != nil || res.User.Username != "gooduser" {
		t.Fatalf("res=%+v err=%v", res, err)
	}
	// 重名 → USERNAME_TAKEN
	if _, err := auth.Register(ctx, "gooduser", "password123", "1.1.1.1"); svcErrCode(err) != "USERNAME_TAKEN" {
		t.Fatalf("重名应 USERNAME_TAKEN,got %v", err)
	}

	// GetUser:存在 / 不存在
	u, err := auth.GetUser(ctx, res.User.ID)
	if err != nil || u.Username != "gooduser" {
		t.Fatalf("GetUser: %+v err=%v", u, err)
	}
	if _, err := auth.GetUser(ctx, uuid.Must(uuid.NewV7())); err != service.ErrUnauthenticated {
		t.Fatalf("不存在的用户应 UNAUTHENTICATED,got %v", err)
	}

	// 注册限速:令牌耗尽 → RATE_LIMITED(注入确定性参数,避免计时依赖)
	auth.SetRateLimit(time.Hour, 1)
	var limited bool
	for i := 0; i < 3; i++ {
		_, err := auth.Register(ctx, fmt.Sprintf("rluser%d", i), "password123", "9.9.9.9")
		if svcErrCode(err) == "RATE_LIMITED" {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatal("连续注册应触发限速")
	}

	// 注册关闭 → REGISTER_CLOSED
	closed := service.NewAuth(store.New(pool), []byte("test-secret-test-secret-test-secret"),
		15*time.Minute, 14*24*time.Hour, false, 1<<30)
	if _, err := closed.Register(ctx, "someone", "password123", "5.5.5.5"); svcErrCode(err) != "REGISTER_CLOSED" {
		t.Fatalf("注册关闭应 REGISTER_CLOSED,got %v", err)
	}
}
