package graph

// This file will not be regenerated automatically.

import (
	"context"
	"strconv"
	"time"

	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

const fileRequestUploadsPage = 50

func gqlFileRequest(r store.ListMyFileRequestsRow) *FileRequest {
	folder := &Node{
		ID:       r.FolderID.String(),
		ParentID: idPtrStr(r.FolderParentID),
		Name:     r.FolderName,
		Kind:     NodeKindFolder,
		InList:   true,
	}
	folder.CreatedAt = r.FolderCreatedAt.Time
	folder.UpdatedAt = r.FolderUpdatedAt.Time
	out := &FileRequest{
		ID:              r.ID.String(),
		Token:           r.Token,
		Folder:          folder,
		Title:           r.Title,
		Note:            r.Note,
		HasPassword:     r.PasswordHash != nil,
		RequireName:     r.RequireName || r.GroupByUploader,
		GroupByUploader: r.GroupByUploader,
		MaxFileSize:     r.MaxFileSize,
		MaxTotalBytes:   r.MaxTotalBytes,
		CreatedAt:       r.CreatedAt.Time,
		FileCount:       int(r.FileCount),
		TotalBytes:      r.TotalBytes,
		UploaderCount:   int(r.UploaderCount),
		NewCount:        int(r.NewCount),
	}
	if r.MaxFiles != nil {
		n := int(*r.MaxFiles)
		out.MaxFiles = &n
	}
	if r.ExpiresAt.Valid {
		t := r.ExpiresAt.Time
		out.ExpiresAt = &t
	}
	if r.LastUploadAt.Valid {
		t := r.LastUploadAt.Time
		out.LastUploadAt = &t
	}
	return out
}

func gqlFileRequestUpload(r store.ListFileRequestUploadsRow) *FileRequestUpload {
	out := &FileRequestUpload{
		ID:           strconv.FormatInt(r.ID, 10),
		FileName:     r.FileName,
		Size:         r.Size,
		UploaderName: r.UploaderName,
		IP:           r.Ip,
		UserAgent:    r.UserAgent,
		CreatedAt:    r.CreatedAt.Time,
	}
	if r.NodeID != nil && r.NodeName != nil {
		n := &Node{
			ID:       r.NodeID.String(),
			ParentID: idPtrStr(r.NodeParentID),
			Name:     *r.NodeName,
			Kind:     NodeKindFile,
			Size:     r.BlobSize,
			Mime:     r.BlobMime,
			Sha256:   r.BlobSha256,
			InList:   true,
		}
		n.CreatedAt = r.NodeCreatedAt.Time
		n.UpdatedAt = r.NodeUpdatedAt.Time
		out.Node = n
	}
	return out
}

func fileRequestInput(in FileRequestInput) service.FileRequestInput {
	out := service.FileRequestInput{
		Title:         in.Title,
		MaxFileSize:   in.MaxFileSize,
		MaxTotalBytes: in.MaxTotalBytes,
		ExpiresAt:     in.ExpiresAt,
	}
	if in.Note != nil {
		out.Note = *in.Note
	}
	if in.RequireName != nil {
		out.RequireName = *in.RequireName
	}
	if in.GroupByUploader != nil {
		out.GroupByUploader = *in.GroupByUploader
	}
	if in.MaxFiles != nil {
		n := int32(min(*in.MaxFiles, 1<<31-1))
		out.MaxFiles = &n
	}
	return out
}

func gqlCollectUpload(name string, size int64, at time.Time) *CollectUpload {
	return &CollectUpload{Name: name, Size: size, CreatedAt: at}
}

// collectGuest 取出并校验收集链接的访客身份;登录用户与分享访客一律拒绝。
func (r *Resolver) collectGuest(ctx context.Context) (*service.CollectGuest, error) {
	ident, err := httpx.IdentityFrom(ctx)
	if err != nil {
		return nil, err
	}
	return r.Collects.Guest(ctx, ident)
}
