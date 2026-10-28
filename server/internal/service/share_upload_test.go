package service_test

// 分享允许对方上传(doc/32 一)与自动刷新(doc/32 二)的集成测试:真 Postgres + MinIO。

import (
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type shareUploadFixture struct {
	*collectFixture
	shares *service.Shares
	su     *service.ShareUploads
	share  store.Share
	guest  *service.Identity
}

func newShareUploadFixture(t *testing.T) *shareUploadFixture {
	t.Helper()
	f := &shareUploadFixture{collectFixture: newCollectFixture(t)}
	f.uploads.SetNotifier(service.NewNotifier(f.pool, fetcher.Guard{AllowLoopback: true}, "https://drive.test"))
	f.shares = service.NewShares(store.New(f.pool), f.auth)
	f.shares.SetRateLimit(time.Millisecond, 1000)
	f.su = service.NewShareUploads(f.pool, f.shares, f.uploads)
	f.su.SetRateLimits(time.Millisecond, 1000)
	sh, err := f.shares.Create(t.Context(), f.owner, f.folder, nil, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	f.share = sh
	access, err := f.shares.Access(t.Context(), sh.Token, "", "3.3.3.3")
	if err != nil {
		t.Fatal(err)
	}
	if f.guest, err = f.auth.ParseAccess(access); err != nil {
		t.Fatal(err)
	}
	return f
}

func (f *shareUploadFixture) put(t *testing.T, parent uuid.UUID, name, uploader string, data []byte) (*store.Node, error) {
	t.Helper()
	view, err := f.su.InitUpload(t.Context(), f.guest, parent, name, int64(len(data)), uploader)
	if err != nil {
		return nil, err
	}
	ps := int(view.Session.PartSize)
	for part, url := range view.PartURLs {
		httpPut(t, url, data[(part-1)*ps:min(part*ps, len(data))])
	}
	n, err := f.su.CompleteUpload(t.Context(), f.guest, view.Session.ID, nil)
	var se *service.Error
	if !errors.As(err, &se) || se.Code != "UPLOAD_PROCESSING" {
		return n, err
	}
	if _, err := f.uploads.ProcessNextBrowserUpload(t.Context()); err != nil {
		return nil, err
	}
	return f.su.CompleteUpload(t.Context(), f.guest, view.Session.ID, nil)
}

func errCode(err error) string {
	var se *service.Error
	if errors.As(err, &se) {
		return se.Code
	}
	if errors.Is(err, service.ErrNotFound) {
		return "NOT_FOUND"
	}
	return ""
}

func TestShareUploadFlow(t *testing.T) {
	f := newShareUploadFixture(t)
	ctx := t.Context()

	// 文件夹:同名文件夹已在就直接用它
	photos, err := f.su.EnsureFolder(ctx, f.guest, f.folder, "照片", "小王")
	if err != nil {
		t.Fatal(err)
	}
	again, err := f.su.EnsureFolder(ctx, f.guest, f.folder, "照片", "")
	if err != nil || again.ID != photos.ID {
		t.Fatalf("ensure again = %v, %v; want same folder", again.ID, err)
	}

	// 传两个同名的:第二个自动改名,不覆盖
	a, err := f.put(t, photos.ID, "a.txt", "小王", []byte("hello"))
	if err != nil {
		t.Fatal(err)
	}
	b, err := f.put(t, photos.ID, "a.txt", "", []byte("world"))
	if err != nil {
		t.Fatal(err)
	}
	if a.Name != "a.txt" || b.Name != "a (1).txt" || a.ParentID == nil || *a.ParentID != photos.ID {
		t.Fatalf("names = %q, %q", a.Name, b.Name)
	}

	// 动态里记成分享访客,带着留的名字
	var kind, label string
	if err := f.pool.QueryRow(ctx, `SELECT actor_kind, actor_label FROM activities WHERE owner_id = $1 AND action = 'upload' ORDER BY id LIMIT 1`, f.owner).Scan(&kind, &label); err != nil {
		t.Fatal(err)
	}
	if kind != service.ActorShare || label != "分享「收件箱」 · 小王" {
		t.Fatalf("actor = %q %q", kind, label)
	}
	// 访问记录:两次 upload,detail 带名字
	visits, err := f.shares.ListVisits(ctx, f.owner, f.share.ID, 10)
	if err != nil {
		t.Fatal(err)
	}
	var details []string
	for _, v := range visits {
		if v.Kind == "upload" && v.Detail != nil {
			details = append(details, *v.Detail)
		}
	}
	slices.Sort(details)
	if !slices.Equal(details, []string{"a (1).txt", "小王 · a.txt"}) {
		t.Fatalf("visit details = %v", details)
	}
	rows, err := f.shares.List(ctx, f.owner)
	if err != nil || len(rows) != 1 || rows[0].UploadCount != 2 || !rows[0].AllowUpload {
		t.Fatalf("list = %+v, %v", rows, err)
	}
	// 通知:同一分享合并成一条
	var notes int
	if err := f.pool.QueryRow(ctx, `SELECT count(*) FROM notifications WHERE owner_id = $1 AND kind = 'share_upload'`, f.owner).Scan(&notes); err != nil {
		t.Fatal(err)
	}
	if notes != 1 {
		t.Fatalf("notifications = %d, want 1", notes)
	}
}

func TestShareUploadGuards(t *testing.T) {
	f := newShareUploadFixture(t)
	ctx := t.Context()

	// 分享范围外的文件夹:当成不存在
	outside, err := f.nodes.CreateFolder(ctx, f.owner, nil, "私人")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.su.InitUpload(ctx, f.guest, outside.ID, "x.txt", 3, ""); errCode(err) != "NOT_FOUND" {
		t.Fatalf("outside init = %v", err)
	}
	if _, err := f.su.EnsureFolder(ctx, f.guest, outside.ID, "x", ""); errCode(err) != "NOT_FOUND" {
		t.Fatalf("outside mkdir = %v", err)
	}

	// 文件分享不能开上传
	file, err := f.put(t, f.folder, "doc.txt", "", []byte("doc"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.shares.Create(ctx, f.owner, file.ID, nil, nil, true); errCode(err) != "NOT_A_FOLDER" {
		t.Fatalf("file share with upload = %v", err)
	}
	fileShare, err := f.shares.Create(ctx, f.owner, file.ID, nil, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.shares.SetUpload(ctx, f.owner, fileShare.ID, true); errCode(err) != "NOT_A_FOLDER" {
		t.Fatalf("set upload on file share = %v", err)
	}

	// 开会话后属主关掉上传:落库时复核,失败
	view, err := f.su.InitUpload(ctx, f.guest, f.folder, "late.txt", 4, "")
	if err != nil {
		t.Fatal(err)
	}
	for _, url := range view.PartURLs {
		httpPut(t, url, []byte("late"))
	}
	if err := f.shares.SetUpload(ctx, f.owner, f.share.ID, false); err != nil {
		t.Fatal(err)
	}
	if _, err := f.su.CompleteUpload(ctx, f.guest, view.Session.ID, nil); errCode(err) != "SHARE_NO_UPLOAD" {
		t.Fatalf("complete after off = %v", err)
	}
	if _, err := f.su.InitUpload(ctx, f.guest, f.folder, "y.txt", 3, ""); errCode(err) != "SHARE_NO_UPLOAD" {
		t.Fatalf("init after off = %v", err)
	}
	// 只读分享的访客也碰不到上传
	if err := f.shares.SetUpload(ctx, f.owner, f.share.ID, true); err != nil {
		t.Fatal(err)
	}
	if err := f.shares.Revoke(ctx, f.owner, f.share.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.su.EnsureFolder(ctx, f.guest, f.folder, "z", ""); errCode(err) != "SHARE_EXPIRED" {
		t.Fatalf("mkdir after revoke = %v", err)
	}
}

func TestChangeHubDeliversFolderChanges(t *testing.T) {
	f := newCollectFixture(t)
	ctx := t.Context()
	hub := service.NewChangeHub(f.pool)
	ch, cancel, ok := hub.Subscribe(f.owner)
	if !ok {
		t.Fatal("subscribe refused")
	}
	defer cancel()
	go hub.Run(ctx)

	// 监听连接起来之前的改动收不到:每隔一会儿建一个,直到收到为止
	deadline := time.After(10 * time.Second)
	tick := time.NewTicker(200 * time.Millisecond)
	defer tick.Stop()
	for i := 0; ; i++ {
		select {
		case c := <-ch:
			if c.Topic != "files" || !slices.Contains(c.Folders, f.folder.String()) {
				t.Fatalf("change = %+v", c)
			}
			hub.Close()
			for range ch { // 关机后通道关闭;剩下的只能是前面多建的文件夹
			}
			return
		case <-tick.C:
			if _, err := f.nodes.CreateFolder(ctx, f.owner, &f.folder, "n"+string(rune('a'+i%26))+uuid.NewString()[:4]); err != nil {
				t.Fatal(err)
			}
		case <-deadline:
			t.Fatal("no change delivered")
		}
	}
}
