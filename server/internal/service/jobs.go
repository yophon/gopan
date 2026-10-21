package service

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// 后台任务(doc/21 二):某个用户发起的一次长活,离线下载一个链接或解压一个压缩包。
// 与按 blob 的 tasks 分开:有属主、有进度、能取消,由独立的 runner 执行,不占缩略图和 hash 校验的 worker。
const (
	JobFetch   = "fetch_url"
	JobExtract = "extract_archive"

	maxActiveJobs  = 10 // 每人排队 + 进行中
	maxFetchURLs   = 10 // 一次提交的链接数
	jobsPageSize   = 50
	orderJobs      = "JOBS"
	jobStaleCheck  = 30 * time.Second
	jobMaxAttempts = 3
)

// FetchConfig 离线下载的开关与上限(doc/21 三.2)。
type FetchConfig struct {
	Enabled   bool
	MaxSize   int64 // 单文件上限
	Rate      int64 // 每个任务每秒最多读多少字节,0 = 不限
	Guard     fetcher.Guard
	UserAgent string
}

type Jobs struct {
	pool     *pgxpool.Pool
	q        *store.Queries
	obj      *objstore.Store
	uploads  *Uploads
	fetch    FetchConfig
	client   *http.Client
	archives *Archives
	wake     chan struct{}
	notify   *Notifier

	heartbeat time.Duration
	fetchIdle time.Duration // 测试缩短
	running   sync.Map      // job id → context.CancelFunc:取消时立刻停下本进程里正在跑的任务
}

func NewJobs(pool *pgxpool.Pool, obj *objstore.Store, uploads *Uploads, fetch FetchConfig) *Jobs {
	return &Jobs{
		pool: pool, q: store.New(pool), obj: obj, uploads: uploads, fetch: fetch,
		client: fetch.Guard.Client(), wake: make(chan struct{}, 1), heartbeat: 5 * time.Second,
	}
}

// SetTiming 测试用:缩短心跳间隔与离线下载的读空闲超时。
func (j *Jobs) SetTiming(heartbeat, fetchIdle time.Duration) {
	j.heartbeat, j.fetchIdle = heartbeat, fetchIdle
}

// SetArchives 解压任务要用压缩包服务(两者互相引用,启动时注入)。
func (j *Jobs) SetArchives(a *Archives) { j.archives = a }

// FetchEnabled 本站是否开了离线下载(界面据此显示入口)。
func (j *Jobs) FetchEnabled() bool { return j.fetch.Enabled }

func (j *Jobs) poke() {
	select {
	case j.wake <- struct{}{}:
	default:
	}
}

func jobTmpKey(id uuid.UUID) string { return "tmp/jobs/" + id.String() }

// ---- 提交与管理 ----

type fetchParams struct {
	URL string `json:"url"`
}

// FetchURLs 提交离线下载:每条链接一个任务,存到 parent(nil = 根目录)。
func (j *Jobs) FetchURLs(ctx context.Context, owner uuid.UUID, parent *uuid.UUID, urls []string, source string) ([]store.UserJob, error) {
	if !j.fetch.Enabled {
		return nil, errf("FETCH_DISABLED", "本站没有开启离线下载")
	}
	clean := make([]string, 0, len(urls))
	for _, raw := range urls {
		if raw == "" {
			continue
		}
		u, err := fetcher.ParseURL(raw)
		if err != nil {
			return nil, errf("INVALID_URL", "链接格式不对,只支持 http / https:%s", truncateRunes(raw, 80))
		}
		clean = append(clean, u.String())
	}
	if len(clean) == 0 {
		return nil, errf("INVALID_INPUT", "没有可下载的链接")
	}
	if len(clean) > maxFetchURLs {
		return nil, errf("INVALID_INPUT", "一次最多 %d 条链接", maxFetchURLs)
	}
	jobs := make([]JobSpec, 0, len(clean))
	for _, u := range clean {
		b, _ := json.Marshal(fetchParams{URL: u})
		jobs = append(jobs, JobSpec{Kind: JobFetch, Params: b, Title: fetcher.Host(u)})
	}
	return j.create(ctx, owner, parent, jobs, source)
}

// JobSpec 一个待建的任务。
type JobSpec struct {
	Kind       string
	Params     []byte
	Title      string
	BytesTotal *int64
	FilesTotal *int32
}

func (j *Jobs) create(ctx context.Context, owner uuid.UUID, parent *uuid.UUID, specs []JobSpec, source string) ([]store.UserJob, error) {
	tx, err := j.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	q := j.q.WithTx(tx)
	// 锁用户行,并发提交时个数上限也算得准
	if _, err := q.GetUserByIDForUpdate(ctx, owner); err != nil {
		return nil, err
	}
	if err := checkTargetFolder(ctx, q, owner, parent); err != nil {
		return nil, err
	}
	active, err := q.CountActiveJobs(ctx, owner)
	if err != nil {
		return nil, err
	}
	if active+int64(len(specs)) > maxActiveJobs {
		return nil, errf("TOO_MANY_JOBS", "排队中的后台任务过多(最多 %d 个),等前面的完成再来", maxActiveJobs)
	}
	if source == "" {
		source = ChatSourceWeb
	}
	out := make([]store.UserJob, 0, len(specs))
	for _, sp := range specs {
		row, err := q.CreateJob(ctx, store.CreateJobParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, Kind: sp.Kind, Params: sp.Params,
			ParentID: parent, Title: sp.Title, BytesTotal: sp.BytesTotal, FilesTotal: sp.FilesTotal, Source: source,
		})
		if err != nil {
			return nil, err
		}
		out = append(out, row)
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	j.poke()
	return out, nil
}

func (j *Jobs) Get(ctx context.Context, owner, id uuid.UUID) (store.UserJob, error) {
	row, err := j.q.GetJob(ctx, store.GetJobParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return row, ErrNotFound
	}
	return row, err
}

// List 新的在前;activeOnly 只列排队与进行中的。
func (j *Jobs) List(ctx context.Context, owner uuid.UUID, activeOnly bool, cursor *string, limit int) ([]store.UserJob, *string, error) {
	if limit <= 0 || limit > jobsPageSize {
		limit = jobsPageSize
	}
	p := store.ListJobsParams{OwnerID: owner, ActiveOnly: activeOnly, Lim: int32(limit)}
	if c := decodeCursor(cursor, orderJobs, false); c != nil {
		p.BeforeID = c.ID
		p.BeforeAt = pgtype.Timestamptz{Time: time.Unix(0, c.T), Valid: true}
	}
	rows, err := j.q.ListJobs(ctx, p)
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) == limit {
		last := rows[len(rows)-1]
		next = encodeCursor(cursorPayload{O: orderJobs, T: last.CreatedAt.Time.UnixNano(), ID: last.ID})
	}
	return rows, next, nil
}

// Cancel 排队的直接取消;正在跑的同时打断本进程里的执行。
func (j *Jobs) Cancel(ctx context.Context, owner, id uuid.UUID) (store.UserJob, error) {
	row, err := j.q.CancelJob(ctx, store.CancelJobParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		cur, gerr := j.Get(ctx, owner, id)
		if gerr != nil {
			return cur, gerr
		}
		return cur, errf("JOB_FINISHED", "任务已经结束")
	}
	if err != nil {
		return row, err
	}
	if c, ok := j.running.Load(id); ok {
		c.(context.CancelFunc)()
	}
	return row, nil
}

// Retry 失败或取消的任务重新排队。
func (j *Jobs) Retry(ctx context.Context, owner, id uuid.UUID) (store.UserJob, error) {
	if _, err := j.Get(ctx, owner, id); err != nil {
		return store.UserJob{}, err
	}
	active, err := j.q.CountActiveJobs(ctx, owner)
	if err != nil {
		return store.UserJob{}, err
	}
	if active >= maxActiveJobs {
		return store.UserJob{}, errf("TOO_MANY_JOBS", "排队中的后台任务过多(最多 %d 个),等前面的完成再来", maxActiveJobs)
	}
	row, err := j.q.RetryJob(ctx, store.RetryJobParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return row, errf("JOB_NOT_RETRYABLE", "只有失败或已取消的任务能重试")
	}
	if err != nil {
		return row, err
	}
	j.poke()
	return row, nil
}

// ClearFinished 清掉自己已结束的任务记录(不动下好的文件)。
func (j *Jobs) ClearFinished(ctx context.Context, owner uuid.UUID) (int64, error) {
	return j.q.DeleteFinishedJobs(ctx, owner)
}

// ---- 执行 ----

// Run 启动 n 个 runner 与收尾循环,阻塞到 ctx 取消。
func (j *Jobs) Run(ctx context.Context, n int) {
	for range n {
		go j.loop(ctx)
	}
	t := time.NewTicker(jobStaleCheck)
	defer t.Stop()
	last := time.Time{}
	for {
		j.Reap(ctx)
		if time.Since(last) > time.Hour {
			if n, err := j.q.CleanupOldJobs(ctx); err != nil && ctx.Err() == nil {
				slog.Error("job cleanup", "err", err)
			} else if n > 0 {
				slog.Info("job cleanup", "deleted", n)
			}
			last = time.Now()
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Reap 进程崩溃或重启留下的 running 任务:心跳停了 2 分钟就退回队列,第 3 次仍中断落到 failed。
func (j *Jobs) Reap(ctx context.Context) {
	if n, err := j.q.RequeueStaleJobs(ctx); err != nil {
		if ctx.Err() == nil {
			slog.Error("requeue stale jobs", "err", err)
		}
	} else if n > 0 {
		slog.Warn("requeued stale jobs", "count", n)
		j.poke()
	}
	ids, err := j.q.FailStaleJobs(ctx)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("fail stale jobs", "err", err)
		}
		return
	}
	for _, id := range ids {
		_ = j.obj.Remove(ctx, jobTmpKey(id))
		if job, err := j.q.GetJobByID(ctx, id); err == nil {
			j.notify.noteJob(ctx, job, false, "运行中断,重试次数已用完")
		}
	}
}

// RunOnce 领一个任务并执行完;没有可领的返回 false(测试与运维脚本用)。
func (j *Jobs) RunOnce(ctx context.Context) (bool, error) {
	job, err := j.q.ClaimJob(ctx)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	j.execute(ctx, job)
	return true, nil
}

func (j *Jobs) loop(ctx context.Context) {
	tick := time.NewTicker(15 * time.Second)
	defer tick.Stop()
	for {
		for ctx.Err() == nil {
			job, err := j.q.ClaimJob(ctx)
			if err != nil {
				if !errors.Is(err, pgx.ErrNoRows) && ctx.Err() == nil {
					slog.Error("claim job", "err", err)
				}
				break
			}
			j.execute(ctx, job)
		}
		select {
		case <-ctx.Done():
			return
		case <-j.wake:
		case <-tick.C:
		}
	}
}

// jobRun 一次执行的进度,心跳时写回。
type jobRun struct {
	job store.UserJob

	mu           sync.Mutex
	title        string
	bytesTotal   *int64
	filesDone    int32
	filesTotal   *int32
	filesSkipped int32
	bytesDone    atomic.Int64
	warning      *Error     // 成功但要提示的情况(比如拿到的是网页)
	result       *uuid.UUID // 失败时也写回的结果(解压到一半:已解出的文件在这个文件夹里)
}

func (r *jobRun) setTitle(t string) { r.mu.Lock(); r.title = t; r.mu.Unlock() }
func (r *jobRun) setBytesTotal(n int64) {
	r.mu.Lock()
	r.bytesTotal = &n
	r.mu.Unlock()
}
func (r *jobRun) setFilesTotal(n int32) {
	r.mu.Lock()
	r.filesTotal = &n
	r.mu.Unlock()
}
func (r *jobRun) fileDone()    { r.mu.Lock(); r.filesDone++; r.mu.Unlock() }
func (r *jobRun) fileSkipped() { r.mu.Lock(); r.filesSkipped++; r.mu.Unlock() }

func (r *jobRun) heartbeatParams() store.HeartbeatJobParams {
	r.mu.Lock()
	defer r.mu.Unlock()
	return store.HeartbeatJobParams{
		ID: r.job.ID, Title: r.title, BytesDone: r.bytesDone.Load(), BytesTotal: r.bytesTotal,
		FilesDone: r.filesDone, FilesTotal: r.filesTotal, FilesSkipped: r.filesSkipped,
	}
}

func (r *jobRun) finishParams(status string, result *uuid.UUID, e *Error) store.FinishJobParams {
	h := r.heartbeatParams()
	p := store.FinishJobParams{
		ID: r.job.ID, Status: status, ResultNodeID: result, Title: h.Title, BytesDone: h.BytesDone,
		BytesTotal: h.BytesTotal, FilesDone: h.FilesDone, FilesTotal: h.FilesTotal, FilesSkipped: h.FilesSkipped,
	}
	if e != nil {
		p.ErrorCode, p.ErrorMessage = &e.Code, &e.Message
	}
	return p
}

func (j *Jobs) execute(parent context.Context, job store.UserJob) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	j.running.Store(job.ID, cancel)
	defer j.running.Delete(job.ID)
	run := &jobRun{job: job, title: job.Title, bytesTotal: job.BytesTotal, filesTotal: job.FilesTotal}

	hbDone := make(chan struct{})
	go j.heartbeatLoop(ctx, run, cancel, hbDone)
	result, err := j.dispatch(ctx, run)
	cancel()
	<-hbDone

	fctx, fcancel := context.WithTimeout(context.WithoutCancel(parent), 10*time.Second)
	defer fcancel()
	defer func() { _ = j.obj.Remove(fctx, jobTmpKey(job.ID)) }()
	// 被取消、或心跳断太久被收尾退回了队列:状态已经不是 running,不再写结果
	if st, serr := j.q.GetJobStatus(fctx, job.ID); serr != nil || st != "running" {
		return
	}
	var se *Error
	switch {
	case err == nil:
		var n int64
		if n, err = j.q.FinishJob(fctx, run.finishParams("done", result, run.warning)); n > 0 {
			j.notify.noteJob(fctx, run.finished(), true, "")
		}
	case errors.As(err, &se):
		slog.Info("job failed", "id", job.ID, "kind", job.Kind, "code", se.Code)
		var n int64
		if n, err = j.q.FinishJob(fctx, run.finishParams("failed", run.result, se)); n > 0 {
			j.notify.noteJob(fctx, run.finished(), false, se.Message)
		}
	case parent.Err() != nil:
		// 停机:交还队列,不算一次尝试,重启后接着跑
		_, err = j.q.ReleaseJob(fctx, store.ReleaseJobParams{ID: job.ID, Refund: 1})
	default:
		e := transientJobError(err)
		slog.Warn("job attempt failed", "id", job.ID, "kind", job.Kind, "attempt", job.Attempts, "err", e.Message)
		if job.Attempts < jobMaxAttempts {
			_, err = j.q.ReleaseJob(fctx, store.ReleaseJobParams{ID: job.ID, ErrorCode: &e.Code, ErrorMessage: &e.Message})
		} else {
			var n int64
			if n, err = j.q.FinishJob(fctx, run.finishParams("failed", nil, e)); n > 0 {
				j.notify.noteJob(fctx, run.finished(), false, e.Message)
			}
		}
	}
	if err != nil {
		slog.Error("finish job", "id", job.ID, "err", err)
	}
}

func (j *Jobs) heartbeatLoop(ctx context.Context, run *jobRun, cancel context.CancelFunc, done chan<- struct{}) {
	defer close(done)
	t := time.NewTicker(j.heartbeat)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		_, err := j.q.HeartbeatJob(ctx, run.heartbeatParams())
		if errors.Is(err, pgx.ErrNoRows) {
			cancel() // 被取消了
			return
		}
		if err != nil && ctx.Err() == nil {
			slog.Warn("job heartbeat", "id", run.job.ID, "err", err)
		}
	}
}

func (j *Jobs) dispatch(ctx context.Context, run *jobRun) (*uuid.UUID, error) {
	switch run.job.Kind {
	case JobFetch:
		return j.runFetch(WithChatSource(ctx, ChatSourceFetch), run)
	case JobExtract:
		if j.archives == nil {
			return nil, errf("UNSUPPORTED", "本次部署没有压缩包功能")
		}
		return j.archives.runExtract(ctx, j, run)
	default:
		return nil, errf("UNSUPPORTED", "未知任务类型 %s", run.job.Kind)
	}
}

// transientJobError 基础设施类错误(网络、存储)转成给界面看的文字;不带链接本身(可能含签名)。
func transientJobError(err error) *Error {
	return &Error{Code: "JOB_ERROR", Message: describeNetErr(err)}
}

func truncateRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// finished 结束时的任务快照(标题可能在执行中改成了真实文件名)。
func (r *jobRun) finished() store.UserJob {
	j := r.job
	r.mu.Lock()
	j.Title = r.title
	r.mu.Unlock()
	return j
}
