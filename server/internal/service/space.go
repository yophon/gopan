package service

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/store"
)

// 空间分析(doc/23 三):全是只读查询,打开页面时现算。清理走已有的删除 / 吊销接口(进回收站、记进动态)。

// 文件分类(与 SQL 函数 gopan_file_category、GraphQL FileCategory 小写同名)。
var FileCategories = []string{"image", "video", "audio", "document", "archive", "text", "other"}

type Space struct {
	q *store.Queries
}

func NewSpace(pool *pgxpool.Pool) *Space { return &Space{q: store.New(pool)} }

type SpaceOverview struct {
	UsedBytes, QuotaBytes  int64
	TrashFiles, TrashBytes int64
	ByType                 []store.SpaceByTypeRow
	DuplicateGroups        int64
	DuplicateReclaimable   int64
	// 历史版本(doc/24):计入已用空间
	VersionFiles, VersionBytes int64
}

func (s *Space) Overview(ctx context.Context, owner uuid.UUID) (*SpaceOverview, error) {
	u, err := s.q.GetUserByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	byType, err := s.q.SpaceByType(ctx, owner)
	if err != nil {
		return nil, err
	}
	trash, err := s.q.SpaceTrash(ctx, owner)
	if err != nil {
		return nil, err
	}
	dup, err := s.q.SpaceDuplicateTotals(ctx, owner)
	if err != nil {
		return nil, err
	}
	ver, err := s.q.SpaceVersions(ctx, owner)
	if err != nil {
		return nil, err
	}
	return &SpaceOverview{
		UsedBytes: u.UsedBytes, QuotaBytes: u.QuotaBytes, TrashFiles: trash.Files, TrashBytes: trash.Bytes,
		ByType: byType, DuplicateGroups: dup.Groups, DuplicateReclaimable: dup.Reclaimable,
		VersionFiles: ver.Files, VersionBytes: ver.Bytes,
	}, nil
}

type SpaceLevel struct {
	Path       string
	Folders    []store.Node
	LooseFiles int64
	LooseBytes int64
}

// Folders 某一层(nil = 根)的子文件夹按大小排,外加这一层直接放着的文件。
func (s *Space) Folders(ctx context.Context, owner uuid.UUID, parent *uuid.UUID) (*SpaceLevel, error) {
	path := "/"
	if parent != nil {
		n, err := s.q.GetNode(ctx, *parent)
		if errors.Is(err, pgx.ErrNoRows) || err == nil && (n.OwnerID != owner || n.Kind != "folder" || n.DeletedAt.Valid) {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		if path, err = s.q.NodePath(ctx, n.ID); err != nil {
			return nil, err
		}
	}
	folders, err := s.q.SpaceFolders(ctx, store.SpaceFoldersParams{OwnerID: owner, ParentID: parent})
	if err != nil {
		return nil, err
	}
	loose, err := s.q.SpaceLooseFiles(ctx, store.SpaceLooseFilesParams{OwnerID: owner, ParentID: parent})
	if err != nil {
		return nil, err
	}
	return &SpaceLevel{Path: path, Folders: folders, LooseFiles: loose.Files, LooseBytes: loose.Bytes}, nil
}

// SpaceFile 列表里的一个文件(或空文件夹),带它所在的位置。
type SpaceFile struct {
	Node     store.Node
	Size     int64
	Mime     string
	Category string
	Path     string // 所在文件夹
}

type pathCache struct {
	q *store.Queries
	m map[uuid.UUID]string
}

func (c *pathCache) of(ctx context.Context, parent *uuid.UUID) (string, error) {
	if parent == nil {
		return "/", nil
	}
	if v, ok := c.m[*parent]; ok {
		return v, nil
	}
	v, err := c.q.NodePath(ctx, *parent)
	if err != nil {
		return "", err
	}
	c.m[*parent] = v
	return v, nil
}

func (s *Space) paths() *pathCache { return &pathCache{q: s.q, m: map[uuid.UUID]string{}} }

func clampLimit(n, def, max int) int {
	if n <= 0 {
		return def
	}
	return min(n, max)
}

func (s *Space) LargeFiles(ctx context.Context, owner uuid.UUID, category string, limit int) ([]SpaceFile, error) {
	rows, err := s.q.SpaceLargeFiles(ctx, store.SpaceLargeFilesParams{OwnerID: owner, Category: category, Lim: int32(clampLimit(limit, 100, 200))})
	if err != nil {
		return nil, err
	}
	pc := s.paths()
	out := make([]SpaceFile, 0, len(rows))
	for _, r := range rows {
		p, err := pc.of(ctx, r.ParentID)
		if err != nil {
			return nil, err
		}
		out = append(out, SpaceFile{Node: store.Node{
			ID: r.ID, OwnerID: r.OwnerID, ParentID: r.ParentID, Name: r.Name, Kind: r.Kind, BlobID: r.BlobID,
			DeletedAt: r.DeletedAt, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt,
		}, Size: r.BlobSize, Mime: r.BlobMime, Category: r.Category, Path: p})
	}
	return out, nil
}

// StaleFiles 超过 days 天没改过、不小于 minSize 的文件。
func (s *Space) StaleFiles(ctx context.Context, owner uuid.UUID, days int, minSize int64, limit int) ([]SpaceFile, error) {
	if days <= 0 {
		days = 365
	}
	if minSize <= 0 {
		minSize = 100 << 20
	}
	rows, err := s.q.SpaceStaleFiles(ctx, store.SpaceStaleFilesParams{
		OwnerID: owner, Before: tstz(time.Now().AddDate(0, 0, -days)), MinSize: minSize, Lim: int32(clampLimit(limit, 100, 200)),
	})
	if err != nil {
		return nil, err
	}
	pc := s.paths()
	out := make([]SpaceFile, 0, len(rows))
	for _, r := range rows {
		p, err := pc.of(ctx, r.ParentID)
		if err != nil {
			return nil, err
		}
		out = append(out, SpaceFile{Node: store.Node{
			ID: r.ID, OwnerID: r.OwnerID, ParentID: r.ParentID, Name: r.Name, Kind: r.Kind, BlobID: r.BlobID,
			DeletedAt: r.DeletedAt, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt,
		}, Size: r.BlobSize, Mime: r.BlobMime, Category: r.Category, Path: p})
	}
	return out, nil
}

type DuplicateGroup struct {
	Sha    string
	Size   int64
	Copies int64
	Files  []SpaceFile // 最早的在前(默认留它)
}

// Duplicates 重复文件组,按能省的空间排;offset 分页。
func (s *Space) Duplicates(ctx context.Context, owner uuid.UUID, offset, limit int) ([]DuplicateGroup, bool, error) {
	limit = clampLimit(limit, 50, 100)
	groups, err := s.q.SpaceDuplicateGroups(ctx, store.SpaceDuplicateGroupsParams{OwnerID: owner, Lim: int32(limit + 1), Off: int32(max(offset, 0))})
	if err != nil {
		return nil, false, err
	}
	more := len(groups) > limit
	if more {
		groups = groups[:limit]
	}
	if len(groups) == 0 {
		return nil, false, nil
	}
	ids := make([]uuid.UUID, len(groups))
	for i, g := range groups {
		ids[i] = g.BlobID
	}
	nodes, err := s.q.SpaceNodesByBlobs(ctx, store.SpaceNodesByBlobsParams{OwnerID: owner, BlobIds: ids})
	if err != nil {
		return nil, false, err
	}
	byBlob := map[uuid.UUID][]store.Node{}
	for _, n := range nodes {
		if n.BlobID != nil {
			byBlob[*n.BlobID] = append(byBlob[*n.BlobID], n)
		}
	}
	pc := s.paths()
	out := make([]DuplicateGroup, 0, len(groups))
	for _, g := range groups {
		dg := DuplicateGroup{Sha: g.Sha256, Size: g.Size, Copies: g.Copies}
		for _, n := range byBlob[g.BlobID] {
			p, err := pc.of(ctx, n.ParentID)
			if err != nil {
				return nil, false, err
			}
			dg.Files = append(dg.Files, SpaceFile{Node: n, Size: g.Size, Category: "", Path: p})
		}
		out = append(out, dg)
	}
	return out, more, nil
}

func (s *Space) EmptyFolders(ctx context.Context, owner uuid.UUID, limit int) ([]SpaceFile, error) {
	rows, err := s.q.SpaceEmptyFolders(ctx, store.SpaceEmptyFoldersParams{OwnerID: owner, Limit: int32(clampLimit(limit, 200, 500))})
	if err != nil {
		return nil, err
	}
	pc := s.paths()
	out := make([]SpaceFile, 0, len(rows))
	for _, n := range rows {
		p, err := pc.of(ctx, n.ParentID)
		if err != nil {
			return nil, err
		}
		out = append(out, SpaceFile{Node: n, Path: p})
	}
	return out, nil
}

type SpaceIdle struct {
	Shares   []store.SpaceIdleSharesRow
	Links    []store.SpaceIdleLinksRow
	Requests []store.SpaceExpiredRequestsRow
}

// Idle 过期或 90 天没人访问的分享、直链,过期的收集链接。
func (s *Space) Idle(ctx context.Context, owner uuid.UUID) (*SpaceIdle, error) {
	shares, err := s.q.SpaceIdleShares(ctx, owner)
	if err != nil {
		return nil, err
	}
	links, err := s.q.SpaceIdleLinks(ctx, owner)
	if err != nil {
		return nil, err
	}
	reqs, err := s.q.SpaceExpiredRequests(ctx, owner)
	if err != nil {
		return nil, err
	}
	return &SpaceIdle{Shares: shares, Links: links, Requests: reqs}, nil
}

// AdminStorage 全站存储(管理端):逻辑 / 物理占用、派生文件、正文索引、待回收。调用方负责校验管理员。
func (s *Space) AdminStorage(ctx context.Context) (store.AdminStorageRow, error) {
	return s.q.AdminStorage(ctx)
}
