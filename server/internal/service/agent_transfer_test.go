package service_test

// agent transfer(MCP 上传)的集成测试:prepare → transfer → complete/abort → cleanup
// 全生命周期,连真 Postgres + MinIO(TEST_DB_URL / TEST_S3_ENDPOINT,未设则跳过)。

import (
	"bytes"
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// svcErrCode 取业务错误码,便于断言落在具体分支。
func svcErrCode(err error) string {
	if se, ok := err.(*service.Error); ok {
		return se.Code
	}
	return ""
}

func TestPrepareAgentUploadValidation(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)

	// 大小非法:0 与超过 1TiB
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", 0, "", nil, "auto", nil); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("size 0 应 INVALID_INPUT,got %v", err)
	}
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", (1<<40)+1, "", nil, "auto", nil); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("超过 1TiB 应 INVALID_INPUT,got %v", err)
	}
	// 非法 sha
	bad := "XYZ"
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", 10, "", &bad, "auto", nil); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("非法 sha 应 INVALID_INPUT,got %v", err)
	}
	// 非法 transport
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", 10, "", nil, "carrier-pigeon", nil); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("非法 transport 应 INVALID_INPUT,got %v", err)
	}
	// 非法 idempotency_key:纯空白 / 超长
	blank := "   "
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", 10, "", nil, "auto", &blank); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("空 idempotency_key 应 INVALID_INPUT,got %v", err)
	}
	long := strings.Repeat("k", 129)
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", 10, "", nil, "auto", &long); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("超长 idempotency_key 应 INVALID_INPUT,got %v", err)
	}
	// 超配额(默认 1GB,见 newAuth)
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "a.txt", 2<<30, "", nil, "single_put", nil); svcErrCode(err) != "QUOTA_EXCEEDED" {
		t.Fatalf("超配额应 QUOTA_EXCEEDED,got %v", err)
	}
}

func TestPrepareAgentUploadInstantAndAutoTransport(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)
	q := store.New(pool)

	// 已 verified 的 blob → 秒传
	data := []byte("agent instant content")
	sha := shaHex(data)
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{
		ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: int64(len(data)), Mime: "text/plain",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := q.MarkBlobVerified(ctx, blob.ID); err != nil {
		t.Fatal(err)
	}
	// sha 归一化:大写 + 前后空白也能命中
	messy := "  " + strings.ToUpper(sha) + " "
	init, err := uploads.PrepareAgentUpload(ctx, owner, nil, "i.txt", int64(len(data)), "", &messy, "auto", nil)
	if err != nil || init.Mode != "instant" || init.Node == nil {
		t.Fatalf("verified blob 应秒传:%+v err=%v", init, err)
	}
	// 同 hash 不同 size → 拒
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "j.txt", int64(len(data))+1, "", &sha, "auto", nil); svcErrCode(err) != "INVALID_INPUT" {
		t.Fatalf("同 hash 不同 size 应 INVALID_INPUT,got %v", err)
	}

	// transport 留空 → auto:小文件走 single_put
	small, err := uploads.PrepareAgentUpload(ctx, owner, nil, "s.bin", 10, "", nil, "", nil)
	if err != nil || small.Mode != "single_put" || small.Transfer == nil || small.Transfer.PutURL == "" {
		t.Fatalf("小文件 auto 应 single_put 且带 PutURL:%+v err=%v", small, err)
	}
	// auto:超过 64MiB 阈值走 multipart(只建会话,不真传字节)
	big, err := uploads.PrepareAgentUpload(ctx, owner, nil, "b.bin", (64<<20)+1, "", nil, "auto", nil)
	if err != nil || big.Mode != "multipart" || big.Transfer.Session.MinioUploadID == nil {
		t.Fatalf("大文件 auto 应 multipart:%+v err=%v", big, err)
	}
	// 5MiB 分片 → 13 片,首次即签全部缺失片
	if big.Transfer.TotalParts != 13 || len(big.Transfer.PartURLs) != 13 {
		t.Fatalf("分片数不符:total=%d urls=%d", big.Transfer.TotalParts, len(big.Transfer.PartURLs))
	}

	// abort:single_put 与 multipart 两条路径都走一遍,重复 abort 是 no-op
	for _, sid := range []uuid.UUID{small.Transfer.Session.ID, big.Transfer.Session.ID} {
		if err := uploads.AbortAgentTransfer(ctx, owner, sid); err != nil {
			t.Fatal(err)
		}
		view, err := uploads.AgentTransfer(ctx, owner, sid, 1, 10)
		if err != nil || view.Session.Status != "aborted" || view.PutURL != "" || len(view.PartURLs) != 0 {
			t.Fatalf("abort 后应 aborted 且不再签 URL:%+v err=%v", view, err)
		}
		if err := uploads.AbortAgentTransfer(ctx, owner, sid); err != nil {
			t.Fatalf("重复 abort 应 no-op:%v", err)
		}
	}
}

func TestPrepareAgentUploadIdempotency(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, nodes, owner := newUploads(t, pool, obj)

	folder, err := nodes.CreateFolder(ctx, owner, nil, "dst")
	if err != nil {
		t.Fatal(err)
	}
	sha := shaHex([]byte("idem content"))
	key := "agent-key-1"
	first, err := uploads.PrepareAgentUpload(ctx, owner, &folder.ID, "f.txt", 100, "", &sha, "single_put", &key)
	if err != nil {
		t.Fatal(err)
	}
	// 同 key 同参数 → 复用同一会话(parent/sha 双非 nil 相等分支)
	again, err := uploads.PrepareAgentUpload(ctx, owner, &folder.ID, "f.txt", 100, "", &sha, "single_put", &key)
	if err != nil || again.Transfer.Session.ID != first.Transfer.Session.ID {
		t.Fatalf("同 key 同参数应复用会话:%+v err=%v", again, err)
	}
	// 同 key 但参数变了 → IDEMPOTENCY_CONFLICT
	otherSha := shaHex([]byte("other"))
	otherFolder, err := nodes.CreateFolder(ctx, owner, nil, "dst2")
	if err != nil {
		t.Fatal(err)
	}
	conflicts := []struct {
		name string
		call func() (*service.AgentUploadInit, error)
	}{
		{"不同 size", func() (*service.AgentUploadInit, error) {
			return uploads.PrepareAgentUpload(ctx, owner, &folder.ID, "f.txt", 101, "", &sha, "single_put", &key)
		}},
		{"不同父目录(nil vs 非 nil)", func() (*service.AgentUploadInit, error) {
			return uploads.PrepareAgentUpload(ctx, owner, nil, "f.txt", 100, "", &sha, "single_put", &key)
		}},
		{"不同父目录(两个非 nil)", func() (*service.AgentUploadInit, error) {
			return uploads.PrepareAgentUpload(ctx, owner, &otherFolder.ID, "f.txt", 100, "", &sha, "single_put", &key)
		}},
		{"不同 sha", func() (*service.AgentUploadInit, error) {
			return uploads.PrepareAgentUpload(ctx, owner, &folder.ID, "f.txt", 100, "", &otherSha, "single_put", &key)
		}},
		{"缺 sha(nil vs 非 nil)", func() (*service.AgentUploadInit, error) {
			return uploads.PrepareAgentUpload(ctx, owner, &folder.ID, "f.txt", 100, "", nil, "single_put", &key)
		}},
		{"不同 transport", func() (*service.AgentUploadInit, error) {
			return uploads.PrepareAgentUpload(ctx, owner, &folder.ID, "f.txt", 100, "", &sha, "multipart", &key)
		}},
	}
	for _, tc := range conflicts {
		if _, err := tc.call(); svcErrCode(err) != "IDEMPOTENCY_CONFLICT" {
			t.Fatalf("%s 应 IDEMPOTENCY_CONFLICT,got %v", tc.name, err)
		}
	}
	// 根目录 + 无 sha 的 key:两侧 nil 也判相等
	key2 := "agent-key-2"
	a1, err := uploads.PrepareAgentUpload(ctx, owner, nil, "g.txt", 50, "", nil, "single_put", &key2)
	if err != nil {
		t.Fatal(err)
	}
	a2, err := uploads.PrepareAgentUpload(ctx, owner, nil, "g.txt", 50, "", nil, "single_put", &key2)
	if err != nil || a2.Transfer.Session.ID != a1.Transfer.Session.ID {
		t.Fatalf("nil parent/sha 也应复用会话:%+v err=%v", a2, err)
	}
}

func TestAgentMultipartLifecycle(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)

	data := bytes.Repeat([]byte{0x5A}, 6<<20) // 6MiB → 5MiB+1MiB 两片
	init, err := uploads.PrepareAgentUpload(ctx, owner, nil, "big.bin", int64(len(data)), "application/octet-stream", nil, "multipart", nil)
	if err != nil || init.Mode != "multipart" || init.Transfer.TotalParts != 2 || len(init.Transfer.PartURLs) != 2 {
		t.Fatalf("multipart 初始化不符:%+v err=%v", init, err)
	}
	sid := init.Transfer.Session.ID

	// 缺片 complete → MISSING_PART
	if _, err := uploads.CompleteAgentTransfer(ctx, owner, sid); svcErrCode(err) != "MISSING_PART" {
		t.Fatalf("缺片应 MISSING_PART,got %v", err)
	}

	// 传第 1 片:非法分页参数按默认容错,已传片可见,只签缺失片
	httpPut(t, init.Transfer.PartURLs[1], data[:5<<20])
	view, err := uploads.AgentTransfer(ctx, owner, sid, 0, 0)
	if err != nil || len(view.UploadedParts) != 1 || view.UploadedParts[0] != 1 {
		t.Fatalf("已传分片应为 [1]:%+v err=%v", view, err)
	}
	u2, ok := view.PartURLs[2]
	if !ok || len(view.PartURLs) != 1 {
		t.Fatalf("应只签缺失的第 2 片:%v", view.PartURLs)
	}

	// 越权 / 不存在 → NOT_FOUND
	if _, err := uploads.AgentTransfer(ctx, uuid.Must(uuid.NewV7()), sid, 1, 1); err != service.ErrNotFound {
		t.Fatalf("他人会话应 NOT_FOUND,got %v", err)
	}
	if _, err := uploads.CompleteAgentTransfer(ctx, owner, uuid.Must(uuid.NewV7())); err != service.ErrNotFound {
		t.Fatalf("complete 不存在的会话应 NOT_FOUND,got %v", err)
	}
	if err := uploads.AbortAgentTransfer(ctx, owner, uuid.Must(uuid.NewV7())); err != service.ErrNotFound {
		t.Fatalf("abort 不存在的会话应 NOT_FOUND,got %v", err)
	}

	// 补齐后 complete → finalizing
	httpPut(t, u2, data[5<<20:])
	sess, err := uploads.CompleteAgentTransfer(ctx, owner, sid)
	if err != nil || sess.Status != "finalizing" {
		t.Fatalf("补齐后应 finalizing:%+v err=%v", sess, err)
	}

	// finalizing 之后:视图不再签 URL;abort 是 no-op;重复 complete 原样返回
	view, err = uploads.AgentTransfer(ctx, owner, sid, 1, 10)
	if err != nil || view.PutURL != "" || len(view.PartURLs) != 0 {
		t.Fatalf("finalizing 视图不应再签 URL:%+v err=%v", view, err)
	}
	if err := uploads.AbortAgentTransfer(ctx, owner, sid); err != nil {
		t.Fatalf("finalizing 后 abort 应 no-op:%v", err)
	}
	sess2, err := uploads.CompleteAgentTransfer(ctx, owner, sid)
	if err != nil || sess2.Status != "finalizing" {
		t.Fatalf("重复 complete 应原样返回:%+v err=%v", sess2, err)
	}

	// worker 定稿 → verifying,节点已建且服务端 sha 已算
	processed, err := uploads.ProcessNextAgentTransfer(ctx)
	if err != nil || !processed {
		t.Fatalf("定稿任务未处理:processed=%v err=%v", processed, err)
	}
	view, err = uploads.AgentTransfer(ctx, owner, sid, 1, 1)
	if err != nil || view.Session.Status != "verifying" || view.Session.NodeID == nil || view.Session.ComputedSha256 == nil {
		t.Fatalf("定稿状态不符:%+v err=%v", view, err)
	}
	if *view.Session.ComputedSha256 != shaHex(data) {
		t.Fatalf("服务端 sha 不符:%s", *view.Session.ComputedSha256)
	}
}

func TestAgentSinglePutErrorPathsAndCompleting(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)

	prepare := func(name string, size int64) *service.AgentTransferView {
		t.Helper()
		init, err := uploads.PrepareAgentUpload(ctx, owner, nil, name, size, "text/plain", nil, "single_put", nil)
		if err != nil {
			t.Fatal(err)
		}
		return init.Transfer
	}
	setCompleting := func(sid uuid.UUID) {
		t.Helper()
		if _, err := pool.Exec(ctx, `UPDATE transfer_sessions SET status = 'completing' WHERE id = $1`, sid); err != nil {
			t.Fatal(err)
		}
	}

	// 未上传就 complete → UPLOAD_MISSING
	empty := prepare("e.txt", 10)
	if _, err := uploads.CompleteAgentTransfer(ctx, owner, empty.Session.ID); svcErrCode(err) != "UPLOAD_MISSING" {
		t.Fatalf("对象缺失应 UPLOAD_MISSING,got %v", err)
	}

	// 实际大小与声明不符 → SIZE_MISMATCH
	mis := prepare("m.txt", 10)
	httpPut(t, mis.PutURL, []byte("short")) // 只有 5 字节
	if _, err := uploads.CompleteAgentTransfer(ctx, owner, mis.Session.ID); svcErrCode(err) != "SIZE_MISMATCH" {
		t.Fatalf("大小不符应 SIZE_MISMATCH,got %v", err)
	}

	// completing 状态恢复:对象在且大小对 → 直接 finalizing
	data := []byte("completing recover data")
	okv := prepare("c.txt", int64(len(data)))
	httpPut(t, okv.PutURL, data)
	setCompleting(okv.Session.ID)
	sess, err := uploads.CompleteAgentTransfer(ctx, owner, okv.Session.ID)
	if err != nil || sess.Status != "finalizing" {
		t.Fatalf("completing 且对象完好应直达 finalizing:%+v err=%v", sess, err)
	}

	// completing 但对象大小不符 → SIZE_MISMATCH(completing 分支)
	m2 := prepare("m2.txt", 10)
	httpPut(t, m2.PutURL, []byte("short"))
	setCompleting(m2.Session.ID)
	if _, err := uploads.CompleteAgentTransfer(ctx, owner, m2.Session.ID); svcErrCode(err) != "SIZE_MISMATCH" {
		t.Fatalf("completing 大小不符应 SIZE_MISMATCH,got %v", err)
	}

	// completing 但对象丢了 → 回退 uploading 再报 UPLOAD_MISSING
	gone := prepare("g.txt", 10)
	setCompleting(gone.Session.ID)
	if _, err := uploads.CompleteAgentTransfer(ctx, owner, gone.Session.ID); svcErrCode(err) != "UPLOAD_MISSING" {
		t.Fatalf("completing 且对象缺失应 UPLOAD_MISSING,got %v", err)
	}
	view, err := uploads.AgentTransfer(ctx, owner, gone.Session.ID, 1, 1)
	if err != nil || view.Session.Status != "uploading" {
		t.Fatalf("对象缺失后会话应回退 uploading:%+v err=%v", view, err)
	}
}

func TestAgentTransferReservationCleanupAndLimits(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)

	// 在途会话的预留字节参与配额判定:900MB 在途 + 200MB 新请求 > 1GB
	holder, err := uploads.PrepareAgentUpload(ctx, owner, nil, "hold.bin", 900<<20, "", nil, "single_put", nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "more.bin", 200<<20, "", nil, "single_put", nil); svcErrCode(err) != "QUOTA_EXCEEDED" {
		t.Fatalf("预留后超配额应 QUOTA_EXCEEDED,got %v", err)
	}

	// 过期清理:single_put 与 multipart 会话都拨到过期
	mp, err := uploads.PrepareAgentUpload(ctx, owner, nil, "mp.bin", 6<<20, "", nil, "multipart", nil)
	if err != nil {
		t.Fatal(err)
	}
	expired := []uuid.UUID{holder.Transfer.Session.ID, mp.Transfer.Session.ID}
	if _, err := pool.Exec(ctx,
		`UPDATE transfer_sessions SET expires_at = now() - interval '1 hour' WHERE id = ANY($1)`, expired); err != nil {
		t.Fatal(err)
	}
	if err := uploads.CleanupAgentTransfers(ctx); err != nil {
		t.Fatal(err)
	}
	for _, sid := range expired {
		view, err := uploads.AgentTransfer(ctx, owner, sid, 1, 1)
		if err != nil || view.Session.Status != "aborted" || view.Session.FailReason == nil || *view.Session.FailReason != "会话过期" {
			t.Fatalf("过期会话应 aborted(会话过期):%+v err=%v", view, err)
		}
	}
	// 没有过期会话时清理是 no-op
	if err := uploads.CleanupAgentTransfers(ctx); err != nil {
		t.Fatalf("空清理应无报错:%v", err)
	}

	// 会话数上限:20 个在途后第 21 个拒
	for i := 0; i < 20; i++ {
		if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, fmt.Sprintf("n%02d.bin", i), 1024, "", nil, "single_put", nil); err != nil {
			t.Fatalf("第 %d 个会话应成功:%v", i+1, err)
		}
	}
	if _, err := uploads.PrepareAgentUpload(ctx, owner, nil, "overflow.bin", 1024, "", nil, "single_put", nil); svcErrCode(err) != "TOO_MANY_SESSIONS" {
		t.Fatalf("超会话上限应 TOO_MANY_SESSIONS,got %v", err)
	}
}
