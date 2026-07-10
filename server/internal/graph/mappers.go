package graph

import (
	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func parseID(s string) (uuid.UUID, error) {
	id, err := uuid.Parse(s)
	if err != nil {
		return uuid.Nil, &service.Error{Code: "INVALID_INPUT", Message: "非法 ID"}
	}
	return id, nil
}

func parseIDPtr(s *string) (*uuid.UUID, error) {
	if s == nil {
		return nil, nil
	}
	id, err := parseID(*s)
	if err != nil {
		return nil, err
	}
	return &id, nil
}

func parseIDs(ss []string) ([]uuid.UUID, error) {
	out := make([]uuid.UUID, 0, len(ss))
	for _, s := range ss {
		id, err := parseID(s)
		if err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, nil
}

func idPtrStr(u *uuid.UUID) *string {
	if u == nil {
		return nil
	}
	s := u.String()
	return &s
}

func gqlUser(u store.User) *User {
	return &User{
		ID: u.ID.String(), Username: u.Username,
		QuotaBytes: u.QuotaBytes, UsedBytes: u.UsedBytes,
	}
}

func gqlAuth(r *service.AuthResult) *AuthPayload {
	return &AuthPayload{AccessToken: r.AccessToken, User: gqlUser(r.User)}
}

// gqlNode 由 store.Node 映射(无 blob 信息,M1 里全是文件夹/无预览)。
func gqlNode(n store.Node) *Node {
	out := &Node{
		ID:       n.ID.String(),
		ParentID: idPtrStr(n.ParentID),
		Name:     n.Name,
		Kind:     NodeKind(map[string]string{"file": "FILE", "folder": "FOLDER"}[n.Kind]),
		Preview:  &PreviewInfo{Kind: PreviewKindNone},
	}
	out.CreatedAt = n.CreatedAt.Time
	out.UpdatedAt = n.UpdatedAt.Time
	if n.DeletedAt.Valid {
		t := n.DeletedAt.Time
		out.DeletedAt = &t
	}
	return out
}

// nodeRow 是三个 List*Row 的公共形状。
type nodeRow struct {
	Node       store.Node
	BlobSize   *int64
	BlobMime   *string
	BlobSha256 *string
}

func gqlNodeRow(r nodeRow) *Node {
	out := gqlNode(r.Node)
	if r.Node.Kind == "file" {
		out.Size = r.BlobSize
		out.Mime = r.BlobMime
		out.Sha256 = r.BlobSha256
	}
	return out
}

func gqlPage[T any](p *service.Page[T], conv func(T) *Node) *NodePage {
	items := make([]*Node, 0, len(p.Items))
	for _, it := range p.Items {
		items = append(items, conv(it))
	}
	return &NodePage{Items: items, NextCursor: p.NextCursor, Total: int(p.Total)}
}
