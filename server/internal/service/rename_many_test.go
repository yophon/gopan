package service_test

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

func TestRenameManySwapsAndUndoesAsOneBatch(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	dir, err := f.nodes.CreateFolder(ctx, f.owner, nil, "剧集")
	if err != nil {
		t.Fatal(err)
	}
	a := f.put(t, &dir.ID, "a.mkv", []byte("A"))
	b := f.put(t, &dir.ID, "b.mkv", []byte("B"))
	c := f.put(t, &dir.ID, "c.mkv", []byte("C"))

	// a、b 互换名字,c 改成序号名;名字没变的项原样返回
	out, err := f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{
		{ID: a.ID, Name: "b.mkv"},
		{ID: b.ID, Name: " a.mkv "},
		{ID: c.ID, Name: "第03集.mkv"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if out[0].Name != "b.mkv" || out[1].Name != "a.mkv" || out[2].Name != "第03集.mkv" {
		t.Fatalf("改名结果:%s %s %s", out[0].Name, out[1].Name, out[2].Name)
	}
	if same, err := f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{{ID: c.ID, Name: "第03集.mkv"}}); err != nil || same[0].Name != "第03集.mkv" {
		t.Fatalf("名字没变:%+v %v", same, err)
	}

	// 一批三条改名动态,同一个 batch_id;按批撤回全部改回去
	var batch uuid.UUID
	var n int
	if err := f.pool.QueryRow(ctx, `SELECT batch_id, count(*) OVER () FROM activities WHERE owner_id = $1 AND action = 'rename' ORDER BY id DESC LIMIT 1`, f.owner).Scan(&batch, &n); err != nil || n != 3 {
		t.Fatalf("动态:%d 条 %v", n, err)
	}
	var batches int
	if err := f.pool.QueryRow(ctx, `SELECT count(DISTINCT batch_id) FROM activities WHERE owner_id = $1 AND action = 'rename'`, f.owner).Scan(&batches); err != nil || batches != 1 {
		t.Fatalf("应是一批:%d %v", batches, err)
	}
	if _, err := f.acts.Undo(ctx, f.owner, service.UndoSelection{BatchID: &batch}); err != nil {
		t.Fatal(err)
	}
	names := map[uuid.UUID]string{}
	for _, id := range []uuid.UUID{a.ID, b.ID, c.ID} {
		n, err := f.nodes.Get(ctx, f.owner, id)
		if err != nil {
			t.Fatal(err)
		}
		names[id] = n.Name
	}
	// 互换的两项撤回时会互相撞名,撞了的照撤回的规矩改个名;c 一定回到原名
	if names[c.ID] != "c.mkv" {
		t.Fatalf("撤回后:%v", names)
	}
}

func TestRenameManyIsAllOrNothing(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	a := f.put(t, nil, "a.txt", []byte("A"))
	b := f.put(t, nil, "b.txt", []byte("B"))
	f.put(t, nil, "占用.txt", []byte("X"))

	// 和这批之外的同名:整批不改
	_, err := f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{{ID: a.ID, Name: "新.txt"}, {ID: b.ID, Name: "占用.txt"}})
	requireCode(t, err, "NAME_CONFLICT")
	if n, _ := f.nodes.Get(ctx, f.owner, a.ID); n.Name != "a.txt" {
		t.Fatalf("冲突时不应改动:%s", n.Name)
	}
	// 批内重名
	_, err = f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{{ID: a.ID, Name: "同.txt"}, {ID: b.ID, Name: "同.txt"}})
	requireCode(t, err, "NAME_CONFLICT")
	// 非法名字、重复的项、不存在的项
	_, err = f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{{ID: a.ID, Name: "a/b"}})
	requireCode(t, err, "INVALID_INPUT")
	_, err = f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{{ID: a.ID, Name: "x"}, {ID: a.ID, Name: "y"}})
	requireCode(t, err, "INVALID_INPUT")
	_, err = f.nodes.RenameMany(ctx, f.owner, []service.RenameItem{{ID: uuid.New(), Name: "x"}})
	requireCode(t, err, "NOT_FOUND")
}
