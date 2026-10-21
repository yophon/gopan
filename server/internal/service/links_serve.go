package service

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// LinkFile /x/{token} 放行后要发的对象。Release 必须调用(归还并发名额)。
type LinkFile struct {
	LinkID, OwnerID uuid.UUID
	Key             string
	Name            string
	Mime            string
	Size            int64
	ETag            string
	ModTime         time.Time
	Release         func()
}

// LinkDenied 被拦下的原因:Status 是要回的 HTTP 状态码;LinkID 为零值表示连直链都不存在(不记账)。
type LinkDenied struct {
	Status     int
	Message    string
	RetryAfter int
	LinkID     uuid.UUID
	OwnerID    uuid.UUID
}

func (d *LinkDenied) Error() string { return fmt.Sprintf("link denied %d", d.Status) }

// Open 判断一次 /x/ 访问放不放行(doc/22 四.1):存在与有效期 → 文件与属主 → 暂停与本月上限 → 来源白名单 → 单 IP 限流 →
// 当天上限 → 全站并发。size 为 "256" / "2048" 时取图片缩略图,没有就回落原图。
func (s *Links) Open(ctx context.Context, token, size string, h HitInfo) (*LinkFile, *LinkDenied) {
	if !s.cfg.Enabled {
		return nil, &LinkDenied{Status: http.StatusNotFound, Message: "链接不存在或已失效"}
	}
	row, err := s.resolve(ctx, token)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, &LinkDenied{Status: http.StatusNotFound, Message: "链接不存在或已失效"}
	}
	if err != nil {
		return nil, &LinkDenied{Status: http.StatusServiceUnavailable, Message: "暂时不可用,稍后再试", RetryAfter: 30}
	}
	l := row.Link
	deny := func(status int, msg string, retry int) (*LinkFile, *LinkDenied) {
		return nil, &LinkDenied{Status: status, Message: msg, RetryAfter: retry, LinkID: l.ID, OwnerID: l.OwnerID}
	}
	if l.ExpiresAt.Valid && !l.ExpiresAt.Time.After(time.Now()) ||
		row.NodeDeletedAt.Valid || row.OwnerDisabledAt.Valid || row.BlobSha256 == nil {
		return deny(http.StatusNotFound, "链接不存在或已失效", 0)
	}
	if l.PausedAt.Valid {
		return deny(http.StatusServiceUnavailable, "这条直链暂停了", 3600)
	}
	if s.meter.monthExceeded() {
		return deny(http.StatusServiceUnavailable, "本站本月的直链流量用完了", 3600)
	}
	allow, allowNo := l.RefererHosts, true
	if allow == nil {
		allow = row.PrefRefererHosts
	}
	if l.AllowNoReferer != nil {
		allowNo = *l.AllowNoReferer
	} else if row.PrefAllowNoReferer != nil {
		allowNo = *row.PrefAllowNoReferer
	}
	if !s.refererAllowed(h.RefererHost, allow, allowNo) {
		return deny(http.StatusForbidden, "这条直链不允许从这里引用", 0)
	}
	if !s.ipLimiter.Allow("x:" + h.IP) {
		return deny(http.StatusTooManyRequests, "请求太频繁,稍后再试", 60)
	}
	limit := s.cfg.DailyBytes
	if l.DailyBytesCap != nil {
		limit = *l.DailyBytesCap
	}
	if s.meter.todayBytes(ctx, l.ID) >= limit {
		if n, _ := s.q.AutoPauseLink(ctx, autoPauseParams(l.ID, "daily_cap")); n > 0 {
			s.forget(token)
			go s.alertDailyCap(l.ID, l.OwnerID, row.NodeName, limit)
		}
		return deny(http.StatusServiceUnavailable, "这条直链今天的流量用完了", 3600)
	}
	select {
	case s.gate <- struct{}{}:
	default:
		return deny(http.StatusServiceUnavailable, "访问的人太多,稍后再试", 10)
	}
	f := &LinkFile{
		LinkID: l.ID, OwnerID: l.OwnerID, Key: objstore.BlobKey(*row.BlobSha256), Name: row.NodeName,
		Size: *row.BlobSize, ETag: `"` + *row.BlobSha256 + `"`, ModTime: row.NodeUpdatedAt.Time,
		Release: func() { <-s.gate },
	}
	if row.BlobMime != nil {
		f.Mime = *row.BlobMime
	}
	if (size == "256" || size == "2048") && strings.HasPrefix(f.Mime, "image/") && f.Mime != "image/svg+xml" {
		key := objstore.DerivedKey(*row.BlobSha256, "thumb"+size)
		if n, err := s.obj.Stat(ctx, key); err == nil {
			f.Key, f.Size, f.Mime = key, n, "image/jpeg"
			f.ETag = `"` + *row.BlobSha256 + "-" + size + `"`
		}
	}
	return f, nil
}

// Record 记一次访问(放行的与被拦的都记);LinkID 为零值的不记。
func (s *Links) Record(link, owner uuid.UUID, h HitInfo, status int, bytes int64) {
	if link == uuid.Nil {
		return
	}
	served := status < 400
	s.meter.record(link, owner, h, status, bytes, served)
}

func (s *Links) refererAllowed(host string, allow []string, allowNoReferer bool) bool {
	if host == "" {
		return allowNoReferer
	}
	if host == hostOf(s.cfg.PublicURL) || len(allow) == 0 {
		return true // 自己的网页上预览、或者没设白名单
	}
	return hostAllowed(host, allow)
}

// hostAllowed 写 example.com 包含它的所有子域。
func hostAllowed(host string, allow []string) bool {
	for _, a := range allow {
		if host == a || strings.HasSuffix(host, "."+a) {
			return true
		}
	}
	return false
}

// RefererHost Referer 头里的主机名(小写、去端口、去 www.);拿不到为空。
func RefererHost(ref string) string {
	if ref == "" {
		return ""
	}
	u, err := url.Parse(ref)
	if err != nil || u.Hostname() == "" {
		return ""
	}
	return strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
}

func hostOf(raw string) string {
	return RefererHost(raw)
}

func (s *Links) alertDailyCap(link, owner uuid.UUID, name string, limit int64) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s.notify.Notify(ctx, owner, Event{
		Kind: EvLinkAlert, Level: LvWarn, Title: "直链今天的流量用完了:" + name,
		Body:   fmt.Sprintf("今天已经发出 %s,到了每天的上限,已暂停到明天。要继续用可以在直链设置里调高上限后手动恢复。", humanBytes(limit)),
		Target: &Target{Kind: TgLink, ID: link.String()},
		GroupKey: "link_cap:" + link.String() + ":" + s.dayStart(time.Now()).Format("2006-01-02"), Once: 24 * time.Hour,
	})
}

func autoPauseParams(id uuid.UUID, reason string) store.AutoPauseLinkParams {
	return store.AutoPauseLinkParams{ID: id, PauseReason: &reason}
}
