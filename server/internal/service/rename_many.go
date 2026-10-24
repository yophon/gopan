package service

import (
	"context"
	"errors"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/store"
)

// 批量改名(doc/27):网页、App 里选一批文件按规则改名(加序号、替换文字、改扩展名),规则在客户端算好,
// 这里只收「哪个改成什么」。要么全改、要么都不改;整批记成一组动态,能一起撤回。

// MaxRenameBatch 一次批量改名最多几项。
const MaxRenameBatch = 1000

// RenameItem 批量改名的一项。
type RenameItem struct {
	ID   uuid.UUID
	Name string
}

// RenameMany 按 items 改名,返回改后的节点(顺序同 items,名字没变的原样返回)。
// 批内互换名字(a→b、b→a)也行:先把要改的都挪到临时名,再改成目标名。
// 批内两项改成同一个名字、或者和这批之外的同名:NAME_CONFLICT,消息里带名字。
func (s *Nodes) RenameMany(ctx context.Context, owner uuid.UUID, items []RenameItem) ([]store.Node, error) {
	if len(items) == 0 {
		return nil, nil
	}
	if len(items) > MaxRenameBatch {
		return nil, errf("INVALID_INPUT", "一次最多改 %d 个", MaxRenameBatch)
	}
	seen := make(map[uuid.UUID]bool, len(items))
	for i := range items {
		items[i].Name = strings.TrimSpace(items[i].Name)
		if err := validateName(items[i].Name); err != nil {
			return nil, err
		}
		if seen[items[i].ID] {
			return nil, errf("INVALID_INPUT", "同一个文件在这批里出现了两次")
		}
		seen[items[i].ID] = true
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "mcp-write:"+owner.String()); err != nil {
		return nil, err
	}
	q := s.q.WithTx(tx)

	type change struct {
		at   int
		cur  store.Node
		name string
	}
	out := make([]store.Node, len(items))
	var changes []change
	// 批内的目标名:同一个文件夹里不能撞
	taken := make(map[string]bool, len(items))
	for i, it := range items {
		cur, err := q.GetActiveNodeForUpdate(ctx, store.GetActiveNodeForUpdateParams{ID: it.ID, OwnerID: owner})
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		parent := ""
		if cur.ParentID != nil {
			parent = cur.ParentID.String()
		}
		key := parent + "/" + it.Name
		if taken[key] {
			return nil, errf("NAME_CONFLICT", "这批里有两项都要改成「%s」", it.Name)
		}
		taken[key] = true
		out[i] = cur
		if cur.Name != it.Name {
			changes = append(changes, change{at: i, cur: cur, name: it.Name})
		}
	}
	if len(changes) == 0 {
		return out, nil
	}
	// 先挪到临时名(在事务里,别人看不到),批内互换名字时不会撞上
	for _, c := range changes {
		if _, err := q.RenameNode(ctx, store.RenameNodeParams{ID: c.cur.ID, OwnerID: owner, Name: ".gopan-rename-" + c.cur.ID.String()}); err != nil {
			return nil, err
		}
	}
	acts := make([]actItem, 0, len(changes))
	for _, c := range changes {
		n, err := q.RenameNode(ctx, store.RenameNodeParams{ID: c.cur.ID, OwnerID: owner, Name: c.name})
		if isUniqueViolation(err) {
			return nil, errf("NAME_CONFLICT", "「%s」已经有同名的文件或文件夹了", c.name)
		}
		if err != nil {
			return nil, err
		}
		out[c.at] = n
		acts = append(acts, nodeAct(OpRename, n, actDetail{OldName: c.cur.Name, NewName: n.Name}))
	}
	if err := recordActs(ctx, q, owner, acts...); err != nil {
		return nil, err
	}
	return out, tx.Commit(ctx)
}
