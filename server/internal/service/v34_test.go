package service_test

// v3.4(doc/22):通知、直链与图床、影音的集成测试。真 Postgres + MinIO;推送的接收端是本机 httptest。

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/pressly/goose/v3"
	"golang.org/x/text/encoding/simplifiedchinese"

	"github.com/yophon/gopan/server/db"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type v34Fixture struct {
	*jobsFixture
	q      *store.Queries
	notify *service.Notifier
	links  *service.Links
	media  *service.Media
	cfg    service.LinkConfig
}

func newV34Fixture(t *testing.T, mod func(*service.LinkConfig)) *v34Fixture {
	t.Helper()
	f := &v34Fixture{jobsFixture: newJobsFixture(t, loopbackFetch())}
	f.q = store.New(f.pool)
	f.notify = service.NewNotifier(f.pool, fetcher.Guard{AllowLoopback: true}, "https://drive.test")
	f.cfg = service.LinkConfig{Enabled: true, DailyBytes: 5 << 30, MonthlyBytes: 200 << 30, ImgMaxSize: 20 << 20, PublicURL: "https://drive.test", Loc: time.UTC}
	if mod != nil {
		mod(&f.cfg)
	}
	f.links = service.NewLinks(f.pool, f.obj, f.uploads, f.notify, f.cfg)
	f.media = service.NewMedia(f.pool, f.obj)
	f.uploads.SetNotifier(f.notify)
	f.jobs.SetNotifier(f.notify)
	return f
}

func (f *v34Fixture) put(t *testing.T, parent uuid.UUID, name string, content []byte) store.Node {
	t.Helper()
	key := "tmp/test/" + uuid.NewString()
	if err := f.obj.Put(t.Context(), key, bytes.NewReader(content), int64(len(content)), "application/octet-stream"); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(content)
	n, err := f.uploads.CommitStaged(t.Context(), service.StagedFile{Owner: f.owner, Parent: &parent, Name: name, Sha: hex.EncodeToString(sum[:]), Size: int64(len(content)), TmpKey: key})
	if err != nil {
		t.Fatal(err)
	}
	return *n
}

func (f *v34Fixture) notes(t *testing.T, owner uuid.UUID) []store.Notification {
	t.Helper()
	rows, _, err := f.notify.List(t.Context(), owner, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	return rows
}

func (f *v34Fixture) kinds(t *testing.T, owner uuid.UUID) []string {
	var out []string
	for _, n := range f.notes(t, owner) {
		out = append(out, n.Kind)
	}
	return out
}

func pngBytes(t *testing.T, c color.Color) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 4, 4))
	for x := 0; x < 4; x++ {
		img.Set(x, x, c)
	}
	var b bytes.Buffer
	if err := png.Encode(&b, img); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// gpsJPEG 一个带 EXIF GPS 的「JPEG」(只有头,足够走图床的类型判断与 GPS 抹除)。
func gpsJPEG(extra string) []byte {
	var tiff bytes.Buffer
	tiff.WriteString("II")
	w := func(v any) { _ = binary.Write(&tiff, binary.LittleEndian, v) }
	w(uint16(42))
	w(uint32(8))
	w(uint16(1))
	w(uint16(0x8825))
	w(uint16(4))
	w(uint32(1))
	w(uint32(8 + 2 + 12 + 4))
	w(uint32(0))
	w(uint16(1))
	w(uint16(2))
	w(uint16(5))
	w(uint32(1))
	w(uint32(8 + 2 + 12 + 4 + 2 + 12 + 4))
	w(uint32(0))
	w(uint32(31))
	w(uint32(1))
	seg := append([]byte("Exif\x00\x00"), tiff.Bytes()...)
	var b bytes.Buffer
	b.Write([]byte{0xFF, 0xD8, 0xFF, 0xE1})
	_ = binary.Write(&b, binary.BigEndian, uint16(len(seg)+2))
	b.Write(seg)
	b.Write([]byte{0xFF, 0xDA, 0x00, 0x02})
	b.WriteString(extra)
	b.Write([]byte{0xFF, 0xD9})
	return b.Bytes()
}

type hookServer struct {
	*httptest.Server
	mu     sync.Mutex
	bodies [][]byte
	sigs   []string
	status int
}

func newHook(t *testing.T) *hookServer {
	h := &hookServer{status: http.StatusOK}
	h.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		h.mu.Lock()
		h.bodies = append(h.bodies, b)
		h.sigs = append(h.sigs, r.Header.Get("X-Gopan-Signature"))
		st := h.status
		h.mu.Unlock()
		w.WriteHeader(st)
		_, _ = w.Write([]byte("nope"))
	}))
	t.Cleanup(h.Close)
	return h
}

func (h *hookServer) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.bodies)
}

func TestNotifyMergeDeliveryAndChannels(t *testing.T) {
	f := newV34Fixture(t, nil)
	ctx := t.Context()
	hook := newHook(t)

	ch, err := f.notify.CreateChannel(ctx, f.owner, service.ChannelInput{
		Kind: service.ChWebhook, Name: "我的 hook", Enabled: true, URL: hook.URL,
		Events: []string{service.EvCollectUpload, service.EvJobFailed, service.EvJobFailed}, Secret: ptr("s3cret"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(ch.Events) != 2 {
		t.Fatalf("事件去重:%v", ch.Events)
	}
	req := uuid.New()
	tgt := &service.Target{Kind: service.TgFileRequest, ID: req.String()}
	f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvCollectUpload, Level: service.LvInfo, Title: "「作业」收到新文件", Body: "张三 上传了 a.jpg", Target: tgt, GroupKey: "collect:" + req.String()})
	f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvCollectUpload, Level: service.LvInfo, Title: "「作业」收到新文件", Body: "李四 上传了 b.jpg", Target: tgt, GroupKey: "collect:" + req.String()})
	f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvJobFailed, Level: service.LvWarn, Title: "离线下载失败"})
	f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvJobDone, Level: service.LvInfo, Title: "离线下载完成"}) // 没订阅
	notes := f.notes(t, f.owner)
	if len(notes) != 3 {
		t.Fatalf("合并后应是 3 条,got %d", len(notes))
	}
	merged := notes[2]
	if merged.Count != 2 || merged.Body != "李四 上传了 b.jpg" {
		t.Fatalf("合并不对:%+v", merged)
	}
	if n, _ := f.notify.Unread(ctx, f.owner); n != 3 {
		t.Fatalf("未读 %d", n)
	}
	if got := f.notify.DeliverDue(ctx); got != 2 {
		t.Fatalf("应投递 2 条(合并的只推第一次、没订阅的不推),got %d", got)
	}
	if hook.count() != 2 {
		t.Fatalf("webhook 收到 %d 条", hook.count())
	}
	var first map[string]any
	_ = json.Unmarshal(hook.bodies[0], &first)
	mac := hmac.New(sha256.New, []byte("s3cret"))
	mac.Write(hook.bodies[0])
	if hook.sigs[0] != "sha256="+hex.EncodeToString(mac.Sum(nil)) {
		t.Fatal("签名不对")
	}
	if first["url"] != "https://drive.test/shares?tab=requests&id="+req.String() || first["kind"] != service.EvCollectUpload {
		t.Fatalf("推送内容:%v", first)
	}
	got, _ := f.notify.Channels(ctx, f.owner)
	if !got[0].LastOkAt.Valid {
		t.Fatal("成功后应记 last_ok_at")
	}

	// Once:时间窗内同组只发一次
	for range 2 {
		f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvQuota, Level: service.LvWarn, Title: "满了", GroupKey: "quota:90", Once: time.Hour})
	}
	if c := strings.Count(strings.Join(f.kinds(t, f.owner), ","), service.EvQuota); c != 1 {
		t.Fatalf("Once 去重失败:%d", c)
	}

	// 已读 / 清空
	if n, _ := f.notify.MarkRead(ctx, f.owner, []int64{notes[0].ID}); n != 1 {
		t.Fatal("标记一条")
	}
	if n, _ := f.notify.MarkRead(ctx, f.owner, nil); n != 3 {
		t.Fatalf("全部已读应再标 3 条,got %d", n)
	}
	if n, _ := f.notify.Unread(ctx, f.owner); n != 0 {
		t.Fatal("应全部已读")
	}
	// 已读之后同组不再合并,另起一条
	f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvCollectUpload, Level: service.LvInfo, Title: "又来了", Target: tgt, GroupKey: "collect:" + req.String()})
	if n, _ := f.notify.Unread(ctx, f.owner); n != 1 {
		t.Fatal("已读的不再合并")
	}

	// 失败重试:500 → 排到 30 秒后,第 4 次仍失败记 failed
	hook.mu.Lock()
	hook.status = http.StatusInternalServerError
	hook.mu.Unlock()
	f.notify.DeliverDue(ctx)
	var status string
	var attempts int32
	if err := f.pool.QueryRow(ctx, `SELECT status, attempts FROM notify_deliveries ORDER BY id DESC LIMIT 1`).Scan(&status, &attempts); err != nil {
		t.Fatal(err)
	}
	if status != "pending" || attempts != 1 {
		t.Fatalf("第一次失败后应等重试:%s %d", status, attempts)
	}
	for range 3 {
		if _, err := f.pool.Exec(ctx, `UPDATE notify_deliveries SET next_at = now() WHERE status = 'pending'`); err != nil {
			t.Fatal(err)
		}
		f.notify.DeliverDue(ctx)
	}
	if err := f.pool.QueryRow(ctx, `SELECT status, attempts FROM notify_deliveries ORDER BY id DESC LIMIT 1`).Scan(&status, &attempts); err != nil {
		t.Fatal(err)
	}
	if status != "failed" || attempts != 4 {
		t.Fatalf("重试 3 次后应失败:%s %d", status, attempts)
	}
	got, _ = f.notify.Channels(ctx, f.owner)
	if got[0].LastError == nil || !strings.Contains(*got[0].LastError, "HTTP 500") {
		t.Fatalf("渠道应记错误:%v", got[0].LastError)
	}
	if err := f.notify.TestChannel(ctx, f.owner, ch.ID); !isCode(err, "NOTIFY_FAILED") {
		t.Fatalf("测试失败应报 NOTIFY_FAILED:%v", err)
	}
	hook.mu.Lock()
	hook.status = http.StatusNoContent
	hook.mu.Unlock()
	if err := f.notify.TestChannel(ctx, f.owner, ch.ID); err != nil {
		t.Fatal(err)
	}

	// 每个渠道每小时最多 20 条
	if _, err := f.pool.Exec(ctx, `DELETE FROM notify_deliveries`); err != nil {
		t.Fatal(err)
	}
	for i := range 25 {
		f.notify.Notify(ctx, f.owner, service.Event{Kind: service.EvJobFailed, Level: service.LvWarn, Title: "失败 " + string(rune('a'+i))})
	}
	var cnt int
	_ = f.pool.QueryRow(ctx, `SELECT count(*) FROM notify_deliveries`).Scan(&cnt)
	if cnt != 20 {
		t.Fatalf("每小时上限 20,got %d", cnt)
	}

	// 密钥:不传保持,空串清除
	up, err := f.notify.UpdateChannel(ctx, f.owner, ch.ID, service.ChannelInput{Kind: service.ChWebhook, Name: "改名", Enabled: false, URL: hook.URL, Events: []string{service.EvJobFailed}})
	if err != nil || up.Secret != "s3cret" || up.Enabled || up.Name != "改名" {
		t.Fatalf("更新:%+v %v", up, err)
	}
	up, _ = f.notify.UpdateChannel(ctx, f.owner, ch.ID, service.ChannelInput{Kind: service.ChWebhook, Name: "改名", Enabled: true, URL: hook.URL, Events: []string{}, Secret: ptr("")})
	if up.Secret != "" {
		t.Fatal("空串应清除密钥")
	}

	// 校验
	bad := []service.ChannelInput{
		{Kind: service.ChWebhook, Name: "x", URL: ""},
		{Kind: service.ChWebhook, Name: "x", URL: "ftp://a"},
		{Kind: service.ChNtfy, Name: "x", Topic: "带 空格"},
		{Kind: service.ChBark, Name: "x"},
		{Kind: service.ChTelegram, Name: "x", Secret: ptr("t")},
		{Kind: "email", Name: "x"},
		{Kind: service.ChNtfy, Name: "", Topic: "a"},
		{Kind: service.ChNtfy, Name: "x", Topic: "a", Events: []string{"nope"}},
		{Kind: service.ChNtfy, Name: "x", Topic: "a", Events: []string{service.EvBackup}}, // 非管理员不能订阅备份
	}
	for i, in := range bad {
		if _, err := f.notify.CreateChannel(ctx, f.owner, in); !isCode(err, "INVALID_INPUT") {
			t.Errorf("bad[%d] 应拒绝:%v", i, err)
		}
	}
	ok, err := f.notify.CreateChannel(ctx, f.owner, service.ChannelInput{Kind: service.ChNtfy, Name: "ntfy", Topic: "gopan_me-1", Enabled: true})
	if err != nil || ok.Url != "https://ntfy.sh" {
		t.Fatalf("ntfy 默认服务器:%+v %v", ok, err)
	}
	// 推不到内网:正常的 Guard 拦回环
	strict := service.NewNotifier(f.pool, fetcher.Guard{}, "")
	if err := strict.TestChannel(ctx, f.owner, ch.ID); !isCode(err, "NOTIFY_FAILED") {
		t.Fatalf("回环地址应被拦:%v", err)
	}
	if err := f.notify.DeleteChannel(ctx, f.owner, ok.ID); err != nil {
		t.Fatal(err)
	}
	if err := f.notify.DeleteChannel(ctx, f.owner, ok.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatal("删两次应 NOT_FOUND")
	}
	if n, _ := f.notify.Clear(ctx, f.owner); n == 0 {
		t.Fatal("清空")
	}
}

func TestNotifyEvents(t *testing.T) {
	f := newV34Fixture(t, nil)
	ctx := t.Context()
	auth := newAuth(f.pool)
	auth.SetNotifier(f.notify)

	// 登录
	res, err := auth.Register(ctx, "ev_user", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	if len(f.notes(t, res.User.ID)) != 0 {
		t.Fatal("注册不提醒")
	}
	lctx := service.WithClientMeta(ctx, service.ClientMeta{IP: "9.9.9.9", UA: "gopan-android/3.4.0 (Android 14; Pixel 8)"})
	if _, err := auth.Login(lctx, "ev_user", "password123", "9.9.9.9"); err != nil {
		t.Fatal(err)
	}
	ns := f.notes(t, res.User.ID)
	if len(ns) != 1 || ns[0].Kind != service.EvLogin || !strings.Contains(ns[0].Title, "Pixel 8") || !strings.Contains(ns[0].Body, "9.9.9.9") {
		t.Fatalf("登录提醒:%+v", ns)
	}

	// 新凭据
	tokens := service.NewMCPTokens(f.q)
	tokens.SetNotifier(f.notify)
	if _, _, err := tokens.Create(ctx, res.User.ID, "PicGo", []string{service.ScopeImgUpload}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := tokens.Create(ctx, res.User.ID, "混用", []string{service.ScopeImgUpload, "files:read"}); !isCode(err, "INVALID_INPUT") {
		t.Fatalf("图床密钥不能混用:%v", err)
	}
	ns = f.notes(t, res.User.ID)
	var tg service.Target
	_ = json.Unmarshal(ns[0].Target, &tg)
	if ns[0].Kind != service.EvCredential || tg.ID != "img" || !strings.Contains(ns[0].Title, "图床密钥") {
		t.Fatalf("凭据提醒:%+v %+v", ns[0], tg)
	}

	// 离线下载失败
	src := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.NotFound(w, r) }))
	defer src.Close()
	f.submit(t, src.URL+"/missing.bin")
	f.runOnce(t)
	if ks := f.kinds(t, f.owner); len(ks) == 0 || ks[0] != service.EvJobFailed {
		t.Fatalf("任务失败应提醒:%v", ks)
	}

	// 配额 90%
	if _, err := f.pool.Exec(ctx, `UPDATE users SET quota_bytes = 1000 WHERE id = $1`, f.owner); err != nil {
		t.Fatal(err)
	}
	f.put(t, f.folder, "big.bin", randBytes(950))
	f.put(t, f.folder, "big2.bin", randBytes(20))
	quota := 0
	for _, k := range f.kinds(t, f.owner) {
		if k == service.EvQuota {
			quota++
		}
	}
	if quota != 1 {
		t.Fatalf("90%% 提醒应只有一次,got %d", quota)
	}

	// 备份心跳:报过平安且超过 36 小时 → 提醒管理员;没报过的实例不提醒
	if _, err := f.pool.Exec(ctx, `UPDATE users SET is_admin = true WHERE id = $1`, res.User.ID); err != nil {
		t.Fatal(err)
	}
	f.notify.CheckHeartbeats(ctx)
	before := len(f.notes(t, res.User.ID))
	if err := f.notify.Heartbeat(ctx, "backup"); err != nil {
		t.Fatal(err)
	}
	f.notify.CheckHeartbeats(ctx)
	if len(f.notes(t, res.User.ID)) != before {
		t.Fatal("刚报过平安不该提醒")
	}
	if _, err := f.pool.Exec(ctx, `UPDATE instance_state SET updated_at = now() - interval '37 hours'`); err != nil {
		t.Fatal(err)
	}
	f.notify.CheckHeartbeats(ctx)
	f.notify.CheckHeartbeats(ctx)
	ns = f.notes(t, res.User.ID)
	if len(ns) != before+1 || ns[0].Kind != service.EvBackup || ns[0].Level != service.LvAlert {
		t.Fatalf("备份超时提醒(每天一次):%d → %d", before, len(ns))
	}
	kinds := service.EventKindsFor(false)
	for _, k := range kinds {
		if k.AdminOnly {
			t.Fatal("非管理员不该看到管理员事件")
		}
	}
}

func TestLinksServeAndRules(t *testing.T) {
	f := newV34Fixture(t, nil)
	ctx := t.Context()
	pic := f.put(t, f.folder, "猫.png", pngBytes(t, color.White))
	if _, err := f.links.CreateLinks(ctx, f.owner, []uuid.UUID{f.folder}, service.LinkMeta{Source: "web"}); !isCode(err, "INVALID_INPUT") {
		t.Fatalf("文件夹不能生成直链:%v", err)
	}
	vs, err := f.links.CreateLinks(ctx, f.owner, []uuid.UUID{pic.ID}, service.LinkMeta{Source: "web", IP: "1.2.3.4"})
	if err != nil {
		t.Fatal(err)
	}
	l := vs[0]
	again, _ := f.links.CreateLinks(ctx, f.owner, []uuid.UUID{pic.ID}, service.LinkMeta{Source: "web"})
	if again[0].ID != l.ID {
		t.Fatal("同一个文件应返回已有直链")
	}
	if f.links.URL(l.Token, l.NodeName) != "https://drive.test/x/"+l.Token+"/%E7%8C%AB.png" || f.links.Status(l) != service.LinkActive {
		t.Fatalf("地址或状态:%s %s", f.links.URL(l.Token, l.NodeName), f.links.Status(l))
	}
	hit := service.HitInfo{IP: "5.5.5.5"}
	open := func(h service.HitInfo) (*service.LinkFile, *service.LinkDenied) {
		file, d := f.links.Open(ctx, l.Token, "", h)
		if file != nil {
			file.Release()
		}
		return file, d
	}
	if file, d := open(hit); d != nil || file.Mime != "image/png" || file.Name != "猫.png" {
		t.Fatalf("应放行:%+v %+v", file, d)
	}
	if _, d := f.links.Open(ctx, "nope", "", hit); d == nil || d.Status != 404 || d.LinkID != uuid.Nil {
		t.Fatal("不存在的 token 404 且不记账")
	}

	// 来源白名单
	hosts := []string{"example.com"}
	if _, err := f.links.Update(ctx, f.owner, l.ID, service.LinkSettings{RefererHosts: &hosts, AllowNoReferer: ptr(false)}); err != nil {
		t.Fatal(err)
	}
	if _, d := open(service.HitInfo{IP: "5.5.5.5", RefererHost: "blog.example.com"}); d != nil {
		t.Fatal("白名单子域应放行")
	}
	if _, d := open(service.HitInfo{IP: "5.5.5.5", RefererHost: "evil.com"}); d == nil || d.Status != 403 {
		t.Fatal("不在白名单应 403")
	}
	if _, d := open(hit); d == nil || d.Status != 403 {
		t.Fatal("不允许无来源时应 403")
	}
	if _, d := open(service.HitInfo{IP: "5.5.5.5", RefererHost: "drive.test"}); d != nil {
		t.Fatal("自己的站点总能引用")
	}
	// 回到默认规则:默认白名单为空、允许无来源
	if _, err := f.links.Update(ctx, f.owner, l.ID, service.LinkSettings{}); err != nil {
		t.Fatal(err)
	}
	if _, err := f.links.SetPrefs(ctx, f.owner, service.LinkPrefs{RefererHosts: []string{"blog.cn"}, AllowNoReferer: true, ImageFolder: "/图床", StripGPS: true}); err != nil {
		t.Fatal(err)
	}
	if _, d := open(service.HitInfo{IP: "5.5.5.5", RefererHost: "other.cn"}); d == nil || d.Status != 403 {
		t.Fatal("默认规则应生效")
	}
	if _, err := f.links.SetPrefs(ctx, f.owner, service.LinkPrefs{AllowNoReferer: true, ImageFolder: "/图床", StripGPS: true}); err != nil {
		t.Fatal(err)
	}

	// 暂停、日上限
	if v, _ := f.links.Pause(ctx, f.owner, l.ID, true); f.links.Status(v) != service.LinkPaused {
		t.Fatal("暂停")
	}
	if _, d := open(hit); d == nil || d.Status != 503 {
		t.Fatal("暂停中 503")
	}
	_, _ = f.links.Pause(ctx, f.owner, l.ID, false)
	if _, err := f.links.Update(ctx, f.owner, l.ID, service.LinkSettings{DailyBytesCap: ptr(int64(1))}); !isCode(err, "INVALID_INPUT") {
		t.Fatal("日上限至少 1 MB")
	}
	if _, err := f.links.Update(ctx, f.owner, l.ID, service.LinkSettings{DailyBytesCap: ptr(int64(1 << 20))}); err != nil {
		t.Fatal(err)
	}
	if _, d := open(hit); d != nil { // 第一次问日用量:从库里读底数(0)
		t.Fatal(d)
	}
	f.links.Record(l.ID, f.owner, hit, 200, 2<<20)
	if _, d := open(hit); d == nil || d.Status != 503 {
		t.Fatal("超过日上限应 503")
	}
	v, _ := f.links.Get(ctx, f.owner, l.ID)
	if f.links.Status(v) != service.LinkPaused || v.PauseReason == nil || *v.PauseReason != "daily_cap" {
		t.Fatalf("应按日上限暂停:%+v", v.PauseReason)
	}
	// 过了一天自动恢复
	if _, err := f.pool.Exec(ctx, `UPDATE links SET paused_at = now() - interval '2 days' WHERE id = $1`, l.ID); err != nil {
		t.Fatal(err)
	}
	f.links.Check(ctx)
	if v, _ := f.links.Get(ctx, f.owner, l.ID); v.PausedAt.Valid {
		t.Fatal("跨天应恢复")
	}

	// 统计落库
	for _, h := range []string{"a.com", "a.com", "b.com", ""} {
		f.links.Record(l.ID, f.owner, service.HitInfo{IP: "6.6.6.6", UA: "curl", Referer: "https://" + h + "/p", RefererHost: h}, 200, 10)
	}
	f.links.Record(l.ID, f.owner, service.HitInfo{IP: "7.7.7.7", RefererHost: "evil.com"}, 403, 0)
	f.links.Check(ctx) // 内含 flush
	stats, err := f.links.Stats(ctx, f.owner, l.ID, 24)
	if err != nil || len(stats) != 1 {
		t.Fatalf("按小时统计:%v %v", stats, err)
	}
	if stats[0].Hits != 5 || stats[0].Blocked != 1 || stats[0].Ips != 3 { // 加上日上限那次 2 MB 的
		t.Fatalf("计数不对:%+v", stats[0])
	}
	refs, _ := f.links.Referers(ctx, f.owner, l.ID, 30)
	byHost := map[string]int64{}
	for _, r := range refs {
		byHost[r.Host] = r.Hits
	}
	if byHost["a.com"] != 2 || byHost["b.com"] != 1 || byHost[""] != 2 || byHost["evil.com"] != 1 {
		t.Fatalf("来源排行:%+v", refs)
	}
	hits, _, _ := f.links.Hits(ctx, f.owner, l.ID, nil)
	if len(hits) != 6 || hits[0].Status != 403 {
		t.Fatalf("访问明细:%d", len(hits))
	}
	v, _ = f.links.Get(ctx, f.owner, l.ID)
	if v.HitsTotal != 5 || v.Usage.TodayHits != 5 || v.Usage.TodayBlocked != 1 || !v.LastHitAt.Valid {
		t.Fatalf("累计:%+v %+v", v.HitsTotal, v.Usage)
	}
	list, _, total, _ := f.links.List(ctx, f.owner, nil)
	if total != 1 || len(list) != 1 || list[0].Usage.Hits != 5 {
		t.Fatalf("列表:%d %+v", total, list)
	}

	// 每天最多 50 个来源,其余并进 "*"
	for i := range 60 {
		f.links.Record(l.ID, f.owner, service.HitInfo{IP: "8.8.8.8", RefererHost: "site" + string(rune('A'+i)) + ".com"}, 200, 1)
	}
	f.links.Check(ctx)
	var hostsN int
	_ = f.pool.QueryRow(ctx, `SELECT count(*) FROM link_referers WHERE link_id = $1`, l.ID).Scan(&hostsN)
	if hostsN != 51 {
		t.Fatalf("来源应封顶 50 + *,got %d", hostsN)
	}

	// 文件进回收站 → 不可用;吊销 → 404
	if err := f.nodes.Delete(ctx, f.owner, []uuid.UUID{pic.ID}); err != nil {
		t.Fatal(err)
	}
	if _, d := open(hit); d == nil || d.Status != 404 {
		t.Fatal("文件在回收站应 404")
	}
	if v, _ := f.links.Get(ctx, f.owner, l.ID); f.links.Status(v) != service.LinkUnavailable {
		t.Fatal("状态应 UNAVAILABLE")
	}
	if _, err := f.nodes.Restore(ctx, f.owner, []uuid.UUID{pic.ID}); err != nil {
		t.Fatal(err)
	}
	if err := f.links.Revoke(ctx, f.owner, l.ID); err != nil {
		t.Fatal(err)
	}
	if _, d := open(hit); d == nil || d.Status != 404 {
		t.Fatal("吊销后立即 404")
	}
	if nl, _ := f.links.NodeLink(ctx, f.owner, pic.ID); nl != nil {
		t.Fatal("吊销后没有有效直链")
	}
	nv, _ := f.links.CreateLinks(ctx, f.owner, []uuid.UUID{pic.ID}, service.LinkMeta{Source: "web"})
	if nv[0].Token == l.Token {
		t.Fatal("吊销后重建应换 token")
	}
}

func TestLinksAnomaliesAndMonthlyCap(t *testing.T) {
	f := newV34Fixture(t, func(c *service.LinkConfig) { c.MonthlyBytes = 1000 })
	ctx := t.Context()
	pic := f.put(t, f.folder, "a.png", pngBytes(t, color.Black))
	vs, err := f.links.CreateLinks(ctx, f.owner, []uuid.UUID{pic.ID}, service.LinkMeta{Source: "web"})
	if err != nil {
		t.Fatal(err)
	}
	l := vs[0]
	if _, err := f.links.Update(ctx, f.owner, l.ID, service.LinkSettings{AutoPause: true}); err != nil {
		t.Fatal(err)
	}
	// 突增:这一小时 400 次,平时为 0
	if _, err := f.pool.Exec(ctx, `INSERT INTO link_stats (link_id, hour, hits, bytes) VALUES ($1, date_trunc('hour', now()), 400, 0)`, l.ID); err != nil {
		t.Fatal(err)
	}
	// 陌生来源:一小时 101 次
	for range 101 {
		f.links.Record(l.ID, f.owner, service.HitInfo{IP: "1.1.1.1", RefererHost: "forum.test"}, 200, 0)
	}
	f.links.Check(ctx)
	f.links.Check(ctx) // 6 小时内不重复
	var spike, ref int
	for _, n := range f.notes(t, f.owner) {
		switch {
		case strings.HasPrefix(n.Title, "直链访问突增"):
			spike++
			if n.Level != service.LvAlert || !strings.Contains(n.Body, "自动暂停") {
				t.Fatalf("开了自动暂停:%+v", n)
			}
		case strings.HasPrefix(n.Title, "有站点在大量引用"):
			ref++
			if !strings.Contains(n.Body, "forum.test") {
				t.Fatal(n.Body)
			}
		}
	}
	if spike != 1 || ref != 1 {
		t.Fatalf("突增 %d、陌生来源 %d", spike, ref)
	}
	if v, _ := f.links.Get(ctx, f.owner, l.ID); !v.PausedAt.Valid || *v.PauseReason != "anomaly" {
		t.Fatal("应按异常自动暂停")
	}
	_, _ = f.links.Pause(ctx, f.owner, l.ID, false)

	// 单 IP 狂刷:十分钟 600 次(提醒是异步的)
	for range 600 {
		f.links.Record(l.ID, f.owner, service.HitInfo{IP: "6.6.6.6"}, 200, 0)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		found := false
		for _, n := range f.notes(t, f.owner) {
			if strings.HasPrefix(n.Title, "有 IP 在狂刷直链") && strings.Contains(n.Body, "6.6.6.6") {
				found = true
			}
		}
		if found {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("没等到单 IP 提醒")
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 本月全站上限 1000 字节:用完后所有直链 503
	if _, d := f.links.Open(ctx, l.Token, "", service.HitInfo{IP: "2.2.2.2"}); d != nil {
		t.Fatal(d)
	} else {
		f.links.Record(l.ID, f.owner, service.HitInfo{IP: "2.2.2.2"}, 200, 2000)
	}
	f.links.Check(ctx)
	if _, d := f.links.Open(ctx, l.Token, "", service.HitInfo{IP: "2.2.2.2"}); d == nil || d.Status != 503 {
		t.Fatal("本月流量用完应 503")
	}
	if v, _ := f.links.Get(ctx, f.owner, l.ID); f.links.Status(v) != service.LinkUnavailable {
		t.Fatal("状态应 UNAVAILABLE")
	}
	if f.links.MonthlyUsed() < 2000 {
		t.Fatal("本月用量")
	}
}

func TestImageUpload(t *testing.T) {
	f := newV34Fixture(t, func(c *service.LinkConfig) { c.ImgMaxSize = 1 << 20 })
	ctx := t.Context()
	up := func(name string, body []byte) (*service.ImageResult, error) {
		return f.links.UploadImage(ctx, service.ImageUpload{Owner: f.owner, Name: name, Body: bytes.NewReader(body), Meta: service.LinkMeta{Source: "api"}})
	}
	res, err := up("image.png", pngBytes(t, color.White))
	if err != nil {
		t.Fatal(err)
	}
	month := time.Now().UTC().Format("2006-01")
	if !strings.HasPrefix(res.URL, "https://drive.test/x/") || !strings.HasPrefix(res.Markdown, "![") || strings.HasPrefix(res.Name, "image") {
		t.Fatalf("返回:%+v", res)
	}
	n, err := f.nodes.GetWithBlob(ctx, f.owner, res.NodeID)
	if err != nil {
		t.Fatal(err)
	}
	parent, _ := f.q.GetNode(ctx, *n.ParentID)
	grand, _ := f.q.GetNode(ctx, *parent.ParentID)
	if parent.Name != month || grand.Name != "图床" || grand.ParentID != nil {
		t.Fatalf("应在 /图床/%s:%s / %s", month, grand.Name, parent.Name)
	}

	// 带 GPS 的 JPEG:默认抹掉,长度不变
	j := gpsJPEG(strings.Repeat("x", 1000))
	res, err = up("家.jpg", j)
	if err != nil {
		t.Fatal(err)
	}
	n, _ = f.nodes.GetWithBlob(ctx, f.owner, res.NodeID)
	r, _ := f.obj.Open(ctx, objstore.BlobKey(*n.BlobSha256))
	stored, _ := io.ReadAll(r)
	r.Close()
	if len(stored) != len(j) || service.JPEGGPS(stored, false) || res.Size != int64(len(j)) {
		t.Fatal("GPS 应被抹掉且长度不变")
	}
	// 关掉抹除:保留并标出
	if _, err := f.links.SetPrefs(ctx, f.owner, service.LinkPrefs{AllowNoReferer: true, ImageFolder: "/blog/img", StripGPS: false}); err != nil {
		t.Fatal(err)
	}
	res, err = up("家2.jpg", gpsJPEG("y"))
	if err != nil {
		t.Fatal(err)
	}
	v, _ := f.links.Get(ctx, f.owner, res.LinkID)
	if !v.HasGps {
		t.Fatal("没抹的应标 has_gps")
	}
	n, _ = f.nodes.GetWithBlob(ctx, f.owner, res.NodeID)
	parent, _ = f.q.GetNode(ctx, *n.ParentID)
	grand, _ = f.q.GetNode(ctx, *parent.ParentID)
	if grand.Name != "img" {
		t.Fatal("换了图床文件夹")
	}

	if _, err := up("a.pdf", []byte("%PDF-1.7 ...")); !isCode(err, "UNSUPPORTED_TYPE") {
		t.Fatalf("非图片:%v", err)
	}
	big := append(pngBytes(t, color.White), make([]byte, 2<<20)...)
	if _, err := up("big.png", big); !isCode(err, "TOO_LARGE") {
		t.Fatalf("超大:%v", err)
	}
	if _, err := f.links.SetPrefs(ctx, f.owner, service.LinkPrefs{ImageFolder: "/"}); !isCode(err, "INVALID_INPUT") {
		t.Fatal("图床文件夹不能是根目录")
	}
}

func TestMediaProgressPlaylistSubtitles(t *testing.T) {
	f := newV34Fixture(t, nil)
	ctx := t.Context()
	for _, name := range []string{"ep10.mp4", "ep2.mp4", "ep1.mkv", "歌.mp3", "notes.txt", "doc.pdf"} {
		f.put(t, f.folder, name, append([]byte(name), randBytes(64)...)) // 内容各不相同,免得去重成同一份(mime 记在内容上)
	}
	gbk, _ := simplifiedchinese.GBK.NewEncoder().String("1\r\n00:00:01,000 --> 00:00:02,000\r\n你好,世界\r\n")
	zh := f.put(t, f.folder, "ep1.zh.srt", []byte(gbk))
	f.put(t, f.folder, "EP1.eng.ass", []byte("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,hi\n"))
	f.put(t, f.folder, "ep2.srt", []byte("1\n00:00:01,000 --> 00:00:02,000\nx\n"))

	children := map[string]uuid.UUID{}
	rows, err := f.q.ListFolderMedia(ctx, store.ListFolderMediaParams{OwnerID: f.owner, ParentID: &f.folder, MimePrefix: ""})
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range rows {
		children[r.Node.Name] = r.Node.ID
	}

	pl, err := f.media.Playlist(ctx, f.owner, children["ep2.mp4"], false)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, r := range pl {
		names = append(names, r.Node.Name)
	}
	if strings.Join(names, ",") != "ep1.mkv,ep2.mp4,ep10.mp4" {
		t.Fatalf("连播列表:%v", names)
	}
	if pl, _ := f.media.Playlist(ctx, f.owner, children["歌.mp3"], false); len(pl) != 1 {
		t.Fatal("音频列表只有一首")
	}
	if pl, _ := f.media.Playlist(ctx, f.owner, children["ep2.mp4"], true); len(pl) != 1 {
		t.Fatal("onlySelf 只给它自己")
	}

	subs, err := f.media.Subtitles(ctx, f.owner, &f.folder, "ep1.mkv")
	if err != nil || len(subs) != 2 {
		t.Fatalf("ep1 的字幕:%+v %v", subs, err)
	}
	if subs[0].Lang != "zh-Hans" || !subs[0].Default || subs[1].Lang != "en" || subs[1].Default {
		t.Fatalf("中文在前且默认:%+v", subs)
	}
	if s, _ := f.media.Subtitles(ctx, f.owner, &f.folder, "ep10.mp4"); len(s) != 0 {
		t.Fatal("ep10 没有字幕(ep1 的不算)")
	}
	vtt, err := f.media.SubtitleVTT(ctx, f.owner, zh.ID)
	if err != nil || !strings.HasPrefix(vtt, "WEBVTT") || !strings.Contains(vtt, "你好,世界") || !strings.Contains(vtt, "00:00:01.000") {
		t.Fatalf("GBK 的 srt 转 VTT:%q %v", vtt, err)
	}
	if _, err := f.media.SubtitleVTT(ctx, f.owner, children["notes.txt"]); !isCode(err, "UNSUPPORTED_TYPE") {
		t.Fatal("不是字幕")
	}
	// 文件夹里只有一个视频:名字对不上的字幕也算它的
	sub, _ := f.nodes.CreateFolder(ctx, f.owner, &f.folder, "电影")
	f.put(t, sub.ID, "Movie.2024.1080p.mp4", randBytes(100))
	f.put(t, sub.ID, "随便.srt", []byte("1\n00:00:01,000 --> 00:00:02,000\nx\n"))
	if s, _ := f.media.Subtitles(ctx, f.owner, &sub.ID, "Movie.2024.1080p.mp4"); len(s) != 1 || s[0].Label != "字幕" {
		t.Fatalf("单视频文件夹:%+v", s)
	}

	// 进度
	vid := children["ep1.mkv"]
	if p, _ := f.media.SaveProgress(ctx, f.owner, vid, 30, 120); p != nil {
		t.Fatal("5 分钟以下不记")
	}
	p, err := f.media.SaveProgress(ctx, f.owner, vid, 100, 3600)
	if err != nil || p == nil || p.Finished || p.Position != 100 {
		t.Fatalf("记进度:%+v %v", p, err)
	}
	if p, _ := f.media.SaveProgress(ctx, f.owner, vid, 9999, 3600); !p.Finished || p.Position != 3600 {
		t.Fatal("超出时长夹到末尾并算看完")
	}
	_, _ = f.media.SaveProgress(ctx, f.owner, vid, 1500, 3600)
	doc := children["doc.pdf"]
	if p, _ := f.media.SaveProgress(ctx, f.owner, doc, 1, 2); p != nil {
		t.Fatal("3 页以下不记")
	}
	if p, _ := f.media.SaveProgress(ctx, f.owner, doc, 4.6, 10); p == nil || p.Position != 5 || p.Finished {
		t.Fatal("PDF 页码取整")
	}
	if p, _ := f.media.SaveProgress(ctx, f.owner, children["notes.txt"], 1, 1000); p != nil {
		t.Fatal("文本不记")
	}
	recent, _ := f.media.RecentPlays(ctx, f.owner, 20)
	if len(recent) != 2 || recent[0].Node.ID != doc {
		t.Fatalf("最近播放:%d", len(recent))
	}
	_, _ = f.media.SaveProgress(ctx, f.owner, doc, 10, 10)
	if recent, _ := f.media.RecentPlays(ctx, f.owner, 20); len(recent) != 1 {
		t.Fatal("看完的不在最近播放里")
	}
	if err := f.media.ClearProgress(ctx, f.owner, vid); err != nil {
		t.Fatal(err)
	}
	if p, _ := f.media.Progress(ctx, f.owner, vid); p != nil {
		t.Fatal("清掉了")
	}
}

// 迁移 00024 的存量回填:octet-stream 的 mkv、audio/webm 的 webm 改正并补排派生任务;别的不动。
func TestMigration00024MimeBackfill(t *testing.T) {
	url := os.Getenv("TEST_DB_URL")
	if url == "" {
		t.Skip("TEST_DB_URL 未设置")
	}
	sqlDB, err := sql.Open("pgx", url)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		t.Fatal(err)
	}
	goose.SetBaseFS(db.Migrations)
	goose.SetLogger(goose.NopLogger())
	_ = goose.SetDialect("postgres")
	if err := goose.UpTo(sqlDB, "migrations", 23); err != nil {
		t.Fatal(err)
	}
	user := uuid.New()
	if _, err := sqlDB.Exec(`INSERT INTO users (id, username, password_hash) VALUES ($1, 'm', 'x')`, user); err != nil {
		t.Fatal(err)
	}
	blob := func(mime string, verified bool) uuid.UUID {
		id := uuid.New()
		if _, err := sqlDB.Exec(`INSERT INTO blobs (id, sha256, size, mime, ref_count, verified) VALUES ($1, $2, 1, $3, 1, $4)`, id, id.String(), mime, verified); err != nil {
			t.Fatal(err)
		}
		return id
	}
	node := func(name string, b uuid.UUID) {
		if _, err := sqlDB.Exec(`INSERT INTO nodes (id, owner_id, name, kind, blob_id) VALUES ($1, $2, $3, 'file', $4)`, uuid.New(), user, name, b); err != nil {
			t.Fatal(err)
		}
	}
	mkv, webm, ts, unverified, keep := blob("application/octet-stream", true), blob("audio/webm", true), blob("application/octet-stream", true), blob("application/octet-stream", false), blob("video/mp4", true)
	node("电影.MKV", mkv)
	node("片段.webm", webm)
	node("index.ts", ts)
	node("录音.aac", unverified)
	node("a.mp4", keep)
	if err := goose.UpTo(sqlDB, "migrations", 24); err != nil {
		t.Fatal(err)
	}
	mimeOf := func(id uuid.UUID) string {
		var m string
		_ = sqlDB.QueryRow(`SELECT mime FROM blobs WHERE id = $1`, id).Scan(&m)
		return m
	}
	tasks := func(id uuid.UUID) string {
		rows, _ := sqlDB.Query(`SELECT kind FROM tasks WHERE blob_id = $1 ORDER BY kind`, id)
		defer rows.Close()
		var ks []string
		for rows.Next() {
			var k string
			_ = rows.Scan(&k)
			ks = append(ks, k)
		}
		return strings.Join(ks, ",")
	}
	if mimeOf(mkv) != "video/x-matroska" || tasks(mkv) != "media_probe,video_cover" {
		t.Fatalf("mkv:%s %s", mimeOf(mkv), tasks(mkv))
	}
	if mimeOf(webm) != "video/webm" || tasks(webm) != "media_probe,video_cover" {
		t.Fatalf("webm:%s %s", mimeOf(webm), tasks(webm))
	}
	if mimeOf(ts) != "application/octet-stream" || tasks(ts) != "" {
		t.Fatal(".ts 是 TypeScript,不动")
	}
	if mimeOf(unverified) != "audio/aac" || tasks(unverified) != "" {
		t.Fatalf("没校验的只改 mime,派生物等校验后再排:%s %s", mimeOf(unverified), tasks(unverified))
	}
	if mimeOf(keep) != "video/mp4" || tasks(keep) != "" {
		t.Fatal("本来就对的不动")
	}
	if err := goose.Up(sqlDB, "migrations"); err != nil {
		t.Fatal(err)
	}
}
