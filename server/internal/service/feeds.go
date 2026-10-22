package service

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/feedparse"
	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/store"
)

// 订阅(doc/23 四):RSS / Atom / 播客 / GitHub Releases,定时检查,新内容建离线下载或剪藏任务。

const (
	FeedRSS    = "rss"
	FeedGitHub = "github"

	FeedModeDownload = "download"
	FeedModeClip     = "clip"
	FeedModeNone     = "none"

	maxFeeds            = 100
	feedDocMax          = 20 << 20 // 大播客的订阅文件十几 MB;只解析前 100 条,读够就停
	feedTimeout         = 20 * time.Second
	feedCheckBatch      = 5
	feedDefaultInterval = 360
	feedMinInterval     = 60
	feedMaxInterval     = 7 * 24 * 60
	feedFailAlert       = 3
	feedItemsPerCheck   = 100
	feedPendingRetry    = 10 * time.Minute
	feedItemsPageSize   = 50
	orderFeedItems      = "FEEDITEMS"
	defaultFeedDir      = "/订阅"
	maxOPMLFeeds        = 100
)

type Feeds struct {
	q         *store.Queries
	jobs      *Jobs
	clips     *Clips
	nodes     *Nodes
	uploads   *Uploads
	notify    *Notifier
	client    *http.Client
	ua        string
	githubAPI string
	mu        sync.Mutex // 同一个订阅不同时检查两次(手动「立即检查」与定时检查撞上)
	checking  map[uuid.UUID]bool
}

func NewFeeds(pool *pgxpool.Pool, jobs *Jobs, clips *Clips, nodes *Nodes, uploads *Uploads, notify *Notifier, guard fetcher.Guard, ua string) *Feeds {
	f := &Feeds{
		q: store.New(pool), jobs: jobs, clips: clips, nodes: nodes, uploads: uploads, notify: notify,
		client: guard.Client(), ua: ua, githubAPI: "https://api.github.com", checking: map[uuid.UUID]bool{},
	}
	jobs.SetFinishHook(f.onJobFinished)
	return f
}

// SetTestEndpoints 测试用:换客户端与 GitHub API 地址。
func (f *Feeds) SetTestEndpoints(c *http.Client, githubAPI string) {
	f.client, f.githubAPI = c, githubAPI
}

func (f *Feeds) Enabled() bool { return f.jobs.FetchEnabled() }

// FeedInput 新建 / 修改订阅。新建时 URL 必填;修改时 URL 与 Kind 忽略。
type FeedInput struct {
	URL             string
	Title           string
	FolderID        *uuid.UUID
	Mode            string
	Include         string
	Exclude         string
	AssetPattern    string
	Prerelease      bool
	KeepLast        int
	IntervalMinutes int
	Enabled         bool
	Initial         int // 第一次检查处理最新的几条(默认 1;导入 OPML 时 0)
}

// FeedPreview 贴地址后先看一眼。
type FeedPreview struct {
	Kind, URL, Title, SiteURL string
	Podcast                   bool
	SuggestedMode             string
	Items                     []feedparse.Item
}

// normalizeFeedURL 认出 GitHub 仓库;其余必须是 http(s) 地址。
func normalizeFeedURL(raw string) (kind, u string, err error) {
	raw = strings.TrimSpace(raw)
	if repo, ok := feedparse.GitHubRepo(raw); ok && (!strings.Contains(raw, "://") || strings.Contains(raw, "github.com/")) {
		// github.com 上的 .atom / .rss 地址按普通订阅处理
		if !strings.HasSuffix(raw, ".atom") && !strings.HasSuffix(raw, ".rss") {
			return FeedGitHub, repo, nil
		}
	}
	pu, perr := fetcher.ParseURL(raw)
	if perr != nil {
		return "", "", errf("INVALID_URL", "地址不对:要么是 RSS / Atom 地址,要么是 GitHub 仓库(owner/repo)")
	}
	return FeedRSS, pu.String(), nil
}

func (f *Feeds) Preview(ctx context.Context, raw, assetPattern string, prerelease bool) (*FeedPreview, error) {
	if !f.Enabled() {
		return nil, errf("FETCH_DISABLED", "本站没有开启离线下载,订阅也用不了")
	}
	kind, u, err := normalizeFeedURL(raw)
	if err != nil {
		return nil, err
	}
	doc, _, _, _, err := f.fetch(ctx, kind, u, "", "", feedparse.SplitPatterns(assetPattern), prerelease)
	if err != nil {
		return nil, err
	}
	p := &FeedPreview{Kind: kind, URL: u, Title: doc.Title, SiteURL: doc.SiteURL, Podcast: doc.Podcast, Items: doc.Items}
	if len(p.Items) > 10 {
		p.Items = p.Items[:10]
	}
	p.SuggestedMode = FeedModeNone
	if doc.Podcast || kind == FeedGitHub {
		p.SuggestedMode = FeedModeDownload
	}
	return p, nil
}

func (in *FeedInput) validate() error {
	switch in.Mode {
	case "":
		in.Mode = FeedModeNone
	case FeedModeDownload, FeedModeClip, FeedModeNone:
	default:
		return errf("INVALID_INPUT", "订阅方式只能是下载附件、剪藏正文或只记录")
	}
	if in.IntervalMinutes == 0 {
		in.IntervalMinutes = feedDefaultInterval
	}
	if in.IntervalMinutes < feedMinInterval || in.IntervalMinutes > feedMaxInterval {
		return errf("INVALID_INPUT", "检查间隔要在 1 小时到 7 天之间")
	}
	if in.KeepLast < 0 || in.KeepLast > 1000 {
		return errf("INVALID_INPUT", "只留最近几条要在 0(不删)到 1000 之间")
	}
	in.Title = truncateRunes(strings.TrimSpace(in.Title), 100)
	in.Include = truncateRunes(strings.TrimSpace(in.Include), 300)
	in.Exclude = truncateRunes(strings.TrimSpace(in.Exclude), 300)
	in.AssetPattern = truncateRunes(strings.TrimSpace(in.AssetPattern), 300)
	return nil
}

// Create 先抓一次(地址不对当场报错),建订阅与默认文件夹「/订阅/标题」,再按 Initial 处理最新的几条。
func (f *Feeds) Create(ctx context.Context, owner uuid.UUID, in FeedInput) (store.Feed, error) {
	if !f.Enabled() {
		return store.Feed{}, errf("FETCH_DISABLED", "本站没有开启离线下载,订阅也用不了")
	}
	if err := in.validate(); err != nil {
		return store.Feed{}, err
	}
	kind, u, err := normalizeFeedURL(in.URL)
	if err != nil {
		return store.Feed{}, err
	}
	n, err := f.q.CountFeeds(ctx, owner)
	if err != nil {
		return store.Feed{}, err
	}
	if n >= maxFeeds {
		return store.Feed{}, errf("TOO_MANY_FEEDS", "最多 %d 个订阅", maxFeeds)
	}
	if kind == FeedGitHub && in.Mode == FeedModeClip {
		in.Mode = FeedModeDownload
	}
	doc, etag, lastMod, _, err := f.fetch(ctx, kind, u, "", "", feedparse.SplitPatterns(in.AssetPattern), in.Prerelease)
	if err != nil {
		return store.Feed{}, err
	}
	title := in.Title
	if title == "" {
		title = truncateRunes(firstNonBlank(doc.Title, fetcher.Host(u), u), 100)
	}
	folder := in.FolderID
	if folder != nil {
		if err := checkTargetFolder(ctx, f.q, owner, folder); err != nil {
			return store.Feed{}, err
		}
	} else {
		_, segs, _ := normalizeFolderPath(defaultFeedDir)
		leaf := clipBaseName(title)
		if folder, err = f.uploads.EnsurePath(withQuiet(ctx), owner, append(segs, leaf)); err != nil {
			return store.Feed{}, err
		}
	}
	feed, err := f.q.CreateFeed(ctx, store.CreateFeedParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, Kind: kind, Url: u, Title: title, SiteUrl: doc.SiteURL,
		FolderID: folder, Mode: in.Mode, IncludeWords: in.Include, ExcludeWords: in.Exclude,
		AssetPattern: in.AssetPattern, Prerelease: in.Prerelease, KeepLast: int32(in.KeepLast),
		IntervalMinutes: int32(in.IntervalMinutes),
	})
	if isUniqueViolation(err) {
		return store.Feed{}, errf("FEED_EXISTS", "已经订阅过这个地址了")
	}
	if err != nil {
		return store.Feed{}, err
	}
	initial := in.Initial
	if initial < 0 {
		initial = 0
	}
	f.ingest(ctx, feed, doc, true, initial)
	if err := f.q.FeedCheckOK(ctx, store.FeedCheckOKParams{ID: feed.ID, Etag: etag, LastModified: lastMod, Title: doc.Title, SiteUrl: doc.SiteURL}); err != nil {
		return feed, err
	}
	f.dispatch(ctx, feed)
	return f.Get(ctx, owner, feed.ID)
}

func firstNonBlank(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func (f *Feeds) Get(ctx context.Context, owner, id uuid.UUID) (store.Feed, error) {
	feed, err := f.q.GetFeed(ctx, store.GetFeedParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return feed, ErrNotFound
	}
	return feed, err
}

func (f *Feeds) List(ctx context.Context, owner uuid.UUID) ([]store.ListFeedsRow, error) {
	return f.q.ListFeeds(ctx, owner)
}

func (f *Feeds) Update(ctx context.Context, owner, id uuid.UUID, in FeedInput) (store.Feed, error) {
	cur, err := f.Get(ctx, owner, id)
	if err != nil {
		return cur, err
	}
	if err := in.validate(); err != nil {
		return cur, err
	}
	if cur.Kind == FeedGitHub && in.Mode == FeedModeClip {
		return cur, errf("INVALID_INPUT", "GitHub 订阅只能下载附件或只记录")
	}
	folder := cur.FolderID
	if in.FolderID != nil {
		if err := checkTargetFolder(ctx, f.q, owner, in.FolderID); err != nil {
			return cur, err
		}
		folder = in.FolderID
	}
	if in.Enabled && folder == nil {
		return cur, errf("NOT_A_FOLDER", "先选一个存放的文件夹")
	}
	title := in.Title
	if title == "" {
		title = cur.Title
	}
	feed, err := f.q.UpdateFeed(ctx, store.UpdateFeedParams{
		ID: id, OwnerID: owner, Title: title, FolderID: folder, Mode: in.Mode, IncludeWords: in.Include,
		ExcludeWords: in.Exclude, AssetPattern: in.AssetPattern, Prerelease: in.Prerelease,
		KeepLast: int32(in.KeepLast), IntervalMinutes: int32(in.IntervalMinutes), Enabled: in.Enabled,
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return feed, ErrNotFound
	}
	return feed, err
}

// Delete 退订;已下好的文件不动。
func (f *Feeds) Delete(ctx context.Context, owner, id uuid.UUID) error {
	n, err := f.q.DeleteFeed(ctx, store.DeleteFeedParams{ID: id, OwnerID: owner})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

func (f *Feeds) Items(ctx context.Context, owner, feedID uuid.UUID, cursor *string) ([]store.FeedItem, *string, error) {
	if _, err := f.Get(ctx, owner, feedID); err != nil {
		return nil, nil, err
	}
	p := store.ListFeedItemsParams{FeedID: feedID, Lim: feedItemsPageSize}
	if c := decodeCursor(cursor, orderFeedItems, false); c != nil {
		p.BeforeID, p.BeforeAt = c.ID, tstz(time.Unix(0, c.T))
	}
	rows, err := f.q.ListFeedItems(ctx, p)
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) == feedItemsPageSize {
		last := rows[len(rows)-1]
		next = encodeCursor(cursorPayload{O: orderFeedItems, T: last.CreatedAt.Time.UnixNano(), ID: last.ID})
	}
	return rows, next, nil
}

// Refresh 立即检查一次(同步,最多 20 秒)。
func (f *Feeds) Refresh(ctx context.Context, owner, id uuid.UUID) (store.Feed, error) {
	feed, err := f.Get(ctx, owner, id)
	if err != nil {
		return feed, err
	}
	if !f.Enabled() {
		return feed, errf("FETCH_DISABLED", "本站没有开启离线下载,订阅也用不了")
	}
	f.check(ctx, feed)
	return f.Get(ctx, owner, id)
}

// FetchItem 手动处理一个条目:mode 为 download(下附件)或 clip(剪藏正文)。
func (f *Feeds) FetchItem(ctx context.Context, owner, itemID uuid.UUID, mode string) (store.FeedItem, error) {
	item, err := f.q.GetFeedItem(ctx, store.GetFeedItemParams{ID: itemID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return item, ErrNotFound
	}
	if err != nil {
		return item, err
	}
	feed, err := f.q.GetFeedByID(ctx, item.FeedID)
	if err != nil {
		return item, err
	}
	if item.State == "queued" && item.JobID != nil {
		return item, errf("ALREADY_QUEUED", "已经在处理了")
	}
	if feed.FolderID == nil {
		return item, errf("NOT_A_FOLDER", "订阅的文件夹不在了,先在设置里换一个")
	}
	switch mode {
	case FeedModeDownload:
		if item.EnclosureUrl == "" {
			return item, errf("NO_ENCLOSURE", "这条没有附件可下载")
		}
	case FeedModeClip:
		if item.Url == "" {
			return item, errf("NO_LINK", "这条没有原文链接")
		}
	default:
		return item, errf("INVALID_INPUT", "只能下载附件或剪藏正文")
	}
	ctx = WithActor(ctx, Actor{Kind: ActorFeed, Ref: feed.ID.String(), Label: feed.Title})
	job, err := f.enqueue(ctx, feed, item, mode)
	if err != nil {
		return item, err
	}
	if err := f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: item.ID, State: "queued", JobID: &job.ID}); err != nil {
		return item, err
	}
	return f.q.GetFeedItem(ctx, store.GetFeedItemParams{ID: itemID, OwnerID: owner})
}

// ---------- 检查 ----------

// CheckDue worker 每 5 分钟调一次:领到期的订阅逐个检查。
func (f *Feeds) CheckDue(ctx context.Context) error {
	if !f.Enabled() {
		return nil
	}
	feeds, err := f.q.ClaimDueFeeds(ctx, feedCheckBatch)
	if err != nil {
		return err
	}
	for _, feed := range feeds {
		if ctx.Err() != nil {
			return nil
		}
		f.check(ctx, feed)
	}
	return nil
}

func (f *Feeds) check(ctx context.Context, feed store.Feed) {
	f.mu.Lock()
	if f.checking[feed.ID] {
		f.mu.Unlock()
		return
	}
	f.checking[feed.ID] = true
	f.mu.Unlock()
	defer func() {
		f.mu.Lock()
		delete(f.checking, feed.ID)
		f.mu.Unlock()
	}()

	ctx = WithActor(ctx, Actor{Kind: ActorFeed, Ref: feed.ID.String(), Label: feed.Title})
	if feed.FolderID == nil {
		_ = f.q.DisableFeed(ctx, store.DisableFeedParams{ID: feed.ID, LastError: strPtr("存放的文件夹被彻底删除了,订阅已暂停;换一个文件夹再打开")})
		return
	}
	if err := checkTargetFolder(ctx, f.q, feed.OwnerID, feed.FolderID); err != nil {
		f.failed(ctx, feed, errf("NOT_A_FOLDER", "存放的文件夹在回收站里"))
		return
	}
	doc, etag, lm, notMod, err := f.fetch(ctx, feed.Kind, feed.Url, feed.Etag, feed.LastModified,
		feedparse.SplitPatterns(feed.AssetPattern), feed.Prerelease)
	if err != nil {
		f.failed(ctx, feed, err)
		return
	}
	if !notMod {
		f.ingest(ctx, feed, doc, !feed.LastOkAt.Valid, 1)
	} else {
		doc = &feedparse.Feed{}
	}
	if err := f.q.FeedCheckOK(ctx, store.FeedCheckOKParams{
		ID: feed.ID, Etag: etag, LastModified: lm, Title: doc.Title, SiteUrl: doc.SiteURL,
	}); err != nil {
		slog.Warn("feed check ok", "feed", feed.ID, "err", err)
	}
	f.reconcile(ctx, feed)
	f.dispatch(ctx, feed)
	if err := f.q.TrimFeedItems(ctx, feed.ID); err != nil {
		slog.Warn("trim feed items", "feed", feed.ID, "err", err)
	}
}

func strPtr(s string) *string { return &s }

// failed 记一次失败(检查间隔翻倍);连续 3 次失败提醒,每天最多一次。
func (f *Feeds) failed(ctx context.Context, feed store.Feed, err error) {
	msg := describeFeedErr(err)
	cnt, e := f.q.FeedCheckFailed(ctx, store.FeedCheckFailedParams{ID: feed.ID, LastError: &msg})
	if e != nil {
		slog.Warn("feed check failed", "feed", feed.ID, "err", e)
		return
	}
	if cnt >= feedFailAlert {
		f.notify.Notify(ctx, feed.OwnerID, Event{
			Kind: EvFeedFailed, Level: LvWarn, Title: fmt.Sprintf("订阅「%s」连续 %d 次检查失败", feed.Title, cnt),
			Body: msg, Target: &Target{Kind: TgFeed, ID: feed.ID.String()},
			GroupKey: "feed_failed:" + feed.ID.String(), Once: 24 * time.Hour,
		})
	}
}

func describeFeedErr(err error) string {
	var se *Error
	if errors.As(err, &se) {
		return se.Message
	}
	return describeNetErr(err)
}

// ingest 把文档里没见过的条目存下来并定好状态。first = 订阅后第一次成功检查:只处理最新的 initial 条。
func (f *Feeds) ingest(ctx context.Context, feed store.Feed, doc *feedparse.Feed, first bool, initial int) {
	items := doc.Items
	if len(items) > feedItemsPerCheck {
		items = items[:feedItemsPerCheck]
	}
	include, exclude := splitWords(feed.IncludeWords), splitWords(feed.ExcludeWords)
	// 从旧到新插,列表按插入时间倒序就是从新到旧
	for i := len(items) - 1; i >= 0; i-- {
		it := items[i]
		state := "queued"
		switch {
		case feed.Mode == FeedModeNone:
			state = "new"
		case !matchWords(it.Title, include, exclude):
			state = "skipped"
		case feed.Mode == FeedModeDownload && it.EnclosureURL == "":
			state = "new"
		case feed.Mode == FeedModeClip && it.Link == "":
			state = "new"
		case first && i >= initial:
			state = "skipped"
		}
		var size *int64
		if it.EnclosureSize > 0 {
			v := it.EnclosureSize
			size = &v
		}
		pub := pgtype.Timestamptz{}
		if !it.Published.IsZero() {
			pub = tstz(it.Published)
		}
		guid := truncateRunes(it.GUID, 500)
		_, err := f.q.InsertFeedItem(ctx, store.InsertFeedItemParams{
			ID: uuid.Must(uuid.NewV7()), FeedID: feed.ID, Guid: guid, Title: truncateRunes(it.Title, 300),
			Url: it.Link, EnclosureUrl: it.EnclosureURL, EnclosureType: truncateRunes(it.EnclosureType, 100),
			EnclosureSize: size, PublishedAt: pub, State: state,
		})
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			slog.Warn("insert feed item", "feed", feed.ID, "err", err)
		}
	}
}

func splitWords(s string) []string {
	var out []string
	for _, w := range feedparse.SplitPatterns(s) {
		if w = strings.ToLower(strings.TrimSpace(w)); w != "" {
			out = append(out, w)
		}
	}
	return out
}

// matchWords 标题含任一 include(没设就不限),且不含任何 exclude。不分大小写。
func matchWords(title string, include, exclude []string) bool {
	t := strings.ToLower(title)
	for _, w := range exclude {
		if strings.Contains(t, w) {
			return false
		}
	}
	if len(include) == 0 {
		return true
	}
	for _, w := range include {
		if strings.Contains(t, w) {
			return true
		}
	}
	return false
}

// reconcile 进程崩溃或钩子没赶上时,按任务的实际状态补写条目。
func (f *Feeds) reconcile(ctx context.Context, feed store.Feed) {
	items, err := f.q.ListRunningFeedItems(ctx, feed.ID)
	if err != nil {
		return
	}
	for _, it := range items {
		job, err := f.q.GetJob(ctx, store.GetJobParams{ID: *it.JobID, OwnerID: feed.OwnerID})
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: it.ID, State: "failed", Error: strPtr("任务记录不见了")})
		case err != nil:
		case job.Status == "done":
			_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: it.ID, State: "done", JobID: it.JobID, NodeID: job.ResultNodeID})
		case job.Status == "failed" || job.Status == "canceled":
			_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: it.ID, State: "failed", JobID: it.JobID, Error: job.ErrorMessage})
		}
	}
}

// dispatch 给等着的条目建任务;后台任务排满了就 10 分钟后再来。
func (f *Feeds) dispatch(ctx context.Context, feed store.Feed) {
	if feed.FolderID == nil {
		return
	}
	items, err := f.q.ListPendingFeedItems(ctx, feed.ID)
	if err != nil {
		slog.Warn("list pending feed items", "feed", feed.ID, "err", err)
		return
	}
	ctx = WithActor(ctx, Actor{Kind: ActorFeed, Ref: feed.ID.String(), Label: feed.Title})
	for _, it := range items {
		mode := feed.Mode
		if mode == FeedModeNone {
			mode = FeedModeDownload
		}
		job, err := f.enqueue(ctx, feed, it, mode)
		var se *Error
		if errors.As(err, &se) && se.Code == "TOO_MANY_JOBS" {
			_ = f.q.SetFeedNextCheck(ctx, store.SetFeedNextCheckParams{ID: feed.ID, NextCheckAt: tstz(time.Now().Add(feedPendingRetry))})
			return
		}
		if err != nil {
			msg := describeFeedErr(err)
			_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: it.ID, State: "failed", Error: &msg})
			continue
		}
		_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: it.ID, State: "queued", JobID: &job.ID})
	}
}

func (f *Feeds) enqueue(ctx context.Context, feed store.Feed, it store.FeedItem, mode string) (store.UserJob, error) {
	if mode == FeedModeClip {
		return f.clips.enqueueForFeed(ctx, feed, it)
	}
	if it.EnclosureUrl == "" {
		return store.UserJob{}, errf("NO_ENCLOSURE", "这条没有附件可下载")
	}
	parent := feed.FolderID
	name := feedFileName(feed, it)
	if feed.Kind == FeedGitHub {
		if tag, _, ok := strings.Cut(it.Guid, "/"); ok && tag != "" {
			n, err := f.uploads.EnsureFolderIn(withQuiet(ctx), feed.OwnerID, feed.FolderID, fetcher.Clean(tag))
			if err != nil {
				return store.UserJob{}, err
			}
			parent = &n.ID
		}
	}
	b, _ := json.Marshal(fetchParams{URL: it.EnclosureUrl, Name: name, FeedID: &feed.ID, FeedItemID: &it.ID, FeedTitle: feed.Title})
	jobs, err := f.jobs.create(ctx, feed.OwnerID, parent, []JobSpec{{Kind: JobFetch, Params: b, Title: truncateRunes(it.Title, 100), BytesTotal: it.EnclosureSize}}, JobSourceFeed)
	if err != nil {
		return store.UserJob{}, err
	}
	return jobs[0], nil
}

var mediaTypeExt = map[string]string{
	"audio/mpeg": ".mp3", "audio/mp3": ".mp3", "audio/mp4": ".m4a", "audio/x-m4a": ".m4a", "audio/aac": ".aac",
	"audio/ogg": ".ogg", "audio/opus": ".opus", "audio/flac": ".flac", "audio/wav": ".wav", "audio/x-wav": ".wav",
	"video/mp4": ".mp4", "video/quicktime": ".mov", "video/webm": ".webm", "video/x-matroska": ".mkv",
	"application/pdf": ".pdf",
}

// feedFileName 播客类:「2026-10-21 节目标题.mp3」;GitHub 用资源原名(放进 tag 文件夹)。
func feedFileName(feed store.Feed, it store.FeedItem) string {
	if feed.Kind == FeedGitHub {
		_, asset, _ := strings.Cut(it.Guid, "/")
		return fetcher.Clean(asset)
	}
	ext := ""
	if u, err := url.Parse(it.EnclosureUrl); err == nil {
		if e := strings.ToLower(path.Ext(u.Path)); len(e) >= 2 && len(e) <= 6 {
			ext = e
		}
	}
	if ext == "" {
		ext = mediaTypeExt[strings.ToLower(strings.TrimSpace(strings.Split(it.EnclosureType, ";")[0]))]
	}
	base := clipBaseName(it.Title)
	if it.PublishedAt.Valid {
		base = it.PublishedAt.Time.Local().Format("2006-01-02") + " " + base
	}
	return base + ext
}

// onJobFinished 任务钩子:订阅建的任务结束时写回条目,下好的发通知、按「只留最近 N 条」清理、接着派下一批。
func (f *Feeds) onJobFinished(ctx context.Context, job store.UserJob, status string, result *uuid.UUID, msg string) {
	var p struct {
		FeedID     *uuid.UUID `json:"feed_id"`
		FeedItemID *uuid.UUID `json:"feed_item_id"`
	}
	if json.Unmarshal(job.Params, &p) != nil || p.FeedItemID == nil || p.FeedID == nil {
		return
	}
	feed, err := f.q.GetFeedByID(ctx, *p.FeedID)
	if err != nil {
		return // 退订了
	}
	jid := job.ID
	switch status {
	case "done":
		_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: *p.FeedItemID, State: "done", JobID: &jid, NodeID: result})
		t := &Target{Kind: TgFeed, ID: feed.ID.String()}
		if result != nil {
			t = &Target{Kind: TgNode, ID: result.String()}
			if n, err := f.q.GetNode(ctx, *result); err == nil && n.ParentID != nil {
				t.ParentID = n.ParentID.String()
			}
		}
		f.notify.Notify(ctx, feed.OwnerID, Event{
			Kind: EvFeedNew, Level: LvInfo, Title: "订阅「" + feed.Title + "」有新内容", Body: job.Title,
			Target: t, GroupKey: "feed_new:" + feed.ID.String(),
		})
		f.prune(ctx, feed)
		f.dispatch(ctx, feed)
	case "failed", "canceled":
		_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: *p.FeedItemID, State: "failed", JobID: &jid, Error: &msg})
		f.dispatch(ctx, feed)
	case "queued":
		_ = f.q.SetFeedItemState(ctx, store.SetFeedItemStateParams{ID: *p.FeedItemID, State: "queued", JobID: &jid})
	}
}

// prune 只留最近 N 条:更早的文件移进回收站(来源是这个订阅,记进动态)。
func (f *Feeds) prune(ctx context.Context, feed store.Feed) {
	if feed.KeepLast <= 0 {
		return
	}
	old, err := f.q.ListPrunableFeedItems(ctx, store.ListPrunableFeedItemsParams{FeedID: feed.ID, Offset: feed.KeepLast})
	if err != nil || len(old) == 0 {
		return
	}
	ctx = WithActor(ctx, Actor{Kind: ActorFeed, Ref: feed.ID.String(), Label: feed.Title})
	for _, it := range old {
		if err := f.nodes.Delete(ctx, feed.OwnerID, []uuid.UUID{*it.NodeID}); err != nil && !errors.Is(err, ErrNotFound) {
			slog.Warn("prune feed item", "feed", feed.ID, "err", err)
			continue
		}
		_ = f.q.ClearFeedItemNode(ctx, it.ID)
	}
}

// ---------- 拉取 ----------

// fetch 拉订阅文档(条件请求)。notMod = 源站回 304。
func (f *Feeds) fetch(ctx context.Context, kind, u, etag, lastMod string, patterns []string, prerelease bool) (doc *feedparse.Feed, newEtag, newLM string, notMod bool, err error) {
	ctx, cancel := context.WithTimeout(ctx, feedTimeout)
	defer cancel()
	target := u
	if kind == FeedGitHub {
		target = f.githubAPI + "/repos/" + u + "/releases?per_page=10"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, "", "", false, errf("INVALID_URL", "地址不对")
	}
	req.Header.Set("User-Agent", f.ua)
	if kind == FeedGitHub {
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	} else {
		req.Header.Set("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
	}
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	if lastMod != "" {
		req.Header.Set("If-Modified-Since", lastMod)
	}
	resp, err := f.client.Do(req)
	if err != nil {
		if e := mapFetchErr(err); e != err {
			return nil, "", "", false, e
		}
		return nil, "", "", false, errf("FETCH_FAILED", "%s", describeNetErr(err))
	}
	defer resp.Body.Close()
	newEtag, newLM = resp.Header.Get("ETag"), resp.Header.Get("Last-Modified")
	switch {
	case resp.StatusCode == http.StatusNotModified:
		return nil, etag, lastMod, true, nil
	case kind == FeedGitHub && resp.StatusCode == http.StatusNotFound:
		return nil, "", "", false, errf("GITHUB_NOT_FOUND", "GitHub 上没有这个仓库(或是私有的)")
	case kind == FeedGitHub && (resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusTooManyRequests):
		return nil, "", "", false, errf("GITHUB_RATE_LIMITED", "GitHub 限流了(匿名每小时 60 次),稍后自动重试")
	case resp.StatusCode/100 != 2:
		return nil, "", "", false, errf("FETCH_HTTP_STATUS", "源站返回 HTTP %d", resp.StatusCode)
	}
	body := io.LimitReader(resp.Body, feedDocMax)
	if kind == FeedGitHub {
		doc, err = feedparse.ParseGitHubReleases(body, u, patterns, prerelease)
	} else {
		doc, err = feedparse.ParseLimit(body, resp.Request.URL, feedItemsPerCheck)
	}
	if err != nil {
		return nil, "", "", false, errf("NOT_A_FEED", "%s", err.Error())
	}
	return doc, newEtag, newLM, false, nil
}

// ---------- OPML ----------

type opmlOutline struct {
	Text     string        `xml:"text,attr"`
	Title    string        `xml:"title,attr"`
	Type     string        `xml:"type,attr"`
	XMLURL   string        `xml:"xmlUrl,attr"`
	HTMLURL  string        `xml:"htmlUrl,attr"`
	Children []opmlOutline `xml:"outline"`
}

type opmlDoc struct {
	XMLName xml.Name `xml:"opml"`
	Version string   `xml:"version,attr"`
	Head    struct {
		Title string `xml:"title"`
	} `xml:"head"`
	Body struct {
		Outlines []opmlOutline `xml:"outline"`
	} `xml:"body"`
}

type OPMLFailure struct{ URL, Reason string }

type OPMLResult struct {
	Added, Existed int
	Failed         []OPMLFailure
}

// ImportOPML 导入播客 App / 阅读器导出的订阅列表:方式按内容自动定(播客下载附件、其余只记录),
// 不补下旧内容,只接以后的新条目。四个一起抓。
func (f *Feeds) ImportOPML(ctx context.Context, owner uuid.UUID, src string) (*OPMLResult, error) {
	var doc opmlDoc
	d := xml.NewDecoder(strings.NewReader(src))
	d.Strict = false
	if err := d.Decode(&doc); err != nil {
		return nil, errf("INVALID_INPUT", "不是 OPML 文件")
	}
	var urls []string
	var walk func([]opmlOutline)
	walk = func(os []opmlOutline) {
		for _, o := range os {
			if o.XMLURL != "" {
				urls = append(urls, strings.TrimSpace(o.XMLURL))
			}
			walk(o.Children)
		}
	}
	walk(doc.Body.Outlines)
	if len(urls) == 0 {
		return nil, errf("INVALID_INPUT", "OPML 里没有订阅地址")
	}
	if len(urls) > maxOPMLFeeds {
		return nil, errf("INVALID_INPUT", "一次最多导入 %d 个", maxOPMLFeeds)
	}
	res := &OPMLResult{}
	var mu sync.Mutex
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for _, u := range urls {
		wg.Add(1)
		sem <- struct{}{}
		go func(u string) {
			defer wg.Done()
			defer func() { <-sem }()
			mode := FeedModeNone
			if p, err := f.Preview(ctx, u, "", false); err == nil && p.Podcast {
				mode = FeedModeDownload
			}
			_, err := f.Create(ctx, owner, FeedInput{URL: u, Mode: mode, Initial: 0})
			mu.Lock()
			defer mu.Unlock()
			var se *Error
			switch {
			case err == nil:
				res.Added++
			case errors.As(err, &se) && se.Code == "FEED_EXISTS":
				res.Existed++
			default:
				res.Failed = append(res.Failed, OPMLFailure{URL: u, Reason: describeFeedErr(err)})
			}
		}(u)
	}
	wg.Wait()
	return res, nil
}

// ExportOPML RSS 订阅导出成 OPML(GitHub 订阅不在里面)。
func (f *Feeds) ExportOPML(ctx context.Context, owner uuid.UUID) (string, error) {
	rows, err := f.q.ListFeeds(ctx, owner)
	if err != nil {
		return "", err
	}
	doc := opmlDoc{Version: "2.0"}
	doc.Head.Title = "gopan 订阅"
	for _, r := range rows {
		if r.Kind != FeedRSS {
			continue
		}
		doc.Body.Outlines = append(doc.Body.Outlines, opmlOutline{Text: r.Title, Title: r.Title, Type: "rss", XMLURL: r.Url, HTMLURL: r.SiteUrl})
	}
	b, err := xml.MarshalIndent(doc, "", "  ")
	if err != nil {
		return "", err
	}
	return xml.Header + string(b) + "\n", nil
}
