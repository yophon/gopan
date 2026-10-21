package service_test

// 后台任务与离线下载(doc/21 二、三)的集成测试:真 Postgres + MinIO,源站是本机 httptest。

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
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

type jobsFixture struct {
	pool    *pgxpool.Pool
	obj     *objstore.Store
	nodes   *service.Nodes
	uploads *service.Uploads
	jobs    *service.Jobs
	owner   uuid.UUID
	folder  uuid.UUID
}

func newJobsFixture(t *testing.T, cfg service.FetchConfig) *jobsFixture {
	t.Helper()
	pool, obj := setup(t), setupS3(t)
	f := &jobsFixture{pool: pool, obj: obj, nodes: service.NewNodes(pool)}
	f.uploads = service.NewUploads(pool, obj, f.nodes, 5<<20, time.Hour)
	if cfg.MaxSize == 0 {
		cfg.MaxSize = 1 << 30
	}
	f.jobs = service.NewJobs(pool, obj, f.uploads, cfg)
	f.jobs.SetTiming(100*time.Millisecond, 2*time.Second)
	res, err := newAuth(pool).Register(t.Context(), "jb_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	f.owner = res.User.ID
	folder, err := f.nodes.CreateFolder(t.Context(), f.owner, nil, "下载")
	if err != nil {
		t.Fatal(err)
	}
	f.folder = folder.ID
	return f
}

func loopbackFetch() service.FetchConfig {
	return service.FetchConfig{Enabled: true, Guard: fetcher.Guard{AllowLoopback: true}}
}

func (f *jobsFixture) submit(t *testing.T, urls ...string) store.UserJob {
	t.Helper()
	rows, err := f.jobs.FetchURLs(t.Context(), f.owner, &f.folder, urls, "web")
	if err != nil {
		t.Fatal(err)
	}
	return rows[0]
}

func (f *jobsFixture) runOnce(t *testing.T) {
	t.Helper()
	ok, err := f.jobs.RunOnce(t.Context())
	if err != nil || !ok {
		t.Fatalf("RunOnce: ok=%v err=%v", ok, err)
	}
}

func (f *jobsFixture) get(t *testing.T, id uuid.UUID) store.UserJob {
	t.Helper()
	j, err := f.jobs.Get(t.Context(), f.owner, id)
	if err != nil {
		t.Fatal(err)
	}
	return j
}

func (f *jobsFixture) usedBytes(t *testing.T) int64 {
	t.Helper()
	u, err := store.New(f.pool).GetUserByID(t.Context(), f.owner)
	if err != nil {
		t.Fatal(err)
	}
	return u.UsedBytes
}

func (f *jobsFixture) tmpGone(t *testing.T, id uuid.UUID) {
	t.Helper()
	if _, err := f.obj.Stat(t.Context(), "tmp/jobs/"+id.String()); err == nil {
		t.Fatal("暂存对象没清掉")
	}
}

func code(j store.UserJob) string {
	if j.ErrorCode == nil {
		return ""
	}
	return *j.ErrorCode
}

func randBytes(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i*31 + i/97)
	}
	return b
}

func TestFetchJobDownloads(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	data := randBytes(3<<20 + 123)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", `attachment; filename*=UTF-8''%E5%90%88%E5%90%8C.pdf`)
		w.Header().Set("Content-Type", "application/pdf")
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
	}))
	defer srv.Close()

	j := f.submit(t, srv.URL+"/dl?id=1&sig=secret")
	if j.Status != "queued" || j.Title != "127.0.0.1" || j.Kind != service.JobFetch {
		t.Fatalf("提交后:%+v", j)
	}
	f.runOnce(t)
	j = f.get(t, j.ID)
	if j.Status != "done" || j.ResultNodeID == nil || j.Title != "合同.pdf" || j.BytesDone != int64(len(data)) ||
		j.BytesTotal == nil || *j.BytesTotal != int64(len(data)) || j.FinishedAt.Valid == false {
		t.Fatalf("完成后:%+v code=%s", j, code(j))
	}
	n, err := f.nodes.GetWithBlob(t.Context(), f.owner, *j.ResultNodeID)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(data)
	if n.Name != "合同.pdf" || n.ParentID == nil || *n.ParentID != f.folder || *n.BlobSize != int64(len(data)) || *n.BlobSha256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("节点:%+v", n)
	}
	var verified bool
	var tasks int
	if err := f.pool.QueryRow(t.Context(), `SELECT b.verified, (SELECT count(*) FROM tasks t WHERE t.blob_id = b.id AND t.kind = 'verify_hash')
		FROM blobs b WHERE b.id = $1`, n.BlobID).Scan(&verified, &tasks); err != nil {
		t.Fatal(err)
	}
	if !verified || tasks != 1 {
		t.Fatalf("blob 应直接记为已校验并排一个 verify_hash 触发派生物:verified=%v tasks=%d", verified, tasks)
	}
	if f.usedBytes(t) != int64(len(data)) {
		t.Fatalf("用量 %d", f.usedBytes(t))
	}
	f.tmpGone(t, j.ID)

	// 同一份内容再下一次:秒传式只加引用,撞名自动改名
	j2 := f.submit(t, srv.URL+"/dl?id=1")
	f.runOnce(t)
	j2 = f.get(t, j2.ID)
	if j2.Status != "done" || j2.Title != "合同 (1).pdf" {
		t.Fatalf("第二次:%+v code=%s", j2, code(j2))
	}
	if f.usedBytes(t) != 2*int64(len(data)) {
		t.Fatalf("配额按逻辑大小各记各的:%d", f.usedBytes(t))
	}
	var blobs int
	f.pool.QueryRow(t.Context(), `SELECT count(*) FROM blobs`).Scan(&blobs)
	if blobs != 1 {
		t.Fatalf("同内容应只存一份,blobs=%d", blobs)
	}
}

// 没有长度(chunked)、没有文件名:名字取 URL 最后一段;断线后不能续传的从头来过。
func TestFetchJobChunked(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	data := randBytes(700 << 10)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for i := 0; i < len(data); i += 64 << 10 {
			w.Write(data[i:min(i+64<<10, len(data))])
			w.(http.Flusher).Flush()
		}
	}))
	defer srv.Close()
	j := f.submit(t, srv.URL+"/files/%E5%8F%91%E7%A5%A8.bin")
	f.runOnce(t)
	j = f.get(t, j.ID)
	if j.Status != "done" || j.Title != "发票.bin" || j.BytesTotal != nil || j.BytesDone != int64(len(data)) {
		t.Fatalf("%+v code=%s", j, code(j))
	}
}

func TestFetchJobQuota(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	if _, err := f.pool.Exec(t.Context(), `UPDATE users SET quota_bytes = $2 WHERE id = $1`, f.owner, 1<<20); err != nil {
		t.Fatal(err)
	}
	data := randBytes(2 << 20)
	var bodyBytes atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/sized" {
			w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		}
		for i := 0; i < len(data); i += 32 << 10 {
			n, err := w.Write(data[i:min(i+32<<10, len(data))])
			bodyBytes.Add(int64(n))
			if err != nil {
				return
			}
			w.(http.Flusher).Flush()
		}
	}))
	defer srv.Close()

	// 有长度:开始前就拒
	j := f.submit(t, srv.URL+"/sized")
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "failed" || code(j) != "QUOTA_EXCEEDED" || j.Attempts != 1 {
		t.Fatalf("有长度:%+v code=%s", j, code(j))
	}
	// 无长度:读到超出剩余空间时停下
	j = f.submit(t, srv.URL+"/chunked")
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "failed" || code(j) != "QUOTA_EXCEEDED" {
		t.Fatalf("无长度:%+v code=%s", j, code(j))
	}
	if j.BytesDone > 1<<20 {
		t.Fatalf("超出配额后还在读:%d", j.BytesDone)
	}
	f.tmpGone(t, j.ID)
	if f.usedBytes(t) != 0 {
		t.Fatal("失败的下载不该占用量")
	}

	// 单文件上限
	g := newJobsFixture(t, service.FetchConfig{Enabled: true, MaxSize: 1 << 20, Guard: fetcher.Guard{AllowLoopback: true}})
	j = g.submit(t, srv.URL+"/sized")
	g.runOnce(t)
	if j = g.get(t, j.ID); code(j) != "FILE_TOO_LARGE" {
		t.Fatalf("单文件上限:%+v code=%s", j, code(j))
	}
}

// 默认 Guard 连不上回环:终态失败,不重试。
func TestFetchJobBlocked(t *testing.T) {
	f := newJobsFixture(t, service.FetchConfig{Enabled: true})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("x")) }))
	defer srv.Close()
	for _, u := range []string{srv.URL + "/a", strings.Replace(srv.URL, "127.0.0.1", "localhost", 1), "http://10.0.0.1/x", "http://169.254.169.254/latest/meta-data/"} {
		j := f.submit(t, u)
		f.runOnce(t)
		if j = f.get(t, j.ID); j.Status != "failed" || code(j) != "FETCH_BLOCKED" || j.Attempts != 1 {
			t.Fatalf("%s:%+v code=%s", u, j, code(j))
		}
		if j.ErrorMessage != nil && strings.Contains(*j.ErrorMessage, "/latest") {
			t.Fatal("错误信息里不该带链接路径")
		}
	}
}

func TestFetchJobHTTPErrors(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/404":
			http.NotFound(w, r)
		case "/503":
			http.Error(w, "busy", http.StatusServiceUnavailable)
		case "/page.zip":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Write([]byte("<html>请先登录</html>"))
		}
	}))
	defer srv.Close()

	j := f.submit(t, srv.URL+"/404")
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "failed" || code(j) != "FETCH_HTTP_STATUS" {
		t.Fatalf("404:%+v code=%s", j, code(j))
	}
	// 5xx 是源站临时出问题:交还队列、按次数退避
	j = f.submit(t, srv.URL+"/503")
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "queued" || j.Attempts != 1 || code(j) != "JOB_ERROR" || !strings.Contains(*j.ErrorMessage, "503") {
		t.Fatalf("503:%+v code=%s", j, code(j))
	}
	if ok, _ := f.jobs.RunOnce(t.Context()); ok {
		t.Fatal("退避期内不该再被领走")
	}
	// 拿到网页:照常完成,带提示
	if _, err := f.jobs.Cancel(t.Context(), f.owner, j.ID); err != nil {
		t.Fatal(err)
	}
	j = f.submit(t, srv.URL+"/page.zip")
	f.runOnce(t)
	if j = f.get(t, j.ID); j.Status != "done" || code(j) != "GOT_HTML" || j.Title != "page.zip" {
		t.Fatalf("网页:%+v code=%s", j, code(j))
	}
}

func TestFetchJobCancelRunning(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(10<<20))
		w.Write(make([]byte, 64<<10))
		w.(http.Flusher).Flush()
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer srv.Close()
	defer close(release)
	j := f.submit(t, srv.URL+"/big.iso")
	done := make(chan struct{})
	go func() {
		defer close(done)
		f.jobs.RunOnce(t.Context())
	}()
	deadline := time.Now().Add(5 * time.Second)
	for f.get(t, j.ID).BytesDone == 0 {
		if time.Now().After(deadline) {
			t.Fatal("心跳没写进度")
		}
		time.Sleep(50 * time.Millisecond)
	}
	if got := f.get(t, j.ID); got.Status != "running" || got.Title != "big.iso" || got.BytesTotal == nil {
		t.Fatalf("运行中:%+v", got)
	}
	if _, err := f.jobs.Cancel(t.Context(), f.owner, j.ID); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("取消后没有停下")
	}
	if j = f.get(t, j.ID); j.Status != "canceled" || j.ResultNodeID != nil {
		t.Fatalf("%+v", j)
	}
	f.tmpGone(t, j.ID)
	// 已结束的不能再取消;取消的能重试
	if _, err := f.jobs.Cancel(t.Context(), f.owner, j.ID); !isCode(err, "JOB_FINISHED") {
		t.Fatalf("got %v", err)
	}
	if r, err := f.jobs.Retry(t.Context(), f.owner, j.ID); err != nil || r.Status != "queued" || r.Attempts != 0 {
		t.Fatalf("retry: %+v %v", r, err)
	}
}

func TestJobLimitsAndManagement(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	ctx := t.Context()
	many := make([]string, 11)
	for i := range many {
		many[i] = "https://example.com/" + strconv.Itoa(i)
	}
	if _, err := f.jobs.FetchURLs(ctx, f.owner, &f.folder, many, "web"); !isCode(err, "INVALID_INPUT") {
		t.Fatalf("11 条应拒,got %v", err)
	}
	if _, err := f.jobs.FetchURLs(ctx, f.owner, &f.folder, []string{"ftp://x/y"}, "web"); !isCode(err, "INVALID_URL") {
		t.Fatalf("ftp 应拒,got %v", err)
	}
	file := mustNode(t, f, "a.txt")
	if _, err := f.jobs.FetchURLs(ctx, f.owner, &file, []string{"https://example.com/a"}, "web"); !isCode(err, "NOT_A_FOLDER") {
		t.Fatalf("目标是文件应拒,got %v", err)
	}
	if _, err := f.jobs.FetchURLs(ctx, f.owner, &f.folder, many[:10], "android"); err != nil {
		t.Fatal(err)
	}
	if _, err := f.jobs.FetchURLs(ctx, f.owner, nil, many[:1], "web"); !isCode(err, "TOO_MANY_JOBS") {
		t.Fatalf("超过 10 个应拒,got %v", err)
	}
	rows, next, err := f.jobs.List(ctx, f.owner, true, nil, 4)
	if err != nil || len(rows) != 4 || next == nil || rows[0].Source != "android" {
		t.Fatalf("分页:%d %v %v", len(rows), next, err)
	}
	rows2, _, err := f.jobs.List(ctx, f.owner, true, next, 50)
	if err != nil || len(rows2) != 6 || rows2[0].ID == rows[3].ID {
		t.Fatalf("第二页:%d %v", len(rows2), err)
	}
	// 别人看不到
	other, _ := newAuth(f.pool).Register(ctx, "jb_other", "password123", "1.1.1.1")
	if _, err := f.jobs.Get(ctx, other.User.ID, rows[0].ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("他人任务应 NOT_FOUND,got %v", err)
	}
	if _, err := f.jobs.Cancel(ctx, other.User.ID, rows[0].ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("他人取消应 NOT_FOUND,got %v", err)
	}
	for _, r := range append(rows, rows2...) {
		if _, err := f.jobs.Cancel(ctx, f.owner, r.ID); err != nil {
			t.Fatal(err)
		}
	}
	if n, err := f.jobs.ClearFinished(ctx, f.owner); err != nil || n != 10 {
		t.Fatalf("清除:%d %v", n, err)
	}

	off := newJobsFixture(t, service.FetchConfig{})
	if _, err := off.jobs.FetchURLs(ctx, off.owner, nil, many[:1], "web"); !isCode(err, "FETCH_DISABLED") {
		t.Fatalf("关掉时应拒,got %v", err)
	}
}

// 进程崩溃留下的 running 任务:2 分钟没心跳退回队列;第 3 次仍中断落到 failed。
func TestJobReap(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	a := f.submit(t, "https://example.com/a")
	b := f.submit(t, "https://example.com/b")
	if _, err := f.pool.Exec(t.Context(), `UPDATE user_jobs SET status = 'running', attempts = CASE WHEN id = $1 THEN 1 ELSE 3 END,
		heartbeat_at = now() - interval '3 minutes' WHERE id IN ($1, $2)`, a.ID, b.ID); err != nil {
		t.Fatal(err)
	}
	f.jobs.Reap(t.Context())
	if j := f.get(t, a.ID); j.Status != "queued" {
		t.Fatalf("a: %+v", j)
	}
	if j := f.get(t, b.ID); j.Status != "failed" || code(j) != "INTERRUPTED" {
		t.Fatalf("b: %+v", j)
	}
}

// 存进传输助手文件夹:会话里自动出现文件卡片,来源记为离线下载。
func TestFetchJobIntoChat(t *testing.T) {
	f := newJobsFixture(t, loopbackFetch())
	chats := service.NewChats(f.pool, f.nodes)
	folder, err := chats.EnsureFolder(t.Context(), f.owner)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("hello")) }))
	defer srv.Close()
	rows, err := f.jobs.FetchURLs(t.Context(), f.owner, &folder.ID, []string{srv.URL + "/note.txt"}, "android")
	if err != nil {
		t.Fatal(err)
	}
	f.runOnce(t)
	msgs, err := chats.ListRecent(t.Context(), f.owner, nil, 10)
	if err != nil || len(msgs) != 1 || msgs[0].NodeID == nil || msgs[0].Source != service.ChatSourceFetch {
		t.Fatalf("msgs=%+v err=%v", msgs, err)
	}
	if j := f.get(t, rows[0].ID); j.ResultNodeID == nil || *j.ResultNodeID != *msgs[0].NodeID {
		t.Fatalf("%+v", j)
	}
}

func mustNode(t *testing.T, f *jobsFixture, name string) uuid.UUID {
	t.Helper()
	key := "tmp/test/" + uuid.NewString()
	if err := f.obj.Put(t.Context(), key, strings.NewReader("x"), 1, "text/plain"); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256([]byte("x"))
	n, err := f.uploads.CommitStaged(t.Context(), service.StagedFile{Owner: f.owner, Parent: &f.folder, Name: name, Sha: hex.EncodeToString(sum[:]), Size: 1, TmpKey: key})
	if err != nil {
		t.Fatal(err)
	}
	return n.ID
}

func isCode(err error, c string) bool {
	var se *service.Error
	return errors.As(err, &se) && se.Code == c
}
