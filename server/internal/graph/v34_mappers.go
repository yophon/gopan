package graph

// This file will not be regenerated automatically.

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"time"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// ---------- 影音 ----------

func gqlPlayProgress(p *store.PlayProgress) *PlayProgress {
	if p == nil {
		return nil
	}
	return &PlayProgress{Position: p.Position, Duration: p.Duration, Finished: p.Finished, UpdatedAt: p.UpdatedAt.Time}
}

func gqlSubtitles(ts []service.SubtitleTrack) []*SubtitleTrack {
	out := make([]*SubtitleTrack, 0, len(ts))
	for _, t := range ts {
		out = append(out, &SubtitleTrack{NodeID: t.NodeID.String(), Name: t.Name, Label: t.Label, Lang: t.Lang, Default: t.Default})
	}
	return out
}

// ---------- 通知 ----------

func gqlNotification(n store.Notification) *Notification {
	out := &Notification{
		ID: strconv.FormatInt(n.ID, 10), Kind: n.Kind, Level: NotificationLevel(strings.ToUpper(n.Level)),
		Title: n.Title, Body: n.Body, Count: int(n.Count),
		CreatedAt: n.CreatedAt.Time, UpdatedAt: n.UpdatedAt.Time, Read: n.ReadAt.Valid,
	}
	var t service.Target
	if len(n.Target) > 0 && json.Unmarshal(n.Target, &t) == nil && t.Kind != "" {
		out.Target = &NotificationTarget{Kind: NotificationTargetKind(t.Kind)}
		if t.ID != "" {
			id := t.ID
			out.Target.ID = &id
		}
		if t.ParentID != "" {
			pid := t.ParentID
			out.Target.ParentID = &pid
		}
	}
	return out
}

func gqlNotifyChannel(c store.NotifyChannel) *NotifyChannel {
	out := &NotifyChannel{
		ID: c.ID.String(), Kind: NotifyChannelKind(strings.ToUpper(c.Kind)), Name: c.Name, Enabled: c.Enabled,
		Events: c.Events, URL: c.Url, Topic: c.Topic, HasSecret: c.Secret != "", LastError: c.LastError,
		CreatedAt: c.CreatedAt.Time,
	}
	if out.Events == nil {
		out.Events = []string{}
	}
	if c.Secret != "" {
		r := []rune(c.Secret)
		out.SecretHint = string(r[max(0, len(r)-4):])
	}
	if c.LastOkAt.Valid {
		t := c.LastOkAt.Time
		out.LastOkAt = &t
	}
	if c.LastErrorAt.Valid {
		t := c.LastErrorAt.Time
		out.LastErrorAt = &t
	}
	return out
}

func channelInput(in NotifyChannelInput) service.ChannelInput {
	out := service.ChannelInput{
		Kind: strings.ToLower(string(in.Kind)), Name: in.Name, Enabled: in.Enabled, Events: in.Events, Secret: in.Secret,
	}
	if in.URL != nil {
		out.URL = *in.URL
	}
	if in.Topic != nil {
		out.Topic = *in.Topic
	}
	return out
}

// ---------- 直链 ----------

func (r *Resolver) gqlLink(v service.LinkView) *DirectLink {
	out := &DirectLink{
		ID: v.ID.String(), Token: v.Token, Path: r.Links.Path(v.Token, v.NodeName), URL: r.Links.URL(v.Token, v.NodeName),
		NodeID: v.NodeID.String(), Name: v.NodeName, Mime: v.Mime, Size: v.Size,
		Status: LinkStatus(r.Links.Status(v)), PauseReason: v.PauseReason, Source: v.Source,
		CreatedAt: v.CreatedAt.Time, RefererHosts: v.RefererHosts, AllowNoReferer: v.AllowNoReferer,
		DailyBytesCap: v.DailyBytesCap, AutoPause: v.AutoPause, HasGps: v.HasGps,
		HitsTotal: v.HitsTotal, BytesTotal: v.BytesTotal,
		Today:      &LinkUsage{Hits: v.Usage.TodayHits, Bytes: v.Usage.TodayBytes, Blocked: v.Usage.TodayBlocked},
		Last30Days: &LinkUsage{Hits: v.Usage.Hits, Bytes: v.Usage.Bytes, Blocked: v.Usage.Blocked},
	}
	if !v.PausedAt.Valid {
		out.PauseReason = nil
	}
	if v.ExpiresAt.Valid {
		t := v.ExpiresAt.Time
		out.ExpiresAt = &t
	}
	if v.LastHitAt.Valid {
		t := v.LastHitAt.Time
		out.LastHitAt = &t
	}
	return out
}

func (r *Resolver) gqlLinks(vs []service.LinkView) []*DirectLink {
	out := make([]*DirectLink, 0, len(vs))
	for _, v := range vs {
		out = append(out, r.gqlLink(v))
	}
	return out
}

func (r *Resolver) gqlLinkPrefs(p service.LinkPrefs) *LinkPrefs {
	cfg := r.Links.Config()
	hosts := p.RefererHosts
	if hosts == nil {
		hosts = []string{}
	}
	return &LinkPrefs{
		RefererHosts: hosts, AllowNoReferer: p.AllowNoReferer, ImageFolder: p.ImageFolder, StripGps: p.StripGPS,
		DailyBytesCapDefault: cfg.DailyBytes, MonthlyBytesCap: cfg.MonthlyBytes, MonthlyBytesUsed: r.Links.MonthlyUsed(),
	}
}

func linkSettings(in DirectLinkInput) service.LinkSettings {
	out := service.LinkSettings{AllowNoReferer: in.AllowNoReferer, DailyBytesCap: in.DailyBytesCap, ExpiresAt: in.ExpiresAt, AutoPause: in.AutoPause}
	if in.RefererHosts != nil {
		h := in.RefererHosts
		out.RefererHosts = &h
	}
	return out
}

func tsPtr(t time.Time, valid bool) *time.Time {
	if !valid {
		return nil
	}
	return &t
}

// subtitlesFor 视频详情的外挂字幕。分享访客:分享的就是这个视频本身时,同文件夹的字幕不在分享范围里,不给。
func (r *Resolver) subtitlesFor(ctx context.Context, obj *Node) ([]service.SubtitleTrack, error) {
	nid, err := parseID(obj.ID)
	if err != nil {
		return nil, err
	}
	ident, err := r.requireNodeAccess(ctx, nid)
	if err != nil {
		return nil, err
	}
	if ident.Scope != service.ScopeUser {
		sh, err := r.Shares.ValidateScope(ctx, ident)
		if err != nil {
			return nil, err
		}
		if sh.NodeID == nid {
			return nil, nil
		}
	}
	pid, err := parseIDPtr(obj.ParentID)
	if err != nil {
		return nil, err
	}
	return r.Media.Subtitles(ctx, ident.UserID, pid, obj.Name)
}
