package worker

// 内容搜索的文字提取(doc/20 第三节):每份内容(blob)提一次,结果写进 blob_texts。
// 判断类型靠嗅探文件头,不信 blobs.mime(那是第一个上传者的文件名决定的)。
// 内容本身的问题(坏 PDF、加密、解不出编码)记为 failed / unsupported 并算任务完成;
// 只有对象存储、数据库这类基础设施错误才返回 error 走重试。

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

const (
	maxTextFileBytes  = 32 << 20  // 文本文件只读前 32MB
	maxPDFBytes       = 200 << 20 // 更大的 PDF 不提
	maxOfficeBytes    = 100 << 20
	textBackfillBatch = 30 // 每分钟最多排这么多个存量 blob,细水长流
)

// SetTextIndex 开关内容索引;pdftotext 为空表示本机没有,PDF 记为不支持。
func (w *Pool) SetTextIndex(enabled bool, pdftotext string) {
	w.textIndex = enabled
	w.pdftotext = pdftotext
}

type extracted struct {
	status    string // done / empty / truncated / unsupported / failed
	pages     []string
	truncated bool
}

func (w *Pool) extractText(ctx context.Context, blobID uuid.UUID) error {
	blob, err := w.q.GetBlob(ctx, blobID)
	if err != nil {
		return err
	}
	if !blob.Verified || blob.RefCount <= 0 {
		return nil // 未校验的等 verify_hash 通过后再来;没人引用的等回收
	}
	nodes, err := w.q.ListNodesByBlob(ctx, &blob.ID)
	if err != nil {
		return err
	}
	names := make([]string, 0, len(nodes))
	for _, n := range nodes {
		names = append(names, n.Name)
	}
	head, err := w.readHead(ctx, objstore.BlobKey(blob.Sha256), 8192)
	if err != nil {
		return err
	}

	var res extracted
	switch kind := fulltext.Sniff(head, names, blob.Mime); kind {
	case fulltext.KindText:
		res, err = w.extractPlain(ctx, blob)
	case fulltext.KindPDF:
		res, err = w.extractPDF(ctx, objstore.BlobKey(blob.Sha256), blob.Size)
	case fulltext.KindZip:
		res, err = w.extractOffice(ctx, blob)
	case fulltext.KindOLE:
		res, err = w.extractOLE(ctx, blob)
	default:
		res = extracted{status: "unsupported"}
	}
	if err != nil {
		return err
	}
	return w.saveText(ctx, blob, res)
}

func (w *Pool) saveText(ctx context.Context, blob store.Blob, res extracted) error {
	p := store.UpsertBlobTextParams{BlobID: blob.ID, Status: res.status, Extractor: fulltext.Version, Terms: []string{}}
	if res.status == "" {
		content, starts, cut := fulltext.Build(res.pages, fulltext.MaxContentBytes)
		terms, termsCut := fulltext.Terms(content, fulltext.MaxTerms)
		p.Content, p.Terms = content, terms
		if len(starts) > 1 {
			p.PageStarts = starts
		}
		switch {
		case content == "":
			p.Status = "empty" // 支持的格式,但没有文字(扫描件、空文档)
		case cut || termsCut || res.truncated:
			p.Status = "truncated"
		default:
			p.Status = "done"
		}
	}
	if err := w.q.UpsertBlobText(ctx, p); err != nil {
		return err
	}
	slog.Info("text extracted", "sha256", blob.Sha256, "status", p.Status, "bytes", len(p.Content), "terms", len(p.Terms))
	return nil
}

func (w *Pool) extractPlain(ctx context.Context, blob store.Blob) (extracted, error) {
	rc, err := w.obj.Open(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return extracted{}, err
	}
	defer rc.Close()
	b, err := io.ReadAll(io.LimitReader(rc, maxTextFileBytes))
	if err != nil {
		return extracted{}, err
	}
	text, ok := fulltext.DecodeText(b)
	if !ok {
		return extracted{status: "unsupported"}, nil
	}
	return extracted{pages: []string{text}, truncated: blob.Size > maxTextFileBytes}, nil
}

func (w *Pool) extractPDF(ctx context.Context, key string, size int64) (extracted, error) {
	if w.pdftotext == "" || size > maxPDFBytes {
		return extracted{status: "unsupported"}, nil
	}
	file, cleanup, err := w.download(ctx, key, maxPDFBytes)
	if err != nil {
		return extracted{}, err
	}
	defer cleanup()
	pages, truncated, err := fulltext.PDFText(ctx, w.pdftotext, file)
	if err != nil {
		if ctx.Err() != nil {
			return extracted{}, ctx.Err() // 停机:下次重来
		}
		slog.Warn("pdf text failed", "key", key, "err", err) // 加密、损坏、超时
		return extracted{status: "failed"}, nil
	}
	return extracted{pages: pages, truncated: truncated}, nil
}

func (w *Pool) extractOffice(ctx context.Context, blob store.Blob) (extracted, error) {
	if blob.Size > maxOfficeBytes {
		return extracted{status: "unsupported"}, nil
	}
	file, cleanup, err := w.download(ctx, objstore.BlobKey(blob.Sha256), maxOfficeBytes)
	if err != nil {
		return extracted{}, err
	}
	defer cleanup()
	zr, err := zip.OpenReader(file)
	if err != nil {
		return extracted{status: "failed"}, nil
	}
	defer zr.Close()
	pages, truncated, err := fulltext.OfficeText(&zr.Reader)
	switch {
	case errors.Is(err, fulltext.ErrNotOffice):
		return extracted{status: "unsupported"}, nil // 普通压缩包
	case err != nil:
		slog.Warn("office text failed", "sha256", blob.Sha256, "err", err)
		return extracted{status: "failed"}, nil
	}
	return extracted{pages: pages, truncated: truncated}, nil
}

// extractOLE 旧版 doc / xls / ppt:只有 Office 组件已经转出过 PDF 时才从派生 PDF 里提;
// 转换完成后 makeOfficePDF 会把提取任务重新排上。
func (w *Pool) extractOLE(ctx context.Context, blob store.Blob) (extracted, error) {
	ds, err := w.q.GetDerivatives(ctx, blob.ID)
	if err != nil {
		return extracted{}, err
	}
	for _, d := range ds {
		if d.Kind == "pdf" {
			return w.extractPDF(ctx, d.MinioKey, d.Size)
		}
	}
	return extracted{status: "unsupported"}, nil
}

// readHead 读对象开头 n 字节(嗅探用)。
func (w *Pool) readHead(ctx context.Context, key string, n int64) ([]byte, error) {
	rc, err := w.obj.Open(ctx, key)
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	b, err := io.ReadAll(io.LimitReader(rc, n))
	if err != nil {
		return nil, err
	}
	return b, nil
}

// download 把对象落到临时文件(zip 要随机读、pdftotext 要文件路径),返回路径与清理函数。
func (w *Pool) download(ctx context.Context, key string, limit int64) (string, func(), error) {
	rc, err := w.obj.Open(ctx, key)
	if err != nil {
		return "", nil, err
	}
	defer rc.Close()
	f, err := os.CreateTemp("", "gopan-extract-*")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() { _ = os.Remove(f.Name()) }
	if _, err := io.Copy(f, io.LimitReader(rc, limit)); err != nil {
		f.Close()
		cleanup()
		return "", nil, fmt.Errorf("下载 %s: %w", key, err)
	}
	if err := f.Close(); err != nil {
		cleanup()
		return "", nil, err
	}
	return f.Name(), cleanup, nil
}

// backfillText 每分钟给一小批还没提过(或提取器版本旧了)的存量内容排上提取任务。
func (w *Pool) backfillText(ctx context.Context) error {
	ids, err := w.q.ListBlobsNeedingText(ctx, store.ListBlobsNeedingTextParams{Extractor: fulltext.Version, Limit: textBackfillBatch})
	if err != nil {
		return err
	}
	for _, id := range ids {
		w.requeue(ctx, "extract_text", id)
	}
	if len(ids) > 0 {
		slog.Info("text backfill queued", "count", len(ids))
	}
	return nil
}

// requeue 同 Enqueue,但已完成的任务也重新排上。
func (w *Pool) requeue(ctx context.Context, kind string, blobID uuid.UUID) {
	if err := w.q.RequeueTask(ctx, store.RequeueTaskParams{ID: uuid.Must(uuid.NewV7()), Kind: kind, BlobID: blobID}); err != nil {
		slog.Error("requeue task", "kind", kind, "err", err)
		return
	}
	select {
	case w.wake <- struct{}{}:
	default:
	}
}
