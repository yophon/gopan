package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"path"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/archive"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// 压缩包的浏览、预览与解压(doc/21 四)。目录按 blob 建一次索引(worker 任务 archive_index),
// 浏览只查索引;预览单个文件时 Go 解压后流式返回;解压是后台任务(user_jobs)。
const (
	archiveMaxExtract = 20_000 // 一次解压的文件数
	archivePageSize   = 200
	orderArchive      = "ARCHIVE"
)

var (
	archivePreviewMax  int64 = 100 << 20 // 单个文件预览上限
	archivePreviewCost int64 = 256 << 20 // 预览前最多先解掉这么多(固实包、压缩 tar)
)

// SetArchivePreviewLimits 测试用:调小单个文件预览的大小与深度上限,返回恢复函数。
func SetArchivePreviewLimits(size, cost int64) func() {
	os, oc := archivePreviewMax, archivePreviewCost
	archivePreviewMax, archivePreviewCost = size, cost
	return func() { archivePreviewMax, archivePreviewCost = os, oc }
}

type Archives struct {
	pool    *pgxpool.Pool
	q       *store.Queries
	obj     *objstore.Store
	uploads *Uploads
	enqueue func(ctx context.Context, kind string, blobID uuid.UUID)
	sem     chan struct{} // 全站同时进行的预览
}

func NewArchives(pool *pgxpool.Pool, obj *objstore.Store, uploads *Uploads) *Archives {
	return &Archives{
		pool: pool, q: store.New(pool), obj: obj, uploads: uploads,
		enqueue: func(context.Context, string, uuid.UUID) {}, sem: make(chan struct{}, 2),
	}
}

func (a *Archives) SetEnqueue(fn func(ctx context.Context, kind string, blobID uuid.UUID)) { a.enqueue = fn }

// IsArchiveName 这个文件名会当压缩包看待(预览类型、入队索引都按它)。
func IsArchiveName(name string) bool { return archive.ByName(name) != "" }

// ---- 建索引(worker 调) ----

// BuildIndex 给一份内容建目录索引并落库。内容层面的问题(格式不对、损坏、加密、字典太大)
// 记成状态、返回 nil;只有存储读不到才返回错误让任务重试。
func (a *Archives) BuildIndex(ctx context.Context, blobID uuid.UUID) error {
	blob, err := a.q.GetBlob(ctx, blobID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if !blob.Verified || blob.RefCount <= 0 {
		return nil
	}
	nodes, err := a.q.ListNodesByBlob(ctx, &blob.ID)
	if err != nil {
		return err
	}
	name := ""
	for _, n := range nodes {
		if !n.DeletedAt.Valid && IsArchiveName(n.Name) {
			name = n.Name
			break
		}
	}
	if name == "" {
		return nil // 没人把它当压缩包(改名成 .zip 时回填会再来)
	}
	ra, size, err := a.obj.OpenReaderAt(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return err
	}
	defer ra.Close()
	cr := archive.NewChunkReader(ra, size)
	head := make([]byte, 512)
	n, _ := cr.ReadAt(head, 0)
	if cr.Err != nil {
		return cr.Err
	}
	f := archive.Resolve(head[:n], name)
	rec := store.InsertBlobArchiveParams{BlobID: blob.ID, Format: string(f), Indexer: archive.Version}
	var idx *archive.Index
	fail := func(status, msg string) {
		rec.Status = status
		rec.Error = &msg
	}
	switch {
	case f == "":
		fail("unsupported", "文件内容不是能识别的压缩包")
	case (f == archive.TarGz || f == archive.TarBz2 || f == archive.TarXz) && size > archive.MaxStreamScan:
		fail("unsupported", "压缩的 tar 超过 4 GB,不建目录;请下载后解压")
	default:
		start := time.Now()
		r, err := archive.Open(cr, size, f)
		if err == nil {
			idx, err = r.Index()
		}
		if cr.Err != nil {
			return fmt.Errorf("读取对象: %w", cr.Err)
		}
		switch {
		case errors.Is(err, archive.ErrEncrypted):
			rec.Encrypted = true
			fail("unsupported", "文件名也加密了,看不到目录")
		case errors.Is(err, archive.ErrDictTooLarge):
			fail("unsupported", "压缩字典超过 64 MB,请下载后解压")
		case errors.Is(err, archive.ErrUnsupported):
			fail("unsupported", "不支持的压缩格式或分卷")
		case err != nil:
			slog.Info("archive index failed", "blob", blob.ID, "format", f, "err", err)
			fail("failed", "压缩包损坏或格式不对")
		default:
			slog.Info("archive indexed", "blob", blob.ID, "format", f, "entries", len(idx.Entries), "took", time.Since(start).Round(time.Millisecond))
		}
	}
	if idx != nil {
		rec.Status = "done"
		if idx.Truncated {
			rec.Status = "truncated"
		}
		rec.Solid, rec.Encrypted = idx.Solid, idx.Encrypted
		rec.EntryCount, rec.FileCount, rec.TotalSize = int32(len(idx.Entries)), int32(idx.Files), idx.TotalSize
	}
	return a.saveIndex(ctx, rec, idx)
}

func (a *Archives) saveIndex(ctx context.Context, rec store.InsertBlobArchiveParams, idx *archive.Index) error {
	tx, err := a.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	q := a.q.WithTx(tx)
	if err := q.DeleteBlobArchive(ctx, rec.BlobID); err != nil {
		return err
	}
	if err := q.InsertBlobArchive(ctx, rec); err != nil {
		return err
	}
	if idx != nil && len(idx.Entries) > 0 {
		rows := make([]store.InsertArchiveEntriesParams, len(idx.Entries))
		for i, e := range idx.Entries {
			var mt pgtype.Timestamptz
			if t := archive.ModTime(e.MTime); t != nil {
				mt = pgtype.Timestamptz{Time: *t, Valid: true}
			}
			rows[i] = store.InsertArchiveEntriesParams{
				BlobID: rec.BlobID, Idx: int32(i), Src: int32(e.Src), Path: e.Path, Parent: e.Parent, Name: e.Name,
				IsDir: e.IsDir, Size: e.Size, PackedSize: e.Packed, Mtime: mt, Flag: e.Flag, Cost: e.Cost,
			}
		}
		if _, err := q.InsertArchiveEntries(ctx, rows); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// ---- 浏览 ----

// ArchiveInfo 压缩包的状态与概要。Status:PENDING(还没建好索引)/ DONE / TRUNCATED / UNSUPPORTED / FAILED。
type ArchiveInfo struct {
	Status    string
	Format    string
	Entries   int
	Files     int
	TotalSize int64
	Solid     bool
	Encrypted bool
	Error     *string
}

func (a *Archives) archiveNode(ctx context.Context, owner, nodeID uuid.UUID) (store.GetNodeWithBlobRow, error) {
	row, err := a.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return row, ErrNotFound
	}
	if err != nil {
		return row, err
	}
	if row.Kind != "file" || row.BlobID == nil || !IsArchiveName(row.Name) {
		return row, errf("NOT_ARCHIVE", "不是压缩包")
	}
	return row, nil
}

// Info 调用方负责权限(属主或分享访客,见 Shares.Authorize)。还没建索引的顺手排上。
func (a *Archives) Info(ctx context.Context, owner, nodeID uuid.UUID) (*ArchiveInfo, error) {
	row, err := a.archiveNode(ctx, owner, nodeID)
	if err != nil {
		return nil, err
	}
	ba, err := a.q.GetBlobArchive(ctx, *row.BlobID)
	if errors.Is(err, pgx.ErrNoRows) {
		if row.BlobVerified != nil && *row.BlobVerified {
			a.enqueue(ctx, "archive_index", *row.BlobID)
		}
		return &ArchiveInfo{Status: "PENDING", Format: string(archive.ByName(row.Name))}, nil
	}
	if err != nil {
		return nil, err
	}
	return &ArchiveInfo{
		Status: strings.ToUpper(ba.Status), Format: ba.Format, Entries: int(ba.EntryCount), Files: int(ba.FileCount),
		TotalSize: ba.TotalSize, Solid: ba.Solid, Encrypted: ba.Encrypted, Error: ba.Error,
	}, nil
}

func (a *Archives) readyArchive(ctx context.Context, owner, nodeID uuid.UUID) (store.GetNodeWithBlobRow, store.BlobArchive, error) {
	row, err := a.archiveNode(ctx, owner, nodeID)
	if err != nil {
		return row, store.BlobArchive{}, err
	}
	ba, err := a.q.GetBlobArchive(ctx, *row.BlobID)
	if errors.Is(err, pgx.ErrNoRows) {
		return row, ba, errf("ARCHIVE_NOT_READY", "还在读取压缩包目录,稍后再试")
	}
	if err != nil {
		return row, ba, err
	}
	if ba.Status != "done" && ba.Status != "truncated" {
		msg := "这个压缩包打不开"
		if ba.Error != nil {
			msg = *ba.Error
		}
		return row, ba, errf("ARCHIVE_UNAVAILABLE", "%s", msg)
	}
	return row, ba, nil
}

// Entries 列压缩包里某个目录(dir 为 "" 即根目录),目录在前、按名字,游标分页。
func (a *Archives) Entries(ctx context.Context, owner, nodeID uuid.UUID, dir string, cursor *string) ([]store.ArchiveEntry, *string, int64, error) {
	row, _, err := a.readyArchive(ctx, owner, nodeID)
	if err != nil {
		return nil, nil, 0, err
	}
	dir = strings.Trim(dir, "/")
	p := store.ListArchiveDirParams{BlobID: *row.BlobID, Parent: dir, AfterIdx: -1, Lim: archivePageSize}
	if c := decodeCursor(cursor, orderArchive, false); c != nil {
		p.AfterIdx, p.AfterDir, p.AfterName = int32(c.T), c.Kind == "folder", c.Name
	}
	rows, err := a.q.ListArchiveDir(ctx, p)
	if err != nil {
		return nil, nil, 0, err
	}
	total, err := a.q.CountArchiveDir(ctx, store.CountArchiveDirParams{BlobID: *row.BlobID, Parent: dir})
	if err != nil {
		return nil, nil, 0, err
	}
	var next *string
	if len(rows) == archivePageSize {
		last := rows[len(rows)-1]
		kind := "file"
		if last.IsDir {
			kind = "folder"
		}
		next = encodeCursor(cursorPayload{O: orderArchive, Kind: kind, Name: last.Name, T: int64(last.Idx)})
	}
	return rows, next, total, nil
}

// ---- 预览单个文件 ----

// ArchiveEntryStream 一个条目的内容。用完 Close(释放并发名额与对象连接)。
type ArchiveEntryStream struct {
	Name string
	Size int64
	Mime string
	io.ReadCloser
}

type entryCloser struct {
	io.Reader
	closers []func()
}

func (e *entryCloser) Close() error {
	for _, c := range e.closers {
		c()
	}
	return nil
}

var flagMessages = map[string]string{
	archive.FlagEncrypted:   "这个文件加密了,打不开",
	archive.FlagUnsafe:      "这个文件的路径不安全(带 ..),不能预览和解压",
	archive.FlagLink:        "这是链接,不是普通文件",
	archive.FlagUnsupported: "这个文件的压缩方式不支持,请下载整个压缩包后解压",
}

// EntryDownloadable 单独读这个条目在限度内(大小、在包里的深度)。
func EntryDownloadable(e store.ArchiveEntry) bool {
	return !e.IsDir && e.Src >= 0 && e.Flag == "" && e.Size <= archivePreviewMax && e.Cost <= archivePreviewCost
}

// EntryPreviewable 能直接预览:图片、PDF、文本。
func EntryPreviewable(e store.ArchiveEntry) bool {
	if !EntryDownloadable(e) {
		return false
	}
	mime := mimeByName(e.Name)
	switch ClassifyPreview(e.Name, &mime, &e.Size) {
	case PvImage, PvPDF, PvText:
		return true
	}
	return false
}

// OpenEntry 预览(download=false,只许图片、PDF、文本)或下载(任意类型)包里的一个文件。
func (a *Archives) OpenEntry(ctx context.Context, owner, nodeID uuid.UUID, idx int32, download bool) (*ArchiveEntryStream, error) {
	row, ba, err := a.readyArchive(ctx, owner, nodeID)
	if err != nil {
		return nil, err
	}
	e, err := a.q.GetArchiveEntry(ctx, store.GetArchiveEntryParams{BlobID: *row.BlobID, Idx: idx})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if e.IsDir || e.Src < 0 {
		return nil, errf("INVALID_INPUT", "这是文件夹")
	}
	if e.Flag != "" {
		return nil, errf("ARCHIVE_ENTRY_UNAVAILABLE", "%s", flagMessages[e.Flag])
	}
	if e.Size > archivePreviewMax {
		return nil, errf("ARCHIVE_ENTRY_TOO_LARGE", "单个文件超过 %s,请解压后再看", humanBytes(archivePreviewMax))
	}
	if e.Cost > archivePreviewCost {
		return nil, errf("ARCHIVE_ENTRY_TOO_DEEP", "要先解开前面的 %s 才能读到它,请解压后再看", humanBytes(e.Cost))
	}
	mime := mimeByName(e.Name)
	if !download {
		switch ClassifyPreview(e.Name, &mime, &e.Size) {
		case PvImage, PvPDF, PvText:
		default:
			return nil, errf("UNSUPPORTED_TYPE", "压缩包里只能直接预览图片、PDF 和文本")
		}
	}
	select {
	case a.sem <- struct{}{}:
	case <-time.After(10 * time.Second):
		return nil, errf("ARCHIVE_BUSY", "同时预览的人太多,稍后再试")
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	release := func() { <-a.sem }
	ra, size, err := a.obj.OpenReaderAt(ctx, objstore.BlobKey(*row.BlobSha256))
	if err != nil {
		release()
		return nil, err
	}
	r, err := archive.Open(archive.NewChunkReader(ra, size), size, archive.Format(ba.Format))
	var rc io.ReadCloser
	if err == nil {
		rc, err = r.OpenEntry(int(e.Src))
	}
	if err != nil {
		ra.Close()
		release()
		return nil, errf("ARCHIVE_CORRUPT", "读不出这个文件:压缩包可能损坏")
	}
	return &ArchiveEntryStream{
		Name: e.Name, Size: e.Size, Mime: mime,
		ReadCloser: &entryCloser{Reader: rc, closers: []func(){func() { rc.Close() }, func() { ra.Close() }, release}},
	}, nil
}

// ---- 解压 ----

// ExtractRequest 解压哪些:Paths 为空即 Base 下的全部;Paths 是包内完整路径,必须在 Base 之下。
// 解出来的路径相对 Base(在包里进了 docs/ 再勾选 2024/,结果是 <新文件夹>/2024/...)。
type ExtractRequest struct {
	NodeID uuid.UUID
	Base   string
	Paths  []string
	Parent *uuid.UUID // 新文件夹建在哪里;nil = 根目录
}

type extractParams struct {
	NodeID uuid.UUID `json:"node_id"`
	BlobID uuid.UUID `json:"blob_id"`
	Base   string    `json:"base,omitempty"`
	Paths  []string  `json:"paths,omitempty"`
	Folder string    `json:"folder"`
}

type selected struct {
	e   store.ArchiveEntry
	rel string
}

// selectEntries 选中的原生条目(按序号),带相对 base 的路径。
func selectEntries(entries []store.ArchiveEntry, base string, paths []string) ([]selected, error) {
	base = strings.Trim(base, "/")
	clean := make([]string, 0, len(paths))
	for _, p := range paths {
		p = strings.Trim(p, "/")
		if p == "" || (base != "" && p != base && !strings.HasPrefix(p, base+"/")) {
			return nil, errf("INVALID_INPUT", "选中的路径不在当前目录下:%s", truncateRunes(p, 80))
		}
		clean = append(clean, p)
	}
	if len(clean) == 0 {
		clean = []string{base}
	}
	var out []selected
	for _, e := range entries {
		if e.Src < 0 {
			continue
		}
		hit := false
		for _, p := range clean {
			if p == "" || e.Path == p || strings.HasPrefix(e.Path, p+"/") {
				hit = true
				break
			}
		}
		if !hit {
			continue
		}
		rel := e.Path
		if base != "" {
			rel = strings.TrimPrefix(e.Path, base+"/")
			if rel == e.Path {
				continue // base 本身这个目录
			}
		}
		out = append(out, selected{e: e, rel: rel})
	}
	return out, nil
}

// Extract 提交解压:预检文件个数与配额,写一个后台任务。
func (a *Archives) Extract(ctx context.Context, jobs *Jobs, owner uuid.UUID, req ExtractRequest, source string) (store.UserJob, error) {
	row, ba, err := a.readyArchive(ctx, owner, req.NodeID)
	if err != nil {
		return store.UserJob{}, err
	}
	entries, err := a.q.ListArchiveEntries(ctx, *row.BlobID)
	if err != nil {
		return store.UserJob{}, err
	}
	sel, err := selectEntries(entries, req.Base, req.Paths)
	if err != nil {
		return store.UserJob{}, err
	}
	var files int32
	var total int64
	for _, s := range sel {
		if !s.e.IsDir && s.e.Flag == "" {
			files++
			total += s.e.Size
		}
	}
	if files == 0 {
		return store.UserJob{}, errf("ARCHIVE_NOTHING", "选中的条目里没有能解压的文件")
	}
	if files > archiveMaxExtract {
		return store.UserJob{}, errf("TOO_MANY_FILES", "一次最多解压 %d 个文件,这次有 %d 个;请分批选择", archiveMaxExtract, files)
	}
	u, err := a.q.GetUserByID(ctx, owner)
	if err != nil {
		return store.UserJob{}, err
	}
	if u.UsedBytes+total > u.QuotaBytes {
		return store.UserJob{}, errf("QUOTA_EXCEEDED", "存储配额不足:解压后有 %s,剩余 %s", humanBytes(total), humanBytes(max(u.QuotaBytes-u.UsedBytes, 0)))
	}
	b, _ := json.Marshal(extractParams{
		NodeID: req.NodeID, BlobID: *row.BlobID, Base: strings.Trim(req.Base, "/"), Paths: req.Paths,
		Folder: archive.BaseName(row.Name),
	})
	_ = ba
	rows, err := jobs.create(ctx, owner, req.Parent, []JobSpec{{
		Kind: JobExtract, Params: b, Title: row.Name, BytesTotal: &total, FilesTotal: &files,
	}}, source)
	if err != nil {
		return store.UserJob{}, err
	}
	return rows[0], nil
}

// runExtract 执行解压:新建目标文件夹,顺序读包,每个文件写暂存、算 sha、流式落库(撞名改名)。
// 出错一律终态(不自动重试:重试会再建一个文件夹,已解出的留在第一个里),已解出的文件保留。
func (a *Archives) runExtract(ctx context.Context, j *Jobs, run *jobRun) (*uuid.UUID, error) {
	var p extractParams
	if err := json.Unmarshal(run.job.Params, &p); err != nil {
		return nil, errf("INVALID_INPUT", "任务参数损坏")
	}
	owner := run.job.OwnerID
	row, err := a.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: p.NodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errf("NOT_FOUND", "压缩包已删除或进了回收站")
	}
	if err != nil {
		return nil, err
	}
	if row.BlobID == nil || *row.BlobID != p.BlobID {
		return nil, errf("ARCHIVE_CHANGED", "压缩包在排队期间被替换了,请重新解压")
	}
	ba, err := a.q.GetBlobArchive(ctx, p.BlobID)
	if err != nil {
		return nil, err
	}
	entries, err := a.q.ListArchiveEntries(ctx, p.BlobID)
	if err != nil {
		return nil, err
	}
	sel, err := selectEntries(entries, p.Base, p.Paths)
	if err != nil {
		return nil, err
	}
	want := make(map[int32]selected, len(sel))
	for _, s := range sel {
		want[s.e.Src] = s
	}

	dest, err := a.uploads.CreateFolderIn(ctx, owner, run.job.ParentID, p.Folder)
	if err != nil {
		return nil, err
	}
	run.result = &dest.ID
	dirs := map[string]uuid.UUID{"": dest.ID}
	var ensure func(rel string) (uuid.UUID, error)
	ensure = func(rel string) (uuid.UUID, error) {
		if id, ok := dirs[rel]; ok {
			return id, nil
		}
		parentRel, name := path.Split(rel)
		pid, err := ensure(strings.TrimSuffix(parentRel, "/"))
		if err != nil {
			return uuid.Nil, err
		}
		n, err := a.uploads.EnsureFolderIn(ctx, owner, &pid, name)
		if err != nil {
			return uuid.Nil, err
		}
		dirs[rel] = n.ID
		return n.ID, nil
	}

	ra, size, err := a.obj.OpenReaderAt(ctx, objstore.BlobKey(*row.BlobSha256))
	if err != nil {
		return nil, err
	}
	defer ra.Close()
	cr := archive.NewChunkReader(ra, size)
	r, err := archive.Open(cr, size, archive.Format(ba.Format))
	if err != nil {
		return nil, errf("ARCHIVE_CORRUPT", "打不开压缩包")
	}
	key := jobTmpKey(run.job.ID)
	current := ""
	err = r.Walk(func(src int, rd io.Reader) (bool, error) {
		if err := ctx.Err(); err != nil {
			return false, err
		}
		s, ok := want[int32(src)]
		if !ok {
			return true, nil
		}
		current = s.rel
		if s.e.IsDir {
			if s.e.Flag != "" {
				return true, nil
			}
			_, err := ensure(s.rel)
			return err == nil, err
		}
		dir, name := path.Split(s.rel)
		if s.e.Flag != "" || rd == nil || validateName(name) != nil {
			run.fileSkipped()
			return true, nil
		}
		pid, err := ensure(strings.TrimSuffix(dir, "/"))
		if err != nil {
			return false, err
		}
		h := sha256.New()
		cnt := &countingReader{r: rd, h: h, run: run, limit: s.e.Size}
		if err := a.obj.Put(ctx, key, cnt, s.e.Size, "application/octet-stream"); err != nil {
			if cnt.err != nil {
				return false, cnt.err // 包里的数据读坏了
			}
			return false, err
		}
		if _, err := a.uploads.CommitStaged(ctx, StagedFile{
			Owner: owner, Parent: &pid, Name: name, Sha: hex.EncodeToString(h.Sum(nil)), Size: s.e.Size, TmpKey: key,
		}); err != nil {
			return false, err
		}
		run.fileDone()
		return true, nil
	})
	var se *Error
	switch {
	case err == nil:
		return &dest.ID, nil
	case ctx.Err() != nil:
		return nil, ctx.Err()
	case errors.As(err, &se):
		return nil, err
	case cr.Err != nil:
		return nil, errf("JOB_ERROR", "读取压缩包失败(存储暂时不可用),已解出的文件保留,可以重试")
	case errors.Is(err, archive.ErrTooLong), errors.Is(err, io.ErrUnexpectedEOF):
		return nil, errf("ARCHIVE_CORRUPT", "压缩包损坏:%s 的实际长度与声明的不一致", truncateRunes(current, 80))
	default:
		slog.Warn("extract failed", "job", run.job.ID, "err", err)
		return nil, errf("ARCHIVE_CORRUPT", "解到 %s 时出错,压缩包可能损坏;已解出的文件保留", truncateRunes(current, 80))
	}
}
