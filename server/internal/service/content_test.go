package service_test

// 内容搜索(content.go)的集成测试。文字直接按 worker 的做法写进 blob_texts(fulltext.Build / Terms),
// 不走对象存储:这里只测查询、权限、分页与摘要。

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// seedText 建 blob + 提取结果 + owner 名下的文件节点,返回节点 id。pages 多于一页时记页码。
func seedText(t *testing.T, pool *pgxpool.Pool, owner uuid.UUID, parent *uuid.UUID, name string, pages ...string) uuid.UUID {
	t.Helper()
	ctx := t.Context()
	q := store.New(pool)
	raw := strings.Join(pages, "\f") + uuid.NewString() // 每份内容不同
	sum := sha256.Sum256([]byte(raw))
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: hex.EncodeToString(sum[:]), Size: int64(len(raw)), Mime: "application/pdf"})
	if err != nil {
		t.Fatal(err)
	}
	content, starts, _ := fulltext.Build(pages, fulltext.MaxContentBytes)
	terms, _ := fulltext.Terms(content, fulltext.MaxTerms)
	p := store.UpsertBlobTextParams{BlobID: blob.ID, Status: "done", Extractor: fulltext.Version, Content: content, Terms: terms}
	if len(starts) > 1 {
		p.PageStarts = starts
	}
	if err := q.UpsertBlobText(ctx, p); err != nil {
		t.Fatal(err)
	}
	n, err := q.CreateNode(ctx, store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent, Name: name, Kind: "file", BlobID: &blob.ID})
	if err != nil {
		t.Fatal(err)
	}
	return n.ID
}

func newContentUser(t *testing.T, pool *pgxpool.Pool) uuid.UUID {
	t.Helper()
	res, err := newAuth(pool).Register(t.Context(), "ct_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	return res.User.ID
}

func hitNames(page *service.ContentPage) []string {
	var out []string
	for _, h := range page.Items {
		out = append(out, h.Row.Name)
	}
	return out
}

func TestContentSearch(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	cs := service.NewContentSearch(pool, true)
	alice, bob := newContentUser(t, pool), newContentUser(t, pool)

	lease := seedText(t, pool, alice, nil, "租房合同.pdf",
		"第一页 甲方:张三 乙方:李四", strings.Repeat("填充文字。", 30)+"第二页 押金条款:押一付三,租房合同自签字之日起生效")
	seedText(t, pool, alice, nil, "笔记.txt", "合同 这两个字分开写在别处,租房也是")
	seedText(t, pool, bob, nil, "bob的租房合同.pdf", "租房合同自签字之日起生效")
	trashed := seedText(t, pool, alice, nil, "删掉的.txt", "租房合同自签字之日起生效")
	if err := service.NewNodes(pool).Delete(ctx, alice, []uuid.UUID{trashed}); err != nil {
		t.Fatal(err)
	}

	page, err := cs.Search(ctx, alice, nil, "租房合同", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	// 只命中 alice 自己、没删的、真正连着出现"租房合同"的那一份
	if names := hitNames(page); len(names) != 1 || names[0] != "租房合同.pdf" {
		t.Fatalf("hits %v", names)
	}
	h := page.Items[0]
	if h.Page != 2 {
		t.Fatalf("应命中第 2 页,got %d", h.Page)
	}
	if !strings.HasPrefix(h.Snippet, "…") || !strings.Contains(h.Snippet, "租房合同") {
		t.Fatalf("snippet %q", h.Snippet)
	}
	if len(h.Highlights) != 1 || string([]rune(h.Snippet)[h.Highlights[0].Start:h.Highlights[0].Start+h.Highlights[0].Length]) != "租房合同" {
		t.Fatalf("highlights %v in %q", h.Highlights, h.Snippet)
	}

	// 不分大小写、全角也能搜到;关键词中间的空格与断行不影响(归一化后一致)
	seedText(t, pool, alice, nil, "invoice.txt", "Total amount due: INVOICE 2024-118")
	for _, q := range []string{"invoice 2024", "ＩＮＶＯＩＣＥ", "amount due"} {
		p, err := cs.Search(ctx, alice, nil, q, nil, nil)
		if err != nil || len(p.Items) != 1 || p.Items[0].Row.Name != "invoice.txt" {
			t.Fatalf("%q: %v %v", q, hitNames(p), err)
		}
		if p.Items[0].Page != 0 {
			t.Fatal("不分页的格式页码应为 0")
		}
	}

	// 太短
	for _, q := range []string{"合", "ab", "   "} {
		if _, err := cs.Search(ctx, alice, nil, q, nil, nil); err == nil || !strings.Contains(err.Error(), "QUERY_TOO_SHORT") {
			t.Fatalf("%q 应 QUERY_TOO_SHORT,got %v", q, err)
		}
	}
	// 关掉内容索引
	if _, err := service.NewContentSearch(pool, false).Search(ctx, alice, nil, "租房合同", nil, nil); err == nil || !strings.Contains(err.Error(), "CONTENT_SEARCH_DISABLED") {
		t.Fatalf("disabled: %v", err)
	}

	// 读文字:分段、页码、最后一段 EOF
	d, err := cs.ReadText(ctx, alice, lease, 0, 10)
	if err != nil || d.Text != "第一页甲方:张三乙方" || d.NextOffset != 10 || d.EOF || d.Page != 1 {
		t.Fatalf("read: %+v %v", d, err)
	}
	d, err = cs.ReadText(ctx, alice, lease, d.Total-5, 100)
	if err != nil || !d.EOF || d.Page != 2 || d.NextOffset != d.Total {
		t.Fatalf("read tail: %+v %v", d, err)
	}
	// 别人的文件、不存在的文件:NOT_FOUND;还没提取的:NOT_INDEXED
	if _, err := cs.ReadText(ctx, bob, lease, 0, 10); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("别人的文件应 NOT_FOUND: %v", err)
	}
	q := store.New(pool)
	b, _ := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: strings.Repeat("ab", 32), Size: 1, Mime: "application/pdf"})
	pending, _ := q.CreateNode(ctx, store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: alice, Name: "还没提.pdf", Kind: "file", BlobID: &b.ID})
	if _, err := cs.ReadText(ctx, alice, pending.ID, 0, 10); err == nil || !strings.Contains(err.Error(), "NOT_INDEXED") {
		t.Fatalf("应 NOT_INDEXED: %v", err)
	}
}

// 分页:一页 50 条,游标接着翻,不重不漏;限定子树时只搜那棵树。
func TestContentSearchPagingAndSubtree(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	cs := service.NewContentSearch(pool, true)
	owner := newContentUser(t, pool)
	nodes := service.NewNodes(pool)
	dir, err := nodes.CreateFolder(ctx, owner, nil, "项目")
	if err != nil {
		t.Fatal(err)
	}
	for i := range 60 {
		var parent *uuid.UUID
		if i%3 == 0 {
			parent = &dir.ID
		}
		seedText(t, pool, owner, parent, fmt.Sprintf("周报%02d.txt", i), fmt.Sprintf("第 %d 周 项目进展顺利", i))
	}
	seen := map[string]bool{}
	var cursor *string
	pages := 0
	for {
		page, err := cs.Search(ctx, owner, nil, "项目进展", cursor, nil)
		if err != nil {
			t.Fatal(err)
		}
		pages++
		for _, n := range hitNames(page) {
			if seen[n] {
				t.Fatalf("重复: %s", n)
			}
			seen[n] = true
		}
		if page.NextCursor == nil {
			break
		}
		cursor = page.NextCursor
	}
	if len(seen) != 60 || pages != 2 {
		t.Fatalf("应 2 页共 60 条,got %d 页 %d 条", pages, len(seen))
	}
	sub, err := cs.Search(ctx, owner, &dir.ID, "项目进展", nil, nil)
	if err != nil || len(sub.Items) != 20 {
		t.Fatalf("子树内应 20 条,got %d %v", len(sub.Items), err)
	}
}
