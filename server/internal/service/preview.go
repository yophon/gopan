package service

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// PreviewKind 与 GraphQL 枚举同名。
const (
	PvNone   = "NONE"
	PvImage  = "IMAGE"
	PvPDF    = "PDF" // 文件本身是 PDF,原文直出
	PvVideo  = "VIDEO"
	PvAudio  = "AUDIO"
	PvText   = "TEXT"
	PvOffice = "OFFICE" // 需转换,contentUrl 是 derived pdf
)

const maxTextPreview = 1 << 20 // 1MB

var officeExts = map[string]bool{
	".doc": true, ".docx": true, ".xls": true, ".xlsx": true,
	".ppt": true, ".pptx": true, ".odt": true, ".ods": true, ".odp": true, ".rtf": true,
}

var textExts = map[string]bool{
	".txt": true, ".md": true, ".log": true, ".json": true, ".xml": true, ".yaml": true, ".yml": true,
	".toml": true, ".ini": true, ".csv": true, ".go": true, ".js": true, ".ts": true, ".py": true,
	".java": true, ".c": true, ".cpp": true, ".h": true, ".rs": true, ".sh": true, ".sql": true,
	".html": true, ".css": true, ".vue": true, ".dart": true, ".kt": true, ".swift": true,
}

// ClassifyPreview 按 mime + 扩展名归类。
func ClassifyPreview(name string, mime *string, size *int64) string {
	m := ""
	if mime != nil {
		m = *mime
	}
	ext := strings.ToLower(filepath.Ext(name))
	switch {
	case strings.HasPrefix(m, "image/"):
		return PvImage
	case m == "application/pdf":
		return PvPDF
	case strings.HasPrefix(m, "video/"):
		return PvVideo
	case strings.HasPrefix(m, "audio/"):
		return PvAudio
	case officeExts[ext]:
		return PvOffice
	case (strings.HasPrefix(m, "text/") || textExts[ext]) && size != nil && *size <= maxTextPreview:
		return PvText
	default:
		return PvNone
	}
}

type PreviewInfo struct {
	Kind        string
	ThumbURL    *string
	LargeURL    *string
	ContentURL  *string
	Status      *string // PENDING/RUNNING/DONE/FAILED,仅 OFFICE 用
	DurationSec *int
}

type Previews struct {
	pool    *pgxpool.Pool
	q       *store.Queries
	obj     *objstore.Store
	enqueue func(ctx context.Context, kind string, blobID uuid.UUID)
}

func NewPreviews(pool *pgxpool.Pool, obj *objstore.Store) *Previews {
	return &Previews{pool: pool, q: store.New(pool), obj: obj,
		enqueue: func(context.Context, string, uuid.UUID) {}}
}

func (s *Previews) SetEnqueue(fn func(ctx context.Context, kind string, blobID uuid.UUID)) {
	s.enqueue = fn
}

// ForList 列表路径:零额外查询。缩略图 URL 乐观签发(派生物可能还没生成,
// 前端 img onerror 回落图标),不查 derivatives 表。
func (s *Previews) ForList(ctx context.Context, name string, sha, mime *string, size *int64) PreviewInfo {
	if sha == nil {
		return PreviewInfo{Kind: PvNone}
	}
	kind := ClassifyPreview(name, mime, size)
	info := PreviewInfo{Kind: kind}
	if kind == PvImage || kind == PvVideo {
		thumbKind := "thumb256"
		if u, err := s.obj.PresignGetInline(ctx, objstore.DerivedKey(*sha, thumbKind)); err == nil {
			info.ThumbURL = &u
		}
		if kind == PvVideo {
			if u, err := s.obj.PresignGetInline(ctx, objstore.DerivedKey(*sha, "cover")); err == nil {
				info.ThumbURL = &u
			}
		}
	}
	return info
}

// ForNode 详情路径:补 largeUrl/contentUrl/状态/时长,给预览模态用。
func (s *Previews) ForNode(ctx context.Context, name string, sha *string, mime *string, size *int64) (PreviewInfo, error) {
	info := s.ForList(ctx, name, sha, mime, size)
	if sha == nil || info.Kind == PvNone {
		return info, nil
	}
	blob, err := s.q.GetBlobBySha256(ctx, *sha)
	if err != nil {
		return info, err
	}

	switch info.Kind {
	case PvImage:
		s.fillDerived(ctx, &info, blob, "thumb2048", &info.LargeURL)
		// 原图兜底(小图没生成 2048 版)
		if u, err := s.obj.PresignGetInline(ctx, objstore.BlobKey(*sha)); err == nil {
			info.ContentURL = &u
		}
	case PvPDF, PvText, PvVideo, PvAudio:
		if u, err := s.obj.PresignGetInline(ctx, objstore.BlobKey(*sha)); err == nil {
			info.ContentURL = &u
		}
		if info.Kind == PvVideo {
			s.fillDerived(ctx, &info, blob, "cover", &info.LargeURL)
		}
		if info.Kind == PvVideo || info.Kind == PvAudio {
			info.DurationSec = durationFromMeta(blob.MediaMeta)
		}
	case PvOffice:
		var pdfURL *string
		s.fillDerived(ctx, &info, blob, "pdf", &pdfURL)
		if pdfURL != nil {
			info.ContentURL = pdfURL
			done := "DONE"
			info.Status = &done
			return info, nil
		}
		// 还没有产物:看任务状态
		task, err := s.q.GetTask(ctx, store.GetTaskParams{Kind: "office_pdf", BlobID: blob.ID})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				return info, nil // 未触发,Status 为空,前端引导 requestPreview
			}
			return info, err
		}
		st := map[string]string{"pending": "PENDING", "running": "RUNNING", "done": "DONE", "failed": "FAILED"}[task.Status]
		info.Status = &st
	}
	return info, nil
}

// Request 惰性触发转换(目前只有 OFFICE 需要)。
func (s *Previews) Request(ctx context.Context, owner, nodeID uuid.UUID) (PreviewInfo, error) {
	row, err := s.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return PreviewInfo{Kind: PvNone}, ErrNotFound
		}
		return PreviewInfo{Kind: PvNone}, err
	}
	info, err := s.ForNode(ctx, row.Name, row.BlobSha256, row.BlobMime, row.BlobSize)
	if err != nil {
		return info, err
	}
	if info.Kind == PvOffice && info.Status == nil && row.BlobID != nil {
		s.enqueue(ctx, "office_pdf", *row.BlobID)
		st := "PENDING"
		info.Status = &st
	}
	return info, nil
}

func (s *Previews) fillDerived(ctx context.Context, info *PreviewInfo, blob store.Blob, kind string, target **string) {
	ds, err := s.q.GetDerivatives(ctx, blob.ID)
	if err != nil {
		return
	}
	for _, d := range ds {
		if d.Kind == kind {
			if u, err := s.obj.PresignGetInline(ctx, d.MinioKey); err == nil {
				*target = &u
			}
			return
		}
	}
}

func durationFromMeta(meta []byte) *int {
	if len(meta) == 0 {
		return nil
	}
	var m struct {
		DurationSec float64 `json:"duration_sec"`
	}
	if json.Unmarshal(meta, &m) != nil || m.DurationSec <= 0 {
		return nil
	}
	d := int(m.DurationSec)
	return &d
}
