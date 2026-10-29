package service

import (
	"bytes"
	"context"
	"image"
	"io"
	"os"

	"github.com/disintegration/imaging"
	"github.com/google/uuid"
	_ "golang.org/x/image/webp" // 原图兜底认得 webp

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/objstore"
)

// 给模型看的图(doc/35):图片用派生的 thumb2048,还没生成就拿原图现缩;视频用封面;PDF 和转好的
// Office 渲染第一页。一律缩成长边不超过 maxPx 的 JPEG(质量 80),存进对话记录也不大。

// 原图兜底的上限:再大的图等派生图生成了再看,不在请求里解码
const maxViewOriginal = 20 << 20

// PDF 渲染第一页要先落盘,太大的不渲染
const maxViewPDF = 200 << 20

// ViewedImage 一张给模型看的图。Source 说明图从哪来:image / video_cover / pdf_page。
type ViewedImage struct {
	Name   string
	Source string
	JPEG   []byte
	Width  int
	Height int
}

// SetPDFRenderer pdftoppm 的路径;空 = 不渲染 PDF(扫描件看不了)。
func (s *Uploads) SetPDFRenderer(bin string) { s.pdftoppm = bin }

// ViewImage 把一个文件变成给模型看的一张图。不是图片、视频、PDF、Office 的报 NOT_IMAGE;
// 还没有可用的预览图报 NOT_READY。
func (s *Uploads) ViewImage(ctx context.Context, owner, nodeID uuid.UUID, maxPx int) (*ViewedImage, error) {
	row, err := s.NodeInfo(ctx, owner, nodeID)
	if err != nil {
		return nil, err
	}
	if row.Kind != "file" || row.BlobSha256 == nil || row.BlobSize == nil {
		return nil, errf("NOT_IMAGE", "文件夹没有图可看")
	}
	blob, err := s.q.GetBlobBySha256(ctx, *row.BlobSha256)
	if err != nil {
		return nil, err
	}
	derived := map[string]string{}
	if ds, err := s.q.GetDerivatives(ctx, blob.ID); err == nil {
		for _, d := range ds {
			derived[d.Kind] = d.MinioKey
		}
	}
	out := &ViewedImage{Name: row.Name}
	var src image.Image
	switch ClassifyPreview(row.Name, row.BlobMime, row.BlobSize) {
	case PvImage:
		out.Source = "image"
		if key, ok := derived["thumb2048"]; ok {
			src, err = s.decodeObject(ctx, key)
		} else if *row.BlobSize <= maxViewOriginal {
			src, err = s.decodeObject(ctx, objstore.BlobKey(*row.BlobSha256))
			if err != nil {
				return nil, errf("NOT_READY", "这张图还没生成预览图,稍后再看")
			}
		} else {
			return nil, errf("NOT_READY", "这张图还没生成预览图,稍后再看")
		}
	case PvVideo:
		out.Source = "video_cover"
		key, ok := derived["cover"]
		if !ok {
			return nil, errf("NOT_READY", "视频封面还没生成,稍后再看")
		}
		src, err = s.decodeObject(ctx, key)
	case PvPDF:
		out.Source = "pdf_page"
		src, err = s.pdfFirstPage(ctx, objstore.BlobKey(*row.BlobSha256), *row.BlobSize, maxPx)
	case PvOffice:
		out.Source = "pdf_page"
		key, ok := derived["pdf"]
		if !ok {
			return nil, errf("NOT_READY", "这个文档还没转换成 PDF,先在网页里打开预览一次")
		}
		src, err = s.pdfFirstPage(ctx, key, maxViewPDF, maxPx)
	default:
		return nil, errf("NOT_IMAGE", "不是图片、视频或文档,没有图可看")
	}
	if err != nil {
		return nil, err
	}
	b := src.Bounds()
	if b.Dx() > maxPx || b.Dy() > maxPx {
		src = imaging.Fit(src, maxPx, maxPx, imaging.Lanczos)
	}
	var buf bytes.Buffer
	if err := imaging.Encode(&buf, src, imaging.JPEG, imaging.JPEGQuality(80)); err != nil {
		return nil, err
	}
	out.JPEG = buf.Bytes()
	out.Width, out.Height = src.Bounds().Dx(), src.Bounds().Dy()
	return out, nil
}

func (s *Uploads) decodeObject(ctx context.Context, key string) (image.Image, error) {
	r, err := s.obj.Open(ctx, key)
	if err != nil {
		return nil, err
	}
	defer r.Close()
	return imaging.Decode(io.LimitReader(r, maxViewOriginal+1), imaging.AutoOrientation(true))
}

// pdfFirstPage 把对象存储里的 PDF 落到临时文件,渲染第一页。
func (s *Uploads) pdfFirstPage(ctx context.Context, key string, size int64, maxPx int) (image.Image, error) {
	if s.pdftoppm == "" {
		return nil, errf("NOT_READY", "本站没装 PDF 渲染工具,看不了 PDF 的页面")
	}
	if size > maxViewPDF {
		return nil, errf("TOO_LARGE", "超过 200 MB 的 PDF 不渲染")
	}
	r, err := s.obj.Open(ctx, key)
	if err != nil {
		return nil, err
	}
	defer r.Close()
	f, err := os.CreateTemp("", "gopan-view-*.pdf")
	if err != nil {
		return nil, err
	}
	defer os.Remove(f.Name())
	_, err = io.Copy(f, io.LimitReader(r, maxViewPDF+1))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return nil, err
	}
	jpg, err := fulltext.PDFFirstPage(ctx, s.pdftoppm, f.Name(), maxPx)
	if err != nil {
		return nil, errf("NOT_READY", "PDF 第一页渲染失败")
	}
	return imaging.Decode(bytes.NewReader(jpg))
}
