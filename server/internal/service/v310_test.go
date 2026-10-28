package service_test

// 收藏、最近打开与历史版本(doc/24)的集成测试:要 TEST_DB_URL 与 TEST_S3_ENDPOINT。

import (
	"bytes"
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type verFixture struct {
	pool     *pgxpool.Pool
	obj      *objstore.Store
	q        *store.Queries
	uploads  *service.Uploads
	nodes    *service.Nodes
	versions *service.Versions
	acts     *service.Activities
	owner    uuid.UUID
}

func newVerFixture(t *testing.T, keep int) *verFixture {
	t.Helper()
	pool, obj := setup(t), setupS3(t)
	uploads, nodes, owner := newUploads(t, pool, obj)
	uploads.SetVersionKeep(keep)
	versions := service.NewVersions(pool, obj, service.VersionPolicy{Keep: keep, TTL: 30 * 24 * time.Hour})
	acts := service.NewActivities(pool, nodes)
	acts.SetVersions(versions)
	return &verFixture{pool: pool, obj: obj, q: store.New(pool), uploads: uploads, nodes: nodes, versions: versions, acts: acts, owner: owner}
}

// put 走 WebDAV 的写入路径(同名即覆盖)。
func (f *verFixture) put(t *testing.T, parent *uuid.UUID, name string, content []byte) *store.Node {
	t.Helper()
	ctx := context.Background()
	tmp := "tmp/test/" + uuid.NewString()
	if _, err := f.obj.PutStream(ctx, tmp, bytes.NewReader(content), "application/octet-stream"); err != nil {
		t.Fatal(err)
	}
	n, err := f.uploads.CommitStreamed(ctx, f.owner, parent, name, shaHex(content), int64(len(content)), tmp)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func (f *verFixture) used(t *testing.T) int64 {
	t.Helper()
	u, err := f.q.GetUserByID(context.Background(), f.owner)
	if err != nil {
		t.Fatal(err)
	}
	return u.UsedBytes
}

func (f *verFixture) refs(t *testing.T, content []byte) int32 {
	t.Helper()
	b, err := f.q.GetBlobBySha256(context.Background(), shaHex(content))
	if err != nil {
		t.Fatal(err)
	}
	return b.RefCount
}

func (f *verFixture) list(t *testing.T, id uuid.UUID) []service.VersionView {
	t.Helper()
	_, vs, err := f.versions.List(context.Background(), f.owner, id)
	if err != nil {
		t.Fatal(err)
	}
	return vs
}

func (f *verFixture) current(t *testing.T, id uuid.UUID) string {
	t.Helper()
	row, err := f.nodes.GetWithBlob(context.Background(), f.owner, id)
	if err != nil || row.BlobSha256 == nil {
		t.Fatalf("node %s: %+v %v", id, row, err)
	}
	return *row.BlobSha256
}

func TestVersionsKeepTrimRestorePurge(t *testing.T) {
	f := newVerFixture(t, 3)
	ctx := context.Background()
	v := [][]byte{[]byte("v1 aaaa"), []byte("v2 bbbbbbbb"), []byte("v3 cc"), []byte("v4 dddddddddd"), []byte("v5 eeeeee")}

	n := f.put(t, nil, "report.txt", v[0])
	firstSaved := n.UpdatedAt.Time
	if again := f.put(t, nil, "report.txt", v[1]); again.ID != n.ID {
		t.Fatal("覆盖应保持同一个节点")
	}
	vs := f.list(t, n.ID)
	if len(vs) != 1 || vs[0].Size != int64(len(v[0])) || vs[0].Sha256 != shaHex(v[0]) || !vs[0].SavedAt.Time.Equal(firstSaved) {
		t.Fatalf("第一次覆盖应留下 v1:%+v", vs)
	}
	if got, want := f.used(t), int64(len(v[0])+len(v[1])); got != want {
		t.Fatalf("旧版本计入已用空间:got %d want %d", got, want)
	}
	// 内容没变的覆盖不留版本
	f.put(t, nil, "report.txt", v[1])
	if len(f.list(t, n.ID)) != 1 {
		t.Fatal("同内容覆盖不应多出版本")
	}
	for _, c := range v[2:] {
		f.put(t, nil, "report.txt", c)
	}
	// 留最近 3 个:v4 v3 v2;v1 被挤掉,引用退回 0
	vs = f.list(t, n.ID)
	if len(vs) != 3 || vs[0].Sha256 != shaHex(v[3]) || vs[2].Sha256 != shaHex(v[1]) {
		t.Fatalf("应只留 v4 v3 v2:%+v", vs)
	}
	if f.refs(t, v[0]) != 0 {
		t.Fatal("挤掉的版本应退引用")
	}
	if got, want := f.used(t), int64(len(v[4])+len(v[3])+len(v[2])+len(v[1])); got != want {
		t.Fatalf("used:got %d want %d", got, want)
	}

	// 恢复 v2:现在的 v5 留成版本,仍只留 3 个(v5 v4 v3),已用空间不变
	before := f.used(t)
	restored, err := f.versions.Restore(ctx, f.owner, vs[2].ID)
	if err != nil {
		t.Fatal(err)
	}
	if restored.ID != n.ID || f.current(t, n.ID) != shaHex(v[1]) {
		t.Fatal("恢复后文件内容应是 v2")
	}
	vs = f.list(t, n.ID)
	if len(vs) != 3 || vs[0].Sha256 != shaHex(v[4]) {
		t.Fatalf("恢复后版本应是 v5 v4 v3:%+v", vs)
	}
	if f.used(t) != before {
		t.Fatalf("恢复只是交换,已用空间不变:got %d before %d", f.used(t), before)
	}

	// 删掉一个版本
	if err := f.versions.Delete(ctx, f.owner, []uuid.UUID{vs[2].ID}); err != nil {
		t.Fatal(err)
	}
	if len(f.list(t, n.ID)) != 2 {
		t.Fatal("删版本没生效")
	}

	// 删进回收站再彻底删除:版本一起清掉,空间与引用全部退回
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{n.ID}); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Purge(ctx, f.owner, []uuid.UUID{n.ID}); err != nil {
		t.Fatal(err)
	}
	if f.used(t) != 0 {
		t.Fatalf("彻底删除后已用空间应为 0,got %d", f.used(t))
	}
	for _, c := range v {
		if f.refs(t, c) != 0 {
			t.Fatalf("%q 的引用没退干净", c)
		}
	}
}

func TestVersionsUndoOverwriteAndCleanup(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	a, b := []byte("draft one"), []byte("draft two, longer")
	n := f.put(t, nil, "plan.md", a)
	f.put(t, nil, "plan.md", b)

	var actID int64
	if err := f.pool.QueryRow(ctx, `SELECT id FROM activities WHERE owner_id = $1 AND action = 'overwrite' ORDER BY id DESC LIMIT 1`, f.owner).Scan(&actID); err != nil {
		t.Fatal(err)
	}
	act, err := f.q.ListActivitiesByIDs(ctx, store.ListActivitiesByIDsParams{OwnerID: f.owner, Ids: []int64{actID}})
	if err != nil || len(act) != 1 || !service.Undoable(act[0]) {
		t.Fatalf("留了旧版本的覆盖应能撤回:%+v %v", act, err)
	}
	res, err := f.acts.Undo(ctx, f.owner, service.UndoSelection{IDs: []int64{actID}})
	if err != nil || res.Done != 1 {
		t.Fatalf("撤回覆盖:%+v %v", res, err)
	}
	if f.current(t, n.ID) != shaHex(a) {
		t.Fatal("撤回覆盖后应回到覆盖前的内容")
	}
	if vs := f.list(t, n.ID); len(vs) != 1 || vs[0].Sha256 != shaHex(b) {
		t.Fatalf("撤回时现在的内容应留成版本:%+v", vs)
	}

	// 到期清理:把版本的换下时间改到保留期之前
	if _, err := f.pool.Exec(ctx, `UPDATE node_versions SET replaced_at = now() - interval '31 days' WHERE owner_id = $1`, f.owner); err != nil {
		t.Fatal(err)
	}
	gone, err := f.versions.CleanupExpired(ctx)
	if err != nil || gone != 1 {
		t.Fatalf("到期清理:%d %v", gone, err)
	}
	if f.used(t) != int64(len(a)) || f.refs(t, b) != 0 {
		t.Fatalf("清理后应退回空间与引用:used=%d", f.used(t))
	}
}

func TestVersionsQuotaAndDisabled(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	a, b := bytes.Repeat([]byte("a"), 100), bytes.Repeat([]byte("b"), 100)
	n := f.put(t, nil, "big.bin", a)
	// 配额 150:留旧版本要 200 放不下,退回只按差额覆盖、不留版本
	if _, err := f.pool.Exec(ctx, `UPDATE users SET quota_bytes = 150 WHERE id = $1`, f.owner); err != nil {
		t.Fatal(err)
	}
	f.put(t, nil, "big.bin", b)
	if len(f.list(t, n.ID)) != 0 || f.used(t) != 100 || f.refs(t, a) != 0 {
		t.Fatalf("空间不够时不留版本:used=%d", f.used(t))
	}
	var detail []byte
	if err := f.pool.QueryRow(ctx, `SELECT detail FROM activities WHERE owner_id = $1 AND action = 'overwrite' ORDER BY id DESC LIMIT 1`, f.owner).Scan(&detail); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(detail, []byte(`"version"`)) {
		t.Fatalf("没留版本的覆盖不该带 version:%s", detail)
	}

	// 关掉历史版本:覆盖即丢
	g := newVerFixture(t, 0)
	m := g.put(t, nil, "x.txt", []byte("one"))
	g.put(t, nil, "x.txt", []byte("two"))
	if len(g.list(t, m.ID)) != 0 || g.used(t) != 3 {
		t.Fatal("keep = 0 时不应留版本")
	}
}

func TestReplaceUpload(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	old := []byte("old contents")
	n := f.put(t, nil, "doc.txt", old)

	// 分片上传 + 替换:定稿时覆盖同名文件
	data := []byte("brand new contents of the document")
	init, err := f.uploads.InitWith(ctx, f.owner, nil, "doc.txt", shaHex(data), int64(len(data)), true)
	if err != nil || init.Session == nil {
		t.Fatalf("init: %+v %v", init, err)
	}
	if init.Session.Session.TargetName != "doc.txt" || !init.Session.Session.ReplaceExisting {
		t.Fatalf("替换上传不应改名:%+v", init.Session.Session)
	}
	for part, url := range init.Session.PartURLs {
		ps := int(init.Session.Session.PartSize)
		httpPut(t, url, data[(part-1)*ps:min(part*ps, len(data))])
	}
	got, err := completeBrowser(t, f.uploads, f.owner, init.Session.Session.ID)
	if err != nil || got.ID != n.ID {
		t.Fatalf("应覆盖原来那个节点:%+v %v", got, err)
	}
	if f.current(t, n.ID) != shaHex(data) {
		t.Fatal("内容没换")
	}
	if vs := f.list(t, n.ID); len(vs) != 1 || vs[0].Sha256 != shaHex(old) {
		t.Fatalf("旧内容应进历史版本:%+v", vs)
	}

	// 秒传 + 替换(内容已在服务器上且校验过;测试里没跑 worker,手动标校验通过)
	if _, err := f.pool.Exec(ctx, `UPDATE blobs SET verified = true`); err != nil {
		t.Fatal(err)
	}
	again, err := f.uploads.InitWith(ctx, f.owner, nil, "doc.txt", shaHex(old), int64(len(old)), true)
	if err != nil || !again.Instant || again.Node.ID != n.ID {
		t.Fatalf("秒传替换:%+v %v", again, err)
	}
	if f.current(t, n.ID) != shaHex(old) || len(f.list(t, n.ID)) != 2 {
		t.Fatal("秒传替换后内容 / 版本不对")
	}

	// 不替换:照旧自动改名
	plain, err := f.uploads.InitWith(ctx, f.owner, nil, "doc.txt", shaHex(data), int64(len(data)), false)
	if err != nil || !plain.Instant || plain.Node.Name != "doc (1).txt" {
		t.Fatalf("不替换应改名:%+v %v", plain, err)
	}
	// 同名的是文件夹:替换也改名
	if _, err := f.nodes.CreateFolder(ctx, f.owner, nil, "photos"); err != nil {
		t.Fatal(err)
	}
	dir, err := f.uploads.InitWith(ctx, f.owner, nil, "photos", shaHex(data), int64(len(data)), true)
	if err != nil || dir.Node == nil || dir.Node.Name != "photos (1)" {
		t.Fatalf("和文件夹同名应改名:%+v %v", dir, err)
	}
}

func TestStarsAndRecent(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	folder, err := f.nodes.CreateFolder(ctx, f.owner, nil, "工作")
	if err != nil {
		t.Fatal(err)
	}
	file := f.put(t, &folder.ID, "合同.pdf", []byte("%PDF-1.4 fake"))
	other := f.put(t, nil, "notes.txt", []byte("notes"))

	if changed, err := f.nodes.SetStarred(ctx, f.owner, []uuid.UUID{folder.ID, uuid.Must(uuid.NewV7())}, true); err != nil || len(changed) != 1 {
		t.Fatalf("收藏(不存在的跳过):%d %v", len(changed), err)
	}
	time.Sleep(5 * time.Millisecond)
	if _, err := f.nodes.SetStarred(ctx, f.owner, []uuid.UUID{file.ID}, true); err != nil {
		t.Fatal(err)
	}
	if row, _ := f.nodes.GetWithBlob(ctx, f.owner, file.ID); !row.UpdatedAt.Time.Equal(file.UpdatedAt.Time) {
		t.Fatal("收藏不应改 updated_at")
	}
	starred, err := f.nodes.Starred(ctx, f.owner)
	if err != nil || len(starred) != 2 || starred[0].Node.ID != file.ID {
		t.Fatalf("收藏列表(最近收藏在前):%+v %v", starred, err)
	}
	// 进回收站就不列,还原回来仍是收藏
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{folder.ID}); err != nil {
		t.Fatal(err)
	}
	if starred, _ = f.nodes.Starred(ctx, f.owner); len(starred) != 0 {
		t.Fatalf("回收站里的不该列:%d", len(starred))
	}
	if _, err := f.nodes.Restore(ctx, f.owner, []uuid.UUID{folder.ID}); err != nil {
		t.Fatal(err)
	}
	if starred, _ = f.nodes.Starred(ctx, f.owner); len(starred) != 2 {
		t.Fatalf("还原后应仍是收藏:%d", len(starred))
	}
	if _, err := f.nodes.SetStarred(ctx, f.owner, []uuid.UUID{folder.ID}, false); err != nil {
		t.Fatal(err)
	}
	if starred, _ = f.nodes.Starred(ctx, f.owner); len(starred) != 1 || starred[0].BlobSize == nil {
		t.Fatalf("取消收藏:%+v", starred)
	}

	// 最近打开:文件夹与别人的忽略,最近的在前
	for _, id := range []uuid.UUID{file.ID, folder.ID, other.ID} {
		if err := f.nodes.MarkOpened(ctx, f.owner, id); err != nil {
			t.Fatal(err)
		}
	}
	if err := f.nodes.MarkOpened(ctx, uuid.Must(uuid.NewV7()), file.ID); err != nil {
		t.Fatal(err)
	}
	// 有进度的顺带带上进度(doc/31 五)
	for _, id := range []uuid.UUID{file.ID, other.ID} {
		if _, err := f.q.UpsertPlayProgress(ctx, store.UpsertPlayProgressParams{OwnerID: f.owner, NodeID: id, Position: 120, Duration: 600}); err != nil {
			t.Fatal(err)
		}
	}
	recent, err := f.nodes.Recent(ctx, f.owner, 0)
	if err != nil || len(recent) != 2 || recent[0].Node.ID != other.ID {
		t.Fatalf("最近打开:%+v %v", recent, err)
	}
	if p := recent[0].Progress; p == nil || p.Position != 120 || p.Duration != 600 || p.Finished {
		t.Fatalf("进度:%+v", p)
	}
	if err := f.nodes.ClearRecent(ctx, f.owner, []uuid.UUID{other.ID}); err != nil {
		t.Fatal(err)
	}
	if recent, _ = f.nodes.Recent(ctx, f.owner, 0); len(recent) != 1 || recent[0].Node.ID != file.ID || recent[0].Progress == nil {
		t.Fatalf("拿掉一个:%+v", recent)
	}
	if _, err := f.q.GetPlayProgress(ctx, store.GetPlayProgressParams{OwnerID: f.owner, NodeID: other.ID}); err == nil {
		t.Fatal("拿掉的那个进度也该清掉")
	}
	if err := f.nodes.ClearRecent(ctx, f.owner, nil); err != nil {
		t.Fatal(err)
	}
	if recent, _ = f.nodes.Recent(ctx, f.owner, 0); len(recent) != 0 {
		t.Fatal("全部清掉")
	}
	if _, err := f.q.GetPlayProgress(ctx, store.GetPlayProgressParams{OwnerID: f.owner, NodeID: file.ID}); err == nil {
		t.Fatal("全部清掉时进度也清掉")
	}
}

func TestPurgeReleasesDuplicateBlobRefs(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()
	folder, err := f.nodes.CreateFolder(ctx, f.owner, nil, "dup")
	if err != nil {
		t.Fatal(err)
	}
	same := []byte("same bytes twice")
	f.put(t, &folder.ID, "a.txt", same)
	f.put(t, &folder.ID, "b.txt", same)
	if f.refs(t, same) != 2 {
		t.Fatal("两个文件应各持一个引用")
	}
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{folder.ID}); err != nil {
		t.Fatal(err)
	}
	if err := f.nodes.Purge(ctx, f.owner, []uuid.UUID{folder.ID}); err != nil {
		t.Fatal(err)
	}
	if f.refs(t, same) != 0 || f.used(t) != 0 {
		t.Fatalf("同一份内容被两个文件指着,彻底删除要退两次:refs=%d used=%d", f.refs(t, same), f.used(t))
	}
}
