package service

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// 历史版本(doc/24):文件被同名覆盖时,旧内容留成一个版本。版本持有 blob 的一个引用、
// 计入属主的 used_bytes(与节点同一套记账),所以空间不够时不留旧版本、照样覆盖;
// 每个文件最多留 keep 个,换下来超过 ttl 的由 worker 定时清掉。

// VersionPolicy 保留策略。Keep = 0 表示覆盖时不留旧版本(已有的版本照常可看、可恢复、到期清理)。
type VersionPolicy struct {
	Keep int
	TTL  time.Duration
}

type Versions struct {
	pool   *pgxpool.Pool
	q      *store.Queries
	obj    *objstore.Store
	policy VersionPolicy
}

func NewVersions(pool *pgxpool.Pool, obj *objstore.Store, policy VersionPolicy) *Versions {
	return &Versions{pool: pool, q: store.New(pool), obj: obj, policy: policy}
}

func (v *Versions) Policy() VersionPolicy { return v.policy }

// overwriteResult 一次覆盖的结果。Changed = false:内容与原来相同,只碰了 updated_at。
type overwriteResult struct {
	Node    store.Node
	Changed bool
	Version *uuid.UUID // 这次留下的旧版本;没留(空间不够、策略关闭、原来是空文件)为 nil
}

// overwriteFileTx 把文件 existing 的内容换成 blob。调用方已在同一事务里锁住属主行(locked)、
// 并确认 blob 行存在且大小一致。引用、配额、版本、子树标脏都在这里调整;动态由调用方记
// (各入口的来源与说明不同)。keep 是保留个数,0 = 不留旧版本。
func overwriteFileTx(ctx context.Context, q *store.Queries, keep int, locked store.User, existing store.Node, blob store.Blob) (overwriteResult, error) {
	if existing.Kind != "file" {
		return overwriteResult{}, errf("NAME_CONFLICT", "同名文件夹已存在")
	}
	if existing.BlobID != nil && *existing.BlobID == blob.ID {
		n, err := q.ReplaceNodeBlob(ctx, store.ReplaceNodeBlobParams{ID: existing.ID, OwnerID: existing.OwnerID, BlobID: &blob.ID})
		return overwriteResult{Node: n}, err
	}
	var oldSize int64
	if existing.BlobID != nil {
		old, err := q.GetBlob(ctx, *existing.BlobID)
		if err != nil {
			return overwriteResult{}, err
		}
		oldSize = old.Size
	}
	// 留旧版本要多占 oldSize;放不下就退回老规矩(只按差额算),还放不下才拒绝
	keepOld := keep > 0 && existing.BlobID != nil && locked.UsedBytes+blob.Size <= locked.QuotaBytes
	if !keepOld && locked.UsedBytes+blob.Size-oldSize > locked.QuotaBytes {
		return overwriteResult{}, errf("QUOTA_EXCEEDED", "存储配额不足")
	}
	n, err := q.ReplaceNodeBlob(ctx, store.ReplaceNodeBlobParams{ID: existing.ID, OwnerID: existing.OwnerID, BlobID: &blob.ID})
	if err != nil {
		return overwriteResult{}, err
	}
	if err := q.IncrementBlobRef(ctx, blob.ID); err != nil {
		return overwriteResult{}, err
	}
	if err := q.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: existing.OwnerID, UsedBytes: blob.Size}); err != nil {
		return overwriteResult{}, err
	}
	res := overwriteResult{Node: n, Changed: true}
	switch {
	case keepOld:
		// 旧内容连同它的引用与配额原样转给版本
		ver, err := q.InsertNodeVersion(ctx, store.InsertNodeVersionParams{
			ID: uuid.Must(uuid.NewV7()), NodeID: existing.ID, OwnerID: existing.OwnerID,
			BlobID: *existing.BlobID, Size: oldSize, SavedAt: existing.UpdatedAt,
		})
		if err != nil {
			return overwriteResult{}, err
		}
		res.Version = &ver.ID
		if err := trimVersionsTx(ctx, q, existing.ID, keep); err != nil {
			return overwriteResult{}, err
		}
	case existing.BlobID != nil:
		if err := q.DecrementBlobRefN(ctx, store.DecrementBlobRefNParams{ID: *existing.BlobID, N: 1}); err != nil {
			return overwriteResult{}, err
		}
		if err := q.SubtractUsedBytes(ctx, store.SubtractUsedBytesParams{ID: existing.OwnerID, UsedBytes: oldSize}); err != nil {
			return overwriteResult{}, err
		}
	}
	if err := q.MarkAncestorsStale(ctx, n.ID); err != nil {
		return overwriteResult{}, err
	}
	return res, nil
}

func trimVersionsTx(ctx context.Context, q *store.Queries, nodeID uuid.UUID, keep int) error {
	if keep <= 0 {
		return nil
	}
	gone, err := q.TrimNodeVersions(ctx, store.TrimNodeVersionsParams{NodeID: nodeID, Keep: int32(keep)})
	if err != nil {
		return err
	}
	return releaseVersions(ctx, q, gone)
}

// ReleaseVersions 给 worker 用:hash 校验失败清理时作废的版本退账。
func ReleaseVersions(ctx context.Context, q *store.Queries, gone []store.NodeVersion) error {
	return releaseVersions(ctx, q, gone)
}

// releaseVersions 删掉的版本退引用、退配额。同一个 blob 可能被好几个版本指着,按次数退。
func releaseVersions(ctx context.Context, q *store.Queries, gone []store.NodeVersion) error {
	if len(gone) == 0 {
		return nil
	}
	refs := map[uuid.UUID]int32{}
	bytes := map[uuid.UUID]int64{}
	for _, v := range gone {
		refs[v.BlobID]++
		bytes[v.OwnerID] += v.Size
	}
	for id, n := range refs {
		if err := q.DecrementBlobRefN(ctx, store.DecrementBlobRefNParams{ID: id, N: n}); err != nil {
			return err
		}
	}
	for owner, b := range bytes {
		if err := q.SubtractUsedBytes(ctx, store.SubtractUsedBytesParams{ID: owner, UsedBytes: b}); err != nil {
			return err
		}
	}
	return nil
}

// VersionView 版本列表的一项。
type VersionView struct {
	store.NodeVersion
	Sha256 string
	Mime   string
}

// activeFile 自己的、不在回收站里的文件。
func (v *Versions) activeFile(ctx context.Context, owner, nodeID uuid.UUID) (store.Node, error) {
	n, err := v.q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.Node{}, ErrNotFound
	}
	if err != nil {
		return store.Node{}, err
	}
	if n.Kind != "file" {
		return store.Node{}, errf("NOT_A_FILE", "文件夹没有历史版本")
	}
	return n, nil
}

// List 一个文件的历史版本,连同文件本身(下载地址要用它现在的名字)。
func (v *Versions) List(ctx context.Context, owner, nodeID uuid.UUID) (store.Node, []VersionView, error) {
	n, err := v.activeFile(ctx, owner, nodeID)
	if err != nil {
		return store.Node{}, nil, err
	}
	rows, err := v.q.ListNodeVersions(ctx, store.ListNodeVersionsParams{NodeID: nodeID, OwnerID: owner})
	if err != nil {
		return store.Node{}, nil, err
	}
	out := make([]VersionView, 0, len(rows))
	for _, r := range rows {
		out = append(out, VersionView{NodeVersion: r.NodeVersion, Sha256: r.BlobSha256, Mime: r.BlobMime})
	}
	return n, out, nil
}

// URL 版本内容的下载地址(预签名,本地计算)。
func (v *Versions) URL(ctx context.Context, sha, name string) (string, error) {
	return v.obj.PresignGet(ctx, objstore.BlobKey(sha), name)
}

// Restore 把文件恢复成某个版本:现在的内容换下来成为一个新版本(恢复也能反悔),
// 选中的版本回到文件上。两边的引用与配额只是交换,不需要额外空间。
func (v *Versions) Restore(ctx context.Context, owner, versionID uuid.UUID) (store.Node, error) {
	tx, err := v.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	q := v.q.WithTx(tx)
	n, err := restoreVersionTx(ctx, q, v.policy.Keep, owner, versionID)
	if err != nil {
		return store.Node{}, err
	}
	return n, tx.Commit(ctx)
}

func restoreVersionTx(ctx context.Context, q *store.Queries, keep int, owner, versionID uuid.UUID) (store.Node, error) {
	ver, err := q.LockNodeVersion(ctx, store.LockNodeVersionParams{ID: versionID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.Node{}, errf("NOT_FOUND", "这个版本已经不在了")
	}
	if err != nil {
		return store.Node{}, err
	}
	n, err := q.GetActiveNodeForUpdate(ctx, store.GetActiveNodeForUpdateParams{ID: ver.NodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.Node{}, errf("NODE_DELETED", "文件在回收站里,先还原它")
	}
	if err != nil {
		return store.Node{}, err
	}
	if n.BlobID != nil && *n.BlobID == ver.BlobID {
		// 和现在的内容一样:这一版没有存在的意义,删掉退账即可
		if err := q.DeleteNodeVersionMoved(ctx, ver.ID); err != nil {
			return store.Node{}, err
		}
		return n, releaseVersions(ctx, q, []store.NodeVersion{ver})
	}
	var current *uuid.UUID
	if n.BlobID != nil {
		cur, err := q.GetBlob(ctx, *n.BlobID)
		if err != nil {
			return store.Node{}, err
		}
		made, err := q.InsertNodeVersion(ctx, store.InsertNodeVersionParams{
			ID: uuid.Must(uuid.NewV7()), NodeID: n.ID, OwnerID: owner,
			BlobID: cur.ID, Size: cur.Size, SavedAt: n.UpdatedAt,
		})
		if err != nil {
			return store.Node{}, err
		}
		current = &made.ID
	}
	if err := q.DeleteNodeVersionMoved(ctx, ver.ID); err != nil {
		return store.Node{}, err
	}
	restored, err := q.ReplaceNodeBlob(ctx, store.ReplaceNodeBlobParams{ID: n.ID, OwnerID: owner, BlobID: &ver.BlobID})
	if err != nil {
		return store.Node{}, err
	}
	if err := q.MarkAncestorsStale(ctx, n.ID); err != nil {
		return store.Node{}, err
	}
	act := fileAct(OpOverwrite, restored, ver.Size)
	act.Detail.Note = "恢复到 " + ver.SavedAt.Time.Format("2006-01-02 15:04") + " 的版本"
	act.Detail.Version = current
	if err := recordActs(ctx, q, owner, act); err != nil {
		return store.Node{}, err
	}
	return restored, trimVersionsTx(ctx, q, n.ID, keep)
}

// Delete 删掉几个版本(只能是自己的)。
func (v *Versions) Delete(ctx context.Context, owner uuid.UUID, ids []uuid.UUID) error {
	if len(ids) == 0 {
		return nil
	}
	return v.inTx(ctx, func(q *store.Queries) error {
		gone, err := q.DeleteNodeVersions(ctx, store.DeleteNodeVersionsParams{OwnerID: owner, Ids: ids})
		if err != nil {
			return err
		}
		return releaseVersions(ctx, q, gone)
	})
}

// Purge 清空一个文件的全部版本(nodeID 为 nil = 自己所有文件的),返回清掉的个数与字节。
func (v *Versions) Purge(ctx context.Context, owner uuid.UUID, nodeID *uuid.UUID) (int, int64, error) {
	var count int
	var bytes int64
	err := v.inTx(ctx, func(q *store.Queries) error {
		var gone []store.NodeVersion
		var err error
		if nodeID != nil {
			gone, err = q.DeleteVersionsOfNode(ctx, store.DeleteVersionsOfNodeParams{OwnerID: owner, NodeID: *nodeID})
		} else {
			gone, err = q.DeleteVersionsOfOwner(ctx, owner)
		}
		if err != nil {
			return err
		}
		count = len(gone)
		for _, g := range gone {
			bytes += g.Size
		}
		return releaseVersions(ctx, q, gone)
	})
	return count, bytes, err
}

// CleanupExpired 清掉换下来超过保留期的版本(worker 每小时跑一次),返回清掉的个数。
func (v *Versions) CleanupExpired(ctx context.Context) (int, error) {
	before := tstz(time.Now().Add(-v.policy.TTL))
	total := 0
	for {
		var n int
		err := v.inTx(ctx, func(q *store.Queries) error {
			gone, err := q.DeleteExpiredVersions(ctx, store.DeleteExpiredVersionsParams{Before: before, Lim: 500})
			if err != nil {
				return err
			}
			n = len(gone)
			return releaseVersions(ctx, q, gone)
		})
		total += n
		if err != nil || n < 500 {
			return total, err
		}
	}
}

func (v *Versions) inTx(ctx context.Context, fn func(q *store.Queries) error) error {
	tx, err := v.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := fn(v.q.WithTx(tx)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
