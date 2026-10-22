package service

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// StagedFile 服务端自己写进暂存的一个文件(离线下载、解压,doc/21 二.2):
// sha 与大小是边写边算的,可信。
type StagedFile struct {
	Owner  uuid.UUID
	Parent *uuid.UUID
	Name   string
	Sha    string
	Size   int64
	TmpKey string
}

// errNameRace 挑好的名字在建节点时被同目录的另一次写入抢走:整笔回滚,重挑。
var errNameRace = errors.New("name taken concurrently")

// CommitStaged 把暂存对象落成文件。与浏览器上传的定稿同一套顺序:锁用户行 → 复核配额与目标 →
// 挑名字(撞名自动改名)→ upsert blob(锁住 hash 行)→ 必要时提升对象 → 建节点、记引用与用量。
// blob 直接记为已校验(字节是服务端自己写的),verify_hash 只用来触发派生物。
// 暂存对象成功失败都删。
func (s *Uploads) CommitStaged(ctx context.Context, f StagedFile) (*store.Node, error) {
	defer func() { _ = s.obj.Remove(context.WithoutCancel(ctx), f.TmpKey) }()
	for range 3 {
		n, err := s.commitStagedOnce(ctx, f)
		if errors.Is(err, errNameRace) {
			continue
		}
		return n, err
	}
	return nil, errf("NAME_CONFLICT", "目标位置同名文件变动太频繁,稍后重试")
}

func (s *Uploads) commitStagedOnce(ctx context.Context, f StagedFile) (*store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	u, err := q.GetUserByIDForUpdate(ctx, f.Owner)
	if err != nil {
		return nil, err
	}
	if u.DisabledAt.Valid {
		return nil, errf("UNAUTHENTICATED", "账号已停用")
	}
	if u.UsedBytes+f.Size > u.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}
	if err := checkTargetFolder(ctx, q, f.Owner, f.Parent); err != nil {
		return nil, err
	}
	name, err := freeName(ctx, q, f.Owner, f.Parent, f.Name)
	if err != nil {
		return nil, err
	}
	mime := mimeByName(name)
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: f.Sha, Size: f.Size, Mime: mime})
	if err != nil {
		return nil, err
	}
	if blob.Size != f.Size {
		return nil, errf("SIZE_MISMATCH", "已有文件的大小与内容不一致")
	}
	n, err := q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: f.Owner, ParentID: f.Parent, Name: name, Kind: "file", BlobID: &blob.ID,
	})
	if isUniqueViolation(err) {
		return nil, errNameRace
	}
	if err != nil {
		return nil, err
	}
	if !blob.Verified {
		if err := s.obj.Promote(ctx, objstore.BlobKey(f.Sha), f.TmpKey, f.Size, mime); err != nil {
			return nil, err
		}
		if err := q.MarkBlobVerified(ctx, blob.ID); err != nil {
			return nil, err
		}
	}
	if err := q.IncrementBlobRef(ctx, blob.ID); err != nil {
		return nil, err
	}
	if err := q.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: f.Owner, UsedBytes: f.Size}); err != nil {
		return nil, err
	}
	if err := q.MarkAncestorsStale(ctx, n.ID); err != nil {
		return nil, err
	}
	if err := attachChatMessage(ctx, q, f.Owner, f.Parent, n.ID); err != nil {
		return nil, err
	}
	if err := recordActs(ctx, q, f.Owner, fileAct(OpUpload, n, f.Size)); err != nil {
		return nil, err
	}
	// 派生物触发与节点同一个事务落库,重启也丢不了
	if err := q.EnqueueTask(ctx, store.EnqueueTaskParams{ID: uuid.Must(uuid.NewV7()), Kind: "verify_hash", BlobID: blob.ID}); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	s.notify.CheckQuota(ctx, f.Owner)
	return &n, nil
}

// CreateFolderIn 在事务外建一个子文件夹,撞名自动改名(解压时的目标文件夹)。
func (s *Uploads) CreateFolderIn(ctx context.Context, owner uuid.UUID, parent *uuid.UUID, name string) (*store.Node, error) {
	for range 3 {
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			return nil, err
		}
		n, err := createFolderTx(ctx, s.q.WithTx(tx), owner, parent, name, true)
		if err == nil {
			err = tx.Commit(ctx)
		}
		tx.Rollback(ctx)
		if errors.Is(err, errNameRace) {
			continue
		}
		if err != nil {
			return nil, err
		}
		return n, nil
	}
	return nil, errf("NAME_CONFLICT", "目标位置同名文件夹变动太频繁,稍后重试")
}

// EnsurePath 从根开始逐层找到或建出 segs 这条路径,返回最后一层(segs 为空 = 根目录)。
func (s *Uploads) EnsurePath(ctx context.Context, owner uuid.UUID, segs []string) (*uuid.UUID, error) {
	var parent *uuid.UUID
	for _, seg := range segs {
		n, err := s.EnsureFolderIn(ctx, owner, parent, seg)
		if err != nil {
			return nil, err
		}
		id := n.ID
		parent = &id
	}
	return parent, nil
}

// EnsureFolderIn 找到或建出 parent 下名为 name 的子文件夹(解压时还原包里的目录结构);
// 同名的是文件就另起一个改了名的文件夹。
func (s *Uploads) EnsureFolderIn(ctx context.Context, owner uuid.UUID, parent *uuid.UUID, name string) (*store.Node, error) {
	existing, err := s.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: parent, Name: name})
	if err == nil && existing.Kind == "folder" {
		return &existing, nil
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}
	return s.CreateFolderIn(ctx, owner, parent, name)
}

func createFolderTx(ctx context.Context, q *store.Queries, owner uuid.UUID, parent *uuid.UUID, name string, rename bool) (*store.Node, error) {
	if err := checkTargetFolder(ctx, q, owner, parent); err != nil {
		return nil, err
	}
	var err error
	if rename {
		if name, err = freeName(ctx, q, owner, parent, name); err != nil {
			return nil, err
		}
	} else if err := validateName(name); err != nil {
		return nil, err
	}
	n, err := q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent, Name: name, Kind: "folder",
	})
	if isUniqueViolation(err) {
		return nil, errNameRace
	}
	if err != nil {
		return nil, err
	}
	if err := q.MarkAncestorsStale(ctx, n.ID); err != nil {
		return nil, err
	}
	return &n, nil
}

// checkTargetFolder 目标必须是属主自己的、没进回收站的文件夹;nil = 根目录。
func checkTargetFolder(ctx context.Context, q *store.Queries, owner uuid.UUID, parent *uuid.UUID) error {
	if parent == nil {
		return nil
	}
	n, err := q.GetNode(ctx, *parent)
	if errors.Is(err, pgx.ErrNoRows) {
		return errf("NOT_A_FOLDER", "目标文件夹不存在")
	}
	if err != nil {
		return err
	}
	if n.OwnerID != owner || n.Kind != "folder" || n.DeletedAt.Valid {
		return errf("NOT_A_FOLDER", "目标文件夹不可用")
	}
	return nil
}
