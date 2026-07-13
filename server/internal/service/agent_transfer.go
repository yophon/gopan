package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

const (
	maxAgentUploadSize = int64(1 << 40)
	autoSinglePutLimit = int64(64 << 20)
	maxPartURLsPerCall = 100
)

type AgentUploadInit struct {
	Mode     string
	Node     *store.Node
	Transfer *AgentTransferView
}

type AgentTransferView struct {
	Session       store.TransferSession
	PutURL        string
	PartURLs      map[int]string
	UploadedParts []int
	TotalParts    int
}

func (s *Uploads) PrepareAgentUpload(
	ctx context.Context,
	owner uuid.UUID,
	parentID *uuid.UUID,
	name string,
	size int64,
	contentType string,
	declaredSHA *string,
	transport string,
	idempotencyKey *string,
) (*AgentUploadInit, error) {
	if size <= 0 || size > maxAgentUploadSize {
		return nil, errf("INVALID_INPUT", "文件大小非法")
	}
	if declaredSHA != nil {
		sha := strings.ToLower(strings.TrimSpace(*declaredSHA))
		if !sha256Re.MatchString(sha) {
			return nil, errf("INVALID_INPUT", "sha256 必须是 64 位小写十六进制")
		}
		declaredSHA = &sha
	}
	if transport == "" || transport == "auto" {
		if size <= autoSinglePutLimit {
			transport = "single_put"
		} else {
			transport = "multipart"
		}
	}
	if transport != "single_put" && transport != "multipart" {
		return nil, errf("INVALID_INPUT", "transport 必须是 auto、single_put 或 multipart")
	}
	if idempotencyKey != nil {
		key := strings.TrimSpace(*idempotencyKey)
		if key == "" || len(key) > 128 {
			return nil, errf("INVALID_INPUT", "idempotency_key 长度必须为 1~128")
		}
		idempotencyKey = &key
		if existing, err := s.q.GetTransferSessionByIdempotencyKey(ctx, store.GetTransferSessionByIdempotencyKeyParams{
			OwnerID: owner, IdempotencyKey: idempotencyKey,
		}); err == nil {
			if existing.Size != size || existing.Transport != transport ||
				!sameUUID(existing.TargetParent, parentID) || !sameString(existing.ExpectedSha256, declaredSHA) {
				return nil, errf("IDEMPOTENCY_CONFLICT", "idempotency_key 已被不同的上传参数使用")
			}
			view, err := s.agentTransferView(ctx, existing, 1, maxPartURLsPerCall)
			return &AgentUploadInit{Mode: existing.Transport, Transfer: view}, err
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return nil, err
		}
	}
	if err := s.nodes.ensureFolder(ctx, owner, parentID); err != nil {
		return nil, err
	}
	name, err := s.nodes.cleanName(ctx, owner, parentID, name)
	if err != nil {
		return nil, err
	}
	user, err := s.q.GetUserByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	reserved, err := s.q.SumActiveTransferBytes(ctx, owner)
	if err != nil {
		return nil, err
	}
	if user.UsedBytes+reserved+size > user.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}
	if declaredSHA != nil {
		blob, err := s.q.GetBlobBySha256(ctx, *declaredSHA)
		if err == nil && blob.Verified {
			if blob.Size != size {
				return nil, errf("INVALID_INPUT", "hash 与文件大小不匹配")
			}
			node, err := s.linkBlob(ctx, owner, parentID, name, blob)
			if err != nil {
				return nil, err
			}
			return &AgentUploadInit{Mode: "instant", Node: &node}, nil
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return nil, err
		}
	}
	active, err := s.q.CountActiveTransferSessions(ctx, owner)
	if err != nil {
		return nil, err
	}
	if active >= maxActiveSessions {
		return nil, errf("TOO_MANY_SESSIONS", "进行中的上传过多,先完成或取消一些")
	}
	if contentType == "" {
		contentType = mimeByName(name)
	}
	id := uuid.Must(uuid.NewV7())
	objectKey := fmt.Sprintf("staging/mcp/%s/%s", owner, id)
	var minioUploadID *string
	if transport == "multipart" {
		uploadID, err := s.obj.NewMultipart(ctx, objectKey, contentType)
		if err != nil {
			return nil, err
		}
		minioUploadID = &uploadID
	}
	sess, err := s.q.CreateTransferSession(ctx, store.CreateTransferSessionParams{
		ID: id, OwnerID: owner, TargetParent: parentID, TargetName: name,
		Size: size, Mime: contentType, ExpectedSha256: declaredSHA,
		ObjectKey: objectKey, Transport: transport, MinioUploadID: minioUploadID,
		PartSize: int32(s.partSize), IdempotencyKey: idempotencyKey,
		ExpiresAt: tstz(time.Now().Add(s.ttl)),
	})
	if err != nil {
		if minioUploadID != nil {
			_ = s.obj.AbortMultipart(ctx, objectKey, *minioUploadID)
		}
		return nil, err
	}
	view, err := s.agentTransferView(ctx, sess, 1, maxPartURLsPerCall)
	if err != nil {
		return nil, err
	}
	return &AgentUploadInit{Mode: transport, Transfer: view}, nil
}

func (s *Uploads) AgentTransfer(ctx context.Context, owner, id uuid.UUID, firstPart, limit int) (*AgentTransferView, error) {
	sess, err := s.q.GetTransferSession(ctx, store.GetTransferSessionParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return s.agentTransferView(ctx, sess, firstPart, limit)
}

func (s *Uploads) agentTransferView(ctx context.Context, sess store.TransferSession, firstPart, limit int) (*AgentTransferView, error) {
	total := int((sess.Size + int64(sess.PartSize) - 1) / int64(sess.PartSize))
	view := &AgentTransferView{Session: sess, TotalParts: total}
	if sess.Status != "uploading" {
		return view, nil
	}
	if sess.Transport == "single_put" {
		u, err := s.obj.PresignPut(ctx, sess.ObjectKey)
		if err != nil {
			return nil, err
		}
		view.PutURL = u
		return view, nil
	}
	if sess.MinioUploadID == nil {
		return nil, errf("BAD_SESSION_STATE", "multipart 会话缺少 upload id")
	}
	done, err := s.obj.ListParts(ctx, sess.ObjectKey, *sess.MinioUploadID)
	if err != nil {
		return nil, err
	}
	for n := range done {
		view.UploadedParts = append(view.UploadedParts, n)
	}
	sort.Ints(view.UploadedParts)
	if firstPart < 1 {
		firstPart = 1
	}
	if limit <= 0 || limit > maxPartURLsPerCall {
		limit = maxPartURLsPerCall
	}
	last := min(total, firstPart+limit-1)
	view.PartURLs = make(map[int]string)
	for n := firstPart; n <= last; n++ {
		if _, ok := done[n]; ok {
			continue
		}
		u, err := s.obj.PresignPart(ctx, sess.ObjectKey, *sess.MinioUploadID, n)
		if err != nil {
			return nil, err
		}
		view.PartURLs[n] = u
	}
	return view, nil
}

func (s *Uploads) CompleteAgentTransfer(ctx context.Context, owner, id uuid.UUID) (*store.TransferSession, error) {
	sess, err := s.q.GetTransferSession(ctx, store.GetTransferSessionParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if sess.Status == "completing" {
		actual, statErr := s.obj.Stat(ctx, sess.ObjectKey)
		if statErr == nil {
			if actual != sess.Size {
				return nil, errf("SIZE_MISMATCH", "声明大小 %d,实际大小 %d", sess.Size, actual)
			}
			finalizing, err := s.q.MarkTransferFinalizing(ctx, store.MarkTransferFinalizingParams{ID: id, OwnerID: owner})
			return &finalizing, err
		}
		if err := s.q.ResetTransferUploading(ctx, store.ResetTransferUploadingParams{ID: id, OwnerID: owner}); err != nil {
			return nil, err
		}
		sess.Status = "uploading"
	}
	if sess.Status != "uploading" {
		return &sess, nil
	}
	if sess.Transport == "multipart" {
		if sess.MinioUploadID == nil {
			return nil, errf("BAD_SESSION_STATE", "multipart 会话缺少 upload id")
		}
		done, err := s.obj.ListParts(ctx, sess.ObjectKey, *sess.MinioUploadID)
		if err != nil {
			return nil, err
		}
		total := int((sess.Size + int64(sess.PartSize) - 1) / int64(sess.PartSize))
		parts := make([]objstore.Part, 0, total)
		for n := 1; n <= total; n++ {
			etag, ok := done[n]
			if !ok {
				return nil, errf("MISSING_PART", "第 %d 片未上传", n)
			}
			parts = append(parts, objstore.Part{Number: n, ETag: etag})
		}
		if _, err := s.q.MarkTransferCompleting(ctx, store.MarkTransferCompletingParams{ID: id, OwnerID: owner}); err != nil {
			return nil, err
		}
		if err := s.obj.CompleteMultipart(ctx, sess.ObjectKey, *sess.MinioUploadID, parts); err != nil {
			_ = s.q.ResetTransferUploading(ctx, store.ResetTransferUploadingParams{ID: id, OwnerID: owner})
			return nil, errf("COMPLETE_FAILED", "合并分片失败:%v", err)
		}
	}
	actual, err := s.obj.Stat(ctx, sess.ObjectKey)
	if err != nil {
		return nil, errf("UPLOAD_MISSING", "上传对象不存在或不可读:%v", err)
	}
	if actual != sess.Size {
		return nil, errf("SIZE_MISMATCH", "声明大小 %d,实际大小 %d", sess.Size, actual)
	}
	finalizing, err := s.q.MarkTransferFinalizing(ctx, store.MarkTransferFinalizingParams{ID: id, OwnerID: owner})
	if err != nil {
		return nil, err
	}
	return &finalizing, nil
}

func sameUUID(a, b *uuid.UUID) bool {
	return a == nil && b == nil || a != nil && b != nil && *a == *b
}

func sameString(a, b *string) bool {
	return a == nil && b == nil || a != nil && b != nil && *a == *b
}

func (s *Uploads) AbortAgentTransfer(ctx context.Context, owner, id uuid.UUID) error {
	sess, err := s.q.GetTransferSession(ctx, store.GetTransferSessionParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if sess.Status != "uploading" {
		return nil
	}
	if sess.Transport == "multipart" && sess.MinioUploadID != nil {
		_ = s.obj.AbortMultipart(ctx, sess.ObjectKey, *sess.MinioUploadID)
	} else {
		_ = s.obj.Remove(ctx, sess.ObjectKey)
	}
	reason := "用户取消"
	return s.q.MarkTransferAborted(ctx, store.MarkTransferAbortedParams{ID: id, OwnerID: owner, FailReason: &reason})
}

// ProcessNextAgentTransfer 从持久化状态机认领一个定稿任务。返回 processed=false
// 表示当前无任务；失败会写入会话，避免 worker 对永久错误无限重试。
func (s *Uploads) ProcessNextAgentTransfer(ctx context.Context) (processed bool, err error) {
	sess, err := s.q.ClaimFinalizingTransfer(ctx)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if err := s.finalizeAgentTransfer(ctx, sess); err != nil {
		reason := err.Error()
		_ = s.q.MarkTransferFailed(ctx, store.MarkTransferFailedParams{ID: sess.ID, FailReason: &reason})
		_ = s.obj.Remove(context.WithoutCancel(ctx), sess.ObjectKey)
		return true, nil
	}
	return true, nil
}

func (s *Uploads) finalizeAgentTransfer(ctx context.Context, sess store.TransferSession) error {
	r, err := s.obj.Open(ctx, sess.ObjectKey)
	if err != nil {
		return err
	}
	h := sha256.New()
	size, copyErr := io.Copy(h, r)
	closeErr := r.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if size != sess.Size {
		return fmt.Errorf("声明大小 %d,实际大小 %d", sess.Size, size)
	}
	sha := hex.EncodeToString(h.Sum(nil))
	if sess.ExpectedSha256 != nil && sha != *sess.ExpectedSha256 {
		return fmt.Errorf("SHA-256 不匹配:声明 %s,实际 %s", *sess.ExpectedSha256, sha)
	}
	if _, err := s.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{
		OwnerID: sess.OwnerID, ParentID: sess.TargetParent, Name: sess.TargetName,
	}); err == nil {
		return errf("NAME_CONFLICT", "目标位置已有同名文件或文件夹")
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	node, err := s.CommitStreamed(ctx, sess.OwnerID, sess.TargetParent, sess.TargetName, sha, size, sess.ObjectKey)
	if err != nil {
		return err
	}
	if err := s.q.MarkTransferVerifying(ctx, store.MarkTransferVerifyingParams{
		ID: sess.ID, ComputedSha256: &sha, NodeID: &node.ID,
	}); err != nil {
		return err
	}
	blob, err := s.q.GetBlobBySha256(ctx, sha)
	if err == nil && blob.Verified {
		return s.q.MarkTransferReady(ctx, sess.ID)
	}
	return err
}

func (s *Uploads) CleanupAgentTransfers(ctx context.Context) error {
	rows, err := s.q.ListExpiredTransferSessions(ctx)
	if err != nil {
		return err
	}
	for _, sess := range rows {
		if sess.Transport == "multipart" && sess.MinioUploadID != nil {
			_ = s.obj.AbortMultipart(ctx, sess.ObjectKey, *sess.MinioUploadID)
		}
		_ = s.obj.Remove(ctx, sess.ObjectKey)
		reason := "会话过期"
		if err := s.q.MarkTransferAborted(ctx, store.MarkTransferAbortedParams{
			ID: sess.ID, OwnerID: sess.OwnerID, FailReason: &reason,
		}); err != nil {
			return err
		}
	}
	return nil
}
