package graph

// This file will not be regenerated automatically.

import "github.com/yophon/gopan/server/internal/service"

func gqlContentHit(h service.ContentHit) *ContentHit {
	r := h.Row
	n := &Node{
		ID:       r.ID.String(),
		ParentID: idPtrStr(r.ParentID),
		Name:     r.Name,
		Kind:     NodeKindFile,
		Size:     &r.BlobSize,
		Mime:     &r.BlobMime,
		Sha256:   &r.BlobSha256,
		InList:   true, // 列表路径:预览走零查询
	}
	n.CreatedAt = r.CreatedAt.Time
	n.UpdatedAt = r.UpdatedAt.Time
	out := &ContentHit{Node: n, Snippet: h.Snippet, Highlights: make([]*TextRange, 0, len(h.Highlights))}
	for _, x := range h.Highlights {
		out.Highlights = append(out.Highlights, &TextRange{Start: x.Start, Length: x.Length})
	}
	if h.Page > 0 {
		p := h.Page
		out.Page = &p
	}
	return out
}
