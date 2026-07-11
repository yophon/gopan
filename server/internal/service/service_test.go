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

func TestShareLifecycle(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)
	shares := service.NewShares(store.New(pool), auth)
	shares.SetRateLimit(time.Millisecond, 1000)

	res, err := auth.Register(ctx, "carol", "password123", "1.1.1.1")
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
	private, err := nodes.CreateFolder(ctx, owner, nil, "private")
	if err != nil {
		t.Fatal(err)
	}

	// 带密码分享 docs
	pw := "sesame88"
	sh, err := shares.Create(ctx, owner, docs.ID, &pw, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(sh.Token) != 10 {
		t.Fatalf("token 应为 10 位,got %q", sh.Token)
	}

	// 公开 info
	info, err := shares.Info(ctx, sh.Token)
	if err != nil || !info.NeedPassword || info.Expired || info.Name != "docs" {
		t.Fatalf("info=%+v err=%v", info, err)
	}

	// 空密码 → SHARE_PASSWORD_REQUIRED;错密码 → BAD_SHARE_PASSWORD
	if _, err := shares.Access(ctx, sh.Token, "", "2.2.2.2"); err == nil {
		t.Fatal("空密码应被拒")
	}
	if _, err := shares.Access(ctx, sh.Token, "wrong", "2.2.2.2"); err == nil {
		t.Fatal("错密码应被拒")
	}

	// 正确密码 → 访客 token,scope=share:{id},sub=属主
	access, err := shares.Access(ctx, sh.Token, pw, "2.2.2.2")
	if err != nil {
		t.Fatal(err)
	}
	guest, err := auth.ParseAccess(access)
	if err != nil || guest.UserID != owner || guest.Scope != "share:"+sh.ID.String() {
		t.Fatalf("guest=%+v err=%v", guest, err)
	}

	// 子树内放行,子树外 NOT_FOUND
	if err := shares.Authorize(ctx, guest, sub.ID); err != nil {
		t.Fatalf("子树内应放行:%v", err)
	}
	if err := shares.Authorize(ctx, guest, docs.ID); err != nil {
		t.Fatalf("分享根自身应放行:%v", err)
	}
	if err := shares.Authorize(ctx, guest, private.ID); err != service.ErrNotFound {
		t.Fatalf("子树外应 NOT_FOUND,got %v", err)
	}

	// 软删分享根 → 分享失效
	if err := nodes.Delete(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := shares.Access(ctx, sh.Token, pw, "2.2.2.2"); err == nil {
		t.Fatal("节点已删的分享应失效")
	}
	if _, err := nodes.Restore(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}

	// 吊销 → 失效且从列表消失
	if err := shares.Revoke(ctx, owner, sh.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := shares.Access(ctx, sh.Token, pw, "2.2.2.2"); err == nil {
		t.Fatal("已吊销的分享应失效")
	}
	if err := shares.Authorize(ctx, guest, sub.ID); err == nil {
		t.Fatal("吊销后已发的访客 token 也应失效")
	}
	ls, err := shares.List(ctx, owner)
	if err != nil || len(ls) != 0 {
		t.Fatalf("吊销后列表应为空,got %d err=%v", len(ls), err)
	}

	// 过期分享:Access 拒绝
	past := time.Now().Add(time.Second)
	sh2, err := shares.Create(ctx, owner, docs.ID, nil, &past)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(1100 * time.Millisecond)
	if _, err := shares.Access(ctx, sh2.Token, "", "2.2.2.2"); err == nil {
		t.Fatal("过期分享应被拒")
	}

	// 无密码分享:空密码直接拿 token;彻底删除节点级联删分享
	sh3, err := shares.Create(ctx, owner, docs.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := shares.Access(ctx, sh3.Token, "", "2.2.2.2"); err != nil {
		t.Fatalf("无密码分享应直接放行:%v", err)
	}
	if err := nodes.Purge(ctx, owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatalf("彻底删除被分享的节点应级联成功:%v", err)
	}
	if _, err := shares.Info(ctx, sh3.Token); err != service.ErrNotFound {
		t.Fatalf("级联删除后分享应 NOT_FOUND,got %v", err)
	}
}

func TestCopyNodes(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)

	res, err := auth.Register(ctx, "dave", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	q := store.New(pool)

	// 树:src/{sub/, f1(blob 100B), sub/f2(同 blob)}
	src, _ := nodes.CreateFolder(ctx, owner, nil, "src")
	sub, _ := nodes.CreateFolder(ctx, owner, &src.ID, "sub")
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{
		ID: uuid.Must(uuid.NewV7()), Sha256: "aa" + string(make([]byte, 0)) + "11223344556677889900112233445566778899001122334455667788990011", Size: 100, Mime: "text/plain",
	})
	if err != nil {
		t.Fatal(err)
	}
	mkfile := func(parent *uuid.UUID, name string) store.Node {
		n, err := q.CreateNode(ctx, store.CreateNodeParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent, Name: name, Kind: "file", BlobID: &blob.ID,
		})
		if err != nil {
			t.Fatal(err)
		}
		if err := q.IncrementBlobRef(ctx, blob.ID); err != nil {
			t.Fatal(err)
		}
		if err := q.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: 100}); err != nil {
			t.Fatal(err)
		}
		return n
	}
	mkfile(&src.ID, "f1.txt")
	mkfile(&sub.ID, "f2.txt")

	before, _ := q.GetUserByID(ctx, owner)

	// 复制 src 到根:重名自动 (1),配额 +200,ref_count +2
	copied, err := nodes.Copy(ctx, owner, []uuid.UUID{src.ID}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(copied) != 1 || copied[0].Name != "src (1)" {
		t.Fatalf("复制根应叫 src (1),got %+v", copied)
	}
	after, _ := q.GetUserByID(ctx, owner)
	if after.UsedBytes-before.UsedBytes != 200 {
		t.Fatalf("配额应 +200,got +%d", after.UsedBytes-before.UsedBytes)
	}
	b, _ := q.GetBlob(ctx, blob.ID)
	if b.RefCount != 4 {
		t.Fatalf("ref_count 应为 4,got %d", b.RefCount)
	}
	// 子树结构完整
	kids, _ := q.ListActiveChildrenLite(ctx, &copied[0].ID)
	if len(kids) != 2 {
		t.Fatalf("复制的子级应有 2 个,got %d", len(kids))
	}

	// 复制到自己的子树 → 拒绝
	if _, err := nodes.Copy(ctx, owner, []uuid.UUID{src.ID}, &sub.ID); err == nil {
		t.Fatal("复制到自身子树应被拒")
	}

	// 超配额 → 拒绝
	if err := q.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: 1 << 30}); err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.Copy(ctx, owner, []uuid.UUID{src.ID}, nil); err == nil {
		t.Fatal("超配额复制应被拒")
	}
}

func TestPurgeExpiredTrash(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)

	res, _ := auth.Register(ctx, "erin", "password123", "1.1.1.1")
	owner := res.User.ID

	f1, _ := nodes.CreateFolder(ctx, owner, nil, "old")
	f2, _ := nodes.CreateFolder(ctx, owner, nil, "fresh")
	if err := nodes.Delete(ctx, owner, []uuid.UUID{f1.ID, f2.ID}); err != nil {
		t.Fatal(err)
	}
	// 把 old 的删除时间拨回 31 天前
	if _, err := pool.Exec(ctx,
		`UPDATE nodes SET deleted_at = now() - interval '31 days' WHERE id = $1`, f1.ID); err != nil {
		t.Fatal(err)
	}

	n, err := nodes.PurgeExpiredTrash(ctx, 30*24*time.Hour)
	if err != nil || n != 1 {
		t.Fatalf("应清理 1 个,got %d err=%v", n, err)
	}
	if _, err := nodes.Get(ctx, owner, f1.ID); err != service.ErrNotFound {
		t.Fatalf("过期项应已彻删,got %v", err)
	}
	if _, err := nodes.Get(ctx, owner, f2.ID); err != nil {
		t.Fatalf("未过期项应保留:%v", err)
	}
}

func TestChangePassword(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	auth.SetRateLimit(time.Millisecond, 1000)

	res, _ := auth.Register(ctx, "frank", "oldpass123", "1.1.1.1")

	// 旧密码错 → 拒
	if _, err := auth.ChangePassword(ctx, res.User.ID, "wrong", "newpass456", "1.1.1.1"); err == nil {
		t.Fatal("旧密码错误应被拒")
	}
	// 改密成功:旧 refresh 全失效,新 pair 可用,新旧密码登录各归其位
	res2, err := auth.ChangePassword(ctx, res.User.ID, "oldpass123", "newpass456", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := auth.Refresh(ctx, res.RefreshToken); err == nil {
		t.Fatal("改密后旧 refresh 应失效")
	}
	if _, err := auth.Refresh(ctx, res2.RefreshToken); err != nil {
		t.Fatalf("改密返回的新 refresh 应可用:%v", err)
	}
	if _, err := auth.Login(ctx, "frank", "oldpass123", "2.2.2.2"); err == nil {
		t.Fatal("旧密码登录应被拒")
	}
	if _, err := auth.Login(ctx, "frank", "newpass456", "2.2.2.2"); err != nil {
		t.Fatalf("新密码登录应成功:%v", err)
	}
}
