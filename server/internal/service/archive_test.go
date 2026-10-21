package service_test

// 压缩包(doc/21 四)的集成测试:真 Postgres + MinIO。建索引 → 浏览 → 预览单个文件 → 解压任务。

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
	"golang.org/x/text/encoding/simplifiedchinese"
)

// zhSolid7z 中文名、固实的 7z(与 internal/archive/testdata 同一份;测试二进制会拿到别处跑,所以嵌进来)
//
//go:embed testdata/zh-solid.7z
var zhSolid7z []byte

type archiveFixture struct {
	*jobsFixture
	archives *service.Archives
	enqueued []string
}

func newArchiveFixture(t *testing.T) *archiveFixture {
	t.Helper()
	f := &archiveFixture{jobsFixture: newJobsFixture(t, loopbackFetch())}
	f.archives = service.NewArchives(f.pool, f.obj, f.uploads)
	f.archives.SetEnqueue(func(_ context.Context, kind string, _ uuid.UUID) { f.enqueued = append(f.enqueued, kind) })
	f.jobs.SetArchives(f.archives)
	return f
}

// put 放一个文件进 f.folder,返回节点(blob 已校验)。
func (f *archiveFixture) put(t *testing.T, name string, content []byte) store.Node {
	t.Helper()
	key := "tmp/test/" + uuid.NewString()
	if err := f.obj.Put(t.Context(), key, bytes.NewReader(content), int64(len(content)), "application/octet-stream"); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(content)
	n, err := f.uploads.CommitStaged(t.Context(), service.StagedFile{Owner: f.owner, Parent: &f.folder, Name: name, Sha: hex.EncodeToString(sum[:]), Size: int64(len(content)), TmpKey: key})
	if err != nil {
		t.Fatal(err)
	}
	return *n
}

func (f *archiveFixture) index(t *testing.T, n store.Node) {
	t.Helper()
	if err := f.archives.BuildIndex(t.Context(), *n.BlobID); err != nil {
		t.Fatal(err)
	}
}

func (f *archiveFixture) read(t *testing.T, n store.Node, path string, download bool) ([]byte, error) {
	t.Helper()
	e := f.entry(t, n, path)
	st, err := f.archives.OpenEntry(t.Context(), f.owner, n.ID, e.Idx, download)
	if err != nil {
		return nil, err
	}
	defer st.Close()
	return io.ReadAll(st)
}

// entry 按路径在索引里找(逐层列目录,顺便测了浏览)。
func (f *archiveFixture) entry(t *testing.T, n store.Node, p string) store.ArchiveEntry {
	t.Helper()
	dir := ""
	if i := strings.LastIndex(p, "/"); i >= 0 {
		dir = p[:i]
	}
	var cursor *string
	for {
		rows, next, _, err := f.archives.Entries(t.Context(), f.owner, n.ID, dir, cursor)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range rows {
			if r.Path == p {
				return r
			}
		}
		if next == nil {
			t.Fatalf("包里没有 %q", p)
		}
		cursor = next
	}
}

type childRow struct {
	ID         uuid.UUID
	Kind       string
	BlobSize   *int64
	BlobSha256 *string
}

// children 列出 parent 下的子节点名 → 节点。
func (f *archiveFixture) children(t *testing.T, parent uuid.UUID) map[string]childRow {
	t.Helper()
	rows, err := f.pool.Query(t.Context(), `SELECT n.id, n.name, n.kind, b.size, b.sha256 FROM nodes n
		LEFT JOIN blobs b ON b.id = n.blob_id WHERE n.parent_id = $1 AND n.deleted_at IS NULL`, parent)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	out := map[string]childRow{}
	for rows.Next() {
		var name string
		var r childRow
		if err := rows.Scan(&r.ID, &name, &r.Kind, &r.BlobSize, &r.BlobSha256); err != nil {
			t.Fatal(err)
		}
		out[name] = r
	}
	return out
}

func testZip(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	add := func(h *zip.FileHeader, body string) {
		fw, err := w.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		fw.Write([]byte(body))
	}
	gbk, _ := simplifiedchinese.GBK.NewEncoder().String("报告/年度总结.txt")
	add(&zip.FileHeader{Name: gbk, NonUTF8: true, Method: zip.Deflate}, "年度总结正文")
	add(&zip.FileHeader{Name: "docs/2024/a.txt", Method: zip.Deflate}, strings.Repeat("a", 5000))
	add(&zip.FileHeader{Name: "docs/2024/b.html"}, "<script>alert(1)</script>")
	add(&zip.FileHeader{Name: "docs/2025/c.bin"}, "\x00\x01\x02")
	add(&zip.FileHeader{Name: "docs/empty/"}, "")
	add(&zip.FileHeader{Name: "dup.txt"}, "one")
	add(&zip.FileHeader{Name: "dup.txt"}, "two")
	add(&zip.FileHeader{Name: "../evil.txt"}, "evil")
	lnk := &zip.FileHeader{Name: "lnk"}
	lnk.SetMode(fs.ModeSymlink | 0o777)
	add(lnk, "/etc/passwd")
	add(&zip.FileHeader{Name: "secret.txt", Flags: 0x1}, "xxxx")
	for i := 0; i < 250; i++ { // 一个目录里 250 条,测翻页
		add(&zip.FileHeader{Name: fmt.Sprintf("many/f%03d.txt", i)}, "x")
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestArchiveIndexAndBrowse(t *testing.T) {
	f := newArchiveFixture(t)
	n := f.put(t, "资料.zip", testZip(t))

	// 还没建索引:PENDING,并顺手排上任务
	info, err := f.archives.Info(t.Context(), f.owner, n.ID)
	if err != nil || info.Status != "PENDING" || len(f.enqueued) != 1 || f.enqueued[0] != "archive_index" {
		t.Fatalf("pending: %+v %v %v", info, err, f.enqueued)
	}
	if _, _, _, err := f.archives.Entries(t.Context(), f.owner, n.ID, "", nil); !isCode(err, "ARCHIVE_NOT_READY") {
		t.Fatalf("未就绪时列目录: %v", err)
	}
	f.index(t, n)
	info, err = f.archives.Info(t.Context(), f.owner, n.ID)
	if err != nil || info.Status != "DONE" || info.Format != "zip" || !info.Encrypted || info.Files != 259 {
		t.Fatalf("info: %+v %v", info, err)
	}
	// 根目录:目录在前、按名字
	rows, next, total, err := f.archives.Entries(t.Context(), f.owner, n.ID, "", nil)
	if err != nil || next != nil {
		t.Fatal(err)
	}
	var names []string
	for _, r := range rows {
		names = append(names, r.Name)
	}
	if !rows[0].IsDir || total != int64(len(rows)) || !strings.Contains(strings.Join(names, ","), "报告") {
		t.Fatalf("根目录: %v", names)
	}
	// 250 条翻两页,不重不漏
	seen := map[string]bool{}
	var cursor *string
	pages := 0
	for {
		rows, next, total, err := f.archives.Entries(t.Context(), f.owner, n.ID, "many", cursor)
		if err != nil || total != 250 {
			t.Fatalf("many: %d %v", total, err)
		}
		for _, r := range rows {
			if seen[r.Name] {
				t.Fatalf("重复 %s", r.Name)
			}
			seen[r.Name] = true
		}
		pages++
		if next == nil {
			break
		}
		cursor = next
	}
	if len(seen) != 250 || pages != 2 {
		t.Fatalf("翻页:%d 条 %d 页", len(seen), pages)
	}
	// 别人的、不是压缩包的
	other, _ := newAuth(f.pool).Register(t.Context(), "ar_other", "password123", "1.1.1.1")
	if _, err := f.archives.Info(t.Context(), other.User.ID, n.ID); !isCode(err, "NOT_FOUND") {
		t.Fatalf("他人: %v", err)
	}
	txt := f.put(t, "a.txt", []byte("hi"))
	if _, err := f.archives.Info(t.Context(), f.owner, txt.ID); !isCode(err, "NOT_ARCHIVE") {
		t.Fatalf("非压缩包: %v", err)
	}
}

func TestArchivePreviewEntry(t *testing.T) {
	f := newArchiveFixture(t)
	n := f.put(t, "x.zip", testZip(t))
	f.index(t, n)
	if b, err := f.read(t, n, "报告/年度总结.txt", false); err != nil || string(b) != "年度总结正文" {
		t.Fatalf("GBK 名的文本: %q %v", b, err)
	}
	// html 能预览(按纯文本给),响应类型的改写在 httpx 测
	e := f.entry(t, n, "docs/2024/b.html")
	st, err := f.archives.OpenEntry(t.Context(), f.owner, n.ID, e.Idx, false)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mime != "text/html; charset=utf-8" || st.Size != int64(len(`<script>alert(1)</script>`)) {
		t.Fatalf("html: %s %d", st.Mime, st.Size)
	}
	st.Close()
	if _, err := f.read(t, n, "docs/2025/c.bin", false); !isCode(err, "UNSUPPORTED_TYPE") {
		t.Fatalf(".bin 预览应拒: %v", err)
	}
	if b, err := f.read(t, n, "docs/2025/c.bin", true); err != nil || len(b) != 3 {
		t.Fatalf(".bin 下载: %v", err)
	}
	for p, code := range map[string]string{"evil.txt": "ARCHIVE_ENTRY_UNAVAILABLE", "lnk": "ARCHIVE_ENTRY_UNAVAILABLE", "secret.txt": "ARCHIVE_ENTRY_UNAVAILABLE"} {
		if _, err := f.read(t, n, p, true); !isCode(err, code) {
			t.Fatalf("%s: %v", p, err)
		}
	}
	if _, err := f.read(t, n, "docs", false); !isCode(err, "INVALID_INPUT") {
		t.Fatalf("目录: %v", err)
	}
	if !service.EntryPreviewable(f.entry(t, n, "docs/2024/a.txt")) || service.EntryPreviewable(f.entry(t, n, "docs/2025/c.bin")) {
		t.Fatal("previewable")
	}
	// 大小与深度上限
	restore := service.SetArchivePreviewLimits(1000, 1<<30)
	if _, err := f.read(t, n, "docs/2024/a.txt", false); !isCode(err, "ARCHIVE_ENTRY_TOO_LARGE") {
		t.Fatalf("太大: %v", err)
	}
	restore()

	// 压缩 tar:后面的文件要先解掉前面的
	var raw bytes.Buffer
	tw := tar.NewWriter(&raw)
	big := bytes.Repeat([]byte("z"), 64<<10)
	tw.WriteHeader(&tar.Header{Name: "big.bin", Size: int64(len(big)), Mode: 0o644})
	tw.Write(big)
	tw.WriteHeader(&tar.Header{Name: "late.txt", Size: 4, Mode: 0o644})
	tw.Write([]byte("late"))
	tw.Close()
	var gz bytes.Buffer
	zw := gzip.NewWriter(&gz)
	zw.Write(raw.Bytes())
	zw.Close()
	tg := f.put(t, "x.tar.gz", gz.Bytes())
	f.index(t, tg)
	if b, err := f.read(t, tg, "late.txt", false); err != nil || string(b) != "late" {
		t.Fatalf("tar.gz: %q %v", b, err)
	}
	restore = service.SetArchivePreviewLimits(100<<20, 1024)
	defer restore()
	if _, err := f.read(t, tg, "late.txt", false); !isCode(err, "ARCHIVE_ENTRY_TOO_DEEP") {
		t.Fatalf("太深: %v", err)
	}
	if b, err := f.read(t, tg, "big.bin", true); err != nil || len(b) != 64<<10 {
		t.Fatalf("第一个文件不深: %v", err)
	}
}

func TestArchiveExtractAll(t *testing.T) {
	f := newArchiveFixture(t)
	data := testZip(t)
	n := f.put(t, "资料.zip", data)
	f.index(t, n)
	before := f.usedBytes(t)
	j, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Parent: &f.folder}, "web")
	if err != nil {
		t.Fatal(err)
	}
	if j.Kind != service.JobExtract || j.FilesTotal == nil || *j.FilesTotal != 256 || j.Title != "资料.zip" {
		t.Fatalf("提交: %+v", j)
	}
	f.runOnce(t)
	j = f.get(t, j.ID)
	if j.Status != "done" || j.FilesDone != 256 || j.FilesSkipped != 3 || j.ResultNodeID == nil {
		t.Fatalf("完成: %+v code=%s", j, code(j))
	}
	top := f.children(t, f.folder)
	dest, ok := top["资料"]
	if !ok || dest.ID != *j.ResultNodeID || dest.Kind != "folder" {
		t.Fatalf("目标文件夹: %v", top)
	}
	in := f.children(t, dest.ID)
	for _, want := range []string{"报告", "docs", "many", "dup.txt", "dup (1).txt"} {
		if _, ok := in[want]; !ok {
			t.Fatalf("缺 %s: %v", want, in)
		}
	}
	for _, skipped := range []string{"evil.txt", "lnk", "secret.txt"} {
		if _, ok := in[skipped]; ok {
			t.Fatalf("%s 不该解出来", skipped)
		}
	}
	docs := f.children(t, in["docs"].ID)
	if _, ok := docs["empty"]; !ok {
		t.Fatal("空目录也要建")
	}
	y2024 := f.children(t, docs["2024"].ID)
	a := y2024["a.txt"]
	sum := sha256.Sum256([]byte(strings.Repeat("a", 5000)))
	if a.BlobSha256 == nil || *a.BlobSha256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("a.txt 内容: %+v", a)
	}
	// 年度总结正文(18)+ a(5000)+ b.html(25)+ c.bin(3)+ dup×2(6)+ 250 个 x
	if got := f.usedBytes(t) - before; got != 18+5000+25+3+6+250 {
		t.Fatalf("新增用量 %d", got)
	}
	// 再解一次:目标文件夹改名,不合并
	j2, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Parent: &f.folder}, "web")
	if err != nil {
		t.Fatal(err)
	}
	f.runOnce(t)
	if j2 = f.get(t, j2.ID); j2.Status != "done" {
		t.Fatalf("第二次: %+v", j2)
	}
	if _, ok := f.children(t, f.folder)["资料 (1)"]; !ok {
		t.Fatal("第二次应解到 资料 (1)")
	}
}

func TestArchiveExtractSelected(t *testing.T) {
	f := newArchiveFixture(t)
	n := f.put(t, "x.zip", testZip(t))
	f.index(t, n)
	// 在 docs 里勾选 2024:结果是 <x>/2024/...,不带 docs
	j, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Base: "docs", Paths: []string{"docs/2024"}}, "web")
	if err != nil {
		t.Fatal(err)
	}
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "done" || j.FilesDone != 2 {
		t.Fatalf("%+v code=%s", j, code(j))
	}
	dest := f.children(t, *j.ResultNodeID)
	if len(dest) != 1 || dest["2024"].Kind != "folder" {
		t.Fatalf("选中解压: %v", dest)
	}
	if j.ParentID != nil {
		t.Fatal("parent 为空 = 根目录")
	}
	// 选中的路径不在 base 下
	if _, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Base: "docs", Paths: []string{"many"}}, "web"); !isCode(err, "INVALID_INPUT") {
		t.Fatalf("越出 base: %v", err)
	}
	// 只选了解不了的
	if _, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Paths: []string{"secret.txt", "lnk"}}, "web"); !isCode(err, "ARCHIVE_NOTHING") {
		t.Fatalf("没有可解的: %v", err)
	}
	// 配额预检
	if _, err := f.pool.Exec(t.Context(), `UPDATE users SET quota_bytes = used_bytes + 100 WHERE id = $1`, f.owner); err != nil {
		t.Fatal(err)
	}
	if _, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID}, "web"); !isCode(err, "QUOTA_EXCEEDED") {
		t.Fatalf("配额: %v", err)
	}
}

func TestArchiveSolid7zAndBroken(t *testing.T) {
	f := newArchiveFixture(t)
	n := f.put(t, "中文.7z", zhSolid7z)
	f.index(t, n)
	j, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Parent: &f.folder}, "web")
	if err != nil {
		t.Fatal(err)
	}
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "done" || j.FilesDone != 3 {
		t.Fatalf("7z: %+v code=%s", j, code(j))
	}
	doc := f.children(t, f.children(t, *j.ResultNodeID)["文档"].ID)
	if doc["报告.txt"].BlobSize == nil || *doc["报告.txt"].BlobSize != 2800 {
		t.Fatalf("7z 解出: %v", doc)
	}

	// 名字是 zip、内容不是:unsupported;坏 zip:failed。都不重试
	junk := f.put(t, "junk.zip", []byte("this is not an archive at all"))
	f.index(t, junk)
	info, _ := f.archives.Info(t.Context(), f.owner, junk.ID)
	if info.Status != "UNSUPPORTED" || info.Error == nil {
		t.Fatalf("junk: %+v", info)
	}
	z := testZip(t)
	broken := f.put(t, "broken.zip", z[:len(z)/2])
	f.index(t, broken)
	if info, _ := f.archives.Info(t.Context(), f.owner, broken.ID); info.Status != "FAILED" {
		t.Fatalf("broken: %+v", info)
	}
	if _, _, _, err := f.archives.Entries(t.Context(), f.owner, broken.ID, "", nil); !isCode(err, "ARCHIVE_UNAVAILABLE") {
		t.Fatalf("坏包列目录: %v", err)
	}
}

// 包里的文件声明长度比实际短:解到它时任务失败,已解出的保留在目标文件夹里。
func TestArchiveExtractCorrupt(t *testing.T) {
	f := newArchiveFixture(t)
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	ok, _ := w.Create("ok.txt")
	ok.Write([]byte("fine"))
	h := &zip.FileHeader{Name: "zbomb.txt", Method: zip.Store, CompressedSize64: 10, UncompressedSize64: 4}
	raw, _ := w.CreateRaw(h)
	raw.Write([]byte("0123456789"))
	w.Close()
	n := f.put(t, "c.zip", buf.Bytes())
	f.index(t, n)
	j, err := f.archives.Extract(t.Context(), f.jobs, f.owner, service.ExtractRequest{NodeID: n.ID, Parent: &f.folder}, "web")
	if err != nil {
		t.Fatal(err)
	}
	f.runOnce(t)
	j = f.get(t, j.ID)
	if j.Status != "failed" || code(j) != "ARCHIVE_CORRUPT" || j.ResultNodeID == nil || j.FilesDone != 1 {
		t.Fatalf("%+v code=%s", j, code(j))
	}
	if _, ok := f.children(t, *j.ResultNodeID)["ok.txt"]; !ok {
		t.Fatal("已解出的应保留")
	}
	f.tmpGone(t, j.ID)
}
