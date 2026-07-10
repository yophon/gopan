package graph

import (
	"context"
	"sort"

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
		n := conv(it)
		n.InList = true
		items = append(items, n)
	}
	return &NodePage{Items: items, NextCursor: p.NextCursor, Total: int(p.Total)}
}

func gqlPreview(p service.PreviewInfo) *PreviewInfo {
	out := &PreviewInfo{
		Kind:        PreviewKind(p.Kind),
		ThumbURL:    p.ThumbURL,
		LargeURL:    p.LargeURL,
		ContentURL:  p.ContentURL,
		DurationSec: p.DurationSec,
	}
	if p.Status != nil {
		st := TaskStatus(*p.Status)
		out.Status = &st
	}
	return out
}

func gqlSession(v *service.SessionView) *UploadSession {
	urls := make([]*PartURL, 0, len(v.PartURLs))
	for n, u := range v.PartURLs {
		urls = append(urls, &PartURL{PartNumber: n, URL: u})
	}
	sort.Slice(urls, func(i, j int) bool { return urls[i].PartNumber < urls[j].PartNumber })
	uploaded := make([]int, len(v.Uploaded))
	copy(uploaded, v.Uploaded)
	return &UploadSession{
		ID:            v.Session.ID.String(),
		PartSize:      int(v.Session.PartSize),
		PartUrls:      urls,
		UploadedParts: uploaded,
		ExpiresAt:     v.Session.ExpiresAt.Time,
		Status:        v.Session.Status,
	}
}

// getNodeFull 取单节点并带上 blob 元信息(size/mime/sha256)。
func (r *Resolver) getNodeFull(ctx context.Context, owner, id uuid.UUID) (*Node, error) {
	row, err := r.Nodes.GetWithBlob(ctx, owner, id)
	if err != nil {
		return nil, err
	}
	return gqlNodeRow(nodeRow{Node: store.Node{
		ID: row.ID, OwnerID: row.OwnerID, ParentID: row.ParentID, Name: row.Name,
		Kind: row.Kind, BlobID: row.BlobID, DeletedAt: row.DeletedAt,
		CreatedAt: row.CreatedAt, UpdatedAt: row.UpdatedAt,
	}, BlobSize: row.BlobSize, BlobMime: row.BlobMime, BlobSha256: row.BlobSha256}), nil
}
