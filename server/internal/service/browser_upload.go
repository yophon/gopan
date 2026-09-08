package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/minio/minio-go/v7"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// Complete seals the session's private object and schedules durable background
// verification. Clients retry UPLOAD_PROCESSING until the committed node is ready.
// Repeated calls return the same node; they never create or charge it twice.
func (s *Uploads) Complete(ctx context.Context, owner, id uuid.UUID, etags map[int]string) (*store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	sess, err := q.TryLockUploadSession(ctx, store.TryLockUploadSessionParams{ID: id, OwnerID: owner})
	if isLockUnavailable(err) {
		return nil, errf("UPLOAD_PROCESSING", "正在处理上传会话")
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if sess.Status == "done" && sess.NodeID != nil {
		n, err := q.GetNode(ctx, *sess.NodeID)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return &n, err
	}
	if sess.Status == "failed" && sess.FailCode != nil && sess.FailReason != nil {
		return nil, errf(*sess.FailCode, "%s", *sess.FailReason)
	}
	if sess.Status != "uploading" && sess.Status != "completing" {
		return nil, errf("BAD_SESSION_STATE", "会话状态是 %s,不能完成", sess.Status)
	}
	if sess.ObjectKey == nil || !sess.ExpiresAt.Time.After(time.Now()) {
		return nil, errf("UPLOAD_EXPIRED", "上传会话已过期或需要重新上传")
	}
	if sess.Status == "completing" {
		return nil, errf("UPLOAD_PROCESSING", "正在校验上传内容")
	}

	// The S3 operation can succeed while its response or the DB commit is lost.
	// A sealed staging object is immutable (the client only has UploadPart URLs),
	// so its presence is enough to resume without merging the multipart again.
	key := *sess.ObjectKey
	_, err = s.obj.Stat(ctx, key)
	if err != nil {
		if !objectMissing(err) {
			return nil, err
		}
		listed, err := s.obj.ListParts(ctx, key, sess.MinioUploadID)
		if err != nil {
			return nil, err
		}
		total := int((sess.Size + int64(sess.PartSize) - 1) / int64(sess.PartSize))
		parts := make([]objstore.Part, 0, total)
		for n := 1; n <= total; n++ {
			etag, ok := etags[n]
			if !ok {
				etag, ok = listed[n]
			}
			if !ok {
				return nil, errf("MISSING_PART", "第 %d 片未上传", n)
			}
			parts = append(parts, objstore.Part{Number: n, ETag: etag})
		}
		if err := s.obj.CompleteMultipart(ctx, key, sess.MinioUploadID, parts); err != nil {
			return nil, errf("COMPLETE_FAILED", "合并分片失败,请重试")
		}
	}
	if err := q.SetUploadSessionStatus(ctx, store.SetUploadSessionStatusParams{ID: id, OwnerID: owner, Status: "completing"}); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return nil, errf("UPLOAD_PROCESSING", "正在校验上传内容")
}

func objectMissing(err error) bool {
	switch minio.ToErrorResponse(err).Code {
	case "NoSuchKey", "NoSuchObject", "NotFound":
		return true
	}
	return false
}

func isLockUnavailable(err error) bool {
	var sqlErr interface{ SQLState() string }
	return errors.As(err, &sqlErr) && sqlErr.SQLState() == "55P03"
}

// ProcessNextBrowserUpload holds only the session row while hashing. Rollback
// leaves it claimable after transient failures or a process restart. SKIP LOCKED
// allows other workers to process other sessions, without duplicate finalization.
func (s *Uploads) ProcessNextBrowserUpload(ctx context.Context) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	sess, err := q.ClaimCompletingUpload(ctx)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}

	// A savepoint lets deterministic business failures roll back all accounting
	// and node changes while recording the terminal failure under the same lock.
	work, err := tx.Begin(ctx)
	if err != nil {
		return false, err
	}
	_, err = s.finalizeBrowserUpload(ctx, work, sess)
	if err != nil {
		if rollbackErr := work.Rollback(ctx); rollbackErr != nil {
			return false, rollbackErr
		}
		var se *Error
		if !errors.As(err, &se) {
			return false, err
		}
		if err := q.FailUploadSession(ctx, store.FailUploadSessionParams{ID: sess.ID, FailCode: &se.Code, FailReason: &se.Message}); err != nil {
			return false, err
		}
	} else if err := work.Commit(ctx); err != nil {
		return false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return false, err
	}
	// Terminal state is durable before removing staging. Cleanup retries removal
	// after crashes or storage errors; it never deletes the shared blob key.
	_ = s.cleanUploadObject(ctx, sess)
	return true, nil
}

func (s *Uploads) finalizeBrowserUpload(ctx context.Context, tx pgx.Tx, sess store.UploadSession) (*store.Node, error) {
	r, err := s.obj.Open(ctx, *sess.ObjectKey)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	size, readErr := io.Copy(h, io.LimitReader(r, sess.Size+1))
	closeErr := r.Close()
	if readErr != nil {
		return nil, readErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if size != sess.Size {
		return nil, errf("SIZE_MISMATCH", "上传内容大小与声明不一致")
	}
	if hex.EncodeToString(h.Sum(nil)) != sess.Sha256 {
		return nil, errf("HASH_MISMATCH", "上传内容与声明的 SHA-256 不一致")
	}
	q := s.q.WithTx(tx)
	u, err := q.GetUserByIDForUpdate(ctx, sess.OwnerID)
	if err != nil {
		return nil, err
	}
	if u.DisabledAt.Valid {
		return nil, errf("UNAUTHENTICATED", "账号已停用")
	}
	if u.UsedBytes+sess.Size > u.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}
	if sess.TargetParent != nil {
		n, err := q.GetNode(ctx, *sess.TargetParent)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		if n.OwnerID != sess.OwnerID || n.Kind != "folder" || n.DeletedAt.Valid {
			return nil, errf("NOT_A_FOLDER", "目标文件夹不可用")
		}
	}
	// Upsert locks the shared hash row before publication. Only verified bytes
	// can reach this step; an existing verified object is never overwritten.
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: sess.Sha256, Size: size, Mime: mimeByName(sess.TargetName)})
	if err != nil {
		return nil, err
	}
	if blob.Size != size {
		return nil, errf("SIZE_MISMATCH", "已有文件的大小与上传内容不一致")
	}
	n, err := q.CreateNode(ctx, store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: sess.OwnerID, ParentID: sess.TargetParent, Name: sess.TargetName, Kind: "file", BlobID: &blob.ID})
	if isUniqueViolation(err) {
		return nil, errf("NAME_CONFLICT", "目标位置已有同名文件,请重新上传")
	}
	if err != nil {
		return nil, err
	}
	if !blob.Verified {
		if err := s.obj.Promote(ctx, objstore.BlobKey(sess.Sha256), *sess.ObjectKey, size, mimeByName(sess.TargetName)); err != nil {
			return nil, err
		}
		if err := q.MarkBlobVerified(ctx, blob.ID); err != nil {
			return nil, err
		}
	}
	if err := q.IncrementBlobRef(ctx, blob.ID); err != nil {
		return nil, err
	}
	if err := q.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: sess.OwnerID, UsedBytes: size}); err != nil {
		return nil, err
	}
	if err := q.MarkAncestorsStale(ctx, n.ID); err != nil {
		return nil, err
	}
	if err := q.FinishUploadSession(ctx, store.FinishUploadSessionParams{ID: sess.ID, NodeID: &n.ID}); err != nil {
		return nil, err
	}
	// Persist the derivative trigger with the node, so a restart cannot lose it.
	if err := q.EnqueueTask(ctx, store.EnqueueTaskParams{ID: uuid.Must(uuid.NewV7()), Kind: "verify_hash", BlobID: blob.ID}); err != nil {
		return nil, err
	}
	return &n, nil
}

func (s *Uploads) cleanUploadObject(ctx context.Context, sess store.UploadSession) error {
	if sess.ObjectKey == nil {
		return nil
	}
	if err := s.obj.Remove(ctx, *sess.ObjectKey); err != nil {
		return err
	}
	return s.q.ClearUploadObjectKey(ctx, sess.ID)
}

func (s *Uploads) Abort(ctx context.Context, owner, id uuid.UUID) error {
	return s.abortBrowserUpload(ctx, owner, id, false)
}

func (s *Uploads) abortBrowserUpload(ctx context.Context, owner, id uuid.UUID, expiredOnly bool) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	sess, err := q.GetUploadSessionForUpdate(ctx, store.GetUploadSessionForUpdateParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if expiredOnly && sess.ExpiresAt.Time.After(time.Now()) {
		return nil
	}
	if sess.Status == "uploading" || sess.Status == "completing" {
		key := objstore.BlobKey(sess.Sha256)
		if sess.ObjectKey != nil {
			key = *sess.ObjectKey
		}
		if err := s.obj.AbortMultipart(ctx, key, sess.MinioUploadID); err != nil && minio.ToErrorResponse(err).Code != "NoSuchUpload" {
			return err
		}
		if err := q.SetUploadSessionStatus(ctx, store.SetUploadSessionStatusParams{ID: id, OwnerID: owner, Status: "aborted"}); err != nil {
			return err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	// For pre-staging sessions only abort multipart; never remove blobs/{sha}.
	return s.cleanUploadObject(ctx, sess)
}

func (s *Uploads) CleanupBrowserUploads(ctx context.Context) (int, error) {
	sessions, err := s.q.ListExpiredSessions(ctx)
	if err != nil {
		return 0, err
	}
	for i, sess := range sessions {
		if err := s.abortBrowserUpload(ctx, sess.OwnerID, sess.ID, true); err != nil {
			return i, err
		}
	}
	return len(sessions), nil
}
