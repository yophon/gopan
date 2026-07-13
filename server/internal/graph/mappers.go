package graph

import (
	"context"
	"sort"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/httpx"
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
		IsAdmin: u.IsAdmin,
	}
}

func gqlAdminUser(u store.User) *AdminUser {
	return &AdminUser{
		ID: u.ID.String(), Username: u.Username,
		IsAdmin: u.IsAdmin, Disabled: u.DisabledAt.Valid,
		QuotaBytes: u.QuotaBytes, UsedBytes: u.UsedBytes,
		CreatedAt: u.CreatedAt.Time,
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
	if n.Kind == "folder" {
		sb, sc, st := n.SubtreeBytes, n.SubtreeCount, n.StatsStale
		out.SubtreeBytes, out.SubtreeCount, out.StatsStale = &sb, &sc, &st
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

func gqlShare(r store.ListMySharesRow) *Share {
	node := &Node{
		ID:       r.NodeID.String(),
		ParentID: idPtrStr(r.NodeParentID),
		Name:     r.NodeName,
		Kind:     NodeKind(map[string]string{"file": "FILE", "folder": "FOLDER"}[r.NodeKind]),
		InList:   true, // 分享列表里的节点预览走零查询乐观路径
	}
	node.CreatedAt = r.NodeCreatedAt.Time
	node.UpdatedAt = r.NodeUpdatedAt.Time
	if r.NodeKind == "file" {
		node.Size = r.BlobSize
		node.Mime = r.BlobMime
		node.Sha256 = r.BlobSha256
	}
	out := &Share{
		ID:          r.ID.String(),
		Token:       r.Token,
		Node:        node,
		HasPassword: r.PasswordHash != nil,
		CreatedAt:   r.CreatedAt.Time,
	}
	if r.ExpiresAt.Valid {
		t := r.ExpiresAt.Time
		out.ExpiresAt = &t
	}
	return out
}

// requireNodeAccess 统一属主/访客鉴权:属主直接放行(下游查询本就带 owner 条件),
// 访客校验分享有效 + 节点在分享子树内。返回身份供下游按 UserID 查询
// (访客 JWT 的 sub 就是分享属主,天然复用属主视角)。
func (r *Resolver) requireNodeAccess(ctx context.Context, nodeID uuid.UUID) (*service.Identity, error) {
	ident, err := httpx.IdentityFrom(ctx)
	if err != nil {
		return nil, err
	}
	if ident.Scope != service.ScopeUser {
		if err := r.Shares.Authorize(ctx, ident, nodeID); err != nil {
			return nil, err
		}
	}
	return ident, nil
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
		SubtreeBytes: row.SubtreeBytes, SubtreeCount: row.SubtreeCount, StatsStale: row.StatsStale,
	}, BlobSize: row.BlobSize, BlobMime: row.BlobMime, BlobSha256: row.BlobSha256}), nil
}
