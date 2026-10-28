package service

import (
	"context"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/store"
)

// 收藏与最近打开(doc/24)。都是节点上的一个时刻,不算对文件的改动:updated_at 不动、不记动态。

const (
	maxStarredList = 500
	maxRecentList  = 500
	maxStarBatch   = 500
)

// QuickRow 收藏 / 最近列表的一行(节点 + blob 元信息)。
type QuickRow struct {
	Node       store.Node
	BlobSize   *int64
	BlobMime   *string
	BlobSha256 *string
	// 「最近」才填:播放 / 阅读进度,没有为 nil
	Progress *store.PlayProgress
}

// SetStarred 收藏或取消收藏;回收站里的、不是自己的静默跳过。返回改到的节点。
func (s *Nodes) SetStarred(ctx context.Context, owner uuid.UUID, ids []uuid.UUID, starred bool) ([]store.Node, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	if len(ids) > maxStarBatch {
		return nil, errf("INVALID_INPUT", "一次最多 %d 个", maxStarBatch)
	}
	return s.q.SetNodesStarred(ctx, store.SetNodesStarredParams{OwnerID: owner, Ids: ids, Starred: starred})
}

// Starred 收藏的文件与文件夹,最近收藏的在前。
func (s *Nodes) Starred(ctx context.Context, owner uuid.UUID) ([]QuickRow, error) {
	rows, err := s.q.ListStarredNodes(ctx, store.ListStarredNodesParams{OwnerID: owner, Limit: maxStarredList})
	if err != nil {
		return nil, err
	}
	return mapSlice(rows, func(r store.ListStarredNodesRow) QuickRow {
		return QuickRow{Node: r.Node, BlobSize: r.BlobSize, BlobMime: r.BlobMime, BlobSha256: r.BlobSha256}
	}), nil
}

// MarkOpened 记一次打开(网页 / App 预览、播放、下载时上报)。文件夹、回收站里的、别人的忽略。
func (s *Nodes) MarkOpened(ctx context.Context, owner, id uuid.UUID) error {
	n, err := s.q.MarkNodeOpened(ctx, store.MarkNodeOpenedParams{ID: id, OwnerID: owner})
	if err != nil || n == 0 {
		return err
	}
	return s.recentChanged(ctx, owner)
}

// recentChanged 告诉这个人的其他设备「最近」变了(doc/32 二)。
func (s *Nodes) recentChanged(ctx context.Context, owner uuid.UUID) error {
	return notifyChange(ctx, s.q, owner, Change{Topic: "recent"})
}

// Recent 最近打开的文件,最近的在前。
func (s *Nodes) Recent(ctx context.Context, owner uuid.UUID, limit int) ([]QuickRow, error) {
	if limit <= 0 || limit > maxRecentList {
		limit = maxRecentList
	}
	rows, err := s.q.ListRecentOpened(ctx, store.ListRecentOpenedParams{OwnerID: owner, Limit: int32(limit)})
	if err != nil {
		return nil, err
	}
	return mapSlice(rows, func(r store.ListRecentOpenedRow) QuickRow {
		q := QuickRow{Node: r.Node, BlobSize: r.BlobSize, BlobMime: r.BlobMime, BlobSha256: r.BlobSha256}
		if r.ProgressPosition != nil && r.ProgressDuration != nil {
			q.Progress = &store.PlayProgress{
				OwnerID: owner, NodeID: r.Node.ID, Position: *r.ProgressPosition, Duration: *r.ProgressDuration,
				Finished: r.ProgressFinished != nil && *r.ProgressFinished, UpdatedAt: r.ProgressUpdatedAt,
			}
		}
		return q
	}), nil
}

// ClearRecent 从「最近」里拿掉几个(ids 为空 = 全部清掉),连播放 / 阅读进度一起清(「接着看」里也没了)。
// 文件本身不受影响。
func (s *Nodes) ClearRecent(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) error {
	if ids == nil {
		ids = []uuid.UUID{}
	}
	if _, err := s.q.ClearNodesOpened(ctx, store.ClearNodesOpenedParams{OwnerID: owner, Ids: ids}); err != nil {
		return err
	}
	if _, err := s.q.ClearPlayProgressMany(ctx, store.ClearPlayProgressManyParams{OwnerID: owner, Ids: ids}); err != nil {
		return err
	}
	return s.recentChanged(ctx, owner)
}
