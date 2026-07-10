package service_test

// 集成测试:需要一个可用的 Postgres,通过 TEST_DB_URL 传入,例如
//   TEST_DB_URL='postgres://gopan:gopan@127.0.0.1:5433/gopan_test?sslmode=disable' go test ./...
// 未设置时整包跳过。每次运行前重建 schema。

import (
	"context"
	"database/sql"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	"github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func setup(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DB_URL")
	if url == "" {
		t.Skip("TEST_DB_URL 未设置,跳过集成测试")
	}
	sqlDB, err := sql.Open("pgx", url)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		t.Fatal(err)
	}
	goose.SetBaseFS(db.Migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("postgres"); err != nil {
		t.Fatal(err)
	}
	if err := goose.Up(sqlDB, "migrations"); err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func newAuth(pool *pgxpool.Pool) *service.Auth {
	return service.NewAuth(store.New(pool), []byte("test-secret-test-secret-test-secret"),
		15*time.Minute, 14*24*time.Hour, true, 1<<30)
}

func TestAuthRefreshRotationAndReuseDetection(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)

	res, err := auth.Register(ctx, "alice", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}

	// access token 可解析
	id, err := auth.ParseAccess(res.AccessToken)
	if err != nil || id.UserID != res.User.ID || id.Scope != service.ScopeUser {
		t.Fatalf("ParseAccess: id=%v err=%v", id, err)
	}

	// 旋转:旧 → 新
	r1, err := auth.Refresh(ctx, res.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	// 重放旧 token:重用检测,且吊销整族
	if _, err := auth.Refresh(ctx, res.RefreshToken); err != service.ErrUnauthenticated {
		t.Fatalf("重用旧 refresh 应被拒,got %v", err)
	}
	if _, err := auth.Refresh(ctx, r1.RefreshToken); err != service.ErrUnauthenticated {
		t.Fatalf("重用后同族新 token 也应被吊销,got %v", err)
	}

	// 错误密码
	if _, err := auth.Login(ctx, "alice", "wrong-password", "1.1.1.1"); err == nil {
		t.Fatal("错误密码应失败")
	}

	// 登录限速:令牌耗尽后应 RATE_LIMITED(注入确定性参数,避免计时依赖)
	auth.SetRateLimit(time.Hour, 3)
	var limited bool
	for i := 0; i < 4; i++ {
		_, err := auth.Login(ctx, "alice", "wrong-password", "2.2.2.2")
		if se, ok := err.(*service.Error); ok && se.Code == "RATE_LIMITED" {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatal("连续失败登录应触发限速")
	}
}

func TestNodeTreeRules(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)

	res, err := auth.Register(ctx, "bob", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID

	docs, err := nodes.CreateFolder(ctx, owner, nil, "docs")
	if err != nil {
		t.Fatal(err)
	}
	sub, err := nodes.CreateFolder(ctx, owner, &docs.ID, "sub")
	if err != nil {
		t.Fatal(err)
	}

	// 同层重名自动加后缀
	d2, err := nodes.CreateFolder(ctx, owner, nil, "docs")
	if err != nil || d2.Name != "docs (1)" {
		t.Fatalf("重名应得 docs (1),got %q err=%v", d2.Name, err)
	}

	// 成环移动被拒
	if _, err := nodes.Move(ctx, owner, []uuid.UUID{docs.ID}, &sub.ID); err == nil {
		t.Fatal("移动到自己的子树应被拒")
	}
	// 移动到自己也被拒
	if _, err := nodes.Move(ctx, owner, []uuid.UUID{docs.ID}, &docs.ID); err == nil {
		t.Fatal("移动到自身应被拒")
	}

	// 软删整树 → 回收站只展示顶层
	if err := nodes.Delete(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}
	trash, err := nodes.Trash(ctx, owner, nil)
	if err != nil {
		t.Fatal(err)
	}
	if trash.Total != 1 || trash.Items[0].Name != "docs" {
		t.Fatalf("回收站应只有顶层 docs,got %+v", trash.Items)
	}

	// 还原后子节点也回来
	if _, err := nodes.Restore(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.Get(ctx, owner, sub.ID); err != nil {
		t.Fatalf("还原后子节点应可见:%v", err)
	}

	// 还原时原位置被占 → 自动改名
	if err := nodes.Delete(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.CreateFolder(ctx, owner, nil, "docs"); err != nil {
		t.Fatal(err)
	}
	restored, err := nodes.Restore(ctx, owner, []uuid.UUID{docs.ID})
	if err != nil {
		t.Fatal(err)
	}
	if restored[0].Name == "docs" {
		t.Fatal("原位被占时还原应自动改名")
	}

	// 彻底删除后不可见
	if err := nodes.Purge(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.Get(ctx, owner, docs.ID); err != service.ErrNotFound {
		t.Fatalf("彻底删除后应 NOT_FOUND,got %v", err)
	}

	// 越权:别人的目录不可见
	res2, err := auth.Register(ctx, "carol", "password123", "3.3.3.3")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.Get(ctx, res2.User.ID, sub.ID); err != service.ErrNotFound {
		t.Fatalf("他人节点应 NOT_FOUND,got %v", err)
	}
}
