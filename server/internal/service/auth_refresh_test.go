package service_test

import (
	"context"
	"errors"
	"testing"

	"github.com/yophon/gopan/server/internal/service"
)

// refresh 的错误分两类:凭证本身无效(UNAUTHENTICATED,resolver 清 cookie、客户端回登录页)
// 与基础设施故障(库、账号中心暂时不可用:保留 cookie,客户端稍后重试)。
// 曾经任何错误都清 cookie,数据库一抖所有网页和 App 用户都被登出。
func TestRefreshInfrastructureErrorIsNotAnAuthFailure(t *testing.T) {
	pool := setup(t)
	auth := newAuth(pool)
	res, err := auth.Register(context.Background(), "refresh_infra", "password123", "1.1.1.9")
	if err != nil {
		t.Fatal(err)
	}

	// 查库失败(这里用已取消的 context 模拟连不上库)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = auth.Refresh(ctx, res.RefreshToken)
	if err == nil {
		t.Fatal("已取消的 context 下 refresh 应失败")
	}
	if service.IsUnauthenticated(err) {
		t.Fatalf("库不可用不应被当成凭证无效:%v", err)
	}

	// 凭证本身无效:是认证失败
	_, err = auth.Refresh(context.Background(), "not-a-valid-refresh-token")
	if !service.IsUnauthenticated(err) {
		t.Fatalf("无效凭证应是 UNAUTHENTICATED,got %v", err)
	}

	// 原来那枚凭证没有被这次失败弄坏,恢复后还能续上
	if _, err := auth.Refresh(context.Background(), res.RefreshToken); err != nil {
		t.Fatalf("故障恢复后应能用原凭证续登:%v", err)
	}
}

func TestIsUnauthenticated(t *testing.T) {
	if !service.IsUnauthenticated(service.ErrUnauthenticated) {
		t.Fatal("ErrUnauthenticated")
	}
	if !service.IsUnauthenticated(errors.Join(errors.New("wrap"), service.ErrUnauthenticated)) {
		t.Fatal("包装过的 ErrUnauthenticated")
	}
	for _, err := range []error{nil, service.ErrIDUnavailable, service.ErrRateLimited, errors.New("connection refused")} {
		if service.IsUnauthenticated(err) {
			t.Fatalf("%v 不应算认证失败", err)
		}
	}
}
