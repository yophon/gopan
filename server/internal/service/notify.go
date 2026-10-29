package service

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/store"
)

// 事件类型(doc/22 三.3)。新增类型时同步 EventKinds。
const (
	EvCollectUpload = "collect_upload"
	EvCollectFull   = "collect_full"
	EvShareUpload   = "share_upload"
	EvJobDone       = "job_done"
	EvJobFailed     = "job_failed"
	EvQuota         = "quota"
	EvLogin         = "login"
	EvCredential    = "credential"
	EvLinkAlert     = "link_alert"
	EvBackup        = "backup"
	EvMassDelete    = "mass_delete"
	EvFeedNew       = "feed_new"
	EvFeedFailed    = "feed_failed"
	EvAssistantWait = "assistant_wait"
	EvAssistantDone = "assistant_done"
)

const (
	LvInfo  = "info"
	LvWarn  = "warn"
	LvAlert = "alert"
)

type EventKind struct {
	Kind, Label            string
	DefaultPush, AdminOnly bool
}

var EventKinds = []EventKind{
	{EvCollectUpload, "收集链接收到文件", true, false},
	{EvCollectFull, "收集链接因空间不足拒收", true, false},
	{EvShareUpload, "分享收到对方传的文件", true, false},
	{EvJobDone, "离线下载 / 解压完成", false, false},
	{EvJobFailed, "离线下载 / 解压失败", true, false},
	{EvQuota, "空间快满", true, false},
	{EvLogin, "新登录", true, false},
	{EvCredential, "新的访问凭据", true, false},
	{EvLinkAlert, "直链异常", true, false},
	{EvMassDelete, "大量删除(WebDAV / Agent)", true, false},
	{EvFeedNew, "订阅有新内容", false, false},
	{EvFeedFailed, "订阅检查失败", true, false},
	{EvAssistantWait, "服务器上的助手等你确认", true, false},
	{EvAssistantDone, "服务器上的助手做完了", true, false},
	{EvBackup, "备份异常", true, true},
}

// EventKindsFor 管理员能订阅只发给管理员的事件。
func EventKindsFor(isAdmin bool) []EventKind {
	var out []EventKind
	for _, k := range EventKinds {
		if isAdmin || !k.AdminOnly {
			out = append(out, k)
		}
	}
	return out
}

// 点开通知跳到哪(与 GraphQL NotificationTargetKind 同名)。
const (
	TgNode        = "NODE"
	TgFileRequest = "FILE_REQUEST"
	TgLink        = "LINK"
	TgJobs        = "JOBS"
	TgSessions    = "SESSIONS"
	TgCredentials = "CREDENTIALS"
	TgAdmin       = "ADMIN"
	TgActivity    = "ACTIVITY" // id = 来源(kind:ref),parent_id = 从什么时候起(RFC 3339)
	TgFeed        = "FEED"
	TgAssistant   = "ASSISTANT" // id = 对话
)

type Target struct {
	Kind     string `json:"kind"`
	ID       string `json:"id,omitempty"`
	ParentID string `json:"parent_id,omitempty"`
}

// Event 一条要发的通知。GroupKey 非空时:Once 为 0 表示 30 分钟内有未读的同组消息就合并;
// Once 大于 0 表示这段时间里同组发过(读没读都算)就不再发(「每天一次」「7 天一次」)。
type Event struct {
	Kind, Level, Title, Body string
	Target                   *Target
	GroupKey                 string
	Once                     time.Duration
}

const (
	notifyPageSize        = 30
	maxNotifyChannels     = 10
	channelHourlyLimit    = 20
	deliveryMaxAttempts   = 4 // 首发 + 重试 3 次
	notifySendTimeout     = 10 * time.Second
	maxNotifyErrorMessage = 300
)

var deliveryBackoff = []time.Duration{30 * time.Second, 2 * time.Minute, 10 * time.Minute}

// Notifier 站内通知与外部推送(doc/22 三)。nil 也能用:所有方法什么都不做,测试与没接通知的地方不用判空。
type Notifier struct {
	pool      *pgxpool.Pool
	q         *store.Queries
	client    *http.Client
	publicURL string
	wake      chan struct{}
}

func NewNotifier(pool *pgxpool.Pool, guard fetcher.Guard, publicURL string) *Notifier {
	return &Notifier{
		pool: pool, q: store.New(pool), client: guard.Client(),
		publicURL: strings.TrimRight(publicURL, "/"), wake: make(chan struct{}, 1),
	}
}

// SetClient 测试用:换成能连 httptest 的客户端。
func (n *Notifier) SetClient(c *http.Client) { n.client = c }

// Notify 用连接池写一条通知。出错只记日志:通知不能拖垮触发它的业务。
func (n *Notifier) Notify(ctx context.Context, owner uuid.UUID, ev Event) {
	if n == nil {
		return
	}
	if err := n.write(ctx, n.q, owner, ev); err != nil {
		slog.Warn("notify", "kind", ev.Kind, "err", err)
	}
}

// NotifyTx 在调用方的事务里写(随事务一起提交或回滚),包在 savepoint 里:写失败不会把外面的事务弄成 aborted。
func (n *Notifier) NotifyTx(ctx context.Context, tx pgx.Tx, owner uuid.UUID, ev Event) {
	if n == nil {
		return
	}
	sp, err := tx.Begin(ctx)
	if err == nil {
		if err = n.write(ctx, n.q.WithTx(sp), owner, ev); err == nil {
			err = sp.Commit(ctx)
		} else {
			_ = sp.Rollback(ctx)
		}
	}
	if err != nil {
		slog.Warn("notify", "kind", ev.Kind, "err", err)
	}
}

// NotifyAdmins 发给每个没被停用的管理员。
func (n *Notifier) NotifyAdmins(ctx context.Context, ev Event) {
	if n == nil {
		return
	}
	ids, err := n.q.ListAdminIDs(ctx)
	if err != nil {
		slog.Warn("notify admins", "err", err)
		return
	}
	for _, id := range ids {
		n.Notify(ctx, id, ev)
	}
}

func (n *Notifier) write(ctx context.Context, q *store.Queries, owner uuid.UUID, ev Event) error {
	var group *string
	if ev.GroupKey != "" {
		group = &ev.GroupKey
	}
	var target []byte
	if ev.Target != nil {
		target, _ = json.Marshal(ev.Target)
	}
	title, body := truncateRunes(ev.Title, 200), truncateRunes(ev.Body, 2000)
	if group != nil {
		if ev.Once > 0 {
			seen, err := q.RecentNotificationExists(ctx, store.RecentNotificationExistsParams{
				OwnerID: owner, GroupKey: group, CreatedAt: pgtype.Timestamptz{Time: time.Now().Add(-ev.Once), Valid: true},
			})
			if err != nil || seen {
				return err
			}
		} else {
			_, err := q.MergeNotification(ctx, store.MergeNotificationParams{
				OwnerID: owner, GroupKey: group, Level: ev.Level, Title: title, Body: body, Target: target,
			})
			if err == nil {
				return nil // 合并进已有的未读消息,不再推送
			}
			if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
		}
	}
	id, err := q.InsertNotification(ctx, store.InsertNotificationParams{
		OwnerID: owner, Kind: ev.Kind, Level: ev.Level, Title: title, Body: body, Target: target, GroupKey: group,
	})
	if err != nil {
		return err
	}
	chans, err := q.ListChannelsForEvent(ctx, store.ListChannelsForEventParams{OwnerID: owner, Kind: ev.Kind})
	if err != nil {
		return err
	}
	queued := false
	hourAgo := pgtype.Timestamptz{Time: time.Now().Add(-time.Hour), Valid: true}
	for _, ch := range chans {
		// 每个渠道每小时最多推 20 条,超出的只留在站内
		cnt, err := q.CountChannelDeliveriesSince(ctx, store.CountChannelDeliveriesSinceParams{ChannelID: ch.ID, CreatedAt: hourAgo})
		if err != nil {
			return err
		}
		if cnt >= channelHourlyLimit {
			continue
		}
		if err := q.InsertDelivery(ctx, store.InsertDeliveryParams{NotificationID: id, ChannelID: ch.ID}); err != nil {
			return err
		}
		queued = true
	}
	if queued {
		// 可能还在调用方的事务里:晚一点再唤醒,别让投递循环扑空(扑空也有 30 秒兜底)
		time.AfterFunc(time.Second, n.poke)
	}
	return nil
}

func (n *Notifier) poke() {
	select {
	case n.wake <- struct{}{}:
	default:
	}
}

// ---------- 站内列表 ----------

func (n *Notifier) List(ctx context.Context, owner uuid.UUID, cursor *string, unreadOnly bool) ([]store.Notification, *string, error) {
	var before int64
	if cursor != nil && *cursor != "" {
		v, err := strconv.ParseInt(decodeB64(*cursor), 10, 64)
		if err != nil || v <= 0 {
			return nil, nil, errf("INVALID_INPUT", "非法游标")
		}
		before = v
	}
	rows, err := n.q.ListNotifications(ctx, store.ListNotificationsParams{
		OwnerID: owner, UnreadOnly: unreadOnly, BeforeID: before, Lim: notifyPageSize,
	})
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) == notifyPageSize {
		c := encodeB64(strconv.FormatInt(rows[len(rows)-1].ID, 10))
		next = &c
	}
	return rows, next, nil
}

func (n *Notifier) Unread(ctx context.Context, owner uuid.UUID) (int, error) {
	c, err := n.q.CountUnreadNotifications(ctx, owner)
	return int(c), err
}

// MarkRead ids 为空 = 全部。
func (n *Notifier) MarkRead(ctx context.Context, owner uuid.UUID, ids []int64) (int64, error) {
	if len(ids) == 0 {
		return n.q.MarkAllNotificationsRead(ctx, owner)
	}
	return n.q.MarkNotificationsRead(ctx, store.MarkNotificationsReadParams{OwnerID: owner, Ids: ids})
}

func (n *Notifier) Clear(ctx context.Context, owner uuid.UUID) (int64, error) {
	return n.q.DeleteAllNotifications(ctx, owner)
}

// ---------- 渠道 ----------

const (
	ChWebhook  = "webhook"
	ChNtfy     = "ntfy"
	ChBark     = "bark"
	ChTelegram = "telegram"
)

type ChannelInput struct {
	Kind, Name string
	Enabled    bool
	Events     []string
	URL, Topic string
	// nil = 保持原值;"" = 清除
	Secret *string
}

func (n *Notifier) Channels(ctx context.Context, owner uuid.UUID) ([]store.NotifyChannel, error) {
	return n.q.ListNotifyChannels(ctx, owner)
}

func (n *Notifier) CreateChannel(ctx context.Context, owner uuid.UUID, in ChannelInput) (store.NotifyChannel, error) {
	cnt, err := n.q.CountNotifyChannels(ctx, owner)
	if err != nil {
		return store.NotifyChannel{}, err
	}
	if cnt >= maxNotifyChannels {
		return store.NotifyChannel{}, errf("LIMIT_EXCEEDED", "推送渠道最多 %d 个", maxNotifyChannels)
	}
	secret := ""
	if in.Secret != nil {
		secret = *in.Secret
	}
	if err := n.normalizeChannel(ctx, owner, &in, secret); err != nil {
		return store.NotifyChannel{}, err
	}
	return n.q.CreateNotifyChannel(ctx, store.CreateNotifyChannelParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, Kind: in.Kind, Name: in.Name, Url: in.URL, Topic: in.Topic,
		Secret: secret, Events: in.Events, Enabled: in.Enabled,
	})
}

func (n *Notifier) UpdateChannel(ctx context.Context, owner, id uuid.UUID, in ChannelInput) (store.NotifyChannel, error) {
	cur, err := n.q.GetNotifyChannel(ctx, store.GetNotifyChannelParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.NotifyChannel{}, ErrNotFound
	}
	if err != nil {
		return store.NotifyChannel{}, err
	}
	secret := cur.Secret
	if in.Secret != nil {
		secret = *in.Secret
	}
	if err := n.normalizeChannel(ctx, owner, &in, secret); err != nil {
		return store.NotifyChannel{}, err
	}
	return n.q.UpdateNotifyChannel(ctx, store.UpdateNotifyChannelParams{
		ID: id, OwnerID: owner, Kind: in.Kind, Name: in.Name, Url: in.URL, Topic: in.Topic,
		Secret: secret, Events: in.Events, Enabled: in.Enabled,
	})
}

func (n *Notifier) DeleteChannel(ctx context.Context, owner, id uuid.UUID) error {
	rows, err := n.q.DeleteNotifyChannel(ctx, store.DeleteNotifyChannelParams{ID: id, OwnerID: owner})
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

// TestChannel 立即发一条测试消息(不进站内列表),结果记到渠道上。
func (n *Notifier) TestChannel(ctx context.Context, owner, id uuid.UUID) error {
	ch, err := n.q.GetNotifyChannel(ctx, store.GetNotifyChannelParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	msg := pushMessage{
		Kind: "test", Level: LvInfo, Title: "gopan 测试消息",
		Body: "这个渠道能收到 gopan 的通知了。", URL: n.publicURL, CreatedAt: time.Now(),
	}
	if err := n.send(ctx, ch, msg); err != nil {
		e := truncateRunes(err.Error(), maxNotifyErrorMessage)
		_ = n.q.SetChannelError(ctx, store.SetChannelErrorParams{ID: ch.ID, LastError: &e})
		return errf("NOTIFY_FAILED", "%s", e)
	}
	_ = n.q.SetChannelOK(ctx, ch.ID)
	return nil
}

func (n *Notifier) normalizeChannel(ctx context.Context, owner uuid.UUID, in *ChannelInput, secret string) error {
	in.Name = strings.TrimSpace(in.Name)
	in.URL = strings.TrimSpace(in.URL)
	in.Topic = strings.TrimSpace(in.Topic)
	if in.Name == "" || utf8.RuneCountInString(in.Name) > 64 {
		return errf("INVALID_INPUT", "名称要 1 到 64 个字")
	}
	u, err := n.q.GetUserByID(ctx, owner)
	if err != nil {
		return err
	}
	allowed := map[string]bool{}
	for _, k := range EventKindsFor(u.IsAdmin) {
		allowed[k.Kind] = true
	}
	events := []string{}
	for _, e := range in.Events {
		if !allowed[e] {
			return errf("INVALID_INPUT", "未知事件类型 %s", e)
		}
		if !slices.Contains(events, e) {
			events = append(events, e)
		}
	}
	in.Events = events
	switch in.Kind {
	case ChWebhook:
		if in.URL == "" {
			return errf("INVALID_INPUT", "要填 Webhook 地址")
		}
	case ChNtfy:
		if in.URL == "" {
			in.URL = "https://ntfy.sh"
		}
		if !validNtfyTopic(in.Topic) {
			return errf("INVALID_INPUT", "ntfy topic 只能是 1 到 64 个字母、数字、- 或 _")
		}
	case ChBark:
		if in.URL == "" {
			in.URL = "https://api.day.app"
		}
		if secret == "" {
			return errf("INVALID_INPUT", "要填 Bark 的 device key")
		}
	case ChTelegram:
		if in.URL == "" {
			in.URL = "https://api.telegram.org"
		}
		if secret == "" || in.Topic == "" {
			return errf("INVALID_INPUT", "要填 bot token 和 chat id")
		}
	default:
		return errf("INVALID_INPUT", "未知渠道类型 %s", in.Kind)
	}
	if _, err := fetcher.ParseURL(in.URL); err != nil {
		return errf("INVALID_INPUT", "地址不对:只收 http / https")
	}
	in.URL = strings.TrimRight(in.URL, "/")
	return nil
}

func validNtfyTopic(s string) bool {
	if s == "" || len(s) > 64 {
		return false
	}
	for _, r := range s {
		if !(r == '-' || r == '_' || r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z') {
			return false
		}
	}
	return true
}

// ---------- 投递 ----------

// Run 投递循环:写入时唤醒,另有 30 秒兜底;阻塞到 ctx 取消。
func (n *Notifier) Run(ctx context.Context) {
	if n == nil {
		return
	}
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		for n.DeliverDue(ctx) > 0 {
		}
		select {
		case <-ctx.Done():
			return
		case <-n.wake:
		case <-tick.C:
		}
	}
}

// DeliverDue 领一批到期的投递发出去,返回领到的条数。
func (n *Notifier) DeliverDue(ctx context.Context) int {
	rows, err := n.q.ClaimDeliveries(ctx, 10)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("claim deliveries", "err", err)
		}
		return 0
	}
	for _, d := range rows {
		n.deliver(ctx, d)
	}
	return len(rows)
}

func (n *Notifier) deliver(ctx context.Context, d store.NotifyDelivery) {
	fail := func(msg string) {
		_ = n.q.FailDelivery(ctx, store.FailDeliveryParams{ID: d.ID, Error: &msg})
	}
	ch, err := n.q.GetNotifyChannelByID(ctx, d.ChannelID)
	if err != nil {
		fail("渠道已删除")
		return
	}
	if !ch.Enabled {
		fail("渠道已停用")
		return
	}
	nt, err := n.q.GetNotification(ctx, d.NotificationID)
	if err != nil {
		fail("通知已删除")
		return
	}
	msg := pushMessage{
		ID: nt.ID, Kind: nt.Kind, Level: nt.Level, Title: nt.Title, Body: nt.Body,
		URL: n.webURL(nt.Target), CreatedAt: nt.CreatedAt.Time,
	}
	err = n.send(ctx, ch, msg)
	if err == nil {
		_ = n.q.MarkDeliverySent(ctx, d.ID)
		_ = n.q.SetChannelOK(ctx, ch.ID)
		return
	}
	if ctx.Err() != nil {
		return // 停机:租约过期后重启的进程接着发
	}
	e := truncateRunes(err.Error(), maxNotifyErrorMessage)
	_ = n.q.SetChannelError(ctx, store.SetChannelErrorParams{ID: ch.ID, LastError: &e})
	if int(d.Attempts) >= deliveryMaxAttempts {
		fail(e)
		return
	}
	next := pgtype.Timestamptz{Time: time.Now().Add(deliveryBackoff[min(int(d.Attempts), len(deliveryBackoff))-1]), Valid: true}
	_ = n.q.RetryDelivery(ctx, store.RetryDeliveryParams{ID: d.ID, NextAt: next, Error: &e})
}

// webURL 推送点开的网页地址(没配站点地址时为空:推送不带链接)。
func (n *Notifier) webURL(raw []byte) string {
	if n.publicURL == "" {
		return ""
	}
	var t Target
	if len(raw) == 0 || json.Unmarshal(raw, &t) != nil {
		return n.publicURL + "/"
	}
	return n.publicURL + TargetPath(t)
}

// TargetPath 通知目标对应的网页路径(网页与安卓按同一套解析,见 doc/22 三.4)。
func TargetPath(t Target) string {
	switch t.Kind {
	case TgNode:
		p := "/drive"
		if t.ParentID != "" {
			p += "/" + t.ParentID
		}
		if t.ID != "" {
			p += "?open=" + t.ID
		}
		return p
	case TgAssistant:
		return "/drive?assistant=" + t.ID
	case TgFileRequest:
		return "/shares?tab=requests&id=" + t.ID
	case TgLink:
		return "/shares?tab=links&id=" + t.ID
	case TgJobs:
		return "/drive?panel=jobs"
	case TgSessions:
		return "/devices"
	case TgCredentials:
		if t.ID == "img" {
			return "/shares?tab=links&panel=keys"
		}
		if t.ID == "webdav" || t.ID == "mcp" {
			return "/drive?panel=" + t.ID
		}
		return "/drive"
	case TgAdmin:
		return "/admin"
	case TgActivity:
		v := url.Values{}
		if t.ID != "" {
			v.Set("actor", t.ID)
		}
		if t.ParentID != "" {
			v.Set("since", t.ParentID)
		}
		if len(v) == 0 {
			return "/activity"
		}
		return "/activity?" + v.Encode()
	case TgFeed:
		if t.ID == "" {
			return "/feeds"
		}
		return "/feeds?id=" + t.ID
	}
	return "/"
}

// ---------- 维护 ----------

// Cleanup 每日:通知保留 90 天、每人最多 1000 条;投递记录 30 天。
func (n *Notifier) Cleanup(ctx context.Context) {
	if n == nil {
		return
	}
	if c, err := n.q.CleanupNotifications(ctx); err != nil {
		slog.Warn("cleanup notifications", "err", err)
	} else if c > 0 {
		slog.Info("cleanup notifications", "deleted", c)
	}
	if _, err := n.q.CleanupDeliveries(ctx); err != nil {
		slog.Warn("cleanup deliveries", "err", err)
	}
}

const backupHeartbeatKey = "heartbeat:backup"

// Heartbeat 外部脚本报平安(gopan admin notify --heartbeat backup)。
func (n *Notifier) Heartbeat(ctx context.Context, name string) error {
	v, _ := json.Marshal(map[string]any{"at": time.Now().UTC()})
	return n.q.SetInstanceState(ctx, store.SetInstanceStateParams{Key: "heartbeat:" + name, Value: v})
}

// CheckHeartbeats 每小时:备份心跳超过 36 小时就提醒管理员(每天最多一次)。从没报过的不提醒。
func (n *Notifier) CheckHeartbeats(ctx context.Context) {
	if n == nil {
		return
	}
	st, err := n.q.GetInstanceState(ctx, backupHeartbeatKey)
	if errors.Is(err, pgx.ErrNoRows) {
		return
	}
	if err != nil {
		slog.Warn("check heartbeats", "err", err)
		return
	}
	age := time.Since(st.UpdatedAt.Time)
	if age < 36*time.Hour {
		return
	}
	n.NotifyAdmins(ctx, Event{
		Kind: EvBackup, Level: LvAlert, Title: "超过 36 小时没有备份",
		Body:     "上次备份成功是 " + st.UpdatedAt.Time.Local().Format("2006-01-02 15:04") + "。看一下宿主机上的 cron 与 /var/log/gopan-backup.log。",
		Target:   &Target{Kind: TgAdmin},
		GroupKey: "backup:stale", Once: 24 * time.Hour,
	})
}
