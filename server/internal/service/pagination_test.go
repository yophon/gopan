package service_test

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// seedChildren 直接插库造数:nFiles 个文件 + nFolders 个文件夹,可选给部分文件挂 blob。
func seedChildren(t *testing.T, pool *pgxpool.Pool, owner uuid.UUID, parent *uuid.UUID, nFolders, nFiles, nWithBlob int) {
	t.Helper()
	ctx := context.Background()
	for i := range nFolders {
		if _, err := pool.Exec(ctx,
			`INSERT INTO nodes (id, owner_id, parent_id, name, kind) VALUES ($1, $2, $3, $4, 'folder')`,
			uuid.Must(uuid.NewV7()), owner, parent, fmt.Sprintf("dir_%03d", i)); err != nil {
			t.Fatal(err)
		}
	}
	var blobIDs []uuid.UUID
	for i := range nWithBlob {
		id := uuid.Must(uuid.NewV7())
		if _, err := pool.Exec(ctx,
			`INSERT INTO blobs (id, sha256, size, ref_count) VALUES ($1, $2, $3, 1)`,
			id, fmt.Sprintf("%064d", i), int64((i+1)*100)); err != nil {
			t.Fatal(err)
		}
		blobIDs = append(blobIDs, id)
	}
	for i := range nFiles {
		var blob *uuid.UUID
		if i < len(blobIDs) {
			blob = &blobIDs[i]
		}
		if _, err := pool.Exec(ctx,
			`INSERT INTO nodes (id, owner_id, parent_id, name, kind, blob_id) VALUES ($1, $2, $3, $4, 'file', $5)`,
			uuid.Must(uuid.NewV7()), owner, parent, fmt.Sprintf("file_%04d", i), blob); err != nil {
			t.Fatal(err)
		}
	}
}

// walkPages 沿游标走完整个列表,断言无重复,返回按序 id。
func walkPages(t *testing.T, list func(cursor *string) (*service.Page[store.ListChildrenRow], error)) []uuid.UUID {
	t.Helper()
	var out []uuid.UUID
	seen := map[uuid.UUID]bool{}
	var cursor *string
	for range 100 {
		page, err := list(cursor)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page.Items {
			if seen[r.ID] {
				t.Fatalf("翻页出现重复节点 %s", r.Name)
			}
			seen[r.ID] = true
			out = append(out, r.ID)
		}
		if page.NextCursor == nil {
			return out
		}
		cursor = page.NextCursor
	}
	t.Fatal("翻页 100 轮未终止")
	return nil
}

func TestKeysetPagination(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)

	ra, err := auth.Register(ctx, "pager", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := ra.User.ID
	dir, err := nodes.CreateFolder(ctx, owner, nil, "big")
	if err != nil {
		t.Fatal(err)
	}
	// 455 = 2 页整 + 55,覆盖"整页边界"和尾页;5 个文件夹验证 folders-first 跨页不乱
	seedChildren(t, pool, owner, &dir.ID, 5, 450, 30)

	for _, tc := range []struct {
		order string
		desc  bool
	}{
		{"NAME", false}, {"NAME", true},
		{"SIZE", false}, {"SIZE", true},
		{"UPDATED_AT", false}, {"UPDATED_AT", true},
	} {
		t.Run(fmt.Sprintf("%s_desc=%v", tc.order, tc.desc), func(t *testing.T) {
			ids := walkPages(t, func(cursor *string) (*service.Page[store.ListChildrenRow], error) {
				return nodes.Children(ctx, owner, &dir.ID, cursor, tc.order, tc.desc)
			})
			if len(ids) != 455 {
				t.Fatalf("全量遍历应 455 个,got %d", len(ids))
			}
		})
	}

	// keyset 与一次性全量的顺序必须一致(NAME asc 与首页查询同序拼接)
	page1, err := nodes.Children(ctx, owner, &dir.ID, nil, "NAME", false)
	if err != nil {
		t.Fatal(err)
	}
	if len(page1.Items) != 200 || page1.Total != 455 || page1.NextCursor == nil {
		t.Fatalf("首页:n=%d total=%d cursor=%v", len(page1.Items), page1.Total, page1.NextCursor)
	}
	for i, r := range page1.Items[:5] {
		if r.Kind != "folder" {
			t.Fatalf("前 5 个应为文件夹,第 %d 个是 %s", i, r.Kind)
		}
	}
	page2, err := nodes.Children(ctx, owner, &dir.ID, page1.NextCursor, "NAME", false)
	if err != nil {
		t.Fatal(err)
	}
	lastP1, firstP2 := page1.Items[len(page1.Items)-1], page2.Items[0]
	if lastP1.Name >= firstP2.Name {
		t.Fatalf("跨页顺序断裂:%q >= %q", lastP1.Name, firstP2.Name)
	}

	// 游标与请求排序不匹配 → 按首页处理,不报错
	mismatch, err := nodes.Children(ctx, owner, &dir.ID, page1.NextCursor, "SIZE", false)
	if err != nil {
		t.Fatal(err)
	}
	if mismatch.Items[0].ID != page1.Items[0].ID {
		t.Fatal("排序不匹配的游标应按首页处理")
	}

	// 搜索 keyset:450 个文件全量遍历
	var scur *string
	seen := 0
	for range 10 {
		sp, err := nodes.Search(ctx, owner, "file_", scur)
		if err != nil {
			t.Fatal(err)
		}
		seen += len(sp.Items)
		if sp.NextCursor == nil {
			break
		}
		scur = sp.NextCursor
	}
	if seen != 450 {
		t.Fatalf("搜索全量应 450,got %d", seen)
	}

	// 回收站 keyset:直接插 250 个已删根节点
	for i := range 250 {
		if _, err := pool.Exec(ctx,
			`INSERT INTO nodes (id, owner_id, name, kind, deleted_at) VALUES ($1, $2, $3, 'file', now() - make_interval(secs => $4::int))`,
			uuid.Must(uuid.NewV7()), owner, fmt.Sprintf("trash_%03d", i), i); err != nil {
			t.Fatal(err)
		}
	}
	var tcur *string
	seen = 0
	for range 10 {
		tp, err := nodes.Trash(ctx, owner, tcur)
		if err != nil {
			t.Fatal(err)
		}
		seen += len(tp.Items)
		if tp.NextCursor == nil {
			break
		}
		tcur = tp.NextCursor
	}
	if seen != 250 {
		t.Fatalf("回收站全量应 250,got %d", seen)
	}
}

func TestSubtreeStats(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)
	q := store.New(pool)

	ra, err := auth.Register(ctx, "stats", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := ra.User.ID
	a, err := nodes.CreateFolder(ctx, owner, nil, "A")
	if err != nil {
		t.Fatal(err)
	}
	bDir, err := nodes.CreateFolder(ctx, owner, &a.ID, "B")
	if err != nil {
		t.Fatal(err)
	}
	// B 里两个文件(100 + 200),A 里一个文件(400)
	seedChildren(t, pool, owner, &bDir.ID, 0, 2, 2)
	if _, err := pool.Exec(ctx,
		`INSERT INTO blobs (id, sha256, size, ref_count) VALUES ($1, $2, 400, 1)`,
		uuid.Must(uuid.NewV7()), strings.Repeat("f", 64)); err != nil {
		t.Fatal(err)
	}
	var blobID uuid.UUID
	if err := pool.QueryRow(ctx, `SELECT id FROM blobs WHERE size = 400`).Scan(&blobID); err != nil {
		t.Fatal(err)
	}
	fileA := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO nodes (id, owner_id, parent_id, name, kind, blob_id) VALUES ($1, $2, $3, 'big.bin', 'file', $4)`,
		fileA, owner, a.ID, blobID); err != nil {
		t.Fatal(err)
	}

	recomputeAll := func() {
		for {
			ids, err := q.ListStaleFolders(ctx, 100)
			if err != nil {
				t.Fatal(err)
			}
			if len(ids) == 0 {
				return
			}
			for _, id := range ids {
				if err := q.RecomputeFolderStats(ctx, id); err != nil {
					t.Fatal(err)
				}
			}
		}
	}
	assertStats := func(id uuid.UUID, wantBytes, wantCount int64) {
		t.Helper()
		var gotBytes, gotCount int64
		var stale bool
		if err := pool.QueryRow(ctx,
			`SELECT subtree_bytes, subtree_count, stats_stale FROM nodes WHERE id = $1`, id).
			Scan(&gotBytes, &gotCount, &stale); err != nil {
			t.Fatal(err)
		}
		if gotBytes != wantBytes || gotCount != wantCount || stale {
			t.Fatalf("stats(%s): bytes=%d count=%d stale=%v,want %d/%d/false", id, gotBytes, gotCount, stale, wantBytes, wantCount)
		}
	}

	recomputeAll()
	assertStats(bDir.ID, 300, 2) // 100+200
	assertStats(a.ID, 700, 3)    // 300 + 400

	// 软删 A 下的 big.bin → A 链标脏,重算后 A 减 400
	if err := nodes.Delete(ctx, owner, []uuid.UUID{fileA}); err != nil {
		t.Fatal(err)
	}
	var stale bool
	if err := pool.QueryRow(ctx, `SELECT stats_stale FROM nodes WHERE id = $1`, a.ID).Scan(&stale); err != nil {
		t.Fatal(err)
	}
	if !stale {
		t.Fatal("删除后 A 应被标脏")
	}
	recomputeAll()
	assertStats(a.ID, 300, 2)
	assertStats(bDir.ID, 300, 2)

	// 移动 B 到根 → A 链与 B 自身都标脏,A 归零
	if _, err := nodes.Move(ctx, owner, []uuid.UUID{bDir.ID}, nil); err != nil {
		t.Fatal(err)
	}
	recomputeAll()
	assertStats(a.ID, 0, 0)
	assertStats(bDir.ID, 300, 2)
}
