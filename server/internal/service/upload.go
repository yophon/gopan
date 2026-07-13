package service

import (
	"context"
	"errors"
	"mime"
	"path/filepath"
	"regexp"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

const maxActiveSessions = 20

var sha256Re = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Uploads struct {
	pool     *pgxpool.Pool
	q        *store.Queries
	obj      *objstore.Store
	nodes    *Nodes
	partSize int64
	ttl      time.Duration
	enqueue  func(ctx context.Context, kind string, blobID uuid.UUID) // worker 注入
}

func NewUploads(pool *pgxpool.Pool, obj *objstore.Store, nodes *Nodes, partSize int64, ttl time.Duration) *Uploads {
	return &Uploads{
		pool: pool, q: store.New(pool), obj: obj, nodes: nodes,
		partSize: partSize, ttl: ttl,
		enqueue: func(context.Context, string, uuid.UUID) {},
	}
}

// SetEnqueue 由 worker 包在启动时注入,避免 service ↔ worker 循环依赖。
func (s *Uploads) SetEnqueue(fn func(ctx context.Context, kind string, blobID uuid.UUID)) {
	s.enqueue = fn
}

type InitResult struct {
	Instant bool
	Node    *store.Node
	Session *SessionView
}

type SessionView struct {
	Session  store.UploadSession
	PartURLs map[int]string // 缺失分片号 → 预签名 PUT
	Uploaded []int          // 已完成分片号
}

func (s *Uploads) Init(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name, sha string, size int64) (*InitResult, error) {
	if !sha256Re.MatchString(sha) {
		return nil, errf("INVALID_INPUT", "sha256 必须是 64 位小写十六进制")
	}
	if size <= 0 || size > 1<<40 {
		return nil, errf("INVALID_INPUT", "文件大小非法")
	}
	if err := s.nodes.ensureFolder(ctx, owner, parentID); err != nil {
		return nil, err
	}
	name, err := s.nodes.cleanName(ctx, owner, parentID, name)
	if err != nil {
		return nil, err
	}

	// 配额(逻辑记账,秒传同样占额)
	u, err := s.q.GetUserByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	if u.UsedBytes+size > u.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}

	// 秒传:blob 已存在且通过服务端校验
	blob, err := s.q.GetBlobBySha256(ctx, sha)
	if err == nil && blob.Verified {
		if blob.Size != size {
			return nil, errf("INVALID_INPUT", "hash 与文件大小不匹配")
		}
		n, err := s.linkBlob(ctx, owner, parentID, name, blob)
		if err != nil {
			return nil, err
		}
		return &InitResult{Instant: true, Node: &n}, nil
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	// 常规:开 multipart 会话
	active, err := s.q.CountActiveSessions(ctx, owner)
	if err != nil {
		return nil, err
	}
	if active >= maxActiveSessions {
		return nil, errf("TOO_MANY_SESSIONS", "进行中的上传过多,先完成或取消一些")
	}
	key := objstore.BlobKey(sha)
	uploadID, err := s.obj.NewMultipart(ctx, key, mimeByName(name))
	if err != nil {
		return nil, err
	}
	sess, err := s.q.CreateUploadSession(ctx, store.CreateUploadSessionParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner,
		Sha256: sha, Size: size,
		TargetParent: parentID, TargetName: name,
		MinioUploadID: uploadID, PartSize: int32(s.partSize),
		ExpiresAt: tstz(time.Now().Add(s.ttl)),
	})
	if err != nil {
		return nil, err
	}
	view, err := s.sessionView(ctx, sess)
	if err != nil {
		return nil, err
	}
	return &InitResult{Session: view}, nil
}

// Session 断点续传入口:返回已传分片和缺失分片的新预签名。
func (s *Uploads) Session(ctx context.Context, owner, id uuid.UUID) (*SessionView, error) {
	sess, err := s.q.GetUploadSession(ctx, store.GetUploadSessionParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if sess.Status != "uploading" {
		return &SessionView{Session: sess}, nil
	}
	return s.sessionView(ctx, sess)
}

func (s *Uploads) sessionView(ctx context.Context, sess store.UploadSession) (*SessionView, error) {
	key := objstore.BlobKey(sess.Sha256)
	done, err := s.obj.ListParts(ctx, key, sess.MinioUploadID)
	if err != nil {
		return nil, err
	}
	total := int((sess.Size + int64(sess.PartSize) - 1) / int64(sess.PartSize))
	urls := make(map[int]string)
	uploaded := make([]int, 0, len(done))
	for n := range done {
		uploaded = append(uploaded, n)
	}
	sort.Ints(uploaded)
	for n := 1; n <= total; n++ {
		if _, ok := done[n]; ok {
			continue
		}
		u, err := s.obj.PresignPart(ctx, key, sess.MinioUploadID, n)
		if err != nil {
			return nil, err
		}
		urls[n] = u
	}
	return &SessionView{Session: sess, PartURLs: urls, Uploaded: uploaded}, nil
}

func (s *Uploads) Complete(ctx context.Context, owner, id uuid.UUID, etags map[int]string) (*store.Node, error) {
	sess, err := s.q.GetUploadSession(ctx, store.GetUploadSessionParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if sess.Status != "uploading" {
		return nil, errf("BAD_SESSION_STATE", "会话状态是 %s,不能完成", sess.Status)
	}

	key := objstore.BlobKey(sess.Sha256)
	total := int((sess.Size + int64(sess.PartSize) - 1) / int64(sess.PartSize))
	// 客户端没报 etag 的分片,从 MinIO 补齐(浏览器跨域可能读不到 ETag 响应头)
	listed, err := s.obj.ListParts(ctx, key, sess.MinioUploadID)
	if err != nil {
		return nil, err
	}
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
	if err := s.setStatus(ctx, sess, "completing", ""); err != nil {
		return nil, err
	}
	if err := s.obj.CompleteMultipart(ctx, key, sess.MinioUploadID, parts); err != nil {
		_ = s.setStatus(ctx, sess, "uploading", "") // 回滚状态,允许重试
		return nil, errf("COMPLETE_FAILED", "合并分片失败:%v", err)
	}

	// blob(pending verify)+ node + 配额,同事务
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)

	blob, err := qtx.UpsertBlob(ctx, store.UpsertBlobParams{
		ID: uuid.Must(uuid.NewV7()), Sha256: sess.Sha256,
		Size: sess.Size, Mime: mimeByName(sess.TargetName),
	})
	if err != nil {
		return nil, err
	}
	if blob.Size != sess.Size {
		return nil, errf("INVALID_INPUT", "同 hash 但大小不一致,拒绝")
	}
	name := sess.TargetName // init 时已解冲突;若期间又冲突,唯一索引兜底
	node, err := qtx.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: sess.TargetParent,
		Name: name, Kind: "file", BlobID: &blob.ID,
	})
	if isUniqueViolation(err) {
		return nil, errf("NAME_CONFLICT", "目标位置已有同名文件,重新发起上传")
	}
	if err != nil {
		return nil, err
	}
	if err := qtx.IncrementBlobRef(ctx, blob.ID); err != nil {
		return nil, err
	}
	if err := qtx.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: sess.Size}); err != nil {
		return nil, err
	}
	if err := qtx.MarkAncestorsStale(ctx, node.ID); err != nil {
		return nil, err
	}
	if err := qtx.SetUploadSessionStatus(ctx, store.SetUploadSessionStatusParams{
		ID: sess.ID, OwnerID: owner, Status: "verifying",
	}); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	s.enqueue(ctx, "verify_hash", blob.ID)
	return &node, nil
}

func (s *Uploads) Abort(ctx context.Context, owner, id uuid.UUID) error {
	sess, err := s.q.GetUploadSession(ctx, store.GetUploadSessionParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if sess.Status != "uploading" {
		return nil
	}
	_ = s.obj.AbortMultipart(ctx, objstore.BlobKey(sess.Sha256), sess.MinioUploadID)
	return s.setStatus(ctx, sess, "aborted", "")
}

// linkBlob 秒传:只建引用。
func (s *Uploads) linkBlob(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name string, blob store.Blob) (store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	node, err := qtx.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parentID,
		Name: name, Kind: "file", BlobID: &blob.ID,
	})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件已存在")
	}
	if err != nil {
		return store.Node{}, err
	}
	if err := qtx.IncrementBlobRef(ctx, blob.ID); err != nil {
		return store.Node{}, err
	}
	if err := qtx.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: blob.Size}); err != nil {
		return store.Node{}, err
	}
	if err := qtx.MarkAncestorsStale(ctx, node.ID); err != nil {
		return store.Node{}, err
	}
	return node, tx.Commit(ctx)
}

// CommitStreamed WebDAV 写入定稿:字节已流式转存到 tmpKey,sha/size 是服务端算的。
// 覆盖语义:目标已有同名文件 → 换 blob 指向并调整引用与配额(不进回收站,
// rclone sync 高频覆盖不能变成垃圾制造机);同名文件夹 → 冲突报错。
// 与 completeUpload 走同一条 verify 管线:新 blob 仍标 pending 并入队 verify_hash,
// 信任模型不分叉,verify 通过后自动触发派生物。
func (s *Uploads) CommitStreamed(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name, sha string, size int64, tmpKey string) (*store.Node, error) {
	defer func() { _ = s.obj.Remove(context.WithoutCancel(ctx), tmpKey) }() // 成功失败都清临时对象

	if err := s.nodes.ensureFolder(ctx, owner, parentID); err != nil {
		return nil, err
	}
	if err := validateName(name); err != nil {
		return nil, err
	}
	existing, err := s.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{
		OwnerID: owner, ParentID: parentID, Name: name,
	})
	overwrite := err == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}
	if overwrite && existing.Kind != "file" {
		return nil, errf("NAME_CONFLICT", "同名文件夹已存在")
	}

	// 配额预检:覆盖按差额算
	var oldSize int64
	if overwrite && existing.BlobID != nil {
		old, err := s.q.GetBlob(ctx, *existing.BlobID)
		if err != nil {
			return nil, err
		}
		oldSize = old.Size
	}
	u, err := s.q.GetUserByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	if u.UsedBytes+size-oldSize > u.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}

	// 对象定稿:内容寻址 key 不存在才拷贝(并发同 sha 拷贝同 key 同内容,无害)
	if _, err := s.q.GetBlobBySha256(ctx, sha); errors.Is(err, pgx.ErrNoRows) {
		if err := s.obj.Copy(ctx, objstore.BlobKey(sha), tmpKey); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)

	blob, err := qtx.UpsertBlob(ctx, store.UpsertBlobParams{
		ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: size, Mime: mimeByName(name),
	})
	if err != nil {
		return nil, err
	}
	if blob.Size != size {
		return nil, errf("INVALID_INPUT", "同 hash 但大小不一致,拒绝")
	}

	var node store.Node
	switch {
	case overwrite && existing.BlobID != nil && *existing.BlobID == blob.ID:
		// 内容没变:只碰 updated_at,引用与配额原样
		node, err = qtx.ReplaceNodeBlob(ctx, store.ReplaceNodeBlobParams{ID: existing.ID, OwnerID: owner, BlobID: &blob.ID})
		if err != nil {
			return nil, err
		}
	case overwrite:
		node, err = qtx.ReplaceNodeBlob(ctx, store.ReplaceNodeBlobParams{ID: existing.ID, OwnerID: owner, BlobID: &blob.ID})
		if err != nil {
			return nil, err
		}
		if err := qtx.IncrementBlobRef(ctx, blob.ID); err != nil {
			return nil, err
		}
		if existing.BlobID != nil {
			if err := qtx.DecrementBlobRefs(ctx, []uuid.UUID{*existing.BlobID}); err != nil {
				return nil, err
			}
		}
		if err := qtx.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: size}); err != nil {
			return nil, err
		}
		if err := qtx.SubtractUsedBytes(ctx, store.SubtractUsedBytesParams{ID: owner, UsedBytes: oldSize}); err != nil {
			return nil, err
		}
	default:
		node, err = qtx.CreateNode(ctx, store.CreateNodeParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parentID,
			Name: name, Kind: "file", BlobID: &blob.ID,
		})
		if isUniqueViolation(err) {
			return nil, errf("NAME_CONFLICT", "目标位置已有同名文件")
		}
		if err != nil {
			return nil, err
		}
		if err := qtx.IncrementBlobRef(ctx, blob.ID); err != nil {
			return nil, err
		}
		if err := qtx.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: size}); err != nil {
			return nil, err
		}
	}
	if err := qtx.MarkAncestorsStale(ctx, node.ID); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	if !blob.Verified {
		s.enqueue(ctx, "verify_hash", blob.ID)
	}
	return &node, nil
}

func (s *Uploads) setStatus(ctx context.Context, sess store.UploadSession, status, reason string) error {
	var rp *string
	if reason != "" {
		rp = &reason
	}
	return s.q.SetUploadSessionStatus(ctx, store.SetUploadSessionStatusParams{
		ID: sess.ID, OwnerID: sess.OwnerID, Status: status, FailReason: rp,
	})
}

// DownloadURL 给文件节点签下载地址。
func (s *Uploads) DownloadURL(ctx context.Context, owner, nodeID uuid.UUID) (string, error) {
	row, err := s.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", ErrNotFound
		}
		return "", err
	}
	if row.Kind != "file" || row.BlobSha256 == nil {
		return "", ErrNotFound
	}
	return s.obj.PresignGet(ctx, objstore.BlobKey(*row.BlobSha256), row.Name)
}

func mimeByName(name string) string {
	if t := mime.TypeByExtension(filepath.Ext(name)); t != "" {
		return t
	}
	return "application/octet-stream"
}
