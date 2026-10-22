package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/store"
)

// 动态的查询与撤回(doc/23 二.2)。

const (
	activityPageSize = 50
	maxUndoItems     = 1000
	orderActivity    = "ACTIVITY"
)

type Activities struct {
	q     *store.Queries
	nodes *Nodes
}

func NewActivities(pool *pgxpool.Pool, nodes *Nodes) *Activities {
	return &Activities{q: store.New(pool), nodes: nodes}
}

// ActivityFilter 列表与按来源撤回的筛选;零值字段不限。
type ActivityFilter struct {
	ActorKind string
	ActorRef  string
	Actions   []string
	NodeID    *uuid.UUID
	BatchID   *uuid.UUID
	Since     *time.Time
	Until     *time.Time
}

func (f ActivityFilter) params(owner uuid.UUID, beforeID int64, limit int) store.ListActivitiesParams {
	p := store.ListActivitiesParams{
		OwnerID: owner, ActorKind: f.ActorKind, ActorRef: f.ActorRef, Actions: f.Actions,
		BeforeID: beforeID, Lim: int32(limit),
	}
	if p.Actions == nil {
		p.Actions = []string{}
	}
	if f.NodeID != nil {
		p.NodeID = *f.NodeID
	}
	if f.BatchID != nil {
		p.BatchID = *f.BatchID
	}
	if f.Since != nil {
		p.Since = tstz(*f.Since)
	}
	if f.Until != nil {
		p.Until = tstz(*f.Until)
	}
	return p
}

// List 新的在前。
func (a *Activities) List(ctx context.Context, owner uuid.UUID, f ActivityFilter, cursor *string, limit int) ([]store.Activity, *string, error) {
	if limit <= 0 || limit > 200 {
		limit = activityPageSize
	}
	var before int64
	if c := decodeCursor(cursor, orderActivity, false); c != nil {
		before = c.T
	}
	rows, err := a.q.ListActivities(ctx, f.params(owner, before, limit))
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) == limit {
		next = encodeCursor(cursorPayload{O: orderActivity, T: rows[len(rows)-1].ID})
	}
	return rows, next, nil
}

// ActivityCursor 从记录 id 造一个列表游标(MCP 用 before_id 翻页)。
func ActivityCursor(beforeID int64) *string {
	return encodeCursor(cursorPayload{O: orderActivity, T: beforeID})
}

// Actors 近 90 天出现过的来源(筛选下拉框)。
func (a *Activities) Actors(ctx context.Context, owner uuid.UUID) ([]store.ListActivityActorsRow, error) {
	return a.q.ListActivityActors(ctx, owner)
}

// ActivityDetail 解出 detail(GraphQL 映射用)。
func ActivityDetail(raw []byte) (from, to *string, oldName, newName string, files, bytes, undoOf int64, note string) {
	var d actDetail
	_ = json.Unmarshal(raw, &d)
	if d.From != nil {
		from = &d.From.Path
	}
	if d.To != nil {
		to = &d.To.Path
	}
	return from, to, d.OldName, d.NewName, d.Files, d.Bytes, d.UndoOf, d.Note
}

// Undoable 这类操作原则上能不能撤回(不看现在的状态;能不能真撤见 PreviewUndo)。
func Undoable(act store.Activity) bool {
	return !act.UndoneAt.Valid && act.NodeID != nil && act.Action != OpOverwrite && act.Action != OpPurge
}

// UndoSelection 撤回哪些:给 id、给一批、或按筛选(某个来源某段时间);三选一。
type UndoSelection struct {
	IDs     []int64
	BatchID *uuid.UUID
	Filter  *ActivityFilter
}

type UndoItem struct {
	Activity store.Activity
	OK       bool
	Reason   string
}

type UndoResult struct {
	Done, Failed int
	Items        []UndoItem
}

func (a *Activities) candidates(ctx context.Context, owner uuid.UUID, sel UndoSelection) ([]store.Activity, error) {
	switch {
	case len(sel.IDs) > 0:
		if len(sel.IDs) > maxUndoItems {
			return nil, errf("INVALID_INPUT", "一次最多撤回 %d 条", maxUndoItems)
		}
		return a.q.ListActivitiesByIDs(ctx, store.ListActivitiesByIDsParams{OwnerID: owner, Ids: sel.IDs})
	case sel.BatchID != nil:
		return a.q.ListActivities(ctx, ActivityFilter{BatchID: sel.BatchID}.params(owner, 0, maxUndoItems))
	case sel.Filter != nil:
		f := *sel.Filter
		if f.ActorKind == "" && f.BatchID == nil && f.NodeID == nil && f.Since == nil {
			return nil, errf("INVALID_INPUT", "按筛选撤回至少要给来源、时间或文件")
		}
		return a.q.ListActivities(ctx, f.params(owner, 0, maxUndoItems))
	}
	return nil, errf("INVALID_INPUT", "没有要撤回的")
}

// PreviewUndo 列出会撤回哪些、哪些撤不了(不改任何东西)。
func (a *Activities) PreviewUndo(ctx context.Context, owner uuid.UUID, sel UndoSelection) (*UndoResult, error) {
	return a.run(ctx, owner, sel, true)
}

// Undo 从新到旧逐条撤回,每条各自成败。撤回本身也记进动态(来源是当前设备,同一批),原记录标「已撤回」。
func (a *Activities) Undo(ctx context.Context, owner uuid.UUID, sel UndoSelection) (*UndoResult, error) {
	return a.run(WithBatch(ctx), owner, sel, false)
}

func (a *Activities) run(ctx context.Context, owner uuid.UUID, sel UndoSelection, dry bool) (*UndoResult, error) {
	acts, err := a.candidates(ctx, owner, sel)
	if err != nil {
		return nil, err
	}
	// 同一次撤回里会被还原的节点:它们现在在回收站里,但轮到依赖它们的那条时已经回来了
	restoring := map[uuid.UUID]bool{}
	for _, act := range acts {
		if act.Action == OpDelete && act.NodeID != nil && !act.UndoneAt.Valid {
			restoring[*act.NodeID] = true
		}
	}
	res := &UndoResult{Items: make([]UndoItem, 0, len(acts))}
	for _, act := range acts {
		reason, err := a.undoOne(withUndoOf(ctx, act.ID), owner, act, restoring, dry)
		if err != nil {
			return nil, err
		}
		item := UndoItem{Activity: act, OK: reason == "", Reason: reason}
		if item.OK {
			res.Done++
			if !dry {
				if err := a.q.MarkActivityUndone(ctx, store.MarkActivityUndoneParams{ID: act.ID, OwnerID: owner}); err != nil {
					slog.Warn("mark activity undone", "id", act.ID, "err", err)
				}
			}
		} else {
			res.Failed++
		}
		res.Items = append(res.Items, item)
	}
	return res, nil
}

// undoOne 返回撤不了的原因(空 = 成功或预览通过);基础设施错误才返回 err。
func (a *Activities) undoOne(ctx context.Context, owner uuid.UUID, act store.Activity, restoring map[uuid.UUID]bool, dry bool) (string, error) {
	if act.UndoneAt.Valid {
		return "已经撤回过", nil
	}
	switch act.Action {
	case OpOverwrite:
		return "覆盖掉的内容找不回来(没有历史版本)", nil
	case OpPurge:
		return "已彻底删除,找不回来", nil
	}
	if act.NodeID == nil {
		return "没有对应的文件", nil
	}
	var d actDetail
	_ = json.Unmarshal(act.Detail, &d)
	n, err := a.q.GetNode(ctx, *act.NodeID)
	if errors.Is(err, pgx.ErrNoRows) || err == nil && n.OwnerID != owner {
		return "文件已彻底删除", nil
	}
	if err != nil {
		return "", err
	}
	inTrash := n.DeletedAt.Valid && !restoring[n.ID]

	switch act.Action {
	case OpMove:
		if inTrash {
			return "文件在回收站里", nil
		}
		if d.To == nil || d.From == nil || !sameParent(n.ParentID, d.To.Parent) {
			return "之后又被移动过", nil
		}
		if reason, err := a.folderUsable(ctx, owner, d.From.Parent, restoring); reason != "" || err != nil {
			return reason, err
		}
		if dry {
			return "", nil
		}
		exists, err := a.q.SiblingNameExists(ctx, store.SiblingNameExistsParams{OwnerID: owner, ParentID: d.From.Parent, Name: n.Name})
		if err != nil {
			return "", err
		}
		if exists {
			if _, err := a.nodes.Rename(ctx, owner, n.ID, undoName(n.Name)); err != nil {
				return businessReason(err)
			}
		}
		_, err = a.nodes.Move(ctx, owner, []uuid.UUID{n.ID}, d.From.Parent)
		return businessReason(err)

	case OpRename:
		if inTrash {
			return "文件在回收站里", nil
		}
		if n.Name != d.NewName || d.OldName == "" {
			return "之后又改过名", nil
		}
		if dry {
			return "", nil
		}
		_, err := a.nodes.Rename(ctx, owner, n.ID, d.OldName)
		var se *Error
		if errors.As(err, &se) && se.Code == "NAME_CONFLICT" {
			return "原来的名字已被别的文件占用", nil
		}
		return businessReason(err)

	case OpDelete:
		if !n.DeletedAt.Valid {
			return "已经不在回收站里了", nil
		}
		if n.ParentID != nil {
			if reason, err := a.folderUsable(ctx, owner, n.ParentID, restoring); reason != "" || err != nil {
				if reason == "原文件夹在回收站里" {
					reason = "所在的文件夹也在回收站里,先撤回那次删除"
				}
				return reason, err
			}
		}
		if dry {
			return "", nil
		}
		_, err := a.nodes.Restore(ctx, owner, []uuid.UUID{n.ID})
		return businessReason(err)

	default: // 新建类与还原:撤回 = 进回收站
		if n.DeletedAt.Valid {
			return "已经在回收站里", nil
		}
		if dry {
			return "", nil
		}
		return businessReason(a.nodes.Delete(ctx, owner, []uuid.UUID{n.ID}))
	}
}

// folderUsable 撤回要放回去的文件夹还在不在(nil = 根目录,总在)。
func (a *Activities) folderUsable(ctx context.Context, owner uuid.UUID, id *uuid.UUID, restoring map[uuid.UUID]bool) (string, error) {
	if id == nil {
		return "", nil
	}
	f, err := a.q.GetNode(ctx, *id)
	if errors.Is(err, pgx.ErrNoRows) || err == nil && f.OwnerID != owner {
		return "原文件夹已彻底删除", nil
	}
	if err != nil {
		return "", err
	}
	if f.DeletedAt.Valid && !restoring[f.ID] {
		return "原文件夹在回收站里", nil
	}
	return "", nil
}

// businessReason 业务错误转成撤不了的原因,其余原样返回。
func businessReason(err error) (string, error) {
	if err == nil {
		return "", nil
	}
	var se *Error
	if errors.As(err, &se) {
		if se.Code == ErrNotFound.Code {
			return "文件已不在", nil
		}
		return se.Message, nil
	}
	return "", err
}

func undoName(name string) string {
	ext := filepath.Ext(name)
	return fmt.Sprintf("%s (撤回 %s)%s", strings.TrimSuffix(name, ext), time.Now().Format("0102-1504"), ext)
}

// Cleanup 每日:动态保留 90 天。
func (a *Activities) Cleanup(ctx context.Context) error {
	n, err := a.q.CleanupActivities(ctx)
	if n > 0 {
		slog.Info("activity cleanup", "deleted", n)
	}
	return err
}
