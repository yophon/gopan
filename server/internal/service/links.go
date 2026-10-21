package service

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// 直链状态(与 GraphQL LinkStatus 同名)。
const (
	LinkActive      = "ACTIVE"
	LinkPaused      = "PAUSED"
	LinkExpired     = "EXPIRED"
	LinkUnavailable = "UNAVAILABLE"
)

const (
	linksPageSize     = 50
	maxLinksPerCall   = 100
	linkCacheTTL      = 15 * time.Second
	linkConcurrency   = 16
	defaultImgFolder  = "/图床"
	maxRefererHosts   = 50
	minLinkDailyBytes = 1 << 20
	orderLinks        = "LINKS"
)

type LinkConfig struct {
	Enabled      bool
	DailyBytes   int64 // 每条每天默认上限
	MonthlyBytes int64 // 全站每月上限
	ImgMaxSize   int64
	PublicURL    string
	Loc          *time.Location
}

// Links 直链与图床(doc/22 四)。字节经 Go 中转:http.ServeContent 从 MinIO 读,边发边计数。
type Links struct {
	pool    *pgxpool.Pool
	q       *store.Queries
	obj     *objstore.Store
	uploads *Uploads
	notify  *Notifier
	cfg     LinkConfig
	meter   *linkMeter

	gate       chan struct{}
	ipLimiter  *keyedLimiter
	imgLimiter *keyedLimiter
	imgGate    chan struct{}

	cacheMu sync.Mutex
	cache   map[string]cachedLink
}

type cachedLink struct {
	row store.ResolveLinkRow
	at  time.Time
}

func NewLinks(pool *pgxpool.Pool, obj *objstore.Store, uploads *Uploads, notify *Notifier, cfg LinkConfig) *Links {
	if cfg.Loc == nil {
		cfg.Loc = time.Local
	}
	cfg.PublicURL = strings.TrimRight(cfg.PublicURL, "/")
	s := &Links{
		pool: pool, q: store.New(pool), obj: obj, uploads: uploads, notify: notify, cfg: cfg,
		gate:       make(chan struct{}, linkConcurrency),
		ipLimiter:  newKeyedLimiter(500*time.Millisecond, 120), // 每 IP 每分钟 120 次
		imgLimiter: newKeyedLimiter(2*time.Second, 30),        // 图床每人每分钟 30 张
		imgGate:    make(chan struct{}, 4),
		cache:      map[string]cachedLink{},
	}
	s.meter = newLinkMeter(s)
	return s
}

func (s *Links) Enabled() bool         { return s.cfg.Enabled }
func (s *Links) Config() LinkConfig    { return s.cfg }
func (s *Links) Location() *time.Location { return s.cfg.Loc }

// LinkView 一条直链加上文件与统计,给 GraphQL 用。
type LinkView struct {
	store.Link
	NodeName      string
	NodeDeleted   bool
	Mime          *string
	Size          *int64
	OwnerDisabled bool
	Usage         LinkUsage
}

type LinkUsage struct {
	TodayHits, TodayBytes, TodayBlocked int64
	Hits, Bytes, Blocked                int64 // 近 30 天
}

type LinkMeta struct {
	Source, IP, UA string
	APIKeyID       *uuid.UUID
}

// LinkSettings 整表单:指针为 nil = 用默认规则 / 永久。
type LinkSettings struct {
	RefererHosts   *[]string
	AllowNoReferer *bool
	DailyBytesCap  *int64
	ExpiresAt      *time.Time
	AutoPause      bool
}

func (s *Links) Path(token, name string) string {
	return "/x/" + token + "/" + url.PathEscape(name)
}

func (s *Links) URL(token, name string) string {
	return s.cfg.PublicURL + s.Path(token, name)
}

// Status 直链现在的状态:过期 > 不可用(文件在回收站、属主停用、全站关了或本月流量用完)> 暂停 > 正常。
func (s *Links) Status(v LinkView) string {
	switch {
	case v.ExpiresAt.Valid && !v.ExpiresAt.Time.After(time.Now()):
		return LinkExpired
	case !s.cfg.Enabled || v.NodeDeleted || v.OwnerDisabled || s.meter.monthExceeded():
		return LinkUnavailable
	case v.PausedAt.Valid:
		return LinkPaused
	}
	return LinkActive
}

// ---------- 创建与管理 ----------

// CreateLinks 每个文件同时只有一条有效直链,已有的直接返回。
func (s *Links) CreateLinks(ctx context.Context, owner uuid.UUID, nodeIDs []uuid.UUID, meta LinkMeta) ([]LinkView, error) {
	if !s.cfg.Enabled {
		return nil, errf("LINKS_DISABLED", "本站没有开直链")
	}
	if len(nodeIDs) == 0 || len(nodeIDs) > maxLinksPerCall {
		return nil, errf("INVALID_INPUT", "一次 1 到 %d 个文件", maxLinksPerCall)
	}
	out := make([]LinkView, 0, len(nodeIDs))
	for _, id := range nodeIDs {
		l, err := s.createOne(ctx, owner, id, meta)
		if err != nil {
			return nil, err
		}
		v, err := s.view(ctx, owner, l.ID)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

func (s *Links) createOne(ctx context.Context, owner, nodeID uuid.UUID, meta LinkMeta) (store.Link, error) {
	n, err := s.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.Link{}, ErrNotFound
	}
	if err != nil {
		return store.Link{}, err
	}
	if n.Kind != "file" || n.BlobSha256 == nil {
		return store.Link{}, errf("INVALID_INPUT", "只能给文件生成直链")
	}
	if l, err := s.q.GetActiveLinkByNode(ctx, store.GetActiveLinkByNodeParams{NodeID: nodeID, OwnerID: owner}); err == nil {
		return l, nil
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return store.Link{}, err
	}
	hasGPS := false
	if n.BlobMime != nil && *n.BlobMime == "image/jpeg" {
		hasGPS = s.blobHasGPS(ctx, *n.BlobSha256)
	}
	for range 3 {
		tok, err := shareToken()
		if err != nil {
			return store.Link{}, err
		}
		l, err := s.q.CreateLink(ctx, store.CreateLinkParams{
			ID: uuid.Must(uuid.NewV7()), OwnerID: owner, NodeID: nodeID, Token: tok, Source: meta.Source,
			CreatedIp: strPtrOrNil(meta.IP), CreatedUa: strPtrOrNil(truncateRunes(meta.UA, sessionMaxUA)),
			ApiKeyID: meta.APIKeyID, HasGps: hasGPS,
		})
		if err == nil {
			return l, nil
		}
		if !isUniqueViolation(err) {
			return store.Link{}, err
		}
		// 撞了 token,或同一个文件被并发建了一条:有了就用那条
		if l, err := s.q.GetActiveLinkByNode(ctx, store.GetActiveLinkByNodeParams{NodeID: nodeID, OwnerID: owner}); err == nil {
			return l, nil
		}
	}
	return store.Link{}, errf("INTERNAL", "生成直链失败,稍后重试")
}

func (s *Links) blobHasGPS(ctx context.Context, sha string) bool {
	r, err := s.obj.Open(ctx, objstore.BlobKey(sha))
	if err != nil {
		return false
	}
	defer r.Close()
	head, _ := io.ReadAll(io.LimitReader(r, exifScanBytes))
	return JPEGGPS(head, false)
}

func strPtrOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func (s *Links) view(ctx context.Context, owner, id uuid.UUID) (LinkView, error) {
	row, err := s.q.GetLinkRow(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) || err == nil && row.Link.OwnerID != owner {
		return LinkView{}, ErrNotFound
	}
	if err != nil {
		return LinkView{}, err
	}
	v := LinkView{
		Link: row.Link, NodeName: row.NodeName, NodeDeleted: row.NodeDeletedAt.Valid,
		Mime: row.BlobMime, Size: row.BlobSize, OwnerDisabled: row.OwnerDisabledAt.Valid,
	}
	vs := []LinkView{v}
	if err := s.fillUsage(ctx, vs); err != nil {
		return LinkView{}, err
	}
	return vs[0], nil
}

func (s *Links) Get(ctx context.Context, owner, id uuid.UUID) (LinkView, error) {
	return s.view(ctx, owner, id)
}

// NodeLink 文件当前有效的直链,没有返回 nil。
func (s *Links) NodeLink(ctx context.Context, owner, nodeID uuid.UUID) (*LinkView, error) {
	l, err := s.q.GetActiveLinkByNode(ctx, store.GetActiveLinkByNodeParams{NodeID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	v, err := s.view(ctx, owner, l.ID)
	if err != nil {
		return nil, err
	}
	return &v, nil
}

func (s *Links) List(ctx context.Context, owner uuid.UUID, cursor *string) ([]LinkView, *string, int, error) {
	p := store.ListLinksParams{OwnerID: owner, Lim: linksPageSize}
	if c := decodeCursor(cursor, orderLinks, false); c != nil {
		p.BeforeID = c.ID
		p.BeforeAt = pgtype.Timestamptz{Time: time.Unix(0, c.T), Valid: true}
	}
	rows, err := s.q.ListLinks(ctx, p)
	if err != nil {
		return nil, nil, 0, err
	}
	total, err := s.q.CountLinks(ctx, owner)
	if err != nil {
		return nil, nil, 0, err
	}
	out := make([]LinkView, len(rows))
	for i, r := range rows {
		out[i] = LinkView{
			Link: r.Link, NodeName: r.NodeName, NodeDeleted: r.NodeDeletedAt.Valid,
			Mime: r.BlobMime, Size: r.BlobSize, OwnerDisabled: r.OwnerDisabledAt.Valid,
		}
	}
	if err := s.fillUsage(ctx, out); err != nil {
		return nil, nil, 0, err
	}
	var next *string
	if len(rows) == linksPageSize {
		last := rows[len(rows)-1].Link
		next = encodeCursor(cursorPayload{O: orderLinks, T: last.CreatedAt.Time.UnixNano(), ID: last.ID})
	}
	return out, next, int(total), nil
}

func (s *Links) fillUsage(ctx context.Context, vs []LinkView) error {
	if len(vs) == 0 {
		return nil
	}
	ids := make([]uuid.UUID, len(vs))
	for i, v := range vs {
		ids[i] = v.ID
	}
	now := time.Now()
	rows, err := s.q.LinkUsage(ctx, store.LinkUsageParams{
		Ids: ids, Today: tsz(s.dayStart(now)), Since: tsz(now.Add(-30 * 24 * time.Hour).Truncate(time.Hour)),
	})
	if err != nil {
		return err
	}
	by := make(map[uuid.UUID]store.LinkUsageRow, len(rows))
	for _, r := range rows {
		by[r.LinkID] = r
	}
	for i := range vs {
		r := by[vs[i].ID]
		vs[i].Usage = LinkUsage{
			TodayHits: r.TodayHits, TodayBytes: r.TodayBytes, TodayBlocked: r.TodayBlocked,
			Hits: r.Hits, Bytes: r.Bytes, Blocked: r.Blocked,
		}
	}
	return nil
}

func (s *Links) Update(ctx context.Context, owner, id uuid.UUID, in LinkSettings) (LinkView, error) {
	var hosts []string
	if in.RefererHosts != nil {
		var err error
		if hosts, err = NormalizeHosts(*in.RefererHosts); err != nil {
			return LinkView{}, err
		}
		if hosts == nil {
			hosts = []string{}
		}
	}
	if in.DailyBytesCap != nil && *in.DailyBytesCap < minLinkDailyBytes {
		return LinkView{}, errf("INVALID_INPUT", "每天的上限至少 1 MB")
	}
	var exp pgtype.Timestamptz
	if in.ExpiresAt != nil {
		if !in.ExpiresAt.After(time.Now()) {
			return LinkView{}, errf("INVALID_INPUT", "有效期要晚于现在")
		}
		exp = tsz(*in.ExpiresAt)
	}
	l, err := s.q.UpdateLinkSettings(ctx, store.UpdateLinkSettingsParams{
		ID: id, OwnerID: owner, RefererHosts: hosts, AllowNoReferer: in.AllowNoReferer,
		DailyBytesCap: in.DailyBytesCap, ExpiresAt: exp, AutoPause: in.AutoPause,
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return LinkView{}, ErrNotFound
	}
	if err != nil {
		return LinkView{}, err
	}
	s.forget(l.Token)
	return s.view(ctx, owner, id)
}

func (s *Links) Pause(ctx context.Context, owner, id uuid.UUID, paused bool) (LinkView, error) {
	l, err := s.q.SetLinkPaused(ctx, store.SetLinkPausedParams{ID: id, OwnerID: owner, Paused: paused})
	if errors.Is(err, pgx.ErrNoRows) {
		return LinkView{}, ErrNotFound
	}
	if err != nil {
		return LinkView{}, err
	}
	s.forget(l.Token)
	return s.view(ctx, owner, id)
}

func (s *Links) Revoke(ctx context.Context, owner, id uuid.UUID) error {
	tok, err := s.q.RevokeLink(ctx, store.RevokeLinkParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	s.forget(tok)
	return nil
}

// ---------- 默认规则 ----------

type LinkPrefs struct {
	RefererHosts   []string
	AllowNoReferer bool
	ImageFolder    string
	StripGPS       bool
}

func (s *Links) Prefs(ctx context.Context, owner uuid.UUID) (LinkPrefs, error) {
	p, err := s.q.GetLinkPrefs(ctx, owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return LinkPrefs{RefererHosts: []string{}, AllowNoReferer: true, ImageFolder: defaultImgFolder, StripGPS: true}, nil
	}
	if err != nil {
		return LinkPrefs{}, err
	}
	return LinkPrefs{RefererHosts: p.RefererHosts, AllowNoReferer: p.AllowNoReferer, ImageFolder: p.ImageFolder, StripGPS: p.StripGps}, nil
}

func (s *Links) SetPrefs(ctx context.Context, owner uuid.UUID, in LinkPrefs) (LinkPrefs, error) {
	hosts, err := NormalizeHosts(in.RefererHosts)
	if err != nil {
		return LinkPrefs{}, err
	}
	if hosts == nil {
		hosts = []string{}
	}
	folder, _, err := normalizeFolderPath(in.ImageFolder)
	if err != nil {
		return LinkPrefs{}, err
	}
	p, err := s.q.UpsertLinkPrefs(ctx, store.UpsertLinkPrefsParams{
		OwnerID: owner, RefererHosts: hosts, AllowNoReferer: in.AllowNoReferer, ImageFolder: folder, StripGps: in.StripGPS,
	})
	if err != nil {
		return LinkPrefs{}, err
	}
	s.forgetAll() // 默认规则影响这个人所有的直链
	return LinkPrefs{RefererHosts: p.RefererHosts, AllowNoReferer: p.AllowNoReferer, ImageFolder: p.ImageFolder, StripGPS: p.StripGps}, nil
}

// MonthlyUsed 全站本月直链流量(含还没落库的)。
func (s *Links) MonthlyUsed() int64 { return s.meter.monthBytes() }

// normalizeFolderPath "/图床/" → "/图床",返回各级名字;根目录不行(图床文件要有个家)。
func normalizeFolderPath(p string) (string, []string, error) {
	var segs []string
	for _, seg := range strings.Split(strings.TrimSpace(p), "/") {
		seg = strings.TrimSpace(seg)
		if seg == "" {
			continue
		}
		if err := validateName(seg); err != nil {
			return "", nil, errf("INVALID_INPUT", "图床文件夹路径里有不合法的名字:%s", seg)
		}
		segs = append(segs, seg)
	}
	if len(segs) == 0 || len(segs) > 10 {
		return "", nil, errf("INVALID_INPUT", "图床文件夹要是 1 到 10 层的路径,如 /图床")
	}
	return "/" + strings.Join(segs, "/"), segs, nil
}

// NormalizeHosts 来源白名单:去掉协议、路径、端口与开头的 *. 和 www.,转小写,去重。
func NormalizeHosts(in []string) ([]string, error) {
	var out []string
	seen := map[string]bool{}
	for _, h := range in {
		h = strings.ToLower(strings.TrimSpace(h))
		if h == "" {
			continue
		}
		if i := strings.Index(h, "://"); i >= 0 {
			h = h[i+3:]
		}
		if i := strings.IndexAny(h, "/?#"); i >= 0 {
			h = h[:i]
		}
		if i := strings.LastIndex(h, "@"); i >= 0 {
			h = h[i+1:]
		}
		if host, _, ok := strings.Cut(h, ":"); ok && !strings.Contains(h, "]") {
			h = host
		}
		h = strings.TrimPrefix(h, "*.")
		h = strings.TrimPrefix(h, "www.")
		h = strings.Trim(h, ".")
		if h == "" || len(h) > 253 || strings.ContainsAny(h, " \t*") {
			return nil, errf("INVALID_INPUT", "来源站点写得不对:%s", h)
		}
		if !seen[h] {
			seen[h] = true
			out = append(out, h)
		}
	}
	if len(out) > maxRefererHosts {
		return nil, errf("INVALID_INPUT", "来源白名单最多 %d 个站点", maxRefererHosts)
	}
	return out, nil
}

// ---------- 统计 ----------

func (s *Links) owned(ctx context.Context, owner, id uuid.UUID) error {
	row, err := s.q.GetLinkRow(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) || err == nil && row.Link.OwnerID != owner {
		return ErrNotFound
	}
	return err
}

func (s *Links) Stats(ctx context.Context, owner, id uuid.UUID, hours int) ([]store.LinkStat, error) {
	if err := s.owned(ctx, owner, id); err != nil {
		return nil, err
	}
	hours = max(1, min(hours, 2160))
	return s.q.ListLinkStats(ctx, store.ListLinkStatsParams{LinkID: id, Hour: tsz(time.Now().Truncate(time.Hour).Add(-time.Duration(hours-1) * time.Hour))})
}

func (s *Links) Referers(ctx context.Context, owner, id uuid.UUID, days int) ([]store.ListLinkReferersRow, error) {
	if err := s.owned(ctx, owner, id); err != nil {
		return nil, err
	}
	days = max(1, min(days, 90))
	since := s.dayStart(time.Now()).AddDate(0, 0, -(days - 1))
	return s.q.ListLinkReferers(ctx, store.ListLinkReferersParams{LinkID: id, Day: pgDate(since)})
}

const linkHitsPageSize = 50

func (s *Links) Hits(ctx context.Context, owner, id uuid.UUID, cursor *string) ([]store.LinkHit, *string, error) {
	if err := s.owned(ctx, owner, id); err != nil {
		return nil, nil, err
	}
	var before int64
	if cursor != nil && *cursor != "" {
		v, err := strconv.ParseInt(decodeB64(*cursor), 10, 64)
		if err != nil || v <= 0 {
			return nil, nil, errf("INVALID_INPUT", "非法游标")
		}
		before = v
	}
	rows, err := s.q.ListLinkHits(ctx, store.ListLinkHitsParams{LinkID: id, BeforeID: before, Lim: linkHitsPageSize})
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) == linkHitsPageSize {
		c := encodeB64(strconv.FormatInt(rows[len(rows)-1].ID, 10))
		next = &c
	}
	return rows, next, nil
}

// ---------- 管理端 ----------

type AdminLinkStats struct {
	MonthHits, MonthBytes int64
	Top                   []store.AdminTopLinksRow
}

func (s *Links) requireAdmin(ctx context.Context, caller uuid.UUID) error {
	u, err := s.q.GetUserByID(ctx, caller)
	if err != nil || !u.IsAdmin || u.DisabledAt.Valid {
		return ErrForbidden
	}
	return nil
}

func (s *Links) AdminStats(ctx context.Context, caller uuid.UUID) (AdminLinkStats, error) {
	if err := s.requireAdmin(ctx, caller); err != nil {
		return AdminLinkStats{}, err
	}
	since := tsz(s.monthStart(time.Now()))
	u, err := s.q.SiteLinkUsageSince(ctx, since)
	if err != nil {
		return AdminLinkStats{}, err
	}
	top, err := s.q.AdminTopLinks(ctx, store.AdminTopLinksParams{Since: since, Lim: 10})
	if err != nil {
		return AdminLinkStats{}, err
	}
	return AdminLinkStats{MonthHits: u.Hits, MonthBytes: max(u.Bytes, s.meter.monthBytes()), Top: top}, nil
}

// AdminStatus 管理端列表里的状态(没有 LinkView 那么全)。
func (s *Links) AdminStatus(r store.AdminTopLinksRow) string {
	return s.Status(LinkView{
		Link:        store.Link{ExpiresAt: r.ExpiresAt, PausedAt: r.PausedAt},
		NodeDeleted: r.NodeDeletedAt.Valid, OwnerDisabled: r.OwnerDisabledAt.Valid,
	})
}

func (s *Links) AdminPause(ctx context.Context, caller, id uuid.UUID, paused bool) error {
	if err := s.requireAdmin(ctx, caller); err != nil {
		return err
	}
	n, err := s.q.AdminSetLinkPaused(ctx, store.AdminSetLinkPausedParams{ID: id, Paused: paused})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	s.forgetAll()
	return nil
}

func (s *Links) AdminRevoke(ctx context.Context, caller, id uuid.UUID) error {
	if err := s.requireAdmin(ctx, caller); err != nil {
		return err
	}
	tok, err := s.q.AdminRevokeLink(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	s.forget(tok)
	return nil
}

// ---------- 时间与缓存 ----------

func (s *Links) dayStart(t time.Time) time.Time {
	t = t.In(s.cfg.Loc)
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, s.cfg.Loc)
}

func (s *Links) monthStart(t time.Time) time.Time {
	t = t.In(s.cfg.Loc)
	return time.Date(t.Year(), t.Month(), 1, 0, 0, 0, 0, s.cfg.Loc)
}

func tsz(t time.Time) pgtype.Timestamptz { return pgtype.Timestamptz{Time: t, Valid: true} }

func pgDate(t time.Time) pgtype.Date {
	return pgtype.Date{Time: time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC), Valid: true}
}

func (s *Links) resolve(ctx context.Context, token string) (store.ResolveLinkRow, error) {
	s.cacheMu.Lock()
	c, ok := s.cache[token]
	s.cacheMu.Unlock()
	if ok && time.Since(c.at) < linkCacheTTL {
		return c.row, nil
	}
	row, err := s.q.ResolveLink(ctx, token)
	if err != nil {
		return row, err
	}
	s.cacheMu.Lock()
	if len(s.cache) > 10000 {
		clear(s.cache)
	}
	s.cache[token] = cachedLink{row: row, at: time.Now()}
	s.cacheMu.Unlock()
	return row, nil
}

func (s *Links) forget(token string) {
	s.cacheMu.Lock()
	delete(s.cache, token)
	s.cacheMu.Unlock()
}

func (s *Links) forgetAll() {
	s.cacheMu.Lock()
	clear(s.cache)
	s.cacheMu.Unlock()
}

// Run 计数落库(30 秒)、异常检查与恢复日上限暂停(5 分钟)、清理(每天),阻塞到 ctx 取消;退出前把计数冲进库。
func (s *Links) Run(ctx context.Context) {
	flush := time.NewTicker(30 * time.Second)
	check := time.NewTicker(5 * time.Minute)
	daily := time.NewTicker(24 * time.Hour)
	defer flush.Stop()
	defer check.Stop()
	defer daily.Stop()
	for {
		select {
		case <-ctx.Done():
			fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
			s.meter.flush(fctx)
			cancel()
			return
		case <-flush.C:
			s.meter.flush(ctx)
		case <-check.C:
			s.Check(ctx)
		case <-daily.C:
			s.Cleanup(ctx)
		}
	}
}

// Cleanup 统计 90 天、明细 30 天、吊销的直链 90 天后连同统计删掉。
func (s *Links) Cleanup(ctx context.Context) {
	for name, f := range map[string]func(context.Context) (int64, error){
		"link_stats": s.q.CleanupLinkStats, "link_referers": s.q.CleanupLinkReferers,
		"link_hits": s.q.CleanupLinkHits, "revoked_links": s.q.CleanupRevokedLinks,
	} {
		if _, err := f(ctx); err != nil {
			slog.Warn("cleanup "+name, "err", err)
		}
	}
}
