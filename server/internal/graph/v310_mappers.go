package graph

// 收藏、最近打开与历史版本(doc/24)。schema.resolvers.go 里的入口只转调这里。

import (
	"context"
	"time"

	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
)

func gqlQuickRows(rows []service.QuickRow) []*Node {
	out := make([]*Node, 0, len(rows))
	for _, r := range rows {
		n := gqlNodeRow(nodeRow{Node: r.Node, BlobSize: r.BlobSize, BlobMime: r.BlobMime, BlobSha256: r.BlobSha256})
		n.InList = true
		out = append(out, n)
	}
	return out
}

// nodeStarred / nodeOpenedAt:收藏与打开记录是属主自己的,分享访客一律看不到。
func nodeStarred(ctx context.Context, obj *Node) bool {
	if id, err := httpx.IdentityFrom(ctx); err != nil || id.Scope != service.ScopeUser {
		return false
	}
	return obj.StarredAt != nil
}

func nodeOpenedAt(ctx context.Context, obj *Node) *time.Time {
	if id, err := httpx.IdentityFrom(ctx); err != nil || id.Scope != service.ScopeUser {
		return nil
	}
	return obj.OpenedAtRaw
}

func (r *Resolver) starredNodes(ctx context.Context) ([]*Node, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := r.Nodes.Starred(ctx, ident.UserID)
	if err != nil {
		return nil, err
	}
	return gqlQuickRows(rows), nil
}

func (r *Resolver) recentFiles(ctx context.Context, limit *int) ([]*Node, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	n := 0
	if limit != nil {
		n = *limit
	}
	rows, err := r.Nodes.Recent(ctx, ident.UserID, n)
	if err != nil {
		return nil, err
	}
	return gqlQuickRows(rows), nil
}

func (r *Resolver) setStarred(ctx context.Context, ids []string, starred bool) ([]*Node, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	uids, err := parseIDs(ids)
	if err != nil {
		return nil, err
	}
	nodes, err := r.Nodes.SetStarred(ctx, ident.UserID, uids, starred)
	if err != nil {
		return nil, err
	}
	out := make([]*Node, 0, len(nodes))
	for _, n := range nodes {
		out = append(out, gqlNode(n))
	}
	return out, nil
}

func (r *Resolver) markOpened(ctx context.Context, nodeID string) (bool, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return false, err
	}
	id, err := parseID(nodeID)
	if err != nil {
		return false, err
	}
	return true, r.Nodes.MarkOpened(ctx, ident.UserID, id)
}

func (r *Resolver) clearRecent(ctx context.Context, ids []string) (bool, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return false, err
	}
	uids, err := parseIDs(ids)
	if err != nil {
		return false, err
	}
	return true, r.Nodes.ClearRecent(ctx, ident.UserID, uids)
}

func (r *Resolver) fileVersions(ctx context.Context, nodeID string) (*FileVersionList, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	id, err := parseID(nodeID)
	if err != nil {
		return nil, err
	}
	node, items, err := r.Versions.List(ctx, ident.UserID, id)
	if err != nil {
		return nil, err
	}
	pol := r.Versions.Policy()
	out := &FileVersionList{Items: make([]*FileVersion, 0, len(items)), Keep: pol.Keep, KeepDays: int(pol.TTL.Hours() / 24)}
	for _, v := range items {
		u, err := r.Versions.URL(ctx, v.Sha256, node.Name)
		if err != nil {
			return nil, err
		}
		out.Items = append(out.Items, &FileVersion{
			ID: v.ID.String(), Size: v.Size, Sha256: v.Sha256, Mime: v.Mime,
			SavedAt: v.SavedAt.Time, ReplacedAt: v.ReplacedAt.Time, DownloadURL: u,
		})
	}
	return out, nil
}

func (r *Resolver) restoreFileVersion(ctx context.Context, versionID string) (*Node, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	id, err := parseID(versionID)
	if err != nil {
		return nil, err
	}
	n, err := r.Versions.Restore(ctx, ident.UserID, id)
	if err != nil {
		return nil, err
	}
	return r.getNodeFull(ctx, ident.UserID, n.ID)
}

func (r *Resolver) deleteFileVersions(ctx context.Context, ids []string) (bool, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return false, err
	}
	uids, err := parseIDs(ids)
	if err != nil {
		return false, err
	}
	return true, r.Versions.Delete(ctx, ident.UserID, uids)
}

func (r *Resolver) purgeFileVersions(ctx context.Context, nodeID *string) (int64, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return 0, err
	}
	id, err := parseIDPtr(nodeID)
	if err != nil {
		return 0, err
	}
	_, bytes, err := r.Versions.Purge(ctx, ident.UserID, id)
	return bytes, err
}
