package service

import (
	"context"
	"errors"
	"io"
	"mime"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

const maxActiveSessions = 20

var sha256Re = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Uploads struct {
	pool interface {
		Begin(context.Context) (pgx.Tx, error)
	}
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
	id := uuid.Must(uuid.NewV7())
	key := "staging/browser/" + owner.String() + "/" + id.String()
	uploadID, err := s.obj.NewMultipart(ctx, key, mimeByName(name))
	if err != nil {
		return nil, err
	}
	sess, err := s.q.CreateUploadSession(ctx, store.CreateUploadSessionParams{
		ID: id, OwnerID: owner, ObjectKey: &key,
		Sha256: sha, Size: size,
		TargetParent: parentID, TargetName: name,
		MinioUploadID: uploadID, PartSize: int32(s.partSize),
		ExpiresAt: tstz(time.Now().Add(s.ttl)),
	})
	if err != nil {
		_ = s.obj.AbortMultipart(context.WithoutCancel(ctx), key, uploadID)
		return nil, err
	}
	view, err := s.sessionView(ctx, sess)
	if err != nil {
		_ = s.Abort(context.WithoutCancel(ctx), owner, id)
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
	if sess.ObjectKey == nil || time.Now().After(sess.ExpiresAt.Time) {
		return nil, errf("UPLOAD_EXPIRED", "上传会话已过期或需要重新上传")
	}
	key := *sess.ObjectKey
	done, err := s.obj.ListParts(ctx, key, sess.MinioUploadID)
	if err != nil {
		// S3 may have sealed the object before the DB transaction committed.
		// Keep this session resumable; Complete reconciles the sealed object.
		if _, statErr := s.obj.Stat(ctx, key); statErr == nil {
			return &SessionView{Session: sess}, nil
		}
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

// linkBlob 秒传:只建引用。
func (s *Uploads) linkBlob(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name string, blob store.Blob) (store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	locked, err := qtx.GetUserByIDForUpdate(ctx, owner)
	if err != nil {
		return store.Node{}, err
	}
	if locked.UsedBytes+blob.Size > locked.QuotaBytes {
		return store.Node{}, errf("QUOTA_EXCEEDED", "存储配额不足")
	}
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
// WebDAV/Agent 的新 blob 仍标 pending 并入队 verify_hash,通过后触发派生物。
// 浏览器则在独立 staging 上先校验,定稿见 browser_upload.go。
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
		if err := s.obj.Promote(ctx, objstore.BlobKey(sha), tmpKey, size, mimeByName(name)); err != nil {
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
	locked, err := qtx.GetUserByIDForUpdate(ctx, owner)
	if err != nil {
		return nil, err
	}
	if locked.UsedBytes+size-oldSize > locked.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}

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

func (s *Uploads) NodeInfo(ctx context.Context, owner, nodeID uuid.UUID) (store.GetNodeWithBlobRow, error) {
	row, err := s.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.GetNodeWithBlobRow{}, ErrNotFound
		}
		return store.GetNodeWithBlobRow{}, err
	}
	if row.DeletedAt.Valid {
		return store.GetNodeWithBlobRow{}, ErrNotFound
	}
	return row, nil
}

func (s *Uploads) ReadText(ctx context.Context, owner, nodeID uuid.UUID, offset, maxBytes int64) (string, bool, error) {
	if offset < 0 || maxBytes <= 0 || maxBytes > 64<<10 {
		return "", false, errf("INVALID_INPUT", "offset 必须非负,max_bytes 必须在 1~65536")
	}
	row, err := s.NodeInfo(ctx, owner, nodeID)
	if err != nil {
		return "", false, err
	}
	if row.Kind != "file" || row.BlobSha256 == nil || row.BlobSize == nil {
		return "", false, ErrNotFound
	}
	mimeType := ""
	if row.BlobMime != nil {
		mimeType = *row.BlobMime
	}
	ext := strings.ToLower(filepath.Ext(row.Name))
	if !strings.HasPrefix(mimeType, "text/") && ext != ".json" && ext != ".xml" && ext != ".md" && ext != ".csv" && ext != ".log" {
		return "", false, errf("UNSUPPORTED_TYPE", "文件不是支持的文本类型")
	}
	if offset >= *row.BlobSize {
		return "", true, nil
	}
	r, err := s.obj.Open(ctx, objstore.BlobKey(*row.BlobSha256))
	if err != nil {
		return "", false, err
	}
	defer r.Close()
	if offset > 0 {
		if _, err := io.CopyN(io.Discard, r, offset); err != nil {
			return "", false, err
		}
	}
	b, err := io.ReadAll(io.LimitReader(r, maxBytes))
	if err != nil {
		return "", false, err
	}
	if !utf8.Valid(b) {
		return "", false, errf("UNSUPPORTED_ENCODING", "文本不是有效 UTF-8")
	}
	return string(b), offset+int64(len(b)) >= *row.BlobSize, nil
}

func mimeByName(name string) string {
	if t := mime.TypeByExtension(filepath.Ext(name)); t != "" {
		return t
	}
	return "application/octet-stream"
}
