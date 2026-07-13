package service_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestAppPasswordsService(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	q := store.New(pool)
	ap := service.NewAppPasswords(q)
	admin := service.NewAdmin(q, 1<<30)

	ra, err := auth.Register(ctx, "davsvc", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}

	// 名称校验
	if _, _, err := ap.Create(ctx, ra.User.ID, "  "); err == nil {
		t.Fatal("空名称应拒")
	}
	plain, row, err := ap.Create(ctx, ra.User.ID, "mbp")
	if err != nil || !strings.HasPrefix(plain, "gopan_") {
		t.Fatalf("Create: %q err=%v", plain, err)
	}

	// List:只见自己的;别人的列表为空
	rows, err := ap.List(ctx, ra.User.ID)
	if err != nil || len(rows) != 1 || rows[0].Name != "mbp" {
		t.Fatalf("List 应只有 mbp 一条:%+v err=%v", rows, err)
	}
	if rows, err := ap.List(ctx, uuid.Must(uuid.NewV7())); err != nil || len(rows) != 0 {
		t.Fatalf("他人列表应为空:%+v err=%v", rows, err)
	}

	// 认证:成功、错密码、错用户名
	uid, err := ap.Authenticate(ctx, "davsvc", plain, "9.9.9.9")
	if err != nil || uid != ra.User.ID {
		t.Fatalf("Authenticate: %v %v", uid, err)
	}
	if _, err := ap.Authenticate(ctx, "davsvc", "gopan_nope", "9.9.9.9"); err != service.ErrUnauthenticated {
		t.Fatalf("错密码应 UNAUTHENTICATED,got %v", err)
	}
	if _, err := ap.Authenticate(ctx, "other", plain, "9.9.9.9"); err != service.ErrUnauthenticated {
		t.Fatalf("用户名不匹配应 UNAUTHENTICATED,got %v", err)
	}

	// 限速只记失败:成功认证不耗令牌
	ap.SetRateLimit(time.Hour, 3)
	for range 20 {
		if _, err := ap.Authenticate(ctx, "davsvc", plain, "8.8.8.8"); err != nil {
			t.Fatalf("成功认证不应被限速:%v", err)
		}
	}
	var limited bool
	for range 4 {
		if _, err := ap.Authenticate(ctx, "davsvc", "gopan_bad", "8.8.8.8"); err == service.ErrRateLimited {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatal("连续失败应触发限速")
	}

	// 属主禁用 → 认证拒
	q2, _ := auth.Register(ctx, "davadmin", "password123", "1.1.1.2")
	if _, err := q.PromoteAdminByUsername(ctx, "davadmin"); err != nil {
		t.Fatal(err)
	}
	if _, err := admin.SetDisabled(ctx, q2.User.ID, ra.User.ID, true); err != nil {
		t.Fatal(err)
	}
	if _, err := ap.Authenticate(ctx, "davsvc", plain, "7.7.7.7"); err != service.ErrUnauthenticated {
		t.Fatalf("禁用属主的应用密码应失效,got %v", err)
	}

	// 吊销
	if err := ap.Revoke(ctx, ra.User.ID, row.ID); err != nil {
		t.Fatal(err)
	}
	if err := ap.Revoke(ctx, ra.User.ID, row.ID); err != service.ErrNotFound {
		t.Fatalf("重复吊销应 NOT_FOUND,got %v", err)
	}
	// 吊销后列表清空
	if rows, err := ap.List(ctx, ra.User.ID); err != nil || len(rows) != 0 {
		t.Fatalf("吊销后 List 应为空:%+v err=%v", rows, err)
	}
}

func TestCommitStreamed(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, 48*time.Hour)
	q := store.New(pool)

	ra, err := auth.Register(ctx, "streamer", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := ra.User.ID

	put := func(content string) (sha string, tmpKey string) {
		t.Helper()
		h := sha256.Sum256([]byte(content))
		tmpKey = "tmp/webdav/" + uuid.Must(uuid.NewV7()).String()
		if _, err := obj.PutStream(ctx, tmpKey, bytes.NewReader([]byte(content)), "text/plain"); err != nil {
			t.Fatal(err)
		}
		return hex.EncodeToString(h[:]), tmpKey
	}

	// 新建
	sha, tmp := put("streamed content")
	n, err := uploads.CommitStreamed(ctx, owner, nil, "s.txt", sha, 16, tmp)
	if err != nil {
		t.Fatal(err)
	}
	if n.Name != "s.txt" {
		t.Fatalf("节点名:%s", n.Name)
	}
	// blob 应为 pending(与上传管线同一信任模型)
	blob, err := q.GetBlobBySha256(ctx, sha)
	if err != nil || blob.Verified {
		t.Fatalf("dav 上传的 blob 应 pending verify:%+v err=%v", blob, err)
	}

	// 同名文件夹冲突
	if _, err := nodes.CreateFolder(ctx, owner, nil, "conflict"); err != nil {
		t.Fatal(err)
	}
	sha2, tmp2 := put("x")
	if _, err := uploads.CommitStreamed(ctx, owner, nil, "conflict", sha2, 1, tmp2); err == nil {
		t.Fatal("同名文件夹应拒")
	}

	// 配额:调小后超额拒,且临时对象要被清理
	if _, err := pool.Exec(ctx, `UPDATE users SET quota_bytes = 20 WHERE id = $1`, owner); err != nil {
		t.Fatal(err)
	}
	sha3, tmp3 := put("this is way too large for quota")
	if _, err := uploads.CommitStreamed(ctx, owner, nil, "big.txt", sha3, 31, tmp3); err == nil {
		t.Fatal("超额应拒")
	}
	if rc, err := obj.Open(ctx, tmp3); err == nil {
		rc.Close()
		t.Log("注意:MinIO Open 是惰性的,读一下才知道对象是否存在")
	}

	// 覆盖:同名文件换内容,配额按差额
	if _, err := pool.Exec(ctx, `UPDATE users SET quota_bytes = 1000 WHERE id = $1`, owner); err != nil {
		t.Fatal(err)
	}
	sha4, tmp4 := put("v2!")
	if _, err := uploads.CommitStreamed(ctx, owner, nil, "s.txt", sha4, 3, tmp4); err != nil {
		t.Fatalf("覆盖失败:%v", err)
	}
	var used int64
	if err := pool.QueryRow(ctx, `SELECT used_bytes FROM users WHERE id = $1`, owner).Scan(&used); err != nil {
		t.Fatal(err)
	}
	if used != 3 {
		t.Fatalf("覆盖后 used 应 3,got %d", used)
	}
	// 旧 blob ref 归零进 GC 轨道
	old, err := q.GetBlobBySha256(ctx, sha)
	if err != nil || old.RefCount != 0 || !old.DerefAt.Valid {
		t.Fatalf("旧 blob 应 ref=0 且记 deref_at:%+v err=%v", old, err)
	}

	// 幂等覆盖:同内容再传,引用配额不动
	sha5, tmp5 := put("v2!")
	if sha5 != sha4 {
		t.Fatal("同内容 sha 应一致")
	}
	if _, err := uploads.CommitStreamed(ctx, owner, nil, "s.txt", sha5, 3, tmp5); err != nil {
		t.Fatalf("幂等覆盖失败:%v", err)
	}
	nb, _ := q.GetBlobBySha256(ctx, sha4)
	if nb.RefCount != 1 {
		t.Fatalf("同内容覆盖后 ref 应仍为 1,got %d", nb.RefCount)
	}
}
