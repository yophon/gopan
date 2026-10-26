package service

// 内容搜索(doc/20 第三节):按文件内容找文件,结果带命中片段与高亮,PDF 带页码。
// 文字由 worker 提取进 blob_texts(见 worker/extract.go 与 internal/fulltext)。

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/store"
)

const (
	orderContent    = "CONTENT"
	contentPageSize = 50
	maxQueryRunes   = 100
	// MaxReadChars read_document_text 一次最多读这么多字
	MaxReadChars = 50_000
)

var (
	errContentDisabled = &Error{Code: "CONTENT_SEARCH_DISABLED", Message: "本站没有开启内容搜索"}
	errQueryTooShort   = &Error{Code: "QUERY_TOO_SHORT", Message: "关键词太短:至少两个汉字或三个字母"}
)

type ContentSearch struct {
	q       *store.Queries
	enabled bool
}

func NewContentSearch(pool *pgxpool.Pool, enabled bool) *ContentSearch {
	return &ContentSearch{q: store.New(pool), enabled: enabled}
}

// ContentHit 一条结果:文件 + 命中片段。
type ContentHit struct {
	Row        store.SearchContentRow
	Snippet    string
	Highlights []fulltext.Range
	Page       int // 0 = 不分页的格式
}

type ContentPage struct {
	Items      []ContentHit
	NextCursor *string
}

// Search 搜 owner 的未删除文件;root 非空时只搜那棵子树(MCP 凭据的访问目录)。
// f 可选(doc/30):类型、修改时间、大小;f.Root 在 root 为空时当作要搜的文件夹。内容搜索只按修改时间排序。
func (s *ContentSearch) Search(ctx context.Context, owner uuid.UUID, root *uuid.UUID, q string, cursor *string, f *SearchFilter) (*ContentPage, error) {
	if !s.enabled {
		return nil, errContentDisabled
	}
	if f == nil {
		f = &SearchFilter{}
	}
	if err := f.validate(); err != nil {
		return nil, err
	}
	if root == nil {
		root = f.Root
	}
	qn := fulltext.Normalize(strings.TrimSpace(q))
	if utf8.RuneCountInString(qn) > maxQueryRunes {
		return nil, errf("INVALID_INPUT", "关键词不超过 %d 个字", maxQueryRunes)
	}
	terms := fulltext.QueryTerms(qn)
	if len(terms) == 0 {
		return nil, errQueryTooShort
	}
	var cUpdated pgtype.Timestamptz
	var cID *uuid.UUID
	if cur := decodeCursor(cursor, orderContent, true); cur != nil {
		cUpdated, cID = tstz(time.Unix(0, cur.T)), &cur.ID
	}
	var rows []store.SearchContentRow
	var err error
	if root != nil {
		var sub []store.SearchContentInSubtreeRow
		sub, err = s.q.SearchContentInSubtree(ctx, store.SearchContentInSubtreeParams{
			Q: qn, Terms: terms, OwnerID: owner, CUpdated: cUpdated, CID: cID, Lim: contentPageSize + 1, Root: *root,
			Types: optTypes(f.Types), After: optTime(f.After), Before: optTime(f.Before), MinSize: f.MinSize, MaxSize: f.MaxSize,
		})
		rows = mapSlice(sub, func(r store.SearchContentInSubtreeRow) store.SearchContentRow { return store.SearchContentRow(r) })
	} else {
		rows, err = s.q.SearchContent(ctx, store.SearchContentParams{
			Q: qn, Terms: terms, OwnerID: owner, CUpdated: cUpdated, CID: cID, Lim: contentPageSize + 1,
			Types: optTypes(f.Types), After: optTime(f.After), Before: optTime(f.Before), MinSize: f.MinSize, MaxSize: f.MaxSize,
		})
	}
	if err != nil {
		return nil, err
	}
	page := &ContentPage{}
	if len(rows) > contentPageSize {
		rows = rows[:contentPageSize]
		last := rows[len(rows)-1]
		page.NextCursor = encodeCursor(cursorPayload{O: orderContent, D: true, T: last.UpdatedAt.Time.UnixNano(), ID: last.ID})
	}
	for _, r := range rows {
		snippet := r.Snippet
		if r.SnippetStart > 1 {
			snippet = "…" + snippet
		}
		if int(r.SnippetStart)-1+utf8.RuneCountInString(r.Snippet) < int(r.ContentLen) {
			snippet += "…"
		}
		page.Items = append(page.Items, ContentHit{
			Row: r, Snippet: snippet, Highlights: fulltext.Highlights(snippet, qn),
			Page: fulltext.PageOf(r.PageStarts, int(r.Pos)),
		})
	}
	return page, nil
}

// DocText 一段提取出的文字。
type DocText struct {
	Name       string
	Status     string
	Text       string
	Offset     int // 本段起点(字符)
	NextOffset int
	Total      int
	EOF        bool
	Page       int // 本段起点所在页,0 = 不分页
}

// ReadText 按字符偏移读一个文件提取出的文字(PDF / Office 也能读)。
func (s *ContentSearch) ReadText(ctx context.Context, owner, nodeID uuid.UUID, offset, maxChars int) (*DocText, error) {
	if !s.enabled {
		return nil, errContentDisabled
	}
	if offset < 0 || maxChars <= 0 || maxChars > MaxReadChars {
		return nil, errf("INVALID_INPUT", "offset 必须非负,max_chars 必须在 1~%d", MaxReadChars)
	}
	r, err := s.q.ReadNodeText(ctx, store.ReadNodeTextParams{Off: int32(offset), Len: int32(maxChars), ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		// 文件不存在,或还没提取过:区分开,Agent 才知道是该等还是该放弃
		return nil, s.notIndexed(ctx, owner, nodeID)
	}
	if err != nil {
		return nil, err
	}
	switch r.Status {
	case "unsupported":
		return nil, errf("UNSUPPORTED_TYPE", "这种格式提取不了文字")
	case "failed":
		return nil, errf("EXTRACT_FAILED", "文字提取失败(文件可能损坏或加密)")
	}
	n := utf8.RuneCountInString(r.Chunk)
	return &DocText{
		Name: r.Name, Status: r.Status, Text: r.Chunk,
		Offset: offset, NextOffset: offset + n, Total: int(r.Total), EOF: offset+n >= int(r.Total),
		Page: fulltext.PageOf(r.PageStarts, offset+1),
	}, nil
}

func (s *ContentSearch) notIndexed(ctx context.Context, owner, nodeID uuid.UUID) error {
	n, err := s.q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && n.Kind != "file") {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	return errf("NOT_INDEXED", "这个文件的文字还没提取,稍后再试")
}
