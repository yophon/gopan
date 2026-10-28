package service_test

// 收集文件(doc/20 第二节)的集成测试:真 Postgres + MinIO,走完整的
// 打开链接 → 开会话 → 分片 PUT → complete → worker 落库。

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type collectFixture struct {
	pool     *pgxpool.Pool
	obj      *objstore.Store
	auth     *service.Auth
	nodes    *service.Nodes
	uploads  *service.Uploads
	collects *service.Collects
	owner    uuid.UUID
	folder   uuid.UUID
}

func newCollectFixture(t *testing.T) *collectFixture {
	t.Helper()
	pool, obj := setup(t), setupS3(t)
	f := &collectFixture{pool: pool, obj: obj, auth: newAuth(pool), nodes: service.NewNodes(pool)}
	f.uploads = service.NewUploads(pool, obj, f.nodes, 5<<20, time.Hour)
	f.collects = service.NewCollects(pool, f.auth, f.uploads)
	f.collects.SetRateLimits(time.Millisecond, time.Millisecond, time.Millisecond, 1000)
	res, err := f.auth.Register(t.Context(), "co_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	f.owner = res.User.ID
	folder, err := f.nodes.CreateFolder(t.Context(), f.owner, nil, "收件箱")
	if err != nil {
		t.Fatal(err)
	}
	f.folder = folder.ID
	return f
}

func (f *collectFixture) create(t *testing.T, in service.FileRequestInput, password string) store.FileRequest {
	t.Helper()
	var pw *string
	if password != "" {
		pw = &password
	}
	r, err := f.collects.Create(t.Context(), f.owner, f.folder, in, pw)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func (f *collectFixture) open(t *testing.T, token, password, name string) *service.CollectGuest {
	t.Helper()
	access, err := f.collects.Open(t.Context(), token, password, name, "2.2.2.2")
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	return f.guest(t, access)
}

func (f *collectFixture) guest(t *testing.T, access string) *service.CollectGuest {
	t.Helper()
	ident, err := f.auth.ParseAccess(access)
	if err != nil {
		t.Fatal(err)
	}
	g, err := f.collects.Guest(t.Context(), ident)
	if err != nil {
		t.Fatalf("guest: %v", err)
	}
	return g
}

// stage 开会话并传完所有分片,返回会话。
func (f *collectFixture) stage(t *testing.T, g *service.CollectGuest, name string, data []byte) store.UploadSession {
	t.Helper()
	view, err := f.collects.InitUpload(t.Context(), g, name, int64(len(data)))
	if err != nil {
		t.Fatalf("init %s: %v", name, err)
	}
	ps := int(view.Session.PartSize)
	for part, url := range view.PartURLs {
		httpPut(t, url, data[(part-1)*ps:min(part*ps, len(data))])
	}
	return view.Session
}

// finish complete → worker 落库 → 再 complete 拿结果。
func (f *collectFixture) finish(t *testing.T, g *service.CollectGuest, id uuid.UUID) (*service.CollectUploadView, error) {
	t.Helper()
	v, err := f.collects.CompleteUpload(t.Context(), g, id, nil)
	var se *service.Error
	if !errors.As(err, &se) || se.Code != "UPLOAD_PROCESSING" {
		return v, err
	}
	if _, err := f.uploads.ProcessNextBrowserUpload(t.Context()); err != nil {
		return nil, err
	}
	return f.collects.CompleteUpload(t.Context(), g, id, nil)
}

func (f *collectFixture) upload(t *testing.T, g *service.CollectGuest, name string, data []byte) (*service.CollectUploadView, error) {
	t.Helper()
	return f.finish(t, g, f.stage(t, g, name, data).ID)
}

func (f *collectFixture) childNames(t *testing.T, parent uuid.UUID) map[string]store.ListChildrenRow {
	t.Helper()
	page, err := f.nodes.Children(t.Context(), f.owner, &parent, nil, "NAME", false)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]store.ListChildrenRow{}
	for _, n := range page.Items {
		out[n.Name] = n
	}
	return out
}

func TestCollectLifecycle(t *testing.T) {
	f := newCollectFixture(t)
	ctx := t.Context()
	r := f.create(t, service.FileRequestInput{Title: "  交作业  ", Note: "PDF 即可", RequireName: true}, "s3cret")
	if r.Title != "交作业" {
		t.Fatalf("标题应去掉首尾空白,got %q", r.Title)
	}

	info, err := f.collects.Info(ctx, r.Token)
	if err != nil || info.Title != "交作业" || !info.NeedPassword || !info.NeedName || info.Closed {
		t.Fatalf("info: %+v %v", info, err)
	}
	_, err = f.collects.Open(ctx, r.Token, "", "张三", "2.2.2.2")
	requireCode(t, err, "COLLECT_PASSWORD_REQUIRED")
	_, err = f.collects.Open(ctx, r.Token, "wrong", "张三", "2.2.2.2")
	requireCode(t, err, "BAD_COLLECT_PASSWORD")
	_, err = f.collects.Open(ctx, r.Token, "s3cret", "  ", "2.2.2.2")
	requireCode(t, err, "COLLECT_NAME_REQUIRED")

	g := f.open(t, r.Token, "s3cret", "张三")
	data := bytes.Repeat([]byte("collect-"), 1<<20) // 8MiB:两片(5MiB 一片)
	got, err := f.upload(t, g, "作业.pdf", data)
	if err != nil {
		t.Fatal(err)
	}
	if got.Name != "作业.pdf" || got.Size != int64(len(data)) {
		t.Fatalf("送达结果: %+v", got)
	}

	// 落在链接的文件夹里,内容正确,计入属主配额
	kids := f.childNames(t, f.folder)
	n, ok := kids["作业.pdf"]
	if !ok || n.BlobSha256 == nil || *n.BlobSha256 != shaHex(data) {
		t.Fatalf("文件应落在收集文件夹: %+v", kids)
	}
	requireObject(t, f.obj, objstore.BlobKey(shaHex(data)), data)
	user, err := store.New(f.pool).GetUserByID(ctx, f.owner)
	if err != nil || user.UsedBytes != int64(len(data)) {
		t.Fatalf("应计入属主配额: %+v %v", user, err)
	}

	// 访客看得到自己送达了什么
	mine, err := f.collects.MyUploads(ctx, g)
	if err != nil || len(mine) != 1 || mine[0].FileName != "作业.pdf" {
		t.Fatalf("my uploads: %+v %v", mine, err)
	}

	// 属主侧:列表统计、收件记录、已读
	row, err := f.collects.Get(ctx, f.owner, r.ID)
	if err != nil || row.FileCount != 1 || row.TotalBytes != int64(len(data)) || row.UploaderCount != 1 || row.NewCount != 1 {
		t.Fatalf("列表统计: %+v %v", row, err)
	}
	ups, err := f.collects.Uploads(ctx, f.owner, r.ID, nil, 50)
	if err != nil || len(ups) != 1 || ups[0].UploaderName != "张三" || ups[0].NodeID == nil || *ups[0].NodeID != n.ID ||
		ups[0].Ip == nil || *ups[0].Ip != "2.2.2.2" {
		t.Fatalf("收件记录: %+v %v", ups, err)
	}
	if err := f.collects.MarkSeen(ctx, f.owner, r.ID); err != nil {
		t.Fatal(err)
	}
	if row, _ = f.collects.Get(ctx, f.owner, r.ID); row.NewCount != 0 {
		t.Fatalf("已读后 newCount 应归零: %+v", row)
	}
	// 别人看不到这条链接的收件记录
	_, _, other := newUploads(t, f.pool, f.obj)
	if _, err := f.collects.Uploads(ctx, other, r.ID, nil, 50); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("非属主应 NOT_FOUND: %v", err)
	}

	// 文件删了,收件记录还在、节点为空
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{n.ID}); err != nil {
		t.Fatal(err)
	}
	ups, _ = f.collects.Uploads(ctx, f.owner, r.ID, nil, 50)
	if len(ups) != 1 || ups[0].NodeID != nil {
		t.Fatalf("文件进回收站后记录保留、不带节点: %+v", ups)
	}

	// 撤销:访客令牌立即失效,链接显示已关闭
	if err := f.collects.Revoke(ctx, f.owner, r.ID); err != nil {
		t.Fatal(err)
	}
	ident, _ := f.auth.ParseAccess(mustOpenToken(t, f, r.Token))
	_, err = f.collects.Guest(ctx, ident)
	requireCode(t, err, "COLLECT_CLOSED")
	if info, _ := f.collects.Info(ctx, r.Token); !info.Closed {
		t.Fatal("撤销后 info 应显示已关闭")
	}
}

// mustOpenToken 模拟"失效前拿到的"访客令牌:失效后 Open 本身就会被拒,所以直接签一张同 scope 的。
func mustOpenToken(t *testing.T, f *collectFixture, token string) string {
	t.Helper()
	r, err := store.New(f.pool).GetFileRequestByToken(t.Context(), token)
	if err != nil {
		t.Fatal(err)
	}
	v, err := store.New(f.pool).CreateFileRequestVisitor(t.Context(), store.CreateFileRequestVisitorParams{
		ID: uuid.Must(uuid.NewV7()), RequestID: r.ID, Name: "x",
	})
	if err != nil {
		t.Fatal(err)
	}
	access, err := f.auth.IssueAccessFor(f.owner, service.CollectScope(r.ID, v.ID), uuid.Nil, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	return access
}

// 匿名访客撞名很常见:两个人同时传 IMG_0001.jpg,两个都落下,后一个自动改名。
func TestCollectNameCollision(t *testing.T) {
	f := newCollectFixture(t)
	r := f.create(t, service.FileRequestInput{Title: "照片"}, "")
	a := f.open(t, r.Token, "", "")
	b := f.open(t, r.Token, "", "")
	sa := f.stage(t, a, "IMG_0001.jpg", []byte("photo from a"))
	sb := f.stage(t, b, "IMG_0001.jpg", []byte("photo from b"))
	va, err := f.finish(t, a, sa.ID)
	if err != nil {
		t.Fatal(err)
	}
	vb, err := f.finish(t, b, sb.ID)
	if err != nil {
		t.Fatal(err)
	}
	if va.Name != "IMG_0001.jpg" || vb.Name != "IMG_0001 (1).jpg" {
		t.Fatalf("应自动改名: %q %q", va.Name, vb.Name)
	}
	kids := f.childNames(t, f.folder)
	if len(kids) != 2 {
		t.Fatalf("两个都应落下: %v", kids)
	}
}

func TestCollectGroupByUploader(t *testing.T) {
	f := newCollectFixture(t)
	r := f.create(t, service.FileRequestInput{Title: "作业", GroupByUploader: true}, "")
	if !r.RequireName {
		t.Fatal("按上传者分文件夹应强制留名")
	}
	_, err := f.collects.Open(t.Context(), r.Token, "", "", "2.2.2.2")
	requireCode(t, err, "COLLECT_NAME_REQUIRED")

	// 名字里的 / 不能变成路径
	zhang := f.open(t, r.Token, "", "张/三")
	li := f.open(t, r.Token, "", "李四")
	for _, c := range []struct {
		g    *service.CollectGuest
		name string
	}{{zhang, "1.txt"}, {zhang, "2.txt"}, {li, "1.txt"}} {
		if _, err := f.upload(t, c.g, c.name, []byte(c.name+uuid.NewString())); err != nil {
			t.Fatal(err)
		}
	}
	kids := f.childNames(t, f.folder)
	zf, ok1 := kids["张_三"]
	lf, ok2 := kids["李四"]
	if !ok1 || !ok2 || zf.Kind != "folder" || lf.Kind != "folder" || len(kids) != 2 {
		t.Fatalf("应按上传者建子文件夹: %v", kids)
	}
	if got := f.childNames(t, zf.ID); len(got) != 2 {
		t.Fatalf("张三的两个文件应在同一个子文件夹: %v", got)
	}
	if got := f.childNames(t, lf.ID); len(got) != 1 {
		t.Fatalf("李四的文件: %v", got)
	}
}

func TestCollectLimits(t *testing.T) {
	f := newCollectFixture(t)
	ctx := t.Context()
	maxFiles := int32(2)
	maxSize := int64(10)
	r := f.create(t, service.FileRequestInput{Title: "限额", MaxFiles: &maxFiles, MaxFileSize: &maxSize}, "")
	g := f.open(t, r.Token, "", "")

	_, err := f.collects.InitUpload(ctx, g, "big.bin", 11)
	requireCode(t, err, "FILE_TOO_LARGE")

	// 进行中的会话也占个数:开两个还没传完,第三个就满了
	s1 := f.stage(t, g, "a.txt", []byte("aaaa"))
	s2 := f.stage(t, g, "b.txt", []byte("bbbb"))
	_, err = f.collects.InitUpload(ctx, g, "c.txt", 4)
	requireCode(t, err, "COLLECT_FULL")
	for _, s := range []uuid.UUID{s1.ID, s2.ID} {
		if _, err := f.finish(t, g, s); err != nil {
			t.Fatal(err)
		}
	}
	// 文件删掉不退额度
	for _, n := range f.childNames(t, f.folder) {
		if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{n.ID}); err != nil {
			t.Fatal(err)
		}
	}
	_, err = f.collects.InitUpload(ctx, g, "d.txt", 4)
	requireCode(t, err, "COLLECT_FULL")

	// 总量上限
	maxTotal := int64(6)
	r2 := f.create(t, service.FileRequestInput{Title: "总量", MaxTotalBytes: &maxTotal}, "")
	g2 := f.open(t, r2.Token, "", "")
	if _, err := f.upload(t, g2, "x.txt", []byte("xxxx")); err != nil {
		t.Fatal(err)
	}
	_, err = f.collects.InitUpload(ctx, g2, "y.txt", 3)
	requireCode(t, err, "COLLECT_FULL")

	// 属主配额不足:访客只看到"收件已满",看不到配额
	execBrowserSQL(t, f.pool, `UPDATE users SET quota_bytes = used_bytes + 5 WHERE id = $1`, f.owner)
	r3 := f.create(t, service.FileRequestInput{Title: "配额"}, "")
	g3 := f.open(t, r3.Token, "", "")
	_, err = f.collects.InitUpload(ctx, g3, "q.bin", 6)
	requireCode(t, err, "COLLECT_FULL")
	// 预留:两个各 3 字节的会话,第二个就超了
	f.stage(t, g3, "q1.bin", []byte("123"))
	_, err = f.collects.InitUpload(ctx, g3, "q2.bin", 3)
	requireCode(t, err, "COLLECT_FULL")
	// 配额在落库前被调低:落库失败也只说"收件已满"
	s4 := f.stage(t, g3, "q3.bin", []byte("1"))
	execBrowserSQL(t, f.pool, `UPDATE users SET quota_bytes = used_bytes WHERE id = $1`, f.owner)
	_, err = f.finish(t, g3, s4.ID)
	requireCode(t, err, "COLLECT_FULL")

	// 访客的会话不占属主自己的名额
	n, err := store.New(f.pool).CountActiveSessions(ctx, f.owner)
	if err != nil || n != 0 {
		t.Fatalf("收集会话不应计入属主的会话数: %d %v", n, err)
	}
}

// 截止前开始的上传允许传完;截止后不能再开新的。撤销则立即拒绝落库。
func TestCollectDeadlineAndRevoke(t *testing.T) {
	f := newCollectFixture(t)
	ctx := t.Context()
	soon := time.Now().Add(time.Hour)
	r := f.create(t, service.FileRequestInput{Title: "截止", ExpiresAt: &soon}, "")
	g := f.open(t, r.Token, "", "")
	started := f.stage(t, g, "late.txt", []byte("started before deadline"))
	execBrowserSQL(t, f.pool, `UPDATE file_requests SET expires_at = now() WHERE id = $1`, r.ID)

	// 已经截止:新会话、新访客都被拒
	_, err := f.collects.InitUpload(ctx, g, "new.txt", 3)
	requireCode(t, err, "COLLECT_CLOSED")
	_, err = f.collects.Open(ctx, r.Token, "", "", "2.2.2.2")
	requireCode(t, err, "COLLECT_CLOSED")
	// 截止前开始的照常落下
	if _, err := f.finish(t, g, started.ID); err != nil {
		t.Fatalf("截止前开始的上传应能完成: %v", err)
	}

	r2 := f.create(t, service.FileRequestInput{Title: "撤销"}, "")
	g2 := f.open(t, r2.Token, "", "")
	s := f.stage(t, g2, "x.txt", []byte("revoked midway"))
	if _, err := f.collects.CompleteUpload(ctx, g2, s.ID, nil); err == nil {
		t.Fatal("应先返回处理中")
	}
	if err := f.collects.Revoke(ctx, f.owner, r2.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.uploads.ProcessNextBrowserUpload(ctx); err != nil {
		t.Fatal(err)
	}
	var code *string
	if err := f.pool.QueryRow(ctx, `SELECT fail_code FROM upload_sessions WHERE id = $1`, s.ID).Scan(&code); err != nil {
		t.Fatal(err)
	}
	if code == nil || *code != "COLLECT_CLOSED" {
		t.Fatalf("撤销后落库应失败为 COLLECT_CLOSED,got %v", code)
	}
	if _, ok := f.childNames(t, f.folder)["x.txt"]; ok {
		t.Fatal("撤销后不应落下文件")
	}

	// 目标文件夹进回收站:链接失效
	r3 := f.create(t, service.FileRequestInput{Title: "回收站"}, "")
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{f.folder}); err != nil {
		t.Fatal(err)
	}
	_, err = f.collects.Open(ctx, r3.Token, "", "", "2.2.2.2")
	requireCode(t, err, "COLLECT_CLOSED")
}

// 收集令牌只够往这条链接里传:读接口(分享访客那一套)一律拒绝;
// 同一链接的另一个访客碰不到别人的会话;分享令牌也冒充不了收集访客。
func TestCollectTokenIsolation(t *testing.T) {
	f := newCollectFixture(t)
	ctx := t.Context()
	r := f.create(t, service.FileRequestInput{Title: "隔离"}, "")
	access, err := f.collects.Open(ctx, r.Token, "", "", "2.2.2.2")
	if err != nil {
		t.Fatal(err)
	}
	ident, err := f.auth.ParseAccess(access)
	if err != nil {
		t.Fatal(err)
	}
	if ident.UserID != f.owner || ident.Scope == service.ScopeUser {
		t.Fatalf("令牌 sub 是属主、scope 不是 user: %+v", ident)
	}
	shares := service.NewShares(store.New(f.pool), f.auth)
	if err := shares.Authorize(ctx, ident, f.folder); !errors.Is(err, service.ErrForbidden) {
		t.Fatalf("收集令牌不能读文件夹: %v", err)
	}
	if _, err := shares.ValidateScope(ctx, ident); !errors.Is(err, service.ErrForbidden) {
		t.Fatalf("收集令牌不是分享令牌: %v", err)
	}

	a := f.guest(t, access)
	b := f.open(t, r.Token, "", "")
	sa := f.stage(t, a, "a.txt", []byte("visitor a"))
	if _, err := f.collects.UploadSession(ctx, b, sa.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("别的访客不能看会话: %v", err)
	}
	if _, err := f.collects.CompleteUpload(ctx, b, sa.ID, nil); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("别的访客不能完成会话: %v", err)
	}
	if err := f.collects.AbortUpload(ctx, b, sa.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("别的访客不能取消会话: %v", err)
	}
	// 属主自己的上传会话,访客按 id 也碰不到
	own := stageBrowser(t, f.uploads, f.owner, "own.txt", shaHex([]byte("own")), []byte("own"))
	if _, err := f.collects.UploadSession(ctx, a, own.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("访客不能碰属主的会话: %v", err)
	}

	// 分享令牌冒充不了收集访客
	sh, err := shares.Create(ctx, f.owner, f.folder, nil, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	shareAccess, err := shares.Access(ctx, sh.Token, "", "3.3.3.3")
	if err != nil {
		t.Fatal(err)
	}
	shareIdent, _ := f.auth.ParseAccess(shareAccess)
	if _, err := f.collects.Guest(ctx, shareIdent); !errors.Is(err, service.ErrForbidden) {
		t.Fatalf("分享令牌应被拒: %v", err)
	}
	// 用户令牌同样不行
	if _, err := f.collects.Guest(ctx, &service.Identity{UserID: f.owner, Scope: service.ScopeUser}); !errors.Is(err, service.ErrForbidden) {
		t.Fatalf("用户令牌应被拒: %v", err)
	}
	// 换了属主的令牌(sub 被篡改不可能,但 scope 指向别人的链接)也不行
	forged := &service.Identity{UserID: uuid.Must(uuid.NewV7()), Scope: ident.Scope}
	if _, err := f.collects.Guest(ctx, forged); !errors.Is(err, service.ErrForbidden) {
		t.Fatalf("属主不符应被拒: %v", err)
	}
}

// 分片预签名把长度签了进去:多传一个字节都会被对象存储拒绝。
func TestCollectPartLengthIsSigned(t *testing.T) {
	f := newCollectFixture(t)
	r := f.create(t, service.FileRequestInput{Title: "长度"}, "")
	g := f.open(t, r.Token, "", "")
	data := []byte("exactly this many bytes")
	view, err := f.collects.InitUpload(t.Context(), g, "a.txt", int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	url := view.PartURLs[1]
	for _, body := range [][]byte{append(bytes.Clone(data), '!'), data[:len(data)-1]} {
		req, _ := http.NewRequest(http.MethodPut, url, bytes.NewReader(body))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		msg, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			t.Fatalf("长度 %d 的请求体不应被接受", len(body))
		}
		if !strings.Contains(string(msg), "Signature") && resp.StatusCode != http.StatusForbidden {
			t.Logf("拒绝原因: %d %s", resp.StatusCode, msg)
		}
	}
	httpPut(t, url, data)
	if _, err := f.finish(t, g, view.Session.ID); err != nil {
		t.Fatal(err)
	}
}

// 密码错太多次:按链接锁住,换 IP 也没用,正确密码也要等。
func TestCollectPasswordLockout(t *testing.T) {
	f := newCollectFixture(t)
	f.collects.SetRateLimits(time.Millisecond, time.Hour, time.Millisecond, 3)
	r := f.create(t, service.FileRequestInput{Title: "锁定"}, "1234")
	for i := range 3 {
		_, err := f.collects.Open(t.Context(), r.Token, "0000", "", "9.9.9."+string(rune('1'+i)))
		requireCode(t, err, "BAD_COLLECT_PASSWORD")
	}
	_, err := f.collects.Open(t.Context(), r.Token, "1234", "", "8.8.8.8")
	requireCode(t, err, "RATE_LIMITED")
}

// 属主被管理员禁用:链接连带失效(与分享一致),重新启用后恢复。
func TestCollectOwnerDisabled(t *testing.T) {
	f := newCollectFixture(t)
	r := f.create(t, service.FileRequestInput{Title: "禁用"}, "")
	g := f.open(t, r.Token, "", "")
	execBrowserSQL(t, f.pool, `UPDATE users SET disabled_at = now() WHERE id = $1`, f.owner)
	ident, _ := f.auth.ParseAccess(mustOpenToken(t, f, r.Token))
	_, err := f.collects.Guest(t.Context(), ident)
	requireCode(t, err, "COLLECT_CLOSED")
	if info, _ := f.collects.Info(t.Context(), r.Token); !info.Closed {
		t.Fatal("属主被禁用时链接应显示已关闭")
	}
	execBrowserSQL(t, f.pool, `UPDATE users SET disabled_at = NULL WHERE id = $1`, f.owner)
	if _, err := f.upload(t, g, "back.txt", []byte("enabled again")); err != nil {
		t.Fatalf("重新启用后应恢复: %v", err)
	}
}

func TestCollectCreateValidation(t *testing.T) {
	f := newCollectFixture(t)
	ctx := t.Context()
	cases := []service.FileRequestInput{
		{Title: ""},
		{Title: strings.Repeat("长", 101)},
		{Title: "x", Note: strings.Repeat("长", 1001)},
		{Title: "x", MaxFileSize: ptr(int64(0))},
		{Title: "x", MaxFiles: ptr(int32(-1))},
		{Title: "x", ExpiresAt: ptr(time.Now().Add(-time.Minute))},
	}
	for _, in := range cases {
		_, err := f.collects.Create(ctx, f.owner, f.folder, in, nil)
		requireCode(t, err, "INVALID_INPUT")
	}
	// 只能对文件夹
	s := stageBrowser(t, f.uploads, f.owner, "f.txt", shaHex([]byte("f")), []byte("f"))
	n, err := completeBrowser(t, f.uploads, f.owner, s.ID)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.collects.Create(ctx, f.owner, n.ID, service.FileRequestInput{Title: "x"}, nil)
	requireCode(t, err, "NOT_A_FOLDER")
	// 别人的文件夹
	_, _, other := newUploads(t, f.pool, f.obj)
	if _, err := f.collects.Create(ctx, other, f.folder, service.FileRequestInput{Title: "x"}, nil); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("别人的文件夹应 NOT_FOUND: %v", err)
	}
	// 修改:整表单替换,别人改不了
	r := f.create(t, service.FileRequestInput{Title: "原标题"}, "")
	up, err := f.collects.Update(ctx, f.owner, r.ID, service.FileRequestInput{Title: "新标题", MaxFiles: ptr(int32(5))})
	if err != nil || up.Title != "新标题" || up.MaxFiles == nil || *up.MaxFiles != 5 {
		t.Fatalf("update: %+v %v", up, err)
	}
	if _, err := f.collects.Update(ctx, other, r.ID, service.FileRequestInput{Title: "x"}); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("别人改应 NOT_FOUND: %v", err)
	}
}
