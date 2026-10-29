package service

// 助手在服务器上运行(doc/34):一轮一行 ai_runs,排队、调度,每一轮起一个 qjs 进程(airun 包)跑网页、App 同一份运行时。
//
// - 运行时发给 gopan 自己的请求走本机回环地址,带一张现签的短时运行凭证(scope ai-run:<对话 id>),
//   只能用助手要的那几个接口、只能碰这一个对话;Host / X-Forwarded-* 填站点对外地址,工具返回的链接才是对外的。
// - 过程中的事件(正在打的字、条目、确认卡)不落库,经 ChangeHub 在进程内推给这个用户的设备;
//   正在打的字 200 毫秒合并一次。对话本身照样由运行时一条条追加进 ai_entries。
// - 要确认时这一轮标 waiting、发通知,等任何设备 Answer;等确认的时间不算进一轮的时长上限。
// - 服务重启:没跑完的标 failed;排队的照常排。

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/service/airun"
)

const (
	RunQueued  = "queued"
	RunRunning = "running"
	RunWaiting = "waiting"
	RunDone    = "done"
	RunFailed  = "failed"
	RunStopped = "stopped"

	// ScopeAIRunPrefix 服务器上跑的一轮用的凭证:ai-run:<对话 id>
	ScopeAIRunPrefix = "ai-run:"
)

var (
	errRunBusy     = &Error{Code: "AI_RUN_BUSY", Message: "这个对话还有一轮没跑完"}
	errRunNotFound = &Error{Code: "NOT_FOUND", Message: "这一轮已经结束了"}
	errNoRunner    = &Error{Code: "AI_RUN_UNAVAILABLE", Message: "服务器上没装助手运行环境"}
)

// AIRunScopeConversation 运行凭证的对话 id;不是运行凭证返回 false。
func AIRunScopeConversation(scope string) (uuid.UUID, bool) {
	rest, ok := strings.CutPrefix(scope, ScopeAIRunPrefix)
	if !ok {
		return uuid.Nil, false
	}
	id, err := uuid.Parse(rest)
	return id, err == nil
}

type AIRunsConfig struct {
	// QJS quickjs-ng 的 qjs 可执行文件
	QJS string
	// Origin 运行时请求 gopan 用的本机地址,如 http://127.0.0.1:8080
	Origin string
	// PublicURL 站点对外地址(工具返回的分享链接用它)
	PublicURL string
	// Max 全站同时跑几轮
	Max int
	// MaxActive 一轮最长跑多久(不算等确认)
	MaxActive time.Duration
	// ApprovalWait 等确认最长多久
	ApprovalWait time.Duration
	// MemoryKB 每个 qjs 进程的内存上限
	MemoryKB int
}

type AIRunView struct {
	ID             uuid.UUID
	ConversationID uuid.UUID
	Status         string
	Approval       json.RawMessage
	Error          string
	CreatedAt      time.Time
	StartedAt      *time.Time
	FinishedAt     *time.Time
}

func (v AIRunView) Active() bool {
	return v.Status == RunQueued || v.Status == RunRunning || v.Status == RunWaiting
}

type liveRun struct {
	id, user, conv uuid.UUID
	stop           chan struct{}
	stopOnce       sync.Once
	answer         chan string
}

func (l *liveRun) requestStop() { l.stopOnce.Do(func() { close(l.stop) }) }

type AIRuns struct {
	pool     *pgxpool.Pool
	chats    *AIChats
	auth     *Auth
	hub      *ChangeHub
	notifier *Notifier
	cfg      AIRunsConfig
	http     *http.Client

	mu     sync.Mutex
	live   map[uuid.UUID]*liveRun
	kick   chan struct{}
	ctx    context.Context
	wg     sync.WaitGroup
	public *url.URL
}

func NewAIRuns(pool *pgxpool.Pool, chats *AIChats, auth *Auth, hub *ChangeHub, notifier *Notifier, cfg AIRunsConfig) *AIRuns {
	if cfg.Max <= 0 {
		cfg.Max = 2
	}
	if cfg.MaxActive <= 0 {
		cfg.MaxActive = 30 * time.Minute
	}
	if cfg.ApprovalWait <= 0 {
		cfg.ApprovalWait = 24 * time.Hour
	}
	r := &AIRuns{
		pool: pool, chats: chats, auth: auth, hub: hub, notifier: notifier, cfg: cfg,
		http: &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		live: map[uuid.UUID]*liveRun{}, kick: make(chan struct{}, 1), ctx: context.Background(),
	}
	if u, err := url.Parse(cfg.PublicURL); err == nil && u.Host != "" {
		r.public = u
	}
	return r
}

// Available 服务器上能不能跑(装了 qjs)。
func (r *AIRuns) Available() bool { return airun.Available(r.cfg.QJS) }

const aiRunCols = `id, conversation_id, status, approval, error, created_at, started_at, finished_at`

func scanAIRun(row pgx.Row) (AIRunView, error) {
	var v AIRunView
	var approval []byte
	err := row.Scan(&v.ID, &v.ConversationID, &v.Status, &approval, &v.Error, &v.CreatedAt, &v.StartedAt, &v.FinishedAt)
	if len(approval) > 0 {
		v.Approval = approval
	}
	return v, err
}

type runInput struct {
	Text     string      `json:"text"`
	Images   []string    `json:"images,omitempty"`
	Context  string      `json:"context"`
	Provider runProvider `json:"provider"`
	Model    string      `json:"model"`
	Mode     string      `json:"mode"`
}

type runProvider struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Kind    string `json:"kind"`
	BaseURL string `json:"baseUrl"`
	Site    bool   `json:"site"`
}

// Start 发一句话交给服务器跑:没有对话就先建;同一个对话同时只能有一轮。
func (r *AIRuns) Start(ctx context.Context, user uuid.UUID, conversation *uuid.UUID, text, contextText string, images []string, providerID uuid.UUID, model string) (AIRunView, AIConversation, error) {
	if !r.Available() {
		return AIRunView{}, AIConversation{}, errNoRunner
	}
	text = strings.TrimSpace(text)
	if text == "" && len(images) == 0 {
		return AIRunView{}, AIConversation{}, errf("INVALID_INPUT", "说点什么")
	}
	if len(text) > 64<<10 || len(contextText) > 64<<10 {
		return AIRunView{}, AIConversation{}, errf("INVALID_INPUT", "太长了")
	}
	if err := CheckChatImages(images); err != nil {
		return AIRunView{}, AIConversation{}, err
	}
	var p runProvider
	var owner *uuid.UUID
	var kind string
	var models []string
	err := r.pool.QueryRow(ctx, `SELECT id, name, kind, base_url, owner_id, models FROM ai_providers WHERE id = $1 AND (owner_id = $2 OR owner_id IS NULL)`, providerID, user).
		Scan(&p.ID, &p.Name, &kind, &p.BaseURL, &owner, &models)
	if errors.Is(err, pgx.ErrNoRows) {
		return AIRunView{}, AIConversation{}, ErrNotFound
	}
	if err != nil {
		return AIRunView{}, AIConversation{}, err
	}
	p.Kind = strings.ToUpper(strings.ReplaceAll(kind, "-", "_"))
	p.Site = owner == nil
	if model == "" && len(models) > 0 {
		model = models[0]
	}
	var conv AIConversation
	if conversation == nil {
		if conv, err = r.chats.Create(ctx, user, titleOf(text), &providerID, model); err != nil {
			return AIRunView{}, conv, err
		}
	} else if conv, err = r.chats.Get(ctx, user, *conversation); err != nil {
		return AIRunView{}, conv, err
	}
	if _, err := r.pool.Exec(ctx, `UPDATE ai_conversations SET runner = 'server', provider_id = $2, model = $3, updated_at = now() WHERE id = $1`, conv.ID, providerID, model); err != nil {
		return AIRunView{}, conv, err
	}
	input, _ := json.Marshal(runInput{Text: text, Images: images, Context: contextText, Provider: p, Model: model, Mode: "proxy"})
	run, err := scanAIRun(r.pool.QueryRow(ctx, `INSERT INTO ai_runs (id, user_id, conversation_id, status, input) VALUES ($1, $2, $3, 'queued', $4) RETURNING `+aiRunCols,
		uuid.Must(uuid.NewV7()), user, conv.ID, input))
	if err != nil {
		if strings.Contains(err.Error(), "uq_ai_runs_active") {
			return AIRunView{}, conv, errRunBusy
		}
		return AIRunView{}, conv, err
	}
	r.publishRun(user, run)
	r.wake()
	return run, conv, nil
}

func titleOf(text string) string {
	for _, line := range strings.Split(text, "\n") {
		if t := strings.TrimSpace(line); t != "" {
			if rs := []rune(t); len(rs) > 40 {
				return string(rs[:40])
			}
			return t
		}
	}
	return ""
}

// Current 这个对话最近的一轮(没有给 nil)。
func (r *AIRuns) Current(ctx context.Context, user, conversation uuid.UUID) (*AIRunView, error) {
	v, err := scanAIRun(r.pool.QueryRow(ctx, `SELECT `+aiRunCols+` FROM ai_runs WHERE user_id = $1 AND conversation_id = $2 ORDER BY created_at DESC LIMIT 1`, user, conversation))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &v, nil
}

// ActiveStatuses 这个用户没结束的几轮:对话 → 状态(对话列表上的「运行中」「等你确认」)。
func (r *AIRuns) ActiveStatuses(ctx context.Context, user uuid.UUID) (map[uuid.UUID]string, error) {
	rows, err := r.pool.Query(ctx, `SELECT conversation_id, status FROM ai_runs WHERE user_id = $1 AND status IN ('queued', 'running', 'waiting')`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID]string{}
	for rows.Next() {
		var c uuid.UUID
		var s string
		if err := rows.Scan(&c, &s); err != nil {
			return nil, err
		}
		out[c] = s
	}
	return out, rows.Err()
}

// SetRunner 对话在哪跑:device / server。
func (r *AIRuns) SetRunner(ctx context.Context, user, conversation uuid.UUID, runner string) error {
	if runner != "device" && runner != "server" {
		return errf("INVALID_INPUT", "runner 只能是 device 或 server")
	}
	tag, err := r.pool.Exec(ctx, `UPDATE ai_conversations SET runner = $3 WHERE id = $1 AND user_id = $2`, conversation, user, runner)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// Answer 确认卡的答复(任何设备)。
func (r *AIRuns) Answer(ctx context.Context, user, runID uuid.UUID, answer string) error {
	if answer != "once" && answer != "session" && answer != "deny" {
		return errf("INVALID_INPUT", "答复只能是 once / session / deny")
	}
	r.mu.Lock()
	l := r.live[runID]
	r.mu.Unlock()
	if l == nil || l.user != user {
		return errRunNotFound
	}
	select {
	case l.answer <- answer:
		return nil
	default:
		return errf("CONFLICT", "已经答复过了")
	}
}

// Stop 停这一轮:排队的直接取消,跑着的让运行时收尾。
func (r *AIRuns) Stop(ctx context.Context, user, runID uuid.UUID) error {
	r.mu.Lock()
	l := r.live[runID]
	r.mu.Unlock()
	if l != nil {
		if l.user != user {
			return errRunNotFound
		}
		l.requestStop()
		return nil
	}
	v, err := scanAIRun(r.pool.QueryRow(ctx, `UPDATE ai_runs SET status = 'stopped', finished_at = now() WHERE id = $1 AND user_id = $2 AND status = 'queued' RETURNING `+aiRunCols, runID, user))
	if errors.Is(err, pgx.ErrNoRows) {
		return errRunNotFound
	}
	if err != nil {
		return err
	}
	r.publishRun(user, v)
	return nil
}

func (r *AIRuns) wake() {
	select {
	case r.kick <- struct{}{}:
	default:
	}
}

// Run 调度:有空位就从队列里取。服务关机时等跑着的几轮被结束(ctx 取消会结束 qjs 进程)。
func (r *AIRuns) Run(ctx context.Context) {
	r.ctx = ctx
	if _, err := r.pool.Exec(ctx, `UPDATE ai_runs SET status = 'failed', error = '服务重启,这一轮中断了', approval = NULL, finished_at = now() WHERE status IN ('running', 'waiting')`); err != nil {
		slog.Warn("ai runs: reset", "err", err)
	}
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	for {
		r.schedule(ctx)
		select {
		case <-ctx.Done():
			r.wg.Wait()
			return
		case <-r.kick:
		case <-tick.C:
		}
	}
}

func (r *AIRuns) schedule(ctx context.Context) {
	r.mu.Lock()
	free := r.cfg.Max - len(r.live)
	busy := map[uuid.UUID]bool{}
	for _, l := range r.live {
		busy[l.user] = true
	}
	r.mu.Unlock()
	if free <= 0 {
		return
	}
	rows, err := r.pool.Query(ctx, `SELECT id, user_id, conversation_id, input FROM ai_runs WHERE status = 'queued' ORDER BY created_at LIMIT 50`)
	if err != nil {
		return
	}
	type queued struct {
		id, user, conv uuid.UUID
		input          json.RawMessage
	}
	var list []queued
	for rows.Next() {
		var q queued
		if rows.Scan(&q.id, &q.user, &q.conv, &q.input) == nil {
			list = append(list, q)
		}
	}
	rows.Close()
	for _, q := range list {
		if free == 0 {
			return
		}
		if busy[q.user] {
			continue // 每人同时一轮
		}
		v, err := scanAIRun(r.pool.QueryRow(ctx, `UPDATE ai_runs SET status = 'running', started_at = now() WHERE id = $1 AND status = 'queued' RETURNING `+aiRunCols, q.id))
		if err != nil {
			continue
		}
		busy[q.user] = true
		free--
		l := &liveRun{id: q.id, user: q.user, conv: q.conv, stop: make(chan struct{}), answer: make(chan string, 1)}
		r.mu.Lock()
		r.live[q.id] = l
		r.mu.Unlock()
		r.publishRun(q.user, v)
		r.wg.Add(1)
		go r.execute(ctx, l, q.input)
	}
}

func (r *AIRuns) execute(ctx context.Context, l *liveRun, input json.RawMessage) {
	defer r.wg.Done()
	started := time.Now()
	host := &runHost{runs: r, l: l}
	res, err := airun.Run(ctx, airun.Options{
		QJS: r.cfg.QJS, MemoryKB: r.cfg.MemoryKB, Origin: r.cfg.Origin,
		ConversationID: l.conv.String(), Args: input, MaxActive: r.cfg.MaxActive,
	}, host, l.stop)
	host.flushLive()
	status, msg := RunDone, ""
	switch {
	case err != nil:
		status, msg = RunFailed, err.Error()
		slog.Warn("ai run", "run", l.id, "err", err, "stderr", res.Stderr)
	case res.Stopped:
		status = RunStopped
	case res.Error != "":
		status, msg = RunFailed, res.Error
	}
	fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	v, uerr := scanAIRun(r.pool.QueryRow(fctx, `UPDATE ai_runs SET status = $2, error = $3, approval = NULL, finished_at = now() WHERE id = $1 RETURNING `+aiRunCols, l.id, status, msg))
	r.mu.Lock()
	delete(r.live, l.id)
	r.mu.Unlock()
	if uerr == nil {
		r.publishRun(l.user, v)
	}
	if status != RunStopped && time.Since(started) > time.Minute && r.notifier != nil {
		var title string
		_ = r.pool.QueryRow(fctx, `SELECT title FROM ai_conversations WHERE id = $1`, l.conv).Scan(&title)
		body := "这一轮做完了"
		if status == RunFailed {
			body = "这一轮出错停下了:" + msg
		}
		r.notifier.Notify(fctx, l.user, Event{
			Kind: EvAssistantDone, Level: LvInfo, Title: "助手做完了:" + title, Body: body,
			Target: &Target{Kind: TgAssistant, ID: l.conv.String()},
		})
	}
	r.wake()
}

// runEvent 推给客户端的这一轮的状态
type runEvent struct {
	Type string     `json:"type"`
	Run  runPayload `json:"run"`
}

type runPayload struct {
	ID       string          `json:"id"`
	Status   string          `json:"status"`
	Approval json.RawMessage `json:"approval,omitempty"`
	Error    string          `json:"error,omitempty"`
}

func (r *AIRuns) publishRun(user uuid.UUID, v AIRunView) {
	if r.hub == nil {
		return
	}
	b, _ := json.Marshal(runEvent{Type: "run", Run: runPayload{ID: v.ID.String(), Status: v.Status, Approval: v.Approval, Error: v.Error}})
	r.hub.Publish(user, Change{Topic: "assistant", Conversation: v.ConversationID.String(), Event: b})
}

// runHost 一轮的宿主:替运行时发请求、推事件、等确认。
type runHost struct {
	runs *AIRuns
	l    *liveRun

	mu        sync.Mutex
	live      json.RawMessage
	liveTimer *time.Timer
}

func (h *runHost) Fetch(ctx context.Context, req airun.Request) (*http.Response, error) {
	method := req.Method
	if method == "" {
		method = http.MethodGet
	}
	var body io.Reader
	if req.Body != nil {
		body = strings.NewReader(*req.Body)
	}
	hr, err := http.NewRequestWithContext(ctx, method, req.URL, body)
	if err != nil {
		return nil, err
	}
	for k, v := range req.Headers {
		hr.Header.Set(k, v)
	}
	origin := strings.TrimRight(h.runs.cfg.Origin, "/")
	if req.URL == origin || strings.HasPrefix(req.URL, origin+"/") {
		// 同源:现签一张只管这个对话的短时凭证;工具返回的链接要用对外地址
		token, err := h.runs.auth.IssueAccessFor(h.l.user, ScopeAIRunPrefix+h.l.conv.String(), uuid.Nil, 10*time.Minute)
		if err != nil {
			return nil, err
		}
		hr.Header.Del("X-Api-Key")
		hr.Header.Set("Authorization", "Bearer "+token)
		if p := h.runs.public; p != nil {
			hr.Host = p.Host
			hr.Header.Set("X-Forwarded-Host", p.Host)
			hr.Header.Set("X-Forwarded-Proto", p.Scheme)
		}
	}
	return h.runs.http.Do(hr)
}

// Emit 正在打的字 200 毫秒合并一次;别的事件先把攒着的字推出去再推自己。
func (h *runHost) Emit(e json.RawMessage) {
	var head struct {
		Type string `json:"type"`
	}
	_ = json.Unmarshal(e, &head)
	if head.Type == "live" {
		h.mu.Lock()
		h.live = e
		if h.liveTimer == nil {
			h.liveTimer = time.AfterFunc(200*time.Millisecond, h.flushLive)
		}
		h.mu.Unlock()
		return
	}
	h.flushLive()
	h.publish(e)
}

func (h *runHost) flushLive() {
	h.mu.Lock()
	e := h.live
	h.live = nil
	if h.liveTimer != nil {
		h.liveTimer.Stop()
		h.liveTimer = nil
	}
	h.mu.Unlock()
	if e != nil {
		h.publish(e)
	}
}

func (h *runHost) publish(e json.RawMessage) {
	if h.runs.hub != nil {
		h.runs.hub.Publish(h.l.user, Change{Topic: "assistant", Conversation: h.l.conv.String(), Event: e})
	}
}

func (h *runHost) Approve(ctx context.Context, view json.RawMessage) (string, error) {
	r := h.runs
	h.flushLive()
	dbctx := context.WithoutCancel(ctx)
	if v, err := scanAIRun(r.pool.QueryRow(dbctx, `UPDATE ai_runs SET status = 'waiting', approval = $2 WHERE id = $1 RETURNING `+aiRunCols, h.l.id, []byte(view))); err == nil {
		r.publishRun(h.l.user, v)
	}
	if r.notifier != nil {
		var card struct {
			Title string `json:"title"`
		}
		_ = json.Unmarshal(view, &card)
		r.notifier.Notify(dbctx, h.l.user, Event{
			Kind: EvAssistantWait, Level: LvInfo, Title: "助手等你确认", Body: card.Title,
			Target: &Target{Kind: TgAssistant, ID: h.l.conv.String()}, GroupKey: "assistant_wait:" + h.l.conv.String(),
		})
	}
	defer func() {
		if v, err := scanAIRun(r.pool.QueryRow(dbctx, `UPDATE ai_runs SET status = 'running', approval = NULL WHERE id = $1 AND status = 'waiting' RETURNING `+aiRunCols, h.l.id)); err == nil {
			r.publishRun(h.l.user, v)
		}
	}()
	timer := time.NewTimer(r.cfg.ApprovalWait)
	defer timer.Stop()
	select {
	case a := <-h.l.answer:
		return a, nil
	case <-h.l.stop:
		return "deny", nil
	case <-timer.C:
		h.l.requestStop()
		return "deny", nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}
