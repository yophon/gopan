package service_test

// v3.5(doc/23):动态与撤回、空间分析与自然顺序、订阅、剪藏的集成测试。真 Postgres + MinIO;
// 订阅源、网页与图片是本机 httptest。

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image/color"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type v35Fixture struct {
	*v34Fixture
	acts  *service.Activities
	space *service.Space
	clips *service.Clips
	feeds *service.Feeds
	web   context.Context
}

const edgeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 Edg/130.0"

func newV35Fixture(t *testing.T) *v35Fixture {
	t.Helper()
	f := &v35Fixture{v34Fixture: newV34Fixture(t, nil)}
	f.nodes.SetNotifier(f.notify)
	f.acts = service.NewActivities(f.pool, f.nodes)
	f.space = service.NewSpace(f.pool)
	guard := fetcher.Guard{AllowLoopback: true}
	f.clips = service.NewClips(f.pool, f.obj, f.jobs, f.uploads, guard, "gopan-test")
	f.jobs.SetClips(f.clips)
	f.feeds = service.NewFeeds(f.pool, f.jobs, f.clips, f.nodes, f.uploads, f.notify, guard, "gopan-test")
	f.web = service.WithActor(t.Context(), service.SessionActor(uuid.New(), edgeUA))
	return f
}

func (f *v35Fixture) acts1(t *testing.T, filter service.ActivityFilter) []store.Activity {
	t.Helper()
	rows, _, err := f.acts.List(t.Context(), f.owner, filter, nil, 200)
	if err != nil {
		t.Fatal(err)
	}
	return rows
}

func (f *v35Fixture) mkdir(t *testing.T, ctx context.Context, parent *uuid.UUID, name string) store.Node {
	t.Helper()
	n, err := f.nodes.CreateFolder(ctx, f.owner, parent, name)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func (f *v35Fixture) putCtx(t *testing.T, ctx context.Context, parent uuid.UUID, name string, content []byte) store.Node {
	t.Helper()
	key := "tmp/test/" + uuid.NewString()
	if err := f.obj.Put(t.Context(), key, bytes.NewReader(content), int64(len(content)), "application/octet-stream"); err != nil {
		t.Fatal(err)
	}
	n, err := f.uploads.CommitStaged(ctx, service.StagedFile{Owner: f.owner, Parent: &parent, Name: name, Sha: sha(content), Size: int64(len(content)), TmpKey: key})
	if err != nil {
		t.Fatal(err)
	}
	return *n
}

func sha(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func actions(rows []store.Activity) []string {
	var out []string
	for _, r := range rows {
		out = append(out, r.Action+":"+r.Name)
	}
	return out
}

func TestActivityRecordsEveryWrite(t *testing.T) {
	f := newV35Fixture(t)
	ctx := f.web
	docs := f.mkdir(t, ctx, nil, "文档")
	sub := f.mkdir(t, ctx, &docs.ID, "子")
	a := f.putCtx(t, ctx, docs.ID, "a.txt", []byte("aaaa"))
	if _, err := f.nodes.Rename(ctx, f.owner, a.ID, "b.txt"); err != nil {
		t.Fatal(err)
	}
	if _, err := f.nodes.Move(ctx, f.owner, []uuid.UUID{a.ID}, &sub.ID); err != nil {
		t.Fatal(err)
	}
	cp, err := f.nodes.Copy(ctx, f.owner, []uuid.UUID{docs.ID}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{docs.ID, sub.ID}); err != nil { // sub 随上级一起进去,只记一条
		t.Fatal(err)
	}
	if _, err := f.nodes.Restore(ctx, f.owner, []uuid.UUID{docs.ID}); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{cp[0].ID}); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Purge(ctx, f.owner, []uuid.UUID{cp[0].ID}); err != nil {
		t.Fatal(err)
	}

	rows := f.acts1(t, service.ActivityFilter{ActorKind: service.ActorWeb})
	got := actions(rows)
	want := []string{"purge:文档 (1)", "delete:文档 (1)", "restore:文档", "delete:文档", "copy:文档 (1)", "move:b.txt", "rename:b.txt", "upload:a.txt", "mkdir:子", "mkdir:文档"}
	if !slices.Equal(got, want) {
		t.Fatalf("activities =\n%v\nwant\n%v", got, want)
	}
	byAction := map[string]store.Activity{}
	for _, r := range rows {
		byAction[r.Action] = r
		if r.ActorKind != service.ActorWeb || r.ActorLabel != "Edge · Windows" {
			t.Fatalf("actor = %s %q", r.ActorKind, r.ActorLabel)
		}
	}
	from, to, oldName, newName, files, bytes, _, _ := service.ActivityDetail(byAction["move"].Detail)
	if from == nil || *from != "/文档" || to == nil || *to != "/文档/子" || byAction["move"].ParentPath != "/文档/子" {
		t.Fatalf("move detail = %v %v %s", from, to, byAction["move"].ParentPath)
	}
	_, _, oldName, newName, _, _, _, _ = service.ActivityDetail(byAction["rename"].Detail)
	if oldName != "a.txt" || newName != "b.txt" {
		t.Fatalf("rename = %q → %q", oldName, newName)
	}
	_, _, _, _, files, bytes, _, _ = service.ActivityDetail(byAction["copy"].Detail)
	if files != 1 || bytes != 4 {
		t.Fatalf("copy totals = %d %d", files, bytes)
	}
	del := rows[3] // delete:文档(子树里 1 个文件)
	_, _, _, _, files, bytes, _, _ = service.ActivityDetail(del.Detail)
	if del.Action != "delete" || files != 1 || bytes != 4 {
		t.Fatalf("delete totals = %s %d %d", del.Action, files, bytes)
	}
	// 一个文件的历史
	hist := f.acts1(t, service.ActivityFilter{NodeID: &a.ID})
	if got := actions(hist); !slices.Equal(got, []string{"move:b.txt", "rename:b.txt", "upload:a.txt"}) {
		t.Fatalf("history = %v", got)
	}
	// 来源列表
	actors, err := f.acts.Actors(t.Context(), f.owner)
	if err != nil || len(actors) != 2 || actors[0].ActorKind != service.ActorWeb || actors[0].Total != int64(len(rows)) || actors[1].ActorKind != service.ActorSystem {
		t.Fatalf("actors = %+v %v", actors, err) // 第二个是夹具建「下载」时没带来源的系统
	}
}

func TestActivityBatchesAndSources(t *testing.T) {
	f := newV35Fixture(t)
	src := f.mkdir(t, f.web, nil, "源")
	dst := f.mkdir(t, f.web, nil, "目标")
	var ids []uuid.UUID
	for i := range 50 {
		ids = append(ids, f.putCtx(t, f.web, src.ID, fmt.Sprintf("f%02d.txt", i), []byte{byte(i)}).ID)
	}
	if _, err := f.nodes.Move(f.web, f.owner, ids, &dst.ID); err != nil {
		t.Fatal(err)
	}
	moves := f.acts1(t, service.ActivityFilter{Actions: []string{"move"}})
	if len(moves) != 50 {
		t.Fatalf("moves = %d", len(moves))
	}
	for _, m := range moves {
		if m.BatchID != moves[0].BatchID {
			t.Fatal("one multi-select move should be one batch")
		}
	}
	// WebDAV / Agent / 后台任务各自的来源
	dav := service.WithActor(t.Context(), service.Actor{Kind: service.ActorWebDAV, Ref: "ap1", Label: "rclone"})
	if _, err := f.nodes.CreateFolderExact(dav, f.owner, nil, "dav"); err != nil {
		t.Fatal(err)
	}
	if _, err := f.nodes.CreateFolderExact(dav, f.owner, nil, "dav"); err == nil {
		t.Fatal("exact mkdir should conflict")
	}
	_, err := f.nodes.MCPMutation(service.WithBatch(service.WithActor(t.Context(), service.Actor{Kind: service.ActorAgent, Ref: "api_key:k", Label: "Claude"})),
		f.owner, "", []byte("x"), false, func(n *service.Nodes) (json.RawMessage, error) {
			if _, err := n.CreateFolder(service.WithBatch(service.WithActor(t.Context(), service.Actor{Kind: service.ActorAgent, Ref: "api_key:k", Label: "Claude"})), f.owner, nil, "agent"); err != nil {
				return nil, err
			}
			return json.RawMessage("{}"), nil
		})
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]string{}
	for _, r := range f.acts1(t, service.ActivityFilter{Actions: []string{"mkdir"}}) {
		got[r.Name] = r.ActorKind + "/" + r.ActorLabel
	}
	if got["dav"] != "webdav/rclone" || got["agent"] != "agent/Claude" || got["源"] != "web/Edge · Windows" {
		t.Fatalf("sources = %v", got)
	}
	// 解压:只记一条汇总(见 TestArchiveExtract* 里的解压流程;这里看没开来源时算系统)
	if a := service.ActorFrom(t.Context()); a.Kind != service.ActorSystem {
		t.Fatalf("default actor = %+v", a)
	}
}

func TestUndoEachKind(t *testing.T) {
	f := newV35Fixture(t)
	ctx := f.web
	a := f.mkdir(t, ctx, nil, "A")
	b := f.mkdir(t, ctx, nil, "B")
	x := f.putCtx(t, ctx, a.ID, "x.txt", []byte("x"))
	if _, err := f.nodes.Move(ctx, f.owner, []uuid.UUID{x.ID}, &b.ID); err != nil {
		t.Fatal(err)
	}
	// 原位置被同名占了:撤回移动时自动改名
	f.putCtx(t, ctx, a.ID, "x.txt", []byte("other"))
	if _, err := f.nodes.Rename(ctx, f.owner, b.ID, "B2"); err != nil {
		t.Fatal(err)
	}
	moveAct := f.acts1(t, service.ActivityFilter{Actions: []string{"move"}})[0]
	renameAct := f.acts1(t, service.ActivityFilter{Actions: []string{"rename"}})[0]

	res, err := f.acts.Undo(ctx, f.owner, service.UndoSelection{IDs: []int64{moveAct.ID, renameAct.ID}})
	if err != nil || res.Done != 2 || res.Failed != 0 {
		t.Fatalf("undo = %+v %v", res, err)
	}
	xn, _ := f.nodes.Get(t.Context(), f.owner, x.ID)
	bn, _ := f.nodes.Get(t.Context(), f.owner, b.ID)
	if xn.ParentID == nil || *xn.ParentID != a.ID || !strings.HasPrefix(xn.Name, "x (撤回 ") || bn.Name != "B" {
		t.Fatalf("after undo: x=%s in %v, b=%s", xn.Name, xn.ParentID, bn.Name)
	}
	// 再撤一次:已经撤回过
	res, _ = f.acts.Undo(ctx, f.owner, service.UndoSelection{IDs: []int64{moveAct.ID}})
	if res.Done != 0 || res.Items[0].Reason != "已经撤回过" {
		t.Fatalf("double undo = %+v", res.Items)
	}
	// 撤回本身记为新的动态,指回原记录
	undoRows := f.acts1(t, service.ActivityFilter{})
	_, _, _, _, _, _, undoOf, _ := service.ActivityDetail(undoRows[0].Detail)
	if undoOf == 0 {
		t.Fatalf("undo records should point back: %v", actions(undoRows[:3]))
	}

	// 删除 → 还原;上传 / 复制 → 进回收站;覆盖、彻底删除撤不了
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{a.ID}); err != nil {
		t.Fatal(err)
	}
	c := f.putCtx(t, ctx, b.ID, "c.txt", []byte("c"))
	cp, err := f.nodes.Copy(ctx, f.owner, []uuid.UUID{c.ID}, nil)
	if err != nil {
		t.Fatal(err)
	}
	gone := f.putCtx(t, ctx, b.ID, "gone.txt", []byte("g"))
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{gone.ID}); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Purge(ctx, f.owner, []uuid.UUID{gone.ID}); err != nil {
		t.Fatal(err)
	}
	var ids []int64
	for _, r := range f.acts1(t, service.ActivityFilter{}) {
		if r.UndoneAt.Valid {
			continue
		}
		switch {
		case r.Action == "delete" && r.Name == "A", r.Action == "upload" && r.Name == "c.txt", r.Action == "copy",
			r.Action == "purge", r.Action == "delete" && r.Name == "gone.txt":
			ids = append(ids, r.ID)
		}
	}
	prev, err := f.acts.PreviewUndo(ctx, f.owner, service.UndoSelection{IDs: ids})
	if err != nil {
		t.Fatal(err)
	}
	reasons := map[string]string{}
	for _, it := range prev.Items {
		reasons[it.Activity.Action+":"+it.Activity.Name] = it.Reason
	}
	if reasons["delete:A"] != "" || reasons["upload:c.txt"] != "" || reasons["copy:c.txt"] != "" ||
		reasons["purge:gone.txt"] == "" || reasons["delete:gone.txt"] != "文件已彻底删除" {
		t.Fatalf("preview reasons = %v", reasons)
	}
	if an, _ := f.nodes.Get(t.Context(), f.owner, a.ID); !an.DeletedAt.Valid {
		t.Fatal("preview must not change anything")
	}
	res, err = f.acts.Undo(ctx, f.owner, service.UndoSelection{IDs: ids})
	if err != nil || res.Done != 3 || res.Failed != 2 {
		t.Fatalf("undo mixed = %+v %v", res, err)
	}
	an, _ := f.nodes.Get(t.Context(), f.owner, a.ID)
	cn, _ := f.nodes.Get(t.Context(), f.owner, c.ID)
	cpn, _ := f.nodes.Get(t.Context(), f.owner, cp[0].ID)
	if an.DeletedAt.Valid || !cn.DeletedAt.Valid || !cpn.DeletedAt.Valid {
		t.Fatalf("after undo: A deleted=%v c deleted=%v copy deleted=%v", an.DeletedAt.Valid, cn.DeletedAt.Valid, cpn.DeletedAt.Valid)
	}
}

func TestUndoBatchOrderAndFilter(t *testing.T) {
	f := newV35Fixture(t)
	agent := service.WithActor(t.Context(), service.Actor{Kind: service.ActorAgent, Ref: "api_key:1", Label: "整理助手"})
	inbox := f.mkdir(t, f.web, nil, "收件箱")
	var files []uuid.UUID
	for i := range 5 {
		files = append(files, f.putCtx(t, f.web, inbox.ID, fmt.Sprintf("%d.jpg", i), []byte{byte(i), 1}).ID)
	}
	start := time.Now().Add(-time.Second)
	// Agent 一次调用:建「照片」、把 5 个文件移进去、删掉收件箱
	batch := service.WithBatch(agent)
	photos := f.mkdir(t, batch, nil, "照片")
	if _, err := f.nodes.Move(batch, f.owner, files, &photos.ID); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Delete(batch, f.owner, []uuid.UUID{inbox.ID}); err != nil {
		t.Fatal(err)
	}
	// 按来源 + 时间筛出来整批撤回:先还原收件箱,文件才能移回去,最后「照片」进回收站
	sel := service.UndoSelection{Filter: &service.ActivityFilter{ActorKind: service.ActorAgent, ActorRef: "api_key:1", Since: &start}}
	prev, err := f.acts.PreviewUndo(f.web, f.owner, sel)
	if err != nil || prev.Done != 7 || prev.Failed != 0 {
		t.Fatalf("preview = %+v %v", prev, err)
	}
	res, err := f.acts.Undo(f.web, f.owner, sel)
	if err != nil || res.Done != 7 {
		t.Fatalf("undo = %+v %v", res, err)
	}
	children, err := f.nodes.Children(t.Context(), f.owner, &inbox.ID, nil, "NAME", false)
	if err != nil || len(children.Items) != 5 {
		t.Fatalf("inbox after undo = %v %v", children, err)
	}
	if pn, _ := f.nodes.Get(t.Context(), f.owner, photos.ID); !pn.DeletedAt.Valid {
		t.Fatal("created folder should be trashed")
	}
	// 撤回的记录来源是当前设备、同一批
	undos := f.acts1(t, service.ActivityFilter{ActorKind: service.ActorWeb, Since: &start})
	var batchID uuid.UUID
	for _, u := range undos {
		if _, _, _, _, _, _, undoOf, _ := service.ActivityDetail(u.Detail); undoOf != 0 {
			if batchID == uuid.Nil {
				batchID = u.BatchID
			} else if u.BatchID != batchID {
				t.Fatal("one undo should be one batch")
			}
		}
	}
	// 至少要给一个条件
	if _, err := f.acts.Undo(f.web, f.owner, service.UndoSelection{Filter: &service.ActivityFilter{}}); err == nil {
		t.Fatal("empty filter accepted")
	}
	// 按批撤回(一次多选移动)
	other := f.mkdir(t, f.web, nil, "其他")
	if _, err := f.nodes.Move(f.web, f.owner, files[:2], &other.ID); err != nil {
		t.Fatal(err)
	}
	mv := f.acts1(t, service.ActivityFilter{Actions: []string{"move"}})[0]
	res, err = f.acts.Undo(f.web, f.owner, service.UndoSelection{BatchID: &mv.BatchID})
	if err != nil || res.Done != 2 {
		t.Fatalf("batch undo = %+v %v", res, err)
	}
}

func TestUndoMoveOriginalFolderGone(t *testing.T) {
	f := newV35Fixture(t)
	a := f.mkdir(t, f.web, nil, "A")
	b := f.mkdir(t, f.web, nil, "B")
	x := f.putCtx(t, f.web, a.ID, "x", []byte("x"))
	if _, err := f.nodes.Move(f.web, f.owner, []uuid.UUID{x.ID}, &b.ID); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Delete(f.web, f.owner, []uuid.UUID{a.ID}); err != nil {
		t.Fatal(err)
	}
	mv := f.acts1(t, service.ActivityFilter{Actions: []string{"move"}})[0]
	res, _ := f.acts.Undo(f.web, f.owner, service.UndoSelection{IDs: []int64{mv.ID}})
	if res.Failed != 1 || res.Items[0].Reason != "原文件夹在回收站里" {
		t.Fatalf("undo = %+v", res.Items)
	}
}

func TestMassDeleteAlert(t *testing.T) {
	f := newV35Fixture(t)
	big := f.mkdir(t, f.web, nil, "大文件夹")
	for i := range 201 {
		f.putCtx(t, f.web, big.ID, fmt.Sprintf("%03d", i), []byte(fmt.Sprint(i)))
	}
	// 网页上自己删的不提醒
	small := f.mkdir(t, f.web, nil, "小")
	if err := f.nodes.Delete(f.web, f.owner, []uuid.UUID{small.ID}); err != nil {
		t.Fatal(err)
	}
	dav := service.WithActor(t.Context(), service.Actor{Kind: service.ActorWebDAV, Ref: "ap-9", Label: "rclone 笔记本"})
	if err := f.nodes.Delete(dav, f.owner, []uuid.UUID{big.ID}); err != nil {
		t.Fatal(err)
	}
	notes := f.notes(t, f.owner)
	var alert *store.Notification
	for i := range notes {
		if notes[i].Kind == service.EvMassDelete {
			alert = &notes[i]
		}
	}
	if alert == nil || !strings.Contains(alert.Title, "rclone 笔记本(WebDAV)10 分钟内删除了 201 个文件") || alert.Level != "alert" {
		t.Fatalf("alert = %+v (kinds %v)", alert, f.kinds(t, f.owner))
	}
	var tg service.Target
	if err := json.Unmarshal(alert.Target, &tg); err != nil || tg.Kind != service.TgActivity || tg.ID != "webdav:ap-9" || tg.ParentID == "" {
		t.Fatalf("target = %s", alert.Target)
	}
	// 一小时内同一个凭据只提醒一次
	more := f.mkdir(t, f.web, nil, "再来")
	f.putCtx(t, f.web, more.ID, "z", []byte("z"))
	if err := f.nodes.Delete(dav, f.owner, []uuid.UUID{more.ID}); err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, k := range f.kinds(t, f.owner) {
		if k == service.EvMassDelete {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("alerts = %d", n)
	}
	if p := service.TargetPath(service.Target{Kind: service.TgActivity, ID: "webdav:ap-9", ParentID: "2026-10-21T00:00:00Z"}); p != "/activity?actor=webdav%3Aap-9&since=2026-10-21T00%3A00%3A00Z" {
		t.Fatalf("path = %s", p)
	}
}

func TestNaturalOrder(t *testing.T) {
	f := newV35Fixture(t)
	dir := f.mkdir(t, f.web, nil, "剧")
	names := []string{"第10集.mp4", "第2集.mp4", "第1集.mp4", "b.txt", "A.txt", "a10.txt", "a2.txt"}
	for _, n := range names {
		f.putCtx(t, f.web, dir.ID, n, []byte(n))
	}
	page, err := f.nodes.Children(t.Context(), f.owner, &dir.ID, nil, "NAME", false)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, it := range page.Items {
		got = append(got, it.Name)
	}
	want := []string{"A.txt", "a2.txt", "a10.txt", "b.txt", "第1集.mp4", "第2集.mp4", "第10集.mp4"}
	if !slices.Equal(got, want) {
		t.Fatalf("order = %v want %v", got, want)
	}
	// 翻页(keyset)不重不漏,也按自然顺序
	many := f.mkdir(t, f.web, nil, "多")
	for i := 1; i <= 205; i++ {
		f.putCtx(t, f.web, many.ID, fmt.Sprintf("f%d", i), []byte(fmt.Sprintf("p%d", i)))
	}
	var all []string
	var cursor *string
	for range 3 {
		p, err := f.nodes.Children(t.Context(), f.owner, &many.ID, cursor, "NAME", false)
		if err != nil {
			t.Fatal(err)
		}
		for _, it := range p.Items {
			all = append(all, it.Name)
		}
		if cursor = p.NextCursor; cursor == nil {
			break
		}
	}
	if len(all) != 205 || all[0] != "f1" || all[1] != "f2" || all[9] != "f10" || all[204] != "f205" {
		t.Fatalf("paged = %d %v … %v", len(all), all[:12], all[len(all)-3:])
	}
	desc, err := f.nodes.Children(t.Context(), f.owner, &many.ID, nil, "NAME", true)
	if err != nil || desc.Items[0].Name != "f205" {
		t.Fatalf("desc first = %v %v", desc.Items[0].Name, err)
	}
}

func TestSpaceAnalysis(t *testing.T) {
	f := newV35Fixture(t)
	pics := f.mkdir(t, f.web, nil, "图")
	docs := f.mkdir(t, f.web, nil, "文档")
	png := pngBytes(t, color.RGBA{1, 2, 3, 255})
	f.putCtx(t, f.web, pics.ID, "a.png", png)
	f.putCtx(t, f.web, docs.ID, "a.png", png) // 重复
	f.putCtx(t, f.web, docs.ID, "a 副本.png", png)
	f.putCtx(t, f.web, docs.ID, "报告.pdf", bytes.Repeat([]byte("p"), 5000))
	f.putCtx(t, f.web, docs.ID, "包.zip", bytes.Repeat([]byte("z"), 300))
	f.putCtx(t, f.web, docs.ID, "笔记.md", []byte("# hi"))
	old := f.putCtx(t, f.web, docs.ID, "老.bin", bytes.Repeat([]byte("o"), 2000))
	trash := f.putCtx(t, f.web, docs.ID, "扔.txt", []byte("12345"))
	if err := f.nodes.Delete(f.web, f.owner, []uuid.UUID{trash.ID}); err != nil {
		t.Fatal(err)
	}
	ov, err := f.space.Overview(t.Context(), f.owner)
	if err != nil {
		t.Fatal(err)
	}
	cats := map[string]int64{}
	for _, c := range ov.ByType {
		cats[c.Category] = c.Files
	}
	if cats["image"] != 3 || cats["document"] != 1 || cats["archive"] != 1 || cats["text"] != 1 || cats["other"] != 1 {
		t.Fatalf("by type = %v", cats)
	}
	if ov.TrashFiles != 1 || ov.TrashBytes != 5 || ov.DuplicateGroups != 1 || ov.DuplicateReclaimable != int64(2*len(png)) {
		t.Fatalf("overview = %+v", ov)
	}
	dups, more, err := f.space.Duplicates(t.Context(), f.owner, 0, 50)
	if err != nil || more || len(dups) != 1 || len(dups[0].Files) != 3 || dups[0].Files[0].Path != "/图" {
		t.Fatalf("dups = %+v %v %v", dups, more, err)
	}
	large, err := f.space.LargeFiles(t.Context(), f.owner, "", 3)
	if err != nil || len(large) != 3 || large[0].Node.Name != "报告.pdf" || large[0].Path != "/文档" || large[0].Category != "document" {
		t.Fatalf("large = %+v %v", large, err)
	}
	onlyImg, _ := f.space.LargeFiles(t.Context(), f.owner, "image", 10)
	if len(onlyImg) != 3 {
		t.Fatalf("image filter = %d", len(onlyImg))
	}
	// 很久没动:把「老.bin」的修改时间改到两年前
	if _, err := f.pool.Exec(t.Context(), `UPDATE nodes SET updated_at = now() - interval '2 years' WHERE id = $1`, old.ID); err != nil {
		t.Fatal(err)
	}
	stale, err := f.space.StaleFiles(t.Context(), f.owner, 365, 1000, 10)
	if err != nil || len(stale) != 1 || stale[0].Node.ID != old.ID {
		t.Fatalf("stale = %+v %v", stale, err)
	}
	// 空文件夹:只列最上面一层;收集链接的文件夹不算;刚建的不算
	empty := f.mkdir(t, f.web, nil, "空")
	f.mkdir(t, f.web, &empty.ID, "空里的空")
	reqFolder := f.mkdir(t, f.web, nil, "收集")
	if _, err := f.pool.Exec(t.Context(), `UPDATE nodes SET created_at = now() - interval '2 days'`); err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(t.Context(), `INSERT INTO file_requests (id, token, owner_id, folder_id, title) VALUES ($1, 'tok1', $2, $3, '作业')`, uuid.New(), f.owner, reqFolder.ID); err != nil {
		t.Fatal(err)
	}
	recomputeStats(t, f.pool)
	ef, err := f.space.EmptyFolders(t.Context(), f.owner, 50)
	if err != nil {
		t.Fatal(err)
	}
	var en []string
	for _, e := range ef {
		en = append(en, e.Node.Name)
	}
	slices.Sort(en)
	if !slices.Equal(en, []string{"下载", "空"}) { // 「下载」是夹具建的空文件夹
		t.Fatalf("empty folders = %v", en)
	}
	// 文件夹逐层
	lv, err := f.space.Folders(t.Context(), f.owner, nil)
	if err != nil || lv.Path != "/" || len(lv.Folders) < 2 || lv.Folders[0].Name != "文档" {
		t.Fatalf("folders = %+v %v", lv, err)
	}
	// 过期的分享与直链
	shares := service.NewShares(f.q, newAuth(f.pool))
	exp := time.Now().Add(time.Hour)
	sh, err := shares.Create(t.Context(), f.owner, docs.ID, nil, &exp, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(t.Context(), `UPDATE shares SET expires_at = now() - interval '1 day' WHERE id = $1`, sh.ID); err != nil {
		t.Fatal(err)
	}
	idle, err := f.space.Idle(t.Context(), f.owner)
	if err != nil || len(idle.Shares) != 1 || idle.Shares[0].Name != "文档" {
		t.Fatalf("idle = %+v %v", idle, err)
	}
	st, err := f.space.AdminStorage(t.Context())
	if err != nil || st.PhysicalBytes <= 0 || st.LogicalBytes < st.PhysicalBytes {
		t.Fatalf("admin storage = %+v %v", st, err)
	}
}

// recomputeStats 子树统计平时由 worker 每分钟重算,测试里直接跑。
func recomputeStats(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	q := store.New(pool)
	ids, err := q.ListStaleFolders(t.Context(), 1000)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range ids {
		if err := q.RecomputeFolderStats(t.Context(), id); err != nil {
			t.Fatal(err)
		}
	}
}

// ---------- 订阅 ----------

type feedServer struct {
	*httptest.Server
	mu       sync.Mutex
	items    []string
	etag     string
	hits     atomic.Int32
	notMod   atomic.Int32
	fail     atomic.Bool
	releases string
}

func newFeedServer(t *testing.T) *feedServer {
	fs := &feedServer{}
	mux := http.NewServeMux()
	mux.HandleFunc("/feed.xml", func(w http.ResponseWriter, r *http.Request) {
		fs.hits.Add(1)
		if fs.fail.Load() {
			http.Error(w, "boom", http.StatusBadGateway)
			return
		}
		fs.mu.Lock()
		body := `<?xml version="1.0"?><rss version="2.0"><channel><title>测试播客</title><link>https://pod.test/</link>` + strings.Join(fs.items, "") + `</channel></rss>`
		etag := fmt.Sprintf(`"%d"`, len(fs.items))
		fs.mu.Unlock()
		if r.Header.Get("If-None-Match") == etag {
			fs.notMod.Add(1)
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("ETag", etag)
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = io.WriteString(w, body)
	})
	mux.HandleFunc("/media/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/mpeg")
		_, _ = io.WriteString(w, "ID3-"+r.URL.Path)
	})
	mux.HandleFunc("/repos/a/b/releases", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, strings.ReplaceAll(fs.releases, "{base}", fs.URL))
	})
	mux.HandleFunc("/dl/", func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "BIN"+r.URL.Path) })
	fs.Server = httptest.NewServer(mux)
	t.Cleanup(fs.Close)
	return fs
}

func (fs *feedServer) addEpisode(n int, date string) {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	fs.items = append([]string{fmt.Sprintf(`<item><title>第%d期</title><guid>ep%d</guid><pubDate>%s</pubDate><enclosure url="%s/media/ep%d.mp3" type="audio/mpeg" length="10"/></item>`,
		n, n, date, fs.URL, n)}, fs.items...)
}

func (f *v35Fixture) runAll(t *testing.T) {
	t.Helper()
	for range 20 {
		ok, err := f.jobs.RunOnce(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		if !ok {
			return
		}
	}
}

func (f *v35Fixture) items(t *testing.T, feed uuid.UUID) []store.FeedItem {
	t.Helper()
	rows, _, err := f.feeds.Items(t.Context(), f.owner, feed, nil)
	if err != nil {
		t.Fatal(err)
	}
	return rows
}

func TestFeedPodcastLifecycle(t *testing.T) {
	f := newV35Fixture(t)
	fs := newFeedServer(t)
	fs.addEpisode(1, "Mon, 05 Oct 2026 08:00:00 +0800")
	fs.addEpisode(2, "Mon, 12 Oct 2026 08:00:00 +0800")

	pv, err := f.feeds.Preview(t.Context(), fs.URL+"/feed.xml", "", false)
	if err != nil || pv.Title != "测试播客" || !pv.Podcast || pv.SuggestedMode != service.FeedModeDownload || len(pv.Items) != 2 {
		t.Fatalf("preview = %+v %v", pv, err)
	}
	feed, err := f.feeds.Create(f.web, f.owner, service.FeedInput{URL: fs.URL + "/feed.xml", Mode: service.FeedModeDownload, KeepLast: 2, Initial: 1})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.feeds.Create(f.web, f.owner, service.FeedInput{URL: fs.URL + "/feed.xml"}); err == nil {
		t.Fatal("duplicate feed accepted")
	}
	// 第一次只下最新一集,旧的标已跳过
	st := map[string]string{}
	for _, it := range f.items(t, feed.ID) {
		st[it.Guid] = it.State
	}
	if st["ep2"] != "queued" || st["ep1"] != "skipped" {
		t.Fatalf("states = %v", st)
	}
	f.runAll(t)
	items := f.items(t, feed.ID)
	if items[0].Guid != "ep2" || items[0].State != "done" || items[0].NodeID == nil {
		t.Fatalf("after download = %+v", items[0])
	}
	n, err := f.nodes.Get(t.Context(), f.owner, *items[0].NodeID)
	if err != nil || n.Name != "2026-10-12 第2期.mp3" {
		t.Fatalf("file = %q %v", n.Name, err)
	}
	path, _ := store.New(f.pool).NodePath(t.Context(), *n.ParentID)
	if path != "/订阅/测试播客" {
		t.Fatalf("folder = %s", path)
	}
	// 来源是订阅;通知只发「有新内容」
	ups := f.acts1(t, service.ActivityFilter{Actions: []string{"upload"}})
	if len(ups) != 1 || ups[0].ActorKind != service.ActorFeed || ups[0].ActorLabel != "测试播客" {
		t.Fatalf("activity = %+v", ups)
	}
	kinds := f.kinds(t, f.owner)
	if !slices.Contains(kinds, service.EvFeedNew) || slices.Contains(kinds, service.EvJobDone) {
		t.Fatalf("kinds = %v", kinds)
	}
	// 没变:304,不重复下
	if _, err := f.feeds.Refresh(f.web, f.owner, feed.ID); err != nil {
		t.Fatal(err)
	}
	if fs.notMod.Load() != 1 {
		t.Fatalf("not modified = %d", fs.notMod.Load())
	}
	// 新一集:自动下;只留 2 集时最旧的进回收站
	fs.addEpisode(3, "Mon, 19 Oct 2026 08:00:00 +0800")
	if _, err := f.feeds.Refresh(f.web, f.owner, feed.ID); err != nil {
		t.Fatal(err)
	}
	f.runAll(t)
	if _, err := f.feeds.FetchItem(f.web, f.owner, itemByGUID(t, f.items(t, feed.ID), "ep1").ID, service.FeedModeDownload); err != nil {
		t.Fatal(err)
	}
	f.runAll(t)
	done := 0
	for _, it := range f.items(t, feed.ID) {
		if it.State == "done" && it.NodeID != nil {
			done++
		}
	}
	if done != 2 {
		t.Fatalf("kept = %d (%+v)", done, f.items(t, feed.ID))
	}
	if ep1 := itemByGUID(t, f.items(t, feed.ID), "ep1"); ep1.State != "done" || ep1.NodeID != nil {
		t.Fatalf("oldest should be pruned: %+v", ep1)
	}
	// 连续 3 次失败:提醒一次,间隔翻倍
	fs.fail.Store(true)
	for range 3 {
		if _, err := f.feeds.Refresh(f.web, f.owner, feed.ID); err != nil {
			t.Fatal(err)
		}
	}
	got, _ := f.feeds.Get(t.Context(), f.owner, feed.ID)
	if got.FailCount != 3 || got.LastError == nil || !strings.Contains(*got.LastError, "502") {
		t.Fatalf("failed feed = %+v", got)
	}
	if !slices.Contains(f.kinds(t, f.owner), service.EvFeedFailed) {
		t.Fatal("no failure alert")
	}
	// OPML 往返
	opml, err := f.feeds.ExportOPML(t.Context(), f.owner)
	if err != nil || !strings.Contains(opml, `xmlUrl="`+fs.URL+`/feed.xml"`) {
		t.Fatalf("opml = %s %v", opml, err)
	}
	if err := f.feeds.Delete(t.Context(), f.owner, feed.ID); err != nil {
		t.Fatal(err)
	}
	fs.fail.Store(false)
	res, err := f.feeds.ImportOPML(f.web, f.owner, opml)
	if err != nil || res.Added != 1 {
		t.Fatalf("import = %+v %v", res, err)
	}
	feeds, _ := f.feeds.List(t.Context(), f.owner)
	if len(feeds) != 1 || feeds[0].Mode != service.FeedModeDownload {
		t.Fatalf("imported = %+v", feeds)
	}
	// 导入不补下旧内容
	for _, it := range f.items(t, feeds[0].ID) {
		if it.State == "queued" {
			t.Fatalf("imported feed should not download backlog: %+v", it)
		}
	}
}

func itemByGUID(t *testing.T, items []store.FeedItem, guid string) store.FeedItem {
	t.Helper()
	for _, it := range items {
		if it.Guid == guid {
			return it
		}
	}
	t.Fatalf("no item %s", guid)
	return store.FeedItem{}
}

func TestFeedGitHubAndFilters(t *testing.T) {
	f := newV35Fixture(t)
	fs := newFeedServer(t)
	fs.releases = `[{"tag_name":"v1.2.0","name":"1.2","published_at":"2026-10-20T00:00:00Z","html_url":"https://github.com/a/b/releases/tag/v1.2.0",
 "assets":[{"name":"app.apk","browser_download_url":"{base}/dl/app.apk","size":5},{"name":"src.tar.gz","browser_download_url":"{base}/dl/src.tgz","size":9}]}]`
	f.feeds.SetTestEndpoints(http.DefaultClient, fs.URL)
	feed, err := f.feeds.Create(f.web, f.owner, service.FeedInput{URL: "https://github.com/a/b", Mode: service.FeedModeDownload, AssetPattern: "*.apk", Initial: 5})
	if err != nil {
		t.Fatal(err)
	}
	if feed.Kind != service.FeedGitHub || feed.Url != "a/b" {
		t.Fatalf("feed = %+v", feed)
	}
	f.runAll(t)
	items := f.items(t, feed.ID)
	if len(items) != 1 || items[0].State != "done" || items[0].NodeID == nil {
		t.Fatalf("items = %+v", items)
	}
	n, _ := f.nodes.Get(t.Context(), f.owner, *items[0].NodeID)
	path, _ := store.New(f.pool).NodePath(t.Context(), *n.ParentID)
	if n.Name != "app.apk" || path != "/订阅/a b/v1.2.0" {
		t.Fatalf("asset = %s in %s", n.Name, path)
	}

	// 关键词过滤 + 只记录
	fs.addEpisode(1, "Mon, 05 Oct 2026 08:00:00 +0800")
	fs.addEpisode(2, "Mon, 12 Oct 2026 08:00:00 +0800")
	rec, err := f.feeds.Create(f.web, f.owner, service.FeedInput{URL: fs.URL + "/feed.xml", Mode: service.FeedModeDownload, Exclude: "第2期", Initial: 5})
	if err != nil {
		t.Fatal(err)
	}
	st := map[string]string{}
	for _, it := range f.items(t, rec.ID) {
		st[it.Guid] = it.State
	}
	if st["ep2"] != "skipped" || st["ep1"] != "queued" {
		t.Fatalf("filtered = %v", st)
	}
	if _, err := f.feeds.Update(f.web, f.owner, rec.ID, service.FeedInput{Mode: service.FeedModeNone, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if _, err := f.feeds.Create(f.web, f.owner, service.FeedInput{URL: "ftp://x"}); err == nil {
		t.Fatal("bad url accepted")
	}
	if _, err := f.feeds.Preview(t.Context(), fs.URL+"/media/x.mp3", "", false); err == nil {
		t.Fatal("non-feed accepted")
	}
}

// ---------- 剪藏 ----------

func newArticleServer(t *testing.T) *httptest.Server {
	png := pngBytes(t, color.RGBA{9, 9, 9, 255})
	mux := http.NewServeMux()
	mux.HandleFunc("/post", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, `<!doctype html><html><head><title>小服务器的网盘 - 测试站</title>
<meta property="og:site_name" content="测试站"></head><body><nav>菜单</nav><article><h1>小服务器的网盘</h1>
<p>这一篇写的是怎么在两核两 G 的机器上跑一个自己的网盘,内容要足够长才能被认成正文,所以这里多写几句。</p>
<p>第二段,带一张图:</p><img src="/img/a.png" alt="截图"><img src="/img/missing.png">
<p>第三段继续写,讲一讲内容寻址、秒传、回收站这些细节,再凑一些字数让正文提取更有把握。</p></article></body></html>`)
	})
	mux.HandleFunc("/img/a.png", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Referer") == "" {
			http.Error(w, "no referer", http.StatusForbidden)
			return
		}
		_, _ = w.Write(png)
	})
	mux.HandleFunc("/file.zip", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/zip")
		_, _ = io.WriteString(w, "PK")
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestClipLifecycle(t *testing.T) {
	f := newV35Fixture(t)
	srv := newArticleServer(t)
	job, existing, err := f.clips.Submit(f.web, f.owner, srv.URL+"/post", nil, false, "web")
	if err != nil || job == nil || existing != nil {
		t.Fatalf("submit = %v %v %v", job, existing, err)
	}
	f.runAll(t)
	got := f.get(t, job.ID)
	if got.Status != "done" || got.ResultNodeID == nil {
		t.Fatalf("job = %+v", got)
	}
	md, err := f.nodes.Get(t.Context(), f.owner, *got.ResultNodeID)
	if err != nil || md.Name != "小服务器的网盘.md" {
		t.Fatalf("md = %q %v", md.Name, err)
	}
	path, _ := store.New(f.pool).NodePath(t.Context(), *md.ParentID)
	if path != "/剪藏/"+time.Now().Format("2006-01") {
		t.Fatalf("folder = %s", path)
	}
	row, _ := f.nodes.GetWithBlob(t.Context(), f.owner, md.ID)
	r, _ := f.obj.Open(t.Context(), objstore.BlobKey(*row.BlobSha256))
	body, _ := io.ReadAll(r)
	r.Close()
	text := string(body)
	for _, want := range []string{"title: \"小服务器的网盘\"", "source: \"" + srv.URL + "/post\"", "](小服务器的网盘.assets/01.png)", srv.URL + "/img/missing.png", "内容寻址"} {
		if !strings.Contains(text, want) {
			t.Fatalf("md missing %q:\n%s", want, text)
		}
	}
	assets, err := f.nodes.Children(t.Context(), f.owner, md.ParentID, nil, "NAME", false)
	if err != nil || len(assets.Items) != 2 || assets.Items[0].Name != "小服务器的网盘.assets" {
		t.Fatalf("folder contents = %+v %v", assets, err)
	}
	clips, _, err := f.clips.List(t.Context(), f.owner, false, "", nil)
	if err != nil || len(clips) != 1 || clips[0].Site != "测试站" || clips[0].Images != 1 || clips[0].ReadAt.Valid || clips[0].AssetsID == nil || *clips[0].AssetsID != assets.Items[0].ID {
		t.Fatalf("clips = %+v %v", clips, err)
	}
	// 动态:来源剪藏,md 与 assets 两条一批
	acts := f.acts1(t, service.ActivityFilter{ActorKind: service.ActorClip})
	if len(acts) != 2 || acts[0].BatchID != acts[1].BatchID {
		t.Fatalf("clip activity = %v", actions(acts))
	}
	// 再剪同一个地址:给已有的;force 重新剪
	job2, existing, err := f.clips.Submit(f.web, f.owner, srv.URL+"/post", nil, false, "web")
	if err != nil || job2 != nil || existing == nil || existing.ID != clips[0].ID {
		t.Fatalf("dedup = %v %v %v", job2, existing, err)
	}
	if job2, _, err = f.clips.Submit(f.web, f.owner, srv.URL+"/post", nil, true, "web"); err != nil || job2 == nil {
		t.Fatalf("force = %v %v", job2, err)
	}
	// 书签栏发来的内容:不再抓页面
	page := `<html><head><title>要登录的页面</title></head><body><article><h1>要登录的页面</h1><p>` + strings.Repeat("只有登录后才看得到的正文。", 10) + `</p></article></body></html>`
	job3, _, err := f.clips.Submit(f.web, f.owner, srv.URL+"/private", []byte(page), false, "web")
	if err != nil {
		t.Fatal(err)
	}
	f.runAll(t)
	if j := f.get(t, job3.ID); j.Status != "done" {
		t.Fatalf("with html = %+v", j)
	}
	// 已读
	cl, err := f.clips.SetRead(t.Context(), f.owner, clips[0].ID, true)
	if err != nil || !cl.ReadAt.Valid {
		t.Fatalf("read = %+v %v", cl, err)
	}
	unread, _ := f.clips.Unread(t.Context(), f.owner)
	if unread != 2 {
		t.Fatalf("unread = %d", unread)
	}
	// 不是网页
	jz, _, err := f.clips.Submit(f.web, f.owner, srv.URL+"/file.zip", nil, false, "web")
	if err != nil {
		t.Fatal(err)
	}
	f.runAll(t)
	if j := f.get(t, jz.ID); j.Status != "failed" || j.ErrorCode == nil || *j.ErrorCode != "NOT_HTML" {
		t.Fatalf("zip = %+v", j)
	}
	// 设置
	if _, err := f.clips.SetPrefs(t.Context(), f.owner, service.ClipPrefs{Folder: "/读/剪", ByMonth: false}); err != nil {
		t.Fatal(err)
	}
	if _, err := f.clips.SetPrefs(t.Context(), f.owner, service.ClipPrefs{Folder: "/../x"}); err == nil {
		t.Fatal("bad folder accepted")
	}
}

func TestMarkdownResolve(t *testing.T) {
	f := newV35Fixture(t)
	md := service.NewMarkdown(f.pool, f.obj)
	notes := f.mkdir(t, f.web, nil, "笔记")
	img := f.mkdir(t, f.web, &notes.ID, "图")
	pic := f.putCtx(t, f.web, img.ID, "a b.png", pngBytes(t, color.RGBA{1, 1, 1, 255}))
	doc := f.putCtx(t, f.web, notes.ID, "读我.md", []byte("![](图/a%20b.png)"))
	other := f.putCtx(t, f.web, f.folder, "别处.md", []byte("x"))
	_, src, err := md.Load(t.Context(), f.owner, doc.ID)
	if err != nil || string(src) != "![](图/a%20b.png)" {
		t.Fatalf("load = %q %v", src, err)
	}
	for rel, want := range map[string]uuid.UUID{"图/a b.png": pic.ID, "./图/a b.png": pic.ID, "../下载/别处.md": other.ID, "/下载/别处.md": other.ID} {
		n, err := md.Resolve(t.Context(), f.owner, &notes.ID, rel)
		if err != nil || n.ID != want {
			t.Fatalf("resolve %q = %v %v", rel, n.Name, err)
		}
	}
	if _, err := md.Resolve(t.Context(), f.owner, &notes.ID, "没有.png"); err == nil {
		t.Fatal("missing resolved")
	}
	if u, err := md.AssetURL(t.Context(), pic); err != nil || !strings.Contains(u, "X-Amz-Signature") {
		t.Fatalf("asset url = %s %v", u, err)
	}
	if _, _, err := md.Load(t.Context(), f.owner, pic.ID); err == nil {
		t.Fatal("png loaded as markdown")
	}
}
