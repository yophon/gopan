// Package worker:进程内任务池。任务持久化在 tasks 表,ClaimTask 用
// FOR UPDATE SKIP LOCKED 抢占,重启后 pending/滞留 running 自动恢复。
package worker

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/metrics"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type Pool struct {
	pool      *pgxpool.Pool
	q         *store.Queries
	obj       *objstore.Store
	nodes     *service.Nodes
	uploads   *service.Uploads
	collects  *service.Collects
	archives  *service.Archives
	trashTTL  time.Duration
	wake      chan struct{}
	ffmpeg    string
	ffprobe   string
	gotenberg string
	// 内容搜索:是否提取文字、pdftotext 路径(空 = PDF 不提)
	textIndex bool
	pdftotext string
	// 通知与影音的周期维护(doc/22)
	notify *service.Notifier
	media  *service.Media
	// 动态清理、订阅检查(doc/23)
	activities *service.Activities
	feeds      *service.Feeds
	// 历史版本到期清理(doc/24)
	versions *service.Versions
}

func New(pool *pgxpool.Pool, obj *objstore.Store, nodes *service.Nodes, trashTTL time.Duration, ffmpeg, ffprobe, gotenberg string) *Pool {
	return &Pool{pool: pool, q: store.New(pool), obj: obj, nodes: nodes, trashTTL: trashTTL,
		wake: make(chan struct{}, 1), ffmpeg: ffmpeg, ffprobe: ffprobe, gotenberg: gotenberg}
}

func (w *Pool) SetUploads(uploads *service.Uploads) {
	w.uploads = uploads
}

func (w *Pool) SetCollects(collects *service.Collects) {
	w.collects = collects
}

// SetArchives 压缩包目录索引(doc/21 四.2):注入后才建索引、才跑回填。
func (w *Pool) SetArchives(archives *service.Archives) {
	w.archives = archives
}

// SetV35 动态的每日清理、订阅的定时检查(doc/23)。
func (w *Pool) SetV35(a *service.Activities, f *service.Feeds) {
	w.activities, w.feeds = a, f
}

// SetVersions 历史版本的到期清理(doc/24)。
func (w *Pool) SetVersions(v *service.Versions) {
	w.versions = v
}

func (w *Pool) SetNotify(n *service.Notifier, m *service.Media) {
	w.notify, w.media = n, m
}

// Enqueue 入队并唤醒 worker(注入给 service 用)。
func (w *Pool) Enqueue(ctx context.Context, kind string, blobID uuid.UUID) {
	if err := w.q.EnqueueTask(ctx, store.EnqueueTaskParams{
		ID: uuid.Must(uuid.NewV7()), Kind: kind, BlobID: blobID,
	}); err != nil {
		slog.Error("enqueue task", "kind", kind, "err", err)
		return
	}
	select {
	case w.wake <- struct{}{}:
	default:
	}
}

// Run 启动 n 个任务 worker + 两个周期清理 goroutine,阻塞到 ctx 取消。
func (w *Pool) Run(ctx context.Context, n int) {
	for i := 0; i < n; i++ {
		go w.taskLoop(ctx)
	}
	go w.periodic(ctx, time.Hour, "session-cleanup", w.cleanupSessions)
	if w.uploads != nil {
		go w.periodic(ctx, 2*time.Second, "browser-upload-finalize", w.finalizeBrowserUploads)
		go w.periodic(ctx, 2*time.Second, "agent-transfer-finalize", w.finalizeAgentTransfers)
		go w.periodic(ctx, time.Hour, "agent-transfer-cleanup", w.uploads.CleanupAgentTransfers)
	}
	go w.periodic(ctx, time.Hour, "blob-gc", w.gcBlobs)
	go w.periodic(ctx, time.Hour, "trash-cleanup", w.cleanupTrash)
	go w.periodic(ctx, 24*time.Hour, "refresh-cleanup", w.cleanupRefreshTokens)
	go w.periodic(ctx, 24*time.Hour, "auth-session-cleanup", w.cleanupAuthSessions)
	go w.periodic(ctx, 24*time.Hour, "share-visit-cleanup", w.cleanupShareVisits)
	if w.collects != nil {
		go w.periodic(ctx, 24*time.Hour, "collect-visitor-cleanup", w.collects.CleanupVisitors)
	}
	go w.periodic(ctx, time.Minute, "subtree-stats", w.recomputeStats)
	go w.periodic(ctx, 5*time.Minute, "task-reap", w.reapTasks)
	if w.textIndex {
		go w.periodic(ctx, time.Minute, "text-backfill", w.backfillText)
	}
	if w.archives != nil {
		go w.periodic(ctx, time.Minute, "archive-backfill", w.backfillArchives)
	}
	if w.notify != nil {
		go w.periodic(ctx, 24*time.Hour, "notification-cleanup", func(ctx context.Context) error { w.notify.Cleanup(ctx); return nil })
		go w.periodic(ctx, time.Hour, "heartbeat-check", func(ctx context.Context) error { w.notify.CheckHeartbeats(ctx); return nil })
	}
	if w.media != nil {
		go w.periodic(ctx, 24*time.Hour, "play-progress-cleanup", w.media.Cleanup)
	}
	if w.activities != nil {
		go w.periodic(ctx, 24*time.Hour, "activity-cleanup", w.activities.Cleanup)
	}
	if w.feeds != nil {
		go w.periodic(ctx, 5*time.Minute, "feed-check", w.feeds.CheckDue)
	}
	if w.versions != nil {
		go w.periodic(ctx, time.Hour, "version-cleanup", w.cleanupVersions)
	}
	<-ctx.Done()
}

func (w *Pool) taskLoop(ctx context.Context) {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		for {
			task, err := w.q.ClaimTask(ctx)
			if err != nil {
				if !errors.Is(err, pgx.ErrNoRows) && ctx.Err() == nil {
					slog.Error("claim task", "err", err)
				}
				break
			}
			w.execute(ctx, task)
		}
		select {
		case <-ctx.Done():
			return
		case <-w.wake:
		case <-tick.C:
		}
	}
}

func (w *Pool) execute(ctx context.Context, t store.Task) {
	var err error
	switch t.Kind {
	case "verify_hash":
		err = w.verifyHash(ctx, t.BlobID)
	case "thumb":
		err = w.makeThumbs(ctx, t.BlobID)
	case "media_probe":
		err = w.probeMedia(ctx, t.BlobID)
	case "video_cover":
		err = w.makeVideoCover(ctx, t.BlobID)
	case "office_pdf":
		err = w.makeOfficePDF(ctx, t.BlobID)
	case "extract_text":
		err = w.extractText(ctx, t.BlobID)
	case "archive_index":
		if w.archives != nil {
			err = w.archives.BuildIndex(ctx, t.BlobID)
		}
	default:
		err = fmt.Errorf("未知任务类型 %s", t.Kind)
	}
	status, errText := "done", (*string)(nil)
	if err != nil {
		status = "failed"
		msg := err.Error()
		errText = &msg
		slog.Error("task failed", "kind", t.Kind, "blob", t.BlobID, "attempt", t.Attempts, "err", err)
		if t.Attempts < 3 {
			status = "pending" // 交还队列重试
		}
	}
	// 停机时 ctx 已取消:收尾仍要写回,否则任务停在 running,要等 5 分钟才被重新领
	fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	if ferr := w.q.FinishTask(fctx, store.FinishTaskParams{ID: t.ID, Status: status, LastError: errText}); ferr != nil {
		slog.Error("finish task", "err", ferr)
	}
	metrics.Tasks.WithLabelValues(t.Kind, status).Inc()
}

// verifyHash 流式重算对象 sha256。不符 = 客户端谎报,删对象、节点、blob,标记会话失败。
func (w *Pool) verifyHash(ctx context.Context, blobID uuid.UUID) error {
	blob, err := w.q.GetBlob(ctx, blobID)
	if err != nil {
		return err
	}
	if blob.Verified {
		// Browser staging is verified before publication. Still deliver the
		// durable derivative trigger, and finish late legacy/Agent references.
		if err := w.q.MarkSessionsVerifyResult(ctx, store.MarkSessionsVerifyResultParams{Sha256: &blob.Sha256, Status: "done"}); err != nil {
			return err
		}
		if err := w.q.MarkTransferReadyBySha(ctx, &blob.Sha256); err != nil {
			return err
		}
		w.enqueueDerivatives(ctx, blob)
		return nil
	}
	key := objstore.BlobKey(blob.Sha256)
	rc, err := w.obj.Open(ctx, key)
	if err != nil {
		return err
	}
	defer rc.Close()
	h := sha256.New()
	size, err := io.Copy(h, rc)
	if err != nil {
		return err
	}
	got := hex.EncodeToString(h.Sum(nil))

	if got == blob.Sha256 && size == blob.Size {
		if err := w.q.MarkBlobVerified(ctx, blobID); err != nil {
			return err
		}
		reason := ""
		_ = w.q.MarkSessionsVerifyResult(ctx, store.MarkSessionsVerifyResultParams{
			Sha256: &blob.Sha256, Status: "done", FailReason: &reason,
		})
		_ = w.q.MarkTransferReadyBySha(ctx, &blob.Sha256)
		slog.Info("blob verified", "sha256", blob.Sha256, "size", size)
		w.enqueueDerivatives(ctx, blob)
		return nil
	}

	// hash 造假或对象损坏:全链路回收
	slog.Warn("hash mismatch, purging", "declared", blob.Sha256, "actual", got, "size", size)
	nodes, err := w.q.ListNodesByBlob(ctx, &blobID)
	if err != nil {
		return err
	}
	tx, err := w.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	qtx := w.q.WithTx(tx)
	// 持有这份内容的历史版本作废;被清掉的节点自己的其它版本也一起退账(doc/24)
	if err := w.releaseVersions(ctx, qtx, func() ([]store.NodeVersion, error) { return qtx.DeleteVersionsByBlob(ctx, blobID) }); err != nil {
		return err
	}
	for _, n := range nodes {
		if err := qtx.MarkAncestorsStale(ctx, n.ID); err != nil {
			return err
		}
		if err := w.releaseVersions(ctx, qtx, func() ([]store.NodeVersion, error) {
			return qtx.PurgeSubtreeVersions(ctx, store.PurgeSubtreeVersionsParams{ID: n.ID, OwnerID: n.OwnerID})
		}); err != nil {
			return err
		}
		if _, err := qtx.PurgeSubtree(ctx, store.PurgeSubtreeParams{ID: n.ID, OwnerID: n.OwnerID}); err != nil {
			return err
		}
		if err := qtx.SubtractUsedBytes(ctx, store.SubtractUsedBytesParams{ID: n.OwnerID, UsedBytes: blob.Size}); err != nil {
			return err
		}
	}
	if err := qtx.DeleteBlob(ctx, blobID); err != nil {
		return err
	}
	reason := "服务端 hash 校验失败"
	if err := qtx.MarkSessionsVerifyResult(ctx, store.MarkSessionsVerifyResultParams{
		Sha256: &blob.Sha256, Status: "failed", FailReason: &reason,
	}); err != nil {
		return err
	}
	if err := qtx.MarkTransfersFailedBySha(ctx, store.MarkTransfersFailedByShaParams{
		ComputedSha256: &blob.Sha256, FailReason: &reason,
	}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	return w.obj.Remove(ctx, key)
}

// releaseVersions 删一批版本(del 在同一事务里执行)并退引用退配额。
func (w *Pool) releaseVersions(ctx context.Context, q *store.Queries, del func() ([]store.NodeVersion, error)) error {
	gone, err := del()
	if err != nil {
		return err
	}
	return service.ReleaseVersions(ctx, q, gone)
}

func (w *Pool) finalizeAgentTransfers(ctx context.Context) error {
	for {
		processed, err := w.uploads.ProcessNextAgentTransfer(ctx)
		if err != nil || !processed {
			return err
		}
	}
}

func (w *Pool) finalizeBrowserUploads(ctx context.Context) error {
	for {
		processed, err := w.uploads.ProcessNextBrowserUpload(ctx)
		if err != nil || !processed {
			return err
		}
	}
}

func (w *Pool) periodic(ctx context.Context, every time.Duration, name string, fn func(context.Context) error) {
	tick := time.NewTicker(every)
	defer tick.Stop()
	for {
		if err := fn(ctx); err != nil && ctx.Err() == nil {
			slog.Error("periodic job failed", "job", name, "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

// cleanupSessions 中止过期上传会话,释放 MinIO 的 multipart 暂存。
func (w *Pool) cleanupSessions(ctx context.Context) error {
	uploads := w.uploads
	if uploads == nil {
		uploads = service.NewUploads(w.pool, w.obj, w.nodes, 0, 0)
	}
	n, err := uploads.CleanupBrowserUploads(ctx)
	if err != nil {
		return err
	}
	if n > 0 {
		slog.Info("expired sessions cleaned", "count", n)
		metrics.Cleanup.WithLabelValues("sessions").Add(float64(n))
	}
	return nil
}

// cleanupTrash 彻删回收站里超过保留期的内容(退配额、减引用,blob 由 GC 接手)。
func (w *Pool) cleanupTrash(ctx context.Context) error {
	n, err := w.nodes.PurgeExpiredTrash(ctx, w.trashTTL)
	if n > 0 {
		slog.Info("trash cleanup", "purged_roots", n, "ttl", w.trashTTL)
		metrics.Cleanup.WithLabelValues("trash").Add(float64(n))
	}
	return err
}

// cleanupVersions 清掉换下来超过保留期的历史版本(退配额、减引用,blob 由 GC 接手)。
func (w *Pool) cleanupVersions(ctx context.Context) error {
	n, err := w.versions.CleanupExpired(ctx)
	if n > 0 {
		slog.Info("version cleanup", "deleted", n)
		metrics.Cleanup.WithLabelValues("versions").Add(float64(n))
	}
	return err
}

// cleanupShareVisits 清掉 180 天前的分享访问记录。所以对外说的"访问次数"
// 是近 180 天的语义 —— 前端说明里要写清楚,别当成历史总量。
func (w *Pool) cleanupShareVisits(ctx context.Context) error {
	n, err := w.q.DeleteExpiredShareVisits(ctx)
	if n > 0 {
		slog.Info("share visits cleanup", "deleted", n)
		metrics.Cleanup.WithLabelValues("share_visits").Add(float64(n))
	}
	return err
}

// reapTasks 把用完重试次数后中断在 running 的任务落到 failed(见 FailExhaustedTasks)。
func (w *Pool) reapTasks(ctx context.Context) error {
	n, err := w.q.FailExhaustedTasks(ctx)
	if n > 0 {
		slog.Warn("tasks reaped", "failed", n)
	}
	return err
}

// cleanupAuthSessions 删掉 90 天没活动的登录设备记录。会话列表只对近期有意义。
func (w *Pool) cleanupAuthSessions(ctx context.Context) error {
	n, err := w.q.DeleteStaleSessions(ctx)
	if n > 0 {
		slog.Info("auth sessions cleanup", "deleted", n)
		metrics.Cleanup.WithLabelValues("auth_sessions").Add(float64(n))
	}
	return err
}

// cleanupRefreshTokens 删过期超 30 天的 refresh 行。留 30 天余量:
// 重用检测靠"已用 token 再现"识别泄露,过期即删会丢取证窗口。
func (w *Pool) cleanupRefreshTokens(ctx context.Context) error {
	n, err := w.q.DeleteExpiredRefreshTokens(ctx)
	if n > 0 {
		slog.Info("refresh tokens cleanup", "deleted", n)
		metrics.Cleanup.WithLabelValues("refresh_tokens").Add(float64(n))
	}
	return err
}

// recomputeStats 重算被写路径标脏的文件夹子树统计(方案 B:最终一致)。
// 单语句 UPDATE 靠行锁与并发标脏串行化;重算窗口内的新变更会再次置脏,下一轮修正。
func (w *Pool) recomputeStats(ctx context.Context) error {
	const batch = 200
	for {
		ids, err := w.q.ListStaleFolders(ctx, batch)
		if err != nil {
			return err
		}
		for _, id := range ids {
			if err := w.q.RecomputeFolderStats(ctx, id); err != nil {
				return err
			}
		}
		if len(ids) > 0 {
			metrics.Cleanup.WithLabelValues("subtree_stats").Add(float64(len(ids)))
		}
		if len(ids) < batch {
			return nil
		}
	}
}

// gcBlobs 删除 ref_count=0 且过宽限期(24h)的 blob 及其对象。
func (w *Pool) gcBlobs(ctx context.Context) error {
	blobs, err := w.q.ListGCableBlobs(ctx)
	if err != nil {
		return err
	}
	for _, b := range blobs {
		// 派生物对象一起清(derivatives 行随 blob 级联删,MinIO 对象要显式删)
		if ds, err := w.q.GetDerivatives(ctx, b.ID); err == nil {
			for _, d := range ds {
				if err := w.obj.Remove(ctx, d.MinioKey); err != nil {
					slog.Error("gc remove derivative", "key", d.MinioKey, "err", err)
				}
			}
		}
		if err := w.obj.Remove(ctx, objstore.BlobKey(b.Sha256)); err != nil {
			slog.Error("gc remove object", "sha256", b.Sha256, "err", err)
			continue
		}
		if err := w.q.DeleteBlob(ctx, b.ID); err != nil {
			return err
		}
	}
	if len(blobs) > 0 {
		slog.Info("blobs gc", "count", len(blobs))
		metrics.Cleanup.WithLabelValues("blobs").Add(float64(len(blobs)))
	}
	return nil
}
