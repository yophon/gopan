package graph

// This file will not be regenerated automatically.

import (
	"context"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func gqlArchiveInfo(a *service.ArchiveInfo) *ArchiveInfo {
	return &ArchiveInfo{
		Status: ArchiveStatus(a.Status), Format: a.Format, EntryCount: a.Entries, FileCount: a.Files,
		TotalSize: a.TotalSize, Solid: a.Solid, Encrypted: a.Encrypted, Error: a.Error,
	}
}

func gqlArchiveEntry(e store.ArchiveEntry) *ArchiveEntry {
	out := &ArchiveEntry{
		Idx: int(e.Idx), Path: e.Path, Name: e.Name, IsDir: e.IsDir, Size: e.Size,
		Previewable: service.EntryPreviewable(e), Downloadable: service.EntryDownloadable(e),
	}
	if e.Mtime.Valid {
		t := e.Mtime.Time
		out.ModifiedAt = &t
	}
	if e.Flag != "" {
		f := e.Flag
		out.Flag = &f
	}
	return out
}

// archiveAccess 属主或分享访客(节点在分享子树内);收集令牌在 Authorize 里被拒。
func (r *Resolver) archiveAccess(ctx context.Context, nodeID string) (uuid.UUID, uuid.UUID, error) {
	id, err := httpx.IdentityFrom(ctx)
	if err != nil {
		return uuid.Nil, uuid.Nil, err
	}
	nid, err := parseID(nodeID)
	if err != nil {
		return uuid.Nil, uuid.Nil, err
	}
	if err := r.Shares.Authorize(ctx, id, nid); err != nil {
		return uuid.Nil, uuid.Nil, err
	}
	return id.UserID, nid, nil
}
