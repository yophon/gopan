package worker

// worker.go 主流程的集成测试:Run/taskLoop/execute 的状态流转、周期清理
// (回收站、refresh token、子树统计)与 agent 传输定稿。依赖同 worker_test.go。

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func mustExec(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatal(err)
	}
}

// waitFor 轮询等待异步条件成立,超时即失败。
func waitFor(t *testing.T, timeout time.Duration, cond func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal(msg)
}

// TestRunProcessesTasksAndStops 端到端跑真实 worker:Run 启动 taskLoop 与各周期
// goroutine,Enqueue 唤醒后 execute 完成缩略图任务,ctx 取消后 Run 返回。
// 一并覆盖 SetUploads(Run 里 agent 定稿分支要 uploads 非空才会启动)。
func TestRunProcessesTasksAndStops(t *testing.T) {
	w, pool, obj := setup(t)
	uploads := service.NewUploads(pool, obj, service.NewNodes(pool), 16<<20, time.Hour)
	uploads.SetEnqueue(w.Enqueue)
	w.SetUploads(uploads)

	blob, _ := seedBlobWithObject(t, pool, obj, pngBytes(t, 512, 512), "image/png")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		w.Run(ctx, 2)
		close(done)
	}()
	w.Enqueue(ctx, "thumb", blob)

	waitFor(t, 20*time.Second, func() bool {
		var status string
		if err := pool.QueryRow(context.Background(),
			`SELECT status FROM tasks WHERE kind = 'thumb' AND blob_id = $1`, blob).Scan(&status); err != nil {
			return false
		}
		if status != "done" {
			return false
		}
		var n int
		if err := pool.QueryRow(context.Background(),
			`SELECT count(*) FROM derivatives WHERE blob_id = $1`, blob).Scan(&n); err != nil {
			return false
		}
		return n == 2
	}, "thumb 任务应被 taskLoop 认领并执行到 done,且产出两档缩略图")

	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("ctx 取消后 Run 应返回")
	}
}

// TestExecuteRetryThenFail 覆盖 execute 的失败路径:attempts 未满交还队列重试,
// 打满进 failed 终态并留 last_error;未知任务类型走 default 分支。
func TestExecuteRetryThenFail(t *testing.T) {
	w, pool, _ := setup(t)
	ctx := context.Background()
	q := store.New(pool)

	// blob 行存在但 MinIO 无对象:thumb 必失败
	blob := uuid.Must(uuid.NewV7())
	mustExec(t, pool, `INSERT INTO blobs (id, sha256, size, mime) VALUES ($1, $2, 10, 'image/png')`,
		blob, hex.EncodeToString(bytes.Repeat([]byte{0x41}, 32)))
	w.Enqueue(ctx, "thumb", blob)

	task, err := q.ClaimTask(ctx) // attempts → 1
	if err != nil {
		t.Fatal(err)
	}
	w.execute(ctx, task)
	var status string
	var lastErr *string
	if err := pool.QueryRow(ctx, `SELECT status, last_error FROM tasks WHERE id = $1`, task.ID).
		Scan(&status, &lastErr); err != nil {
		t.Fatal(err)
	}
	if status != "pending" || lastErr == nil || *lastErr == "" {
		t.Fatalf("attempts 未满应交还队列重试并记错误,got %s/%v", status, lastErr)
	}

	// 手动把 attempts 提到 2,再认领(→3)执行:应进 failed 终态
	mustExec(t, pool, `UPDATE tasks SET attempts = 2 WHERE id = $1`, task.ID)
	task, err = q.ClaimTask(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if task.Attempts != 3 {
		t.Fatalf("认领应把 attempts 提到 3,got %d", task.Attempts)
	}
	w.execute(ctx, task)
	if err := pool.QueryRow(ctx, `SELECT status FROM tasks WHERE id = $1`, task.ID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != "failed" {
		t.Fatalf("重试打满应 failed,got %s", status)
	}

	// 未知任务类型:tasks 表 CHECK 不允许落库,构造内存任务覆盖 default 分支
	w.execute(ctx, store.Task{ID: uuid.Must(uuid.NewV7()), Kind: "bogus", BlobID: blob, Attempts: 3})

	// 其余任务类型对不存在的 blob 都应走失败路径(覆盖 execute 各分支入口)
	for _, kind := range []string{"media_probe", "video_cover", "office_pdf"} {
		w.execute(ctx, store.Task{ID: uuid.Must(uuid.NewV7()), Kind: kind, BlobID: uuid.Must(uuid.NewV7()), Attempts: 3})
	}

	// Enqueue 对不存在的 blob 触发外键错误:只记日志不 panic
	w.Enqueue(ctx, "thumb", uuid.Must(uuid.NewV7()))
}

func TestCleanupTrash(t *testing.T) {
	w, pool, _ := setup(t) // trashTTL = 30 天
	ctx := context.Background()

	user := uuid.Must(uuid.NewV7())
	mustExec(t, pool, `INSERT INTO users (id, username, password_hash, used_bytes) VALUES ($1, 'trash', 'x', 13)`, user)
	mkBlob := func(tag byte, size int) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		mustExec(t, pool, `INSERT INTO blobs (id, sha256, size, ref_count) VALUES ($1, $2, $3, 1)`,
			id, hex.EncodeToString(bytes.Repeat([]byte{tag}, 32)), size)
		return id
	}
	mkTrashNode := func(name string, blob uuid.UUID, deletedAge string) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		mustExec(t, pool,
			`INSERT INTO nodes (id, owner_id, name, kind, blob_id, deleted_at)
			 VALUES ($1, $2, $3, 'file', $4, now() - $5::interval)`,
			id, user, name, blob, deletedAge)
		return id
	}
	blobOld, blobFresh := mkBlob(0x51, 9), mkBlob(0x52, 4)
	nodeOld := mkTrashNode("old.bin", blobOld, "31 days")     // 超保留期,应彻删
	nodeFresh := mkTrashNode("fresh.bin", blobFresh, "1 day") // 保留期内,不动

	if err := w.cleanupTrash(ctx); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM nodes WHERE id = $1`, nodeOld).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("超保留期的回收站节点应被彻删")
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM nodes WHERE id = $1`, nodeFresh).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatal("保留期内的回收站节点不应被动")
	}
	var used int64
	if err := pool.QueryRow(ctx, `SELECT used_bytes FROM users WHERE id = $1`, user).Scan(&used); err != nil {
		t.Fatal(err)
	}
	if used != 4 {
		t.Fatalf("彻删应退配额,used_bytes 应剩 4,got %d", used)
	}
	var refs int
	if err := pool.QueryRow(ctx, `SELECT ref_count FROM blobs WHERE id = $1`, blobOld).Scan(&refs); err != nil {
		t.Fatal(err)
	}
	if refs != 0 {
		t.Fatalf("彻删应减 blob 引用到 0,got %d", refs)
	}
}

func TestCleanupRefreshTokens(t *testing.T) {
	w, pool, _ := setup(t)
	ctx := context.Background()

	user := uuid.Must(uuid.NewV7())
	mustExec(t, pool, `INSERT INTO users (id, username, password_hash) VALUES ($1, 'rt', 'x')`, user)
	mkToken := func(hash, expOffset string) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		mustExec(t, pool,
			`INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at)
			 VALUES ($1, $2, $3, $4, now() + $5::interval)`,
			id, user, hash, uuid.Must(uuid.NewV7()), expOffset)
		return id
	}
	ancient := mkToken("h-ancient", "-31 days") // 过期超 30 天,应删
	recent := mkToken("h-recent", "-1 day")     // 过期但在 30 天取证窗口内,留
	live := mkToken("h-live", "1 day")          // 未过期,留

	if err := w.cleanupRefreshTokens(ctx); err != nil {
		t.Fatal(err)
	}
	count := func(id uuid.UUID) int {
		var n int
		if err := pool.QueryRow(ctx, `SELECT count(*) FROM refresh_tokens WHERE id = $1`, id).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	if count(ancient) != 0 {
		t.Fatal("过期超 30 天的 refresh token 应被删")
	}
	if count(recent) != 1 || count(live) != 1 {
		t.Fatal("取证窗口内与未过期的 refresh token 都应保留")
	}
}

func TestRecomputeStats(t *testing.T) {
	w, pool, _ := setup(t)
	ctx := context.Background()

	user := uuid.Must(uuid.NewV7())
	mustExec(t, pool, `INSERT INTO users (id, username, password_hash) VALUES ($1, 'stats', 'x')`, user)
	mkBlob := func(tag byte, size int) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		mustExec(t, pool, `INSERT INTO blobs (id, sha256, size, ref_count) VALUES ($1, $2, $3, 1)`,
			id, hex.EncodeToString(bytes.Repeat([]byte{tag}, 32)), size)
		return id
	}
	mkNode := func(parent *uuid.UUID, name, kind string, blob *uuid.UUID) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		mustExec(t, pool,
			`INSERT INTO nodes (id, owner_id, parent_id, name, kind, blob_id) VALUES ($1, $2, $3, $4, $5, $6)`,
			id, user, parent, name, kind, blob)
		return id
	}
	// 目录树:root/(f1=5B, sub/(f2=7B));folder 建行时 stats_stale 默认 true
	b1, b2 := mkBlob(0x61, 5), mkBlob(0x62, 7)
	root := mkNode(nil, "root", "folder", nil)
	sub := mkNode(&root, "sub", "folder", nil)
	mkNode(&root, "f1.bin", "file", &b1)
	mkNode(&sub, "f2.bin", "file", &b2)

	if err := w.recomputeStats(ctx); err != nil {
		t.Fatal(err)
	}
	check := func(id uuid.UUID, wantBytes, wantCount int64) {
		t.Helper()
		var bytes_, count int64
		var stale bool
		if err := pool.QueryRow(ctx,
			`SELECT subtree_bytes, subtree_count, stats_stale FROM nodes WHERE id = $1`, id).
			Scan(&bytes_, &count, &stale); err != nil {
			t.Fatal(err)
		}
		if bytes_ != wantBytes || count != wantCount || stale {
			t.Fatalf("子树统计应为 %d 字节/%d 文件且 stale=false,got %d/%d/%v",
				wantBytes, wantCount, bytes_, count, stale)
		}
	}
	check(root, 12, 2) // 递归含子目录
	check(sub, 7, 1)

	// 再跑一轮:无脏目录,应直接返回
	if err := w.recomputeStats(ctx); err != nil {
		t.Fatal(err)
	}
}

// TestFinalizeAgentTransfers 覆盖 agent 传输定稿:成功单进 verifying 并建节点、
// 入队 verify_hash;大小不符的单标 failed;verify 通过后会话转 ready。
func TestFinalizeAgentTransfers(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	uploads := service.NewUploads(pool, obj, service.NewNodes(pool), 16<<20, time.Hour)
	uploads.SetEnqueue(w.Enqueue)
	w.SetUploads(uploads)

	user := uuid.Must(uuid.NewV7())
	mustExec(t, pool, `INSERT INTO users (id, username, password_hash) VALUES ($1, 'agent', 'x')`, user)

	mkTransfer := func(name, key string, content []byte, declaredSize int64, sha *string) uuid.UUID {
		if err := obj.Put(ctx, key, bytes.NewReader(content), int64(len(content)), "application/octet-stream"); err != nil {
			t.Fatal(err)
		}
		id := uuid.Must(uuid.NewV7())
		mustExec(t, pool,
			`INSERT INTO transfer_sessions (id, owner_id, target_name, size, expected_sha256, object_key, transport, status, expires_at)
			 VALUES ($1, $2, $3, $4, $5, $6, 'single_put', 'finalizing', now() + interval '1 hour')`,
			id, user, name, declaredSize, sha, key)
		return id
	}
	content := []byte("agent streamed bytes")
	sum := sha256.Sum256(content)
	sha := hex.EncodeToString(sum[:])
	okSess := mkTransfer("agent.bin", "staging/agent/"+uuid.NewString(), content, int64(len(content)), &sha)
	badSess := mkTransfer("bad.bin", "staging/agent/"+uuid.NewString(), []byte("short"), 999, nil) // 声明大小与实际不符

	if err := w.finalizeAgentTransfers(ctx); err != nil {
		t.Fatal(err)
	}

	// 成功单:verifying + computed sha + 节点已建 + verify_hash 已入队
	var status string
	var computed *string
	var nodeID *uuid.UUID
	if err := pool.QueryRow(ctx,
		`SELECT status, computed_sha256, node_id FROM transfer_sessions WHERE id = $1`, okSess).
		Scan(&status, &computed, &nodeID); err != nil {
		t.Fatal(err)
	}
	if status != "verifying" || computed == nil || *computed != sha || nodeID == nil {
		t.Fatalf("定稿后应 verifying 且记录 sha/节点,got %s/%v/%v", status, computed, nodeID)
	}
	var nodeName string
	if err := pool.QueryRow(ctx, `SELECT name FROM nodes WHERE id = $1`, *nodeID).Scan(&nodeName); err != nil {
		t.Fatal(err)
	}
	if nodeName != "agent.bin" {
		t.Fatalf("应建出目标节点,got %s", nodeName)
	}
	var blobID uuid.UUID
	if err := pool.QueryRow(ctx, `SELECT id FROM blobs WHERE sha256 = $1`, sha).Scan(&blobID); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := pool.QueryRow(ctx,
		`SELECT count(*) FROM tasks WHERE kind = 'verify_hash' AND blob_id = $1`, blobID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatal("定稿应入队 verify_hash 任务")
	}

	// 失败单:failed 且带原因
	var failReason *string
	if err := pool.QueryRow(ctx,
		`SELECT status, fail_reason FROM transfer_sessions WHERE id = $1`, badSess).
		Scan(&status, &failReason); err != nil {
		t.Fatal(err)
	}
	if status != "failed" || failReason == nil || *failReason == "" {
		t.Fatalf("大小不符的单应 failed 且带原因,got %s/%v", status, failReason)
	}

	// verify 走完:blob 已被 Promote 到内容寻址 key,校验通过后会话转 ready
	if err := w.verifyHash(ctx, blobID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT status FROM transfer_sessions WHERE id = $1`, okSess).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != "ready" {
		t.Fatalf("verify 通过后会话应 ready,got %s", status)
	}
}
