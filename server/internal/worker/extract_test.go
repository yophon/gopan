package worker

// 文字提取(extract.go)的集成测试:真 Postgres + MinIO。各种格式逐个断言状态与内容;
// PDF 依赖 pdftotext,本机 / CI 装了才测。

import (
	"archive/zip"
	"bytes"
	"context"
	"os/exec"
	"slices"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/text/encoding/simplifiedchinese"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/fulltext/fulltexttest"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// seedFile 建一个已校验、有人引用的 blob,并挂一个叫 name 的文件节点。
func seedFile(t *testing.T, pool *pgxpool.Pool, obj *objstore.Store, owner uuid.UUID, name string, content []byte, mime string) uuid.UUID {
	t.Helper()
	blob, _ := seedBlobWithObject(t, pool, obj, content, mime)
	mustExec(t, pool, `UPDATE blobs SET verified = true WHERE id = $1`, blob)
	mustExec(t, pool, `INSERT INTO nodes (id, owner_id, name, kind, blob_id) VALUES ($1, $2, $3, 'file', $4)`,
		uuid.Must(uuid.NewV7()), owner, name, blob)
	return blob
}

func newOwner(t *testing.T, pool *pgxpool.Pool) uuid.UUID {
	t.Helper()
	id := uuid.Must(uuid.NewV7())
	mustExec(t, pool, `INSERT INTO users (id, username, password_hash) VALUES ($1, $2, 'x')`, id, "u"+id.String()[:8])
	return id
}

func textRow(t *testing.T, pool *pgxpool.Pool, blob uuid.UUID) store.BlobText {
	t.Helper()
	bt, err := store.New(pool).GetBlobText(context.Background(), blob)
	if err != nil {
		t.Fatalf("blob_texts 行缺失: %v", err)
	}
	return bt
}

func docxBytes(t *testing.T, body string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string]string{
		"[Content_Types].xml": "<Types/>",
		"word/document.xml":   `<w:document><w:body><w:p><w:r><w:t>` + body + `</w:t></w:r></w:p></w:body></w:document>`,
	} {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte(content))
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestExtractTextFormats(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	owner := newOwner(t, pool)
	gbk, _ := simplifiedchinese.GBK.NewEncoder().Bytes([]byte("采购合同 第三条 付款方式"))
	var plainZip bytes.Buffer
	zw := zip.NewWriter(&plainZip)
	f, _ := zw.Create("photo.jpg")
	_, _ = f.Write([]byte("not text"))
	_ = zw.Close()

	cases := []struct {
		name, mime string
		content    []byte
		status     string
		contains   string
	}{
		{"笔记.md", "text/markdown", []byte("# 租房\n合同\n押一付三"), "done", "租房合同押一付三"},
		{"旧文件.txt", "text/plain", gbk, "done", "采购合同第三条付款方式"},
		{"README", "application/octet-stream", []byte("plain readme without extension"), "done", "plain readme"},
		{"合同.docx", "application/octet-stream", docxBytes(t, "甲方 张三 乙方 李四"), "done", "甲方张三乙方李四"},
		{"空.docx", "application/octet-stream", docxBytes(t, ""), "empty", ""},
		{"照片.zip", "application/zip", plainZip.Bytes(), "unsupported", ""},
		{"图.png", "image/png", pngBytes(t, 4, 4), "unsupported", ""},
		{"坏.docx", "application/octet-stream", []byte("PK\x03\x04 truncated garbage"), "failed", ""},
		{"数据.bin", "application/octet-stream", []byte{0, 1, 2, 3, 0, 0, 7}, "unsupported", ""},
	}
	for _, c := range cases {
		blob := seedFile(t, pool, obj, owner, c.name, c.content, c.mime)
		if err := w.extractText(ctx, blob); err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		bt := textRow(t, pool, blob)
		if bt.Status != c.status || bt.Extractor != fulltext.Version || !strings.Contains(bt.Content, c.contains) {
			t.Errorf("%s: status=%s content=%q", c.name, bt.Status, bt.Content)
		}
		if c.status == "done" && len(bt.Terms) == 0 {
			t.Errorf("%s: done 应有词项", c.name)
		}
	}
}

// 词项能命中:GIN @> 查询 + 子串复核,就是 S2 内容搜索的骨架
func TestExtractTextSearchable(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	owner := newOwner(t, pool)
	hit := seedFile(t, pool, obj, owner, "a.txt", []byte("本合同自双方签字之日起生效"), "text/plain")
	miss := seedFile(t, pool, obj, owner, "b.txt", []byte("合同?不,这里只有合和同两个字分开写"), "text/plain")
	for _, b := range []uuid.UUID{hit, miss} {
		if err := w.extractText(ctx, b); err != nil {
			t.Fatal(err)
		}
	}
	q := "签字之日"
	rows, err := pool.Query(ctx, `SELECT blob_id FROM blob_texts WHERE terms @> $1::text[] AND strpos(lower(content), lower($2)) > 0`,
		fulltext.QueryTerms(q), q)
	if err != nil {
		t.Fatal(err)
	}
	var got []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		_ = rows.Scan(&id)
		got = append(got, id)
	}
	if len(got) != 1 || got[0] != hit {
		t.Fatalf("应只命中一份: %v", got)
	}
}

func TestExtractTextSkipsUnverified(t *testing.T) {
	w, pool, obj := setup(t)
	blob, _ := seedBlobWithObject(t, pool, obj, []byte("hello unverified"), "text/plain")
	if err := w.extractText(context.Background(), blob); err != nil {
		t.Fatal(err)
	}
	if _, err := store.New(pool).GetBlobText(context.Background(), blob); err == nil {
		t.Fatal("未校验的 blob 不应提取")
	}
}

func TestExtractTextPDF(t *testing.T) {
	bin, err := exec.LookPath("pdftotext")
	if err != nil {
		t.Skip("没有 pdftotext,跳过")
	}
	w, pool, obj := setup(t)
	w.SetTextIndex(true, bin)
	owner := newOwner(t, pool)
	// 名字故意不是 .pdf:按文件头认
	blob := seedFile(t, pool, obj, owner, "scan.bin", fulltexttest.PDF("Lease agreement page one", "Deposit terms page two"), "application/octet-stream")
	if err := w.extractText(context.Background(), blob); err != nil {
		t.Fatal(err)
	}
	bt := textRow(t, pool, blob)
	if bt.Status != "done" || !strings.Contains(bt.Content, "Deposit terms") || len(bt.PageStarts) != 2 {
		t.Fatalf("pdf: %+v", bt)
	}
	pos := strings.Index(bt.Content, "Deposit") + 1
	if fulltext.PageOf(bt.PageStarts, len([]rune(bt.Content[:pos-1]))+1) != 2 {
		t.Fatal("第二页的内容应算在第 2 页")
	}
	// 没有 pdftotext:记为不支持
	w.SetTextIndex(true, "")
	blob2 := seedFile(t, pool, obj, owner, "b.pdf", fulltexttest.PDF("other"), "application/pdf")
	if err := w.extractText(context.Background(), blob2); err != nil {
		t.Fatal(err)
	}
	if bt := textRow(t, pool, blob2); bt.Status != "unsupported" {
		t.Fatalf("无 pdftotext 应 unsupported,got %s", bt.Status)
	}
}

// 回填:没提过的排上;已在队列里的、最终失败的不排;提取器升级后已完成的重排。
func TestBackfillText(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	q := store.New(pool)
	owner := newOwner(t, pool)
	fresh := seedFile(t, pool, obj, owner, "fresh.txt", []byte("fresh text"), "text/plain")
	failed := seedFile(t, pool, obj, owner, "failed.txt", []byte("failed text"), "text/plain")
	old := seedFile(t, pool, obj, owner, "old.txt", []byte("old version text"), "text/plain")
	mustExec(t, pool, `INSERT INTO tasks (id, kind, blob_id, status, attempts) VALUES ($1, 'extract_text', $2, 'failed', 3)`, uuid.Must(uuid.NewV7()), failed)
	mustExec(t, pool, `INSERT INTO tasks (id, kind, blob_id, status) VALUES ($1, 'extract_text', $2, 'done')`, uuid.Must(uuid.NewV7()), old)
	mustExec(t, pool, `INSERT INTO blob_texts (blob_id, status, extractor) VALUES ($1, 'done', $2)`, old, fulltext.Version-1)

	if err := w.backfillText(ctx); err != nil {
		t.Fatal(err)
	}
	var queued []uuid.UUID
	rows, _ := pool.Query(ctx, `SELECT blob_id FROM tasks WHERE kind = 'extract_text' AND status = 'pending'`)
	for rows.Next() {
		var id uuid.UUID
		_ = rows.Scan(&id)
		queued = append(queued, id)
	}
	if len(queued) != 2 || !slices.Contains(queued, fresh) || !slices.Contains(queued, old) {
		t.Fatalf("应排上 fresh 与 old,got %v", queued)
	}
	// 再跑一次:已在队列里的不重复
	if err := w.backfillText(ctx); err != nil {
		t.Fatal(err)
	}
	n, _ := q.CountBlobsNeedingText(ctx, fulltext.Version)
	if n != 3 {
		t.Fatalf("需要提取的应有 3 个(含失败的),got %d", n)
	}
	// verify_hash 优先于回填出来的提取任务
	mustExec(t, pool, `INSERT INTO tasks (id, kind, blob_id) VALUES ($1, 'verify_hash', $2)`, uuid.Must(uuid.NewV7()), fresh)
	task, err := q.ClaimTask(ctx)
	if err != nil || task.Kind != "verify_hash" {
		t.Fatalf("verify_hash 应先领: %v %v", task.Kind, err)
	}
}

// 开了内容索引:新 blob 校验通过后排上提取
func TestEnqueueDerivativesText(t *testing.T) {
	w, pool, obj := setup(t)
	w.SetTextIndex(true, "")
	blob, _ := seedBlobWithObject(t, pool, obj, []byte("hello"), "text/plain")
	b, err := store.New(pool).GetBlob(context.Background(), blob)
	if err != nil {
		t.Fatal(err)
	}
	w.enqueueDerivatives(context.Background(), b)
	var n int
	if err := pool.QueryRow(context.Background(), `SELECT count(*) FROM tasks WHERE kind = 'extract_text' AND blob_id = $1`, blob).Scan(&n); err != nil || n != 1 {
		t.Fatalf("应排上提取任务: %d %v", n, err)
	}
}
