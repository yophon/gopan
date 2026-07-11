package service

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/store"
)

const pageSize = 200

type Nodes struct {
	pool *pgxpool.Pool
	q    *store.Queries
}

func NewNodes(pool *pgxpool.Pool) *Nodes {
	return &Nodes{pool: pool, q: store.New(pool)}
}

func (s *Nodes) Get(ctx context.Context, owner, id uuid.UUID) (store.Node, error) {
	n, err := s.q.GetNode(ctx, id)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.Node{}, ErrNotFound
		}
		return store.Node{}, err
	}
	if n.OwnerID != owner {
		return store.Node{}, ErrNotFound // 不区分"不存在/不是你的",防探测
	}
	return n, nil
}

// ---- 列表 ----

type Page[T any] struct {
	Items      []T
	NextCursor *string
	Total      int64
}

func (s *Nodes) Children(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, cursor *string, order string, desc bool) (*Page[store.ListChildrenRow], error) {
	if parentID != nil {
		p, err := s.Get(ctx, owner, *parentID)
		if err != nil {
			return nil, err
		}
		if p.Kind != "folder" || p.DeletedAt.Valid {
			return nil, ErrNotFound
		}
	}
	offset := decodeCursor(cursor)
	rows, err := s.q.ListChildren(ctx, store.ListChildrenParams{
		OwnerID: owner, ParentID: parentID,
		OrderBy: order, Descending: desc,
		PageLimit: pageSize, PageOffset: offset,
	})
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountChildren(ctx, store.CountChildrenParams{OwnerID: owner, ParentID: parentID})
	if err != nil {
		return nil, err
	}
	return &Page[store.ListChildrenRow]{Items: rows, Total: total, NextCursor: nextCursor(offset, len(rows), total)}, nil
}

func (s *Nodes) Search(ctx context.Context, owner uuid.UUID, q string, cursor *string) (*Page[store.SearchNodesRow], error) {
	kw := strings.TrimSpace(q)
	if kw == "" {
		return &Page[store.SearchNodesRow]{}, nil
	}
	offset := decodeCursor(cursor)
	kwp := &kw
	rows, err := s.q.SearchNodes(ctx, store.SearchNodesParams{
		OwnerID: owner, Column2: kwp, Limit: pageSize, Offset: offset,
	})
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountSearchNodes(ctx, store.CountSearchNodesParams{OwnerID: owner, Column2: kwp})
	if err != nil {
		return nil, err
	}
	return &Page[store.SearchNodesRow]{Items: rows, Total: total, NextCursor: nextCursor(offset, len(rows), total)}, nil
}

// SearchInSubtree 访客搜索:范围限定在分享根的子树内。
func (s *Nodes) SearchInSubtree(ctx context.Context, root uuid.UUID, q string, cursor *string) (*Page[store.SearchNodesInSubtreeRow], error) {
	kw := strings.TrimSpace(q)
	if kw == "" {
		return &Page[store.SearchNodesInSubtreeRow]{}, nil
	}
	offset := decodeCursor(cursor)
	kwp := &kw
	rows, err := s.q.SearchNodesInSubtree(ctx, store.SearchNodesInSubtreeParams{
		ID: root, Column2: kwp, Limit: pageSize, Offset: offset,
	})
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountSearchNodesInSubtree(ctx, store.CountSearchNodesInSubtreeParams{ID: root, Column2: kwp})
	if err != nil {
		return nil, err
	}
	return &Page[store.SearchNodesInSubtreeRow]{Items: rows, Total: total, NextCursor: nextCursor(offset, len(rows), total)}, nil
}

func (s *Nodes) Trash(ctx context.Context, owner uuid.UUID, cursor *string) (*Page[store.ListTrashRow], error) {
	offset := decodeCursor(cursor)
	rows, err := s.q.ListTrash(ctx, store.ListTrashParams{OwnerID: owner, Limit: pageSize, Offset: offset})
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountTrash(ctx, owner)
	if err != nil {
		return nil, err
	}
	return &Page[store.ListTrashRow]{Items: rows, Total: total, NextCursor: nextCursor(offset, len(rows), total)}, nil
}

// ---- 写操作 ----

func (s *Nodes) CreateFolder(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name string) (store.Node, error) {
	if err := s.ensureFolder(ctx, owner, parentID); err != nil {
		return store.Node{}, err
	}
	name, err := s.cleanName(ctx, owner, parentID, name)
	if err != nil {
		return store.Node{}, err
	}
	n, err := s.q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parentID,
		Name: name, Kind: "folder",
	})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件或文件夹已存在")
	}
	return n, err
}

func (s *Nodes) Rename(ctx context.Context, owner, id uuid.UUID, name string) (store.Node, error) {
	cur, err := s.Get(ctx, owner, id)
	if err != nil {
		return store.Node{}, err
	}
	if cur.DeletedAt.Valid {
		return store.Node{}, ErrNotFound
	}
	name = strings.TrimSpace(name)
	if err := validateName(name); err != nil {
		return store.Node{}, err
	}
	n, err := s.q.RenameNode(ctx, store.RenameNodeParams{ID: id, OwnerID: owner, Name: name})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件或文件夹已存在")
	}
	return n, err
}

func (s *Nodes) Move(ctx context.Context, owner uuid.UUID, ids []uuid.UUID, target *uuid.UUID) ([]store.Node, error) {
	if err := s.ensureFolder(ctx, owner, target); err != nil {
		return nil, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)

	out := make([]store.Node, 0, len(ids))
	for _, id := range ids {
		if _, err := s.Get(ctx, owner, id); err != nil {
			return nil, err
		}
		if target != nil {
			// 不能移进自己或自己的子树
			cyclic, err := qtx.IsDescendant(ctx, store.IsDescendantParams{ID: id, ID_2: *target})
			if err != nil {
				return nil, err
			}
			if cyclic {
				return nil, errf("CYCLIC_MOVE", "不能移动到自身或其子文件夹内")
			}
		}
		n, err := qtx.MoveNode(ctx, store.MoveNodeParams{ID: id, OwnerID: owner, ParentID: target})
		if isUniqueViolation(err) {
			return nil, errf("NAME_CONFLICT", "目标位置已存在同名文件或文件夹")
		}
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return out, nil
}

const maxCopyNodes = 10000

// copyEntry 复制清单项,父在前的顺序,parent 是清单内下标(-1 = 挂到复制目标)。
type copyEntry struct {
	parent int
	name   string
	kind   string
	blobID *uuid.UUID
	size   int64
}

// Copy 复制节点(多选)到目标文件夹。内容寻址下文件复制是零物理拷贝:
// 新 node 指向同一 blob,引用计数 +1,配额按逻辑大小照扣。
func (s *Nodes) Copy(ctx context.Context, owner uuid.UUID, ids []uuid.UUID, target *uuid.UUID) ([]store.Node, error) {
	if err := s.ensureFolder(ctx, owner, target); err != nil {
		return nil, err
	}

	// 收集清单(快照语义:以遍历瞬间为准,不加锁)
	var entries []copyEntry
	var total int64
	rootIdx := make([]int, 0, len(ids))
	taken := map[string]bool{}
	for _, id := range ids {
		n, err := s.Get(ctx, owner, id)
		if err != nil {
			return nil, err
		}
		if n.DeletedAt.Valid {
			return nil, ErrNotFound
		}
		if target != nil {
			// 目标在源子树内 = 递归复制自己,无限膨胀
			cyclic, err := s.q.IsDescendant(ctx, store.IsDescendantParams{ID: id, ID_2: *target})
			if err != nil {
				return nil, err
			}
			if cyclic {
				return nil, errf("CYCLIC_COPY", "不能复制到自身或其子文件夹内")
			}
		}
		name, err := s.cleanNameTaken(ctx, owner, target, n.Name, taken)
		if err != nil {
			return nil, err
		}
		taken[name] = true
		rootIdx = append(rootIdx, len(entries))
		row, err := s.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: id, OwnerID: owner})
		if err != nil {
			return nil, err
		}
		size := int64(0)
		if row.BlobSize != nil {
			size = *row.BlobSize
		}
		entries = append(entries, copyEntry{parent: -1, name: name, kind: n.Kind, blobID: n.BlobID, size: size})
		total += size
		if n.Kind == "folder" {
			sub, subTotal, err := s.collectSubtree(ctx, id, len(entries)-1, len(entries))
			if err != nil {
				return nil, err
			}
			entries = append(entries, sub...)
			total += subTotal
		}
		if len(entries) > maxCopyNodes {
			return nil, errf("INVALID_INPUT", "单次复制不能超过 %d 个节点,请分批", maxCopyNodes)
		}
	}

	// 配额预检(逻辑记账,复制同样占额)
	u, err := s.q.GetUserByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	if u.UsedBytes+total > u.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}

	// 单事务落库
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)

	newIDs := make([]uuid.UUID, len(entries))
	out := make([]store.Node, 0, len(rootIdx))
	for i, e := range entries {
		var parent *uuid.UUID
		if e.parent == -1 {
			parent = target
		} else {
			parent = &newIDs[e.parent]
		}
		n, err := qtx.CreateNode(ctx, store.CreateNodeParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent,
			Name: e.name, Kind: e.kind, BlobID: e.blobID,
		})
		if isUniqueViolation(err) {
			return nil, errf("NAME_CONFLICT", "目标位置已存在同名文件或文件夹")
		}
		if err != nil {
			return nil, err
		}
		newIDs[i] = n.ID
		if e.blobID != nil {
			if err := qtx.IncrementBlobRef(ctx, *e.blobID); err != nil {
				return nil, err
			}
		}
	}
	if total > 0 {
		if err := qtx.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: total}); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	for _, ri := range rootIdx {
		n, err := s.q.GetNode(ctx, newIDs[ri])
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, nil
}

// collectSubtree 深度优先收集活跃子树,parentIdx 是父项在清单中的下标,base 是本批的起始下标。
func (s *Nodes) collectSubtree(ctx context.Context, folderID uuid.UUID, parentIdx, base int) ([]copyEntry, int64, error) {
	children, err := s.q.ListActiveChildrenLite(ctx, &folderID)
	if err != nil {
		return nil, 0, err
	}
	var entries []copyEntry
	var total int64
	for _, c := range children {
		size := int64(0)
		if c.BlobSize != nil {
			size = *c.BlobSize
		}
		entries = append(entries, copyEntry{parent: parentIdx, name: c.Name, kind: c.Kind, blobID: c.BlobID, size: size})
		total += size
		if c.Kind == "folder" {
			sub, subTotal, err := s.collectSubtree(ctx, c.ID, base+len(entries)-1, base+len(entries))
			if err != nil {
				return nil, 0, err
			}
			entries = append(entries, sub...)
			total += subTotal
		}
		if base+len(entries) > maxCopyNodes {
			return nil, 0, errf("INVALID_INPUT", "单次复制不能超过 %d 个节点,请分批", maxCopyNodes)
		}
	}
	return entries, total, nil
}

// PurgeExpiredTrash 彻删回收站里超过保留期的顶层节点(整树),worker 周期调用。
func (s *Nodes) PurgeExpiredTrash(ctx context.Context, ttl time.Duration) (int, error) {
	rows, err := s.q.ListExpiredTrashRoots(ctx, tstz(time.Now().Add(-ttl)))
	if err != nil {
		return 0, err
	}
	byOwner := map[uuid.UUID][]uuid.UUID{}
	for _, r := range rows {
		byOwner[r.OwnerID] = append(byOwner[r.OwnerID], r.ID)
	}
	purged := 0
	for owner, ids := range byOwner {
		if err := s.Purge(ctx, owner, ids); err != nil {
			return purged, err
		}
		purged += len(ids)
	}
	return purged, nil
}

func (s *Nodes) Delete(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) error {
	for _, id := range ids {
		if err := s.q.SoftDeleteSubtree(ctx, store.SoftDeleteSubtreeParams{ID: id, OwnerID: owner}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Nodes) Restore(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) ([]store.Node, error) {
	out := make([]store.Node, 0, len(ids))
	for _, id := range ids {
		n, err := s.Get(ctx, owner, id)
		if err != nil {
			return nil, err
		}
		// 原位置已有同名活跃节点 → 先给要还原的改名
		exists, err := s.q.SiblingNameExists(ctx, store.SiblingNameExistsParams{
			OwnerID: owner, ParentID: n.ParentID, Name: n.Name,
		})
		if err != nil {
			return nil, err
		}
		if exists {
			fresh := timestampedName(n.Name)
			if err := s.q.RenameNodeAnyState(ctx, store.RenameNodeAnyStateParams{ID: id, OwnerID: owner, Name: fresh}); err != nil {
				return nil, err
			}
		}
		if err := s.q.RestoreSubtree(ctx, store.RestoreSubtreeParams{ID: id, OwnerID: owner}); err != nil {
			return nil, err
		}
		restored, err := s.Get(ctx, owner, id)
		if err != nil {
			return nil, err
		}
		out = append(out, restored)
	}
	return out, nil
}

func (s *Nodes) Purge(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	var blobIDs []uuid.UUID
	for _, id := range ids {
		got, err := qtx.PurgeSubtree(ctx, store.PurgeSubtreeParams{ID: id, OwnerID: owner})
		if err != nil {
			return err
		}
		for _, b := range got {
			if b != nil {
				blobIDs = append(blobIDs, *b)
			}
		}
	}
	if len(blobIDs) > 0 {
		if err := qtx.DecrementBlobRefs(ctx, blobIDs); err != nil {
			return err
		}
		// used_bytes 是逻辑记账:同 blob 被引用两次要扣两次,按出现次数累加
		sizes, err := qtx.GetBlobSizes(ctx, blobIDs)
		if err != nil {
			return err
		}
		bySize := make(map[uuid.UUID]int64, len(sizes))
		for _, r := range sizes {
			bySize[r.ID] = r.Size
		}
		var total int64
		for _, id := range blobIDs {
			total += bySize[id]
		}
		if err := qtx.SubtractUsedBytes(ctx, store.SubtractUsedBytesParams{ID: owner, UsedBytes: total}); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (s *Nodes) PurgeTrash(ctx context.Context, owner uuid.UUID) error {
	roots, err := s.q.ListTrashRootsForPurge(ctx, owner)
	if err != nil {
		return err
	}
	if len(roots) == 0 {
		return nil
	}
	return s.Purge(ctx, owner, roots)
}

// ---- 内部 ----

func (s *Nodes) ensureFolder(ctx context.Context, owner uuid.UUID, id *uuid.UUID) error {
	if id == nil {
		return nil // 根目录
	}
	n, err := s.Get(ctx, owner, *id)
	if err != nil {
		return err
	}
	if n.Kind != "folder" || n.DeletedAt.Valid {
		return errf("NOT_A_FOLDER", "目标不是文件夹")
	}
	return nil
}

// cleanName 校验名字并在冲突时自动加后缀 "name (1)"。
func (s *Nodes) cleanName(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name string) (string, error) {
	return s.cleanNameTaken(ctx, owner, parentID, name, nil)
}

// cleanNameTaken 同 cleanName,额外避开 taken 中的名字(批量操作里同批尚未落库的名)。
func (s *Nodes) cleanNameTaken(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name string, taken map[string]bool) (string, error) {
	name = strings.TrimSpace(name)
	if err := validateName(name); err != nil {
		return "", err
	}
	candidate := name
	for i := 1; i <= 100; i++ {
		exists := taken[candidate]
		if !exists {
			var err error
			exists, err = s.q.SiblingNameExists(ctx, store.SiblingNameExistsParams{
				OwnerID: owner, ParentID: parentID, Name: candidate,
			})
			if err != nil {
				return "", err
			}
		}
		if !exists {
			return candidate, nil
		}
		ext := filepath.Ext(name)
		candidate = fmt.Sprintf("%s (%d)%s", strings.TrimSuffix(name, ext), i, ext)
	}
	return "", errf("NAME_CONFLICT", "同名文件过多")
}

func validateName(name string) error {
	if name == "" || len(name) > 255 {
		return errf("INVALID_INPUT", "名称不能为空且不超过 255 字节")
	}
	if strings.ContainsAny(name, "/\x00") || name == "." || name == ".." {
		return errf("INVALID_INPUT", "名称含非法字符")
	}
	return nil
}

func timestampedName(name string) string {
	ext := filepath.Ext(name)
	return fmt.Sprintf("%s (还原 %s)%s", strings.TrimSuffix(name, ext), time.Now().Format("0102-150405"), ext)
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func tstz(t time.Time) pgtype.Timestamptz {
	return pgtype.Timestamptz{Time: t, Valid: true}
}

// ---- 游标:base64("o:<offset>"),实现可替换 ----

func decodeCursor(c *string) int32 {
	if c == nil {
		return 0
	}
	var off int32
	if _, err := fmt.Sscanf(decodeB64(*c), "o:%d", &off); err != nil || off < 0 {
		return 0
	}
	return off
}

func nextCursor(offset int32, got int, total int64) *string {
	next := offset + int32(got)
	if int64(next) >= total || got == 0 {
		return nil
	}
	s := encodeB64(fmt.Sprintf("o:%d", next))
	return &s
}

func (s *Nodes) GetWithBlob(ctx context.Context, owner, id uuid.UUID) (store.GetNodeWithBlobRow, error) {
	row, err := s.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: id, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.GetNodeWithBlobRow{}, ErrNotFound
		}
		return store.GetNodeWithBlobRow{}, err
	}
	return row, nil
}
