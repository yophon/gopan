package service

// 搜索筛选(doc/30):类型、修改时间、大小、只搜某个文件夹的子树、排序。
// 不带筛选的文件名搜索照旧走 Search(按修改时间的键集翻页,旧客户端的游标还能用);带了走 SearchFiltered(偏移翻页)。

import (
	"context"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"

	"github.com/yophon/gopan/server/internal/store"
)

const (
	orderSearchFiltered = "SEARCH_FILTERED"
	// 偏移翻页最多翻到这里(再往后请缩小筛选)
	maxSearchOffset = 10_000
)

// SearchType 能筛的类型:gopan_file_category 的取值(迁移 00027,与空间分析一致)加 folder。
var searchTypes = map[string]bool{
	"folder": true, "image": true, "video": true, "audio": true, "document": true, "archive": true, "text": true, "other": true,
}

type SearchFilter struct {
	Types   []string
	After   *time.Time
	Before  *time.Time
	MinSize *int64
	MaxSize *int64
	Root    *uuid.UUID
	// UPDATED(默认)/ NAME / SIZE
	Order string
}

// conditions 设了任何条件(只换排序不算)
func (f *SearchFilter) conditions() bool {
	return f != nil && (len(f.Types) > 0 || f.After != nil || f.Before != nil || f.MinSize != nil || f.MaxSize != nil || f.Root != nil)
}

// Used 要走筛选搜索:有条件,或者换了排序
func (f *SearchFilter) Used() bool {
	return f != nil && (f.conditions() || (f.Order != "" && f.Order != "UPDATED"))
}

func (f *SearchFilter) validate() error {
	for _, t := range f.Types {
		if !searchTypes[t] {
			return errf("INVALID_INPUT", "不认识的类型 %q", t)
		}
	}
	if (f.MinSize != nil && *f.MinSize < 0) || (f.MaxSize != nil && *f.MaxSize < 0) {
		return errf("INVALID_INPUT", "大小不能是负数")
	}
	switch f.Order {
	case "", "UPDATED", "NAME", "SIZE":
	default:
		return errf("INVALID_INPUT", "不认识的排序 %q", f.Order)
	}
	return nil
}

func optTime(t *time.Time) pgtype.Timestamptz {
	if t == nil {
		return pgtype.Timestamptz{}
	}
	return tstz(*t)
}

func optTypes(t []string) []string {
	if len(t) == 0 {
		return nil
	}
	return t
}

// SearchFiltered 带筛选的文件名搜索;关键词为空时只按筛选找(没有任何条件就什么都不返回)。
func (s *Nodes) SearchFiltered(ctx context.Context, owner uuid.UUID, q string, f SearchFilter, cursor *string) (*Page[store.SearchNodesRow], error) {
	if err := f.validate(); err != nil {
		return nil, err
	}
	kw := strings.TrimSpace(q)
	if kw == "" && !f.conditions() {
		return &Page[store.SearchNodesRow]{}, nil
	}
	off := 0
	if cur := decodeCursor(cursor, orderSearchFiltered, false); cur != nil && cur.Off > 0 {
		off = cur.Off
	}
	order := f.Order
	if order == "" {
		order = "UPDATED"
	}
	rows, err := s.q.SearchNodesFiltered(ctx, store.SearchNodesFilteredParams{
		OwnerID: owner, Q: kw, Root: f.Root, Types: optTypes(f.Types),
		After: optTime(f.After), Before: optTime(f.Before), MinSize: f.MinSize, MaxSize: f.MaxSize,
		Sort: order, Off: int32(off), Lim: pageSize,
	})
	if err != nil {
		return nil, err
	}
	total, err := s.q.CountSearchNodesFiltered(ctx, store.CountSearchNodesFilteredParams{
		OwnerID: owner, Q: kw, Root: f.Root, Types: optTypes(f.Types),
		After: optTime(f.After), Before: optTime(f.Before), MinSize: f.MinSize, MaxSize: f.MaxSize,
	})
	if err != nil {
		return nil, err
	}
	page := &Page[store.SearchNodesRow]{
		Items: mapSlice(rows, func(r store.SearchNodesFilteredRow) store.SearchNodesRow { return store.SearchNodesRow(r) }),
		Total: total,
	}
	if next := off + len(rows); len(rows) == pageSize && int64(next) < total && next < maxSearchOffset {
		page.NextCursor = encodeCursor(cursorPayload{O: orderSearchFiltered, Off: next})
	}
	return page, nil
}
