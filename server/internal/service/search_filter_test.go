package service_test

// 带筛选的搜索(search_filter.go,doc/30)的集成测试:类型、修改时间、大小、只搜某个文件夹、排序、翻页。

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func seedSized(t *testing.T, pool *pgxpool.Pool, owner uuid.UUID, parent *uuid.UUID, name, mime string, size int64) uuid.UUID {
	t.Helper()
	q := store.New(pool)
	sum := sha256.Sum256([]byte(uuid.NewString()))
	blob, err := q.UpsertBlob(t.Context(), store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: hex.EncodeToString(sum[:]), Size: size, Mime: mime})
	if err != nil {
		t.Fatal(err)
	}
	n, err := q.CreateNode(t.Context(), store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent, Name: name, Kind: "file", BlobID: &blob.ID})
	if err != nil {
		t.Fatal(err)
	}
	return n.ID
}

func names(page *service.Page[store.SearchNodesRow]) []string {
	var out []string
	for _, r := range page.Items {
		out = append(out, r.Name)
	}
	return out
}

func sameSet(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	m := map[string]int{}
	for _, g := range got {
		m[g]++
	}
	for _, w := range want {
		if m[w] == 0 {
			return false
		}
		m[w]--
	}
	return true
}

func TestSearchFiltered(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	nodes := service.NewNodes(pool)
	owner := newContentUser(t, pool)

	work, err := nodes.CreateFolder(ctx, owner, nil, "工作")
	if err != nil {
		t.Fatal(err)
	}
	old, err := nodes.CreateFolder(ctx, owner, &work.ID, "旧")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.CreateFolder(ctx, owner, nil, "合同"); err != nil {
		t.Fatal(err)
	}
	seedSized(t, pool, owner, &work.ID, "合同.pdf", "application/pdf", 100)
	seedSized(t, pool, owner, &work.ID, "照片.jpg", "image/jpeg", 5000)
	oldID := seedSized(t, pool, owner, &old.ID, "旧合同.pdf", "application/pdf", 50)
	seedSized(t, pool, owner, nil, "合同草稿.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2000)
	if _, err := pool.Exec(ctx, "UPDATE nodes SET updated_at = now() - interval '2 years' WHERE id = $1", oldID); err != nil {
		t.Fatal(err)
	}
	other := newContentUser(t, pool)
	seedSized(t, pool, other, nil, "合同-别人的.pdf", "application/pdf", 100)

	search := func(q string, f service.SearchFilter) []string {
		t.Helper()
		page, err := nodes.SearchFiltered(ctx, owner, q, f, nil)
		if err != nil {
			t.Fatal(err)
		}
		if int(page.Total) != len(page.Items) {
			t.Fatalf("total %d 与条数 %d 对不上", page.Total, len(page.Items))
		}
		return names(page)
	}

	if got := search("合同", service.SearchFilter{Types: []string{"document"}}); !sameSet(got, []string{"合同.pdf", "旧合同.pdf", "合同草稿.docx"}) {
		t.Fatalf("按类型:%v", got)
	}
	if got := search("合同", service.SearchFilter{Types: []string{"folder"}}); !sameSet(got, []string{"合同"}) {
		t.Fatalf("只要文件夹:%v", got)
	}
	if got := search("", service.SearchFilter{Root: &work.ID}); !sameSet(got, []string{"旧", "合同.pdf", "照片.jpg", "旧合同.pdf"}) {
		t.Fatalf("只搜「工作」里:%v", got)
	}
	if got := search("", service.SearchFilter{MinSize: ptr[int64](1000), Order: "SIZE"}); len(got) != 2 || got[0] != "照片.jpg" || got[1] != "合同草稿.docx" {
		t.Fatalf("按大小:%v", got)
	}
	weekAgo := time.Now().Add(-7 * 24 * time.Hour)
	if got := search("合同", service.SearchFilter{After: &weekAgo, Types: []string{"document"}}); !sameSet(got, []string{"合同.pdf", "合同草稿.docx"}) {
		t.Fatalf("最近一周:%v", got)
	}
	if got := search("合同", service.SearchFilter{Before: &weekAgo}); !sameSet(got, []string{"旧合同.pdf"}) {
		t.Fatalf("一周以前:%v", got)
	}
	if got := search("", service.SearchFilter{Order: "NAME"}); len(got) != 0 {
		t.Fatalf("没关键词也没条件应该什么都没有:%v", got)
	}
	if got := search("合同", service.SearchFilter{Order: "NAME"}); len(got) != 4 || got[0] != "合同" {
		t.Fatalf("按名字:%v", got)
	}
	var se *service.Error
	if _, err := nodes.SearchFiltered(ctx, owner, "x", service.SearchFilter{Types: []string{"movie"}}, nil); !errors.As(err, &se) || se.Code != "INVALID_INPUT" {
		t.Fatalf("不认识的类型应 INVALID_INPUT:%v", err)
	}
	if got := search("", service.SearchFilter{Root: &old.ID, Types: []string{"document"}}); !sameSet(got, []string{"旧合同.pdf"}) {
		t.Fatalf("子文件夹:%v", got)
	}
	// 别人的文件夹当范围:什么都搜不到
	page, err := service.NewNodes(pool).SearchFiltered(ctx, other, "", service.SearchFilter{Root: &work.ID}, nil)
	if err != nil || len(page.Items) != 0 {
		t.Fatalf("别人的文件夹不该搜到东西:%v %v", err, names(page))
	}
}

func TestSearchFilteredPages(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	nodes := service.NewNodes(pool)
	owner := newContentUser(t, pool)
	dir, err := nodes.CreateFolder(ctx, owner, nil, "很多")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 230; i++ {
		seedSized(t, pool, owner, &dir.ID, uuid.NewString()[:8]+".mp4", "video/mp4", int64(i+1))
	}
	seen := map[uuid.UUID]bool{}
	var cursor *string
	var last int64 = 1 << 62
	for range 5 {
		page, err := nodes.SearchFiltered(ctx, owner, "", service.SearchFilter{Types: []string{"video"}, Order: "SIZE"}, cursor)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page.Items {
			if seen[r.ID] {
				t.Fatalf("重复 %s", r.Name)
			}
			seen[r.ID] = true
			if r.BlobSize == nil || *r.BlobSize > last {
				t.Fatalf("没按大小从大到小排")
			}
			last = *r.BlobSize
		}
		if page.NextCursor == nil {
			break
		}
		cursor = page.NextCursor
	}
	if len(seen) != 230 {
		t.Fatalf("翻完应 230 个,got %d", len(seen))
	}
}

func TestContentSearchFilter(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	nodes := service.NewNodes(pool)
	owner := newContentUser(t, pool)
	dir, err := nodes.CreateFolder(ctx, owner, nil, "项目")
	if err != nil {
		t.Fatal(err)
	}
	seedText(t, pool, owner, &dir.ID, "里面.pdf", "季度预算说明")
	outID := seedText(t, pool, owner, nil, "外面.pdf", "季度预算说明")
	if _, err := pool.Exec(ctx, "UPDATE nodes SET updated_at = now() - interval '1 year' WHERE id = $1", outID); err != nil {
		t.Fatal(err)
	}
	cs := service.NewContentSearch(pool, true)
	page, err := cs.Search(ctx, owner, nil, "季度预算", nil, &service.SearchFilter{Root: &dir.ID})
	if err != nil {
		t.Fatal(err)
	}
	if got := hitNames(page); !sameSet(got, []string{"里面.pdf"}) {
		t.Fatalf("只搜「项目」:%v", got)
	}
	monthAgo := time.Now().AddDate(0, -1, 0)
	page, err = cs.Search(ctx, owner, nil, "季度预算", nil, &service.SearchFilter{After: &monthAgo})
	if err != nil {
		t.Fatal(err)
	}
	if got := hitNames(page); !sameSet(got, []string{"里面.pdf"}) {
		t.Fatalf("最近一个月:%v", got)
	}
	page, err = cs.Search(ctx, owner, nil, "季度预算", nil, &service.SearchFilter{Types: []string{"image"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 0 {
		t.Fatalf("只要图片应该没有:%v", hitNames(page))
	}
}
