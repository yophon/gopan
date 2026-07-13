package service_test

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestAdmin(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	q := store.New(pool)
	admin := service.NewAdmin(q, 1<<30)
	nodes := service.NewNodes(pool)
	shares := service.NewShares(q, auth)

	ra, err := auth.Register(ctx, "alice", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	rb, err := auth.Register(ctx, "bob", "password123", "1.1.1.2")
	if err != nil {
		t.Fatal(err)
	}
	// 等价于 CLI promote
	if n, err := q.PromoteAdminByUsername(ctx, "alice"); err != nil || n != 1 {
		t.Fatalf("promote: n=%d err=%v", n, err)
	}

	// 非 admin 一律 FORBIDDEN
	if _, err := admin.ListUsers(ctx, rb.User.ID); err != service.ErrForbidden {
		t.Fatalf("非 admin ListUsers 应 FORBIDDEN,got %v", err)
	}
	if _, err := admin.SetQuota(ctx, rb.User.ID, ra.User.ID, 1); err != service.ErrForbidden {
		t.Fatalf("非 admin SetQuota 应 FORBIDDEN,got %v", err)
	}

	// ListUsers:两个用户都在
	us, err := admin.ListUsers(ctx, ra.User.ID)
	if err != nil || len(us) != 2 {
		t.Fatalf("ListUsers: n=%d err=%v", len(us), err)
	}

	// 输入校验分支
	if _, err := admin.CreateUser(ctx, ra.User.ID, "x", "password123", nil); err == nil {
		t.Fatal("用户名过短应拒")
	}
	if _, err := admin.CreateUser(ctx, ra.User.ID, "dave", "short", nil); err == nil {
		t.Fatal("密码过短应拒")
	}
	neg := int64(-1)
	if _, err := admin.CreateUser(ctx, ra.User.ID, "dave", "password123", &neg); err == nil {
		t.Fatal("负配额应拒")
	}
	if _, err := admin.SetQuota(ctx, ra.User.ID, ra.User.ID, -1); err == nil {
		t.Fatal("SetQuota 负配额应拒")
	}
	if _, err := admin.SetQuota(ctx, ra.User.ID, uuid.Must(uuid.NewV7()), 1); err != service.ErrNotFound {
		t.Fatalf("SetQuota 不存在的用户应 NOT_FOUND,got %v", err)
	}

	// 建号:不受注册开关限制、可指定配额、重名拒
	quota := int64(5 << 20)
	cu, err := admin.CreateUser(ctx, ra.User.ID, "carol", "password123", &quota)
	if err != nil || cu.QuotaBytes != quota {
		t.Fatalf("CreateUser: %+v err=%v", cu, err)
	}
	if _, err := admin.CreateUser(ctx, ra.User.ID, "carol", "password123", nil); err == nil {
		t.Fatal("重名建号应失败")
	}
	if _, err := auth.Login(ctx, "carol", "password123", "1.1.1.3"); err != nil {
		t.Fatalf("新建用户应能登录:%v", err)
	}

	// 调配额
	if u, err := admin.SetQuota(ctx, ra.User.ID, cu.ID, 42); err != nil || u.QuotaBytes != 42 {
		t.Fatalf("SetQuota: %+v err=%v", u, err)
	}

	// bob 建分享,禁用后三处连带失效
	folder, err := nodes.CreateFolder(ctx, rb.User.ID, nil, "docs")
	if err != nil {
		t.Fatal(err)
	}
	sh, err := shares.Create(ctx, rb.User.ID, folder.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if info, err := shares.Info(ctx, sh.Token); err != nil || info.Expired {
		t.Fatalf("禁用前分享应有效:%+v err=%v", info, err)
	}

	if u, err := admin.SetDisabled(ctx, ra.User.ID, rb.User.ID, true); err != nil || !u.DisabledAt.Valid {
		t.Fatalf("SetDisabled: %+v err=%v", u, err)
	}
	// 登录拒
	if _, err := auth.Login(ctx, "bob", "password123", "1.1.1.4"); err == nil {
		t.Fatal("禁用用户登录应被拒")
	} else if se, ok := err.(*service.Error); !ok || se.Code != "ACCOUNT_DISABLED" {
		t.Fatalf("应返回 ACCOUNT_DISABLED,got %v", err)
	}
	// refresh 拒(禁用吊销全族)
	if _, err := auth.Refresh(ctx, rb.RefreshToken); err != service.ErrUnauthenticated {
		t.Fatalf("禁用用户 refresh 应被拒,got %v", err)
	}
	// 分享连带失效
	if info, err := shares.Info(ctx, sh.Token); err != nil || !info.Expired {
		t.Fatalf("禁用后分享应失效:%+v err=%v", info, err)
	}
	if _, err := shares.Access(ctx, sh.Token, "", "1.1.1.5"); err == nil {
		t.Fatal("禁用后访客换 token 应被拒")
	}

	// 解禁后恢复
	if _, err := admin.SetDisabled(ctx, ra.User.ID, rb.User.ID, false); err != nil {
		t.Fatal(err)
	}
	if info, err := shares.Info(ctx, sh.Token); err != nil || info.Expired {
		t.Fatalf("解禁后分享应恢复:%+v err=%v", info, err)
	}
	if _, err := auth.Login(ctx, "bob", "password123", "1.1.1.6"); err != nil {
		t.Fatalf("解禁后应能登录:%v", err)
	}

	// 不能禁用自己
	if _, err := admin.SetDisabled(ctx, ra.User.ID, ra.User.ID, true); err == nil {
		t.Fatal("禁用自己应被拒")
	}

	// 重置密码:旧密码失效、新密码可登录、hash 不落明文
	plain, err := admin.ResetPassword(ctx, ra.User.ID, cu.ID)
	if err != nil || len(plain) < 12 {
		t.Fatalf("ResetPassword: %q err=%v", plain, err)
	}
	if _, err := auth.Login(ctx, "carol", "password123", "1.1.1.7"); err == nil {
		t.Fatal("重置后旧密码应失效")
	}
	if _, err := auth.Login(ctx, "carol", plain, "1.1.1.8"); err != nil {
		t.Fatalf("重置后新密码应可登录:%v", err)
	}
	if u, _ := q.GetUserByID(ctx, cu.ID); bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(plain)) != nil {
		t.Fatal("库里应存新密码的 bcrypt hash")
	}

	// Overview + 失败任务重排
	blobID := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx, `INSERT INTO blobs (id, sha256, size) VALUES ($1, 'deadbeef', 1)`, blobID); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO tasks (id, kind, blob_id, status, attempts) VALUES ($1, 'thumb', $2, 'failed', 3)`,
		uuid.Must(uuid.NewV7()), blobID); err != nil {
		t.Fatal(err)
	}
	ov, err := admin.Overview(ctx, ra.User.ID)
	if err != nil || ov.UserCount != 3 || ov.BlobCount != 1 {
		t.Fatalf("Overview: %+v err=%v", ov, err)
	}
	n, err := admin.RetryFailedTasks(ctx, ra.User.ID)
	if err != nil || n != 1 {
		t.Fatalf("RetryFailedTasks: n=%d err=%v", n, err)
	}
	var status string
	var attempts int
	if err := pool.QueryRow(ctx, `SELECT status, attempts FROM tasks WHERE blob_id = $1`, blobID).
		Scan(&status, &attempts); err != nil {
		t.Fatal(err)
	}
	if status != "pending" || attempts != 0 {
		t.Fatalf("重排后应 pending/attempts=0,got %s/%d", status, attempts)
	}
}
