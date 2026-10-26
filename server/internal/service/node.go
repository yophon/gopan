package service

import (
	"context"
	"encoding/json"
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
	pool interface {
		Begin(context.Context) (pgx.Tx, error)
	}
	q      *store.Queries
	notify *Notifier
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
	rows, err := s.listChildrenPage(ctx, owner, parentID, decodeCursor(cursor, order, desc), order, desc)
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountChildren(ctx, store.CountChildrenParams{OwnerID: owner, ParentID: parentID})
	if err != nil {
		return nil, err
	}
	return &Page[store.ListChildrenRow]{Items: rows, Total: total, NextCursor: childrenNextCursor(rows, order, desc)}, nil
}

// listChildrenPage 按游标分派:无游标走首页查询,有游标走对应排序的 keyset 查询。
// 各 keyset 查询的行形状与 ListChildrenRow 完全一致,靠结构体转换收敛到同一类型。
func (s *Nodes) listChildrenPage(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, cur *cursorPayload, order string, desc bool) ([]store.ListChildrenRow, error) {
	if cur == nil {
		return s.q.ListChildren(ctx, store.ListChildrenParams{
			OwnerID: owner, ParentID: parentID,
			OrderBy: order, Descending: desc, PageLimit: pageSize,
		})
	}
	switch order {
	case "SIZE":
		var size int64
		if cur.Size != nil {
			size = *cur.Size
		}
		if desc {
			rows, err := s.q.ListChildrenSizeDesc(ctx, store.ListChildrenSizeDescParams{
				OwnerID: owner, ParentID: parentID, PageLimit: pageSize,
				CKind: cur.Kind, CSizeNull: cur.Size == nil, CSize: size, CID: cur.ID,
			})
			return mapSlice(rows, func(r store.ListChildrenSizeDescRow) store.ListChildrenRow { return store.ListChildrenRow(r) }), err
		}
		rows, err := s.q.ListChildrenSizeAsc(ctx, store.ListChildrenSizeAscParams{
			OwnerID: owner, ParentID: parentID, PageLimit: pageSize,
			CKind: cur.Kind, CSizeNull: cur.Size == nil, CSize: size, CID: cur.ID,
		})
		return mapSlice(rows, func(r store.ListChildrenSizeAscRow) store.ListChildrenRow { return store.ListChildrenRow(r) }), err
	case "UPDATED_AT":
		t := tstz(time.Unix(0, cur.T))
		if desc {
			rows, err := s.q.ListChildrenUpdatedDesc(ctx, store.ListChildrenUpdatedDescParams{
				OwnerID: owner, ParentID: parentID, PageLimit: pageSize,
				CKind: cur.Kind, CUpdated: t, CID: cur.ID,
			})
			return mapSlice(rows, func(r store.ListChildrenUpdatedDescRow) store.ListChildrenRow { return store.ListChildrenRow(r) }), err
		}
		rows, err := s.q.ListChildrenUpdatedAsc(ctx, store.ListChildrenUpdatedAscParams{
			OwnerID: owner, ParentID: parentID, PageLimit: pageSize,
			CKind: cur.Kind, CUpdated: t, CID: cur.ID,
		})
		return mapSlice(rows, func(r store.ListChildrenUpdatedAscRow) store.ListChildrenRow { return store.ListChildrenRow(r) }), err
	default: // NAME
		if desc {
			rows, err := s.q.ListChildrenNameDesc(ctx, store.ListChildrenNameDescParams{
				OwnerID: owner, ParentID: parentID, PageLimit: pageSize,
				CKind: cur.Kind, CName: cur.Name, CID: cur.ID,
			})
			return mapSlice(rows, func(r store.ListChildrenNameDescRow) store.ListChildrenRow { return store.ListChildrenRow(r) }), err
		}
		rows, err := s.q.ListChildrenNameAsc(ctx, store.ListChildrenNameAscParams{
			OwnerID: owner, ParentID: parentID, PageLimit: pageSize,
			CKind: cur.Kind, CName: cur.Name, CID: cur.ID,
		})
		return mapSlice(rows, func(r store.ListChildrenNameAscRow) store.ListChildrenRow { return store.ListChildrenRow(r) }), err
	}
}

func (s *Nodes) Search(ctx context.Context, owner uuid.UUID, q string, cursor *string) (*Page[store.SearchNodesRow], error) {
	kw := strings.TrimSpace(q)
	if kw == "" {
		return &Page[store.SearchNodesRow]{}, nil
	}
	kwp := &kw
	var rows []store.SearchNodesRow
	var err error
	if cur := decodeCursor(cursor, orderSearch, true); cur != nil {
		after, aerr := s.q.SearchNodesAfter(ctx, store.SearchNodesAfterParams{
			OwnerID: owner, Column2: kwp, Limit: pageSize,
			CUpdated: tstz(time.Unix(0, cur.T)), CID: cur.ID,
		})
		rows, err = mapSlice(after, func(r store.SearchNodesAfterRow) store.SearchNodesRow { return store.SearchNodesRow(r) }), aerr
	} else {
		rows, err = s.q.SearchNodes(ctx, store.SearchNodesParams{OwnerID: owner, Column2: kwp, Limit: pageSize})
	}
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountSearchNodes(ctx, store.CountSearchNodesParams{OwnerID: owner, Column2: kwp})
	if err != nil {
		return nil, err
	}
	next := timelineNextCursor(rows, orderSearch, func(r store.SearchNodesRow) (time.Time, uuid.UUID) {
		return r.UpdatedAt.Time, r.ID
	})
	return &Page[store.SearchNodesRow]{Items: rows, Total: total, NextCursor: next}, nil
}

// SearchInSubtree 访客搜索:范围限定在分享根的子树内。
func (s *Nodes) SearchInSubtree(ctx context.Context, root uuid.UUID, q string, cursor *string) (*Page[store.SearchNodesInSubtreeRow], error) {
	kw := strings.TrimSpace(q)
	if kw == "" {
		return &Page[store.SearchNodesInSubtreeRow]{}, nil
	}
	kwp := &kw
	var rows []store.SearchNodesInSubtreeRow
	var err error
	if cur := decodeCursor(cursor, orderSearch, true); cur != nil {
		after, aerr := s.q.SearchNodesInSubtreeAfter(ctx, store.SearchNodesInSubtreeAfterParams{
			ID: root, Column2: kwp, Limit: pageSize,
			CUpdated: tstz(time.Unix(0, cur.T)), CID: cur.ID,
		})
		rows, err = mapSlice(after, func(r store.SearchNodesInSubtreeAfterRow) store.SearchNodesInSubtreeRow {
			return store.SearchNodesInSubtreeRow(r)
		}), aerr
	} else {
		rows, err = s.q.SearchNodesInSubtree(ctx, store.SearchNodesInSubtreeParams{ID: root, Column2: kwp, Limit: pageSize})
	}
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountSearchNodesInSubtree(ctx, store.CountSearchNodesInSubtreeParams{ID: root, Column2: kwp})
	if err != nil {
		return nil, err
	}
	next := timelineNextCursor(rows, orderSearch, func(r store.SearchNodesInSubtreeRow) (time.Time, uuid.UUID) {
		return r.UpdatedAt.Time, r.ID
	})
	return &Page[store.SearchNodesInSubtreeRow]{Items: rows, Total: total, NextCursor: next}, nil
}

func (s *Nodes) Trash(ctx context.Context, owner uuid.UUID, cursor *string) (*Page[store.ListTrashRow], error) {
	var rows []store.ListTrashRow
	var err error
	if cur := decodeCursor(cursor, orderTrash, true); cur != nil {
		after, aerr := s.q.ListTrashAfter(ctx, store.ListTrashAfterParams{
			OwnerID: owner, Limit: pageSize,
			CDeleted: tstz(time.Unix(0, cur.T)), CID: cur.ID,
		})
		rows, err = mapSlice(after, func(r store.ListTrashAfterRow) store.ListTrashRow { return store.ListTrashRow(r) }), aerr
	} else {
		rows, err = s.q.ListTrash(ctx, store.ListTrashParams{OwnerID: owner, Limit: pageSize})
	}
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountTrash(ctx, owner)
	if err != nil {
		return nil, err
	}
	next := timelineNextCursor(rows, orderTrash, func(r store.ListTrashRow) (time.Time, uuid.UUID) {
		return r.DeletedAt.Time, r.ID
	})
	return &Page[store.ListTrashRow]{Items: rows, Total: total, NextCursor: next}, nil
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
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	n, err := qtx.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parentID,
		Name: name, Kind: "folder",
	})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件或文件夹已存在")
	}
	if err != nil {
		return store.Node{}, err
	}
	if err := recordActs(ctx, qtx, owner, nodeAct(OpMkdir, n, actDetail{})); err != nil {
		return store.Node{}, err
	}
	return n, tx.Commit(ctx)
}

// CreateFolderExact 按给定的名字建文件夹,同名就报 NAME_CONFLICT(WebDAV MKCOL 的语义,不自动改名)。
func (s *Nodes) CreateFolderExact(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name string) (store.Node, error) {
	if err := validateName(name); err != nil {
		return store.Node{}, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	n, err := qtx.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parentID, Name: name, Kind: "folder",
	})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件或文件夹已存在")
	}
	if err != nil {
		return store.Node{}, err
	}
	if err := recordActs(ctx, qtx, owner, nodeAct(OpMkdir, n, actDetail{})); err != nil {
		return store.Node{}, err
	}
	return n, tx.Commit(ctx)
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
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	n, err := qtx.RenameNode(ctx, store.RenameNodeParams{ID: id, OwnerID: owner, Name: name})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件或文件夹已存在")
	}
	if err != nil {
		return store.Node{}, err
	}
	if cur.Name != n.Name {
		if err := recordActs(ctx, qtx, owner, nodeAct(OpRename, n, actDetail{OldName: cur.Name, NewName: n.Name})); err != nil {
			return store.Node{}, err
		}
	}
	return n, tx.Commit(ctx)
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
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "mcp-write:"+owner.String()); err != nil {
		return nil, err
	}
	qtx := s.q.WithTx(tx)

	out := make([]store.Node, 0, len(ids))
	var acts []actItem
	for _, id := range ids {
		prev, err := s.Get(ctx, owner, id)
		if err != nil {
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
		// 新旧两条祖先链都要标脏(id 含自身,向上即新链)
		if err := qtx.MarkAncestorsStale(ctx, id); err != nil {
			return nil, err
		}
		if prev.ParentID != nil {
			if err := qtx.MarkAncestorsStale(ctx, *prev.ParentID); err != nil {
				return nil, err
			}
		}
		out = append(out, n)
		if !sameParent(prev.ParentID, target) {
			acts = append(acts, nodeAct(OpMove, n, actDetail{From: &actPlace{Parent: prev.ParentID}, To: &actPlace{Parent: target}}))
		}
	}
	if err := recordActs(ctx, qtx, owner, acts...); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return out, nil
}

func sameParent(a, b *uuid.UUID) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
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
	if total > 0 {
		locked, err := qtx.GetUserByIDForUpdate(ctx, owner)
		if err != nil {
			return nil, err
		}
		if locked.UsedBytes+total > locked.QuotaBytes {
			return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
		}
	}

	newIDs := make([]uuid.UUID, len(entries))
	out := make([]store.Node, 0, len(rootIdx))
	var acts []actItem
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
		if e.parent == -1 {
			src := ids[len(acts)]
			files, bytes := copyTotals(entries, i)
			acts = append(acts, nodeAct(OpCopy, n, actDetail{Source: &src, Files: files, Bytes: bytes}))
		}
	}
	if err := recordActs(ctx, qtx, owner, acts...); err != nil {
		return nil, err
	}
	if total > 0 {
		if err := qtx.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: total}); err != nil {
			return nil, err
		}
	}
	if target != nil {
		if err := qtx.MarkAncestorsStale(ctx, *target); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	s.notify.CheckQuota(ctx, owner)
	for _, ri := range rootIdx {
		n, err := s.q.GetNode(ctx, newIDs[ri])
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, nil
}

// copyTotals 清单里从第 root 项(一个复制的顶层)开始、到下一个顶层之前的文件数与字节。
func copyTotals(entries []copyEntry, root int) (files, bytes int64) {
	for i := root; i < len(entries); i++ {
		if i > root && entries[i].parent == -1 {
			break
		}
		if entries[i].kind == "file" {
			files++
			bytes += entries[i].size
		}
	}
	return files, bytes
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
	ctx = WithActor(ctx, Actor{Kind: ActorSystem, Label: "回收站到期清理"})
	purged := 0
	for owner, ids := range byOwner {
		if err := s.Purge(ctx, owner, ids); err != nil {
			return purged, err
		}
		purged += len(ids)
	}
	return purged, nil
}

// Delete 把节点(整棵子树)移进回收站。任何一个 id 不存在或不属于 owner 就整批返回 ErrNotFound、一个都不删,
// 与 rename/move/restore 一致;已在回收站里的节点再删一次是空操作。
func (s *Nodes) Delete(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) error {
	nodes := make([]store.Node, 0, len(ids))
	for _, id := range ids {
		n, err := s.Get(ctx, owner, id)
		if err != nil {
			return err
		}
		nodes = append(nodes, n)
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	var acts []actItem
	for _, n := range nodes {
		res, err := qtx.SoftDeleteSubtree(ctx, store.SoftDeleteSubtreeParams{ID: n.ID, OwnerID: owner})
		if err != nil {
			return err
		}
		if err := qtx.MarkAncestorsStale(ctx, n.ID); err != nil {
			return err
		}
		// 已在回收站里的(或随同批里的上级一起进去了的)不再记一笔
		if res.Nodes > 0 {
			acts = append(acts, nodeAct(OpDelete, n, actDetail{Files: res.Files, Bytes: res.Bytes}))
		}
	}
	if err := recordActs(ctx, qtx, owner, acts...); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	if len(acts) > 0 {
		s.checkMassDelete(ctx, owner)
	}
	return nil
}

func (s *Nodes) Restore(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) ([]store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	qtx := s.q.WithTx(tx)
	out := make([]store.Node, 0, len(ids))
	var acts []actItem
	for _, id := range ids {
		n, err := qtx.GetNode(ctx, id)
		if errors.Is(err, pgx.ErrNoRows) || err == nil && n.OwnerID != owner {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		wasDeleted := n.DeletedAt.Valid
		// 原位置已有同名活跃节点 → 先给要还原的改名
		exists, err := qtx.SiblingNameExists(ctx, store.SiblingNameExistsParams{
			OwnerID: owner, ParentID: n.ParentID, Name: n.Name,
		})
		if err != nil {
			return nil, err
		}
		if exists && wasDeleted {
			fresh := timestampedName(n.Name)
			if err := qtx.RenameNodeAnyState(ctx, store.RenameNodeAnyStateParams{ID: id, OwnerID: owner, Name: fresh}); err != nil {
				return nil, err
			}
		}
		if err := qtx.RestoreSubtree(ctx, store.RestoreSubtreeParams{ID: id, OwnerID: owner}); err != nil {
			return nil, err
		}
		if err := qtx.MarkAncestorsStale(ctx, id); err != nil {
			return nil, err
		}
		restored, err := qtx.GetNode(ctx, id)
		if err != nil {
			return nil, err
		}
		out = append(out, restored)
		if wasDeleted {
			d := actDetail{}
			if restored.Name != n.Name {
				d.OldName, d.NewName = n.Name, restored.Name
			}
			acts = append(acts, nodeAct(OpRestore, restored, d))
		}
	}
	if err := recordActs(ctx, qtx, owner, acts...); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
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
	type purged struct {
		node  store.Node
		blobs []uuid.UUID
	}
	var roots []purged
	var versions []store.NodeVersion
	for _, id := range ids {
		n, err := qtx.GetNode(ctx, id)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		// 标脏在删之前:行删掉后就爬不了父链了
		if err := qtx.MarkAncestorsStale(ctx, id); err != nil {
			return err
		}
		// 历史版本也在节点行删掉之前收走,一起退引用退配额
		vs, err := qtx.PurgeSubtreeVersions(ctx, store.PurgeSubtreeVersionsParams{ID: id, OwnerID: owner})
		if err != nil {
			return err
		}
		versions = append(versions, vs...)
		got, err := qtx.PurgeSubtree(ctx, store.PurgeSubtreeParams{ID: id, OwnerID: owner})
		if err != nil {
			return err
		}
		var mine []uuid.UUID
		for _, b := range got {
			if b != nil {
				blobIDs = append(blobIDs, *b)
				mine = append(mine, *b)
			}
		}
		if n.ID != uuid.Nil && n.OwnerID == owner {
			roots = append(roots, purged{node: n, blobs: mine})
		}
	}
	bySize := map[uuid.UUID]int64{}
	if len(blobIDs) > 0 {
		sizes, err := qtx.GetBlobSizes(ctx, blobIDs)
		if err != nil {
			return err
		}
		for _, r := range sizes {
			bySize[r.ID] = r.Size
		}
	}
	acts := make([]actItem, 0, len(roots))
	for _, r := range roots {
		d := actDetail{Files: int64(len(r.blobs))}
		for _, b := range r.blobs {
			d.Bytes += bySize[b]
		}
		acts = append(acts, nodeAct(OpPurge, r.node, d))
	}
	if err := recordActs(ctx, qtx, owner, acts...); err != nil {
		return err
	}
	if err := releaseVersions(ctx, qtx, versions); err != nil {
		return err
	}
	if len(blobIDs) > 0 {
		if err := decrementBlobRefs(ctx, qtx, blobIDs); err != nil {
			return err
		}
		// used_bytes 是逻辑记账:同 blob 被引用两次要扣两次,按出现次数累加
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

// decrementBlobRefs 按出现次数退引用:子树里两个文件指向同一份内容,要退两次
// (DecrementBlobRefs 用 = ANY,重复的 id 只会退一次)。
func decrementBlobRefs(ctx context.Context, q *store.Queries, ids []uuid.UUID) error {
	counts := map[uuid.UUID]int32{}
	for _, id := range ids {
		counts[id]++
	}
	for id, n := range counts {
		if err := q.DecrementBlobRefN(ctx, store.DecrementBlobRefNParams{ID: id, N: n}); err != nil {
			return err
		}
	}
	return nil
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

// Path 属主自己的节点从根开始的路径(如 /订阅/某播客)。
func (s *Nodes) Path(ctx context.Context, owner, id uuid.UUID) (string, error) {
	if _, err := s.Get(ctx, owner, id); err != nil {
		return "", err
	}
	return s.q.NodePath(ctx, id)
}

// ---- 大量删除提醒(doc/23 二.3) ----

const (
	massDeleteWindow = 10 * time.Minute
	massDeleteFiles  = 200
	massDeleteBytes  = 2 << 30
)

// unattended 不需要人在场的来源:WebDAV 客户端、Agent、图床工具。网页和 App 上的删除是自己点的,不提醒。
func unattended(kind string) bool {
	return kind == ActorWebDAV || kind == ActorAgent || kind == ActorImg
}

var actorKindLabel = map[string]string{ActorWebDAV: "WebDAV", ActorAgent: "Agent", ActorImg: "图床密钥"}

// checkMassDelete 同一个凭据 10 分钟内删进回收站 ≥ 200 个文件或 ≥ 2 GiB 时提醒,每小时最多一次。
// 在自己的(子)事务里查和写:出错不影响已经提交的删除。
func (s *Nodes) checkMassDelete(ctx context.Context, owner uuid.UUID) {
	a := ActorFrom(ctx)
	if s.notify == nil || !unattended(a.Kind) {
		return
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return
	}
	defer tx.Rollback(ctx)
	since := time.Now().Add(-massDeleteWindow)
	sum, err := s.q.WithTx(tx).SumRecentDeletes(ctx, store.SumRecentDeletesParams{
		OwnerID: owner, ActorKind: a.Kind, ActorRef: a.Ref, At: tstz(since),
	})
	if err != nil || sum.Files < massDeleteFiles && sum.Bytes < massDeleteBytes {
		return
	}
	who := a.Label
	if who == "" {
		who = actorKindLabel[a.Kind]
	}
	s.notify.NotifyTx(ctx, tx, owner, Event{
		Kind: EvMassDelete, Level: LvAlert,
		Title:    fmt.Sprintf("%s(%s)10 分钟内删除了 %d 个文件", who, actorKindLabel[a.Kind], sum.Files),
		Body:     fmt.Sprintf("共 %s,都还在回收站里。点开看是哪些,可以一键撤回;不是你的操作就去吊销这个凭据。", humanBytes(sum.Bytes)),
		Target:   &Target{Kind: TgActivity, ID: a.Kind + ":" + a.Ref, ParentID: since.UTC().Format(time.RFC3339)},
		GroupKey: "mass_delete:" + a.Kind + ":" + a.Ref, Once: time.Hour,
	})
	_ = tx.Commit(ctx)
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

// ---- 游标:base64(JSON 排序键元组),对外仍是不透明字符串(v1 的 offset 游标自然失效,按首页处理) ----

const (
	orderSearch = "SEARCH"
	orderTrash  = "TRASH"
)

type cursorPayload struct {
	V    int       `json:"v"`           // 版本,现为 2
	O    string    `json:"o"`           // NAME / SIZE / UPDATED_AT / SEARCH / TRASH
	D    bool      `json:"d"`           // 方向
	Kind string    `json:"k,omitempty"` // children:folders-first 的 kind 键
	Name string    `json:"n,omitempty"` // NAME 键
	Size *int64    `json:"s,omitempty"` // SIZE 键,nil = 该行无 blob(文件夹)
	T    int64     `json:"t,omitempty"` // 时间键 unixnano(UPDATED_AT / SEARCH / TRASH)
	Off  int       `json:"off,omitempty"` // 偏移翻页(SEARCH_FILTERED)
	ID   uuid.UUID `json:"id"`          // 平局决胜
}

// decodeCursor 解析游标并校验与本次请求的排序一致;不一致或解析失败按首页处理
// (用户翻页途中切排序、或旧版本游标,都归为"从头来")。
func decodeCursor(c *string, order string, desc bool) *cursorPayload {
	if c == nil || *c == "" {
		return nil
	}
	var p cursorPayload
	if err := json.Unmarshal([]byte(decodeB64(*c)), &p); err != nil {
		return nil
	}
	if p.V != 2 || p.O != order || (order != orderSearch && order != orderTrash && order != orderContent && p.D != desc) {
		return nil
	}
	return &p
}

func encodeCursor(p cursorPayload) *string {
	p.V = 2
	b, err := json.Marshal(p)
	if err != nil {
		return nil
	}
	s := encodeB64(string(b))
	return &s
}

// childrenNextCursor 从本页末行提取排序键;不满一页即到底。
func childrenNextCursor(rows []store.ListChildrenRow, order string, desc bool) *string {
	if len(rows) < pageSize {
		return nil
	}
	last := rows[len(rows)-1]
	p := cursorPayload{O: order, D: desc, Kind: last.Kind, ID: last.ID}
	switch order {
	case "SIZE":
		p.Size = last.BlobSize
	case "UPDATED_AT":
		p.T = last.UpdatedAt.Time.UnixNano()
	default:
		p.Name = last.Name
	}
	return encodeCursor(p)
}

// timelineNextCursor 搜索/回收站共用:固定"时间 DESC, id"排序的游标。
func timelineNextCursor[T any](rows []T, order string, key func(T) (time.Time, uuid.UUID)) *string {
	if len(rows) < pageSize {
		return nil
	}
	t, id := key(rows[len(rows)-1])
	return encodeCursor(cursorPayload{O: order, D: true, T: t.UnixNano(), ID: id})
}

func mapSlice[S, D any](in []S, f func(S) D) []D {
	out := make([]D, len(in))
	for i, v := range in {
		out[i] = f(v)
	}
	return out
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
