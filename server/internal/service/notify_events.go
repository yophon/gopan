package service

import (
	"context"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/store"
)

// 各处业务触发通知的入口(doc/22 三.3)。都容忍 Notifier 为 nil。

func (s *Uploads) SetNotifier(n *Notifier)      { s.notify = n }
func (j *Jobs) SetNotifier(n *Notifier)         { j.notify = n }
func (a *Auth) SetNotifier(n *Notifier)         { a.notify = n }
func (m *MCPTokens) SetNotifier(n *Notifier)    { m.notify = n }
func (a *AppPasswords) SetNotifier(n *Notifier) { a.notify = n }
func (o *OAuth) SetNotifier(n *Notifier)        { o.notify = n }
func (s *Nodes) SetNotifier(n *Notifier)        { s.notify = n }

// noteCollectUpload 收集链接收到一个文件:同一链接 30 分钟内合并成一条。随落库的事务一起提交。
func (n *Notifier) noteCollectUpload(ctx context.Context, tx pgx.Tx, owner uuid.UUID, req store.LockFileRequestRow, uploader, file string, size int64) {
	who := uploader
	if who == "" {
		who = "匿名访客"
	}
	n.NotifyTx(ctx, tx, owner, Event{
		Kind: EvCollectUpload, Level: LvInfo, Title: "「" + req.Title + "」收到新文件",
		Body:   fmt.Sprintf("%s 上传了 %s(%s)", who, file, humanBytes(size)),
		Target: &Target{Kind: TgFileRequest, ID: req.ID.String()}, GroupKey: "collect:" + req.ID.String(),
	})
}

// noteShareUpload 访客往分享里传了一个文件:同一分享 30 分钟内合并成一条。随落库的事务一起提交。
func (n *Notifier) noteShareUpload(ctx context.Context, tx pgx.Tx, owner uuid.UUID, sh store.LockShareRow, uploader string, node store.Node, size int64) {
	who := uploader
	if who == "" {
		who = "访客"
	}
	t := &Target{Kind: TgNode, ID: node.ID.String()}
	if node.ParentID != nil {
		t.ParentID = node.ParentID.String()
	}
	n.NotifyTx(ctx, tx, owner, Event{
		Kind: EvShareUpload, Level: LvInfo, Title: "分享「" + sh.NodeName + "」收到新文件",
		Body:   fmt.Sprintf("%s 传了 %s(%s)", who, node.Name, humanBytes(size)),
		Target: t, GroupKey: "share_upload:" + sh.ID.String(),
	})
}

// noteCollectFull 属主空间不够,访客的上传被拒(每条链接每天一次)。不在业务事务里写:那个事务要回滚。
func (n *Notifier) noteCollectFull(ctx context.Context, owner, reqID uuid.UUID, title string) {
	n.Notify(context.WithoutCancel(ctx), owner, Event{
		Kind: EvCollectFull, Level: LvWarn, Title: "「" + title + "」因空间不足拒收了文件",
		Body:   "网盘剩余空间不够,访客的上传被拒了。清理一些文件,或请管理员调高配额。",
		Target: &Target{Kind: TgFileRequest, ID: reqID.String()}, GroupKey: "collect_full:" + reqID.String(), Once: 24 * time.Hour,
	})
}

// CheckQuota 用量越过 90% / 100% 时提醒,同一档 7 天只提醒一次。在增加用量的事务提交后调用。
func (n *Notifier) CheckQuota(ctx context.Context, owner uuid.UUID) {
	if n == nil {
		return
	}
	u, err := n.q.GetUserByID(ctx, owner)
	if err != nil || u.QuotaBytes <= 0 {
		return
	}
	pct := float64(u.UsedBytes) / float64(u.QuotaBytes)
	var lvl, group, title string
	switch {
	case pct >= 1:
		lvl, group, title = LvAlert, "quota:100", "网盘空间满了"
	case pct >= 0.9:
		lvl, group, title = LvWarn, "quota:90", "网盘空间用了 90%"
	default:
		return
	}
	n.Notify(ctx, owner, Event{
		Kind: EvQuota, Level: lvl, Title: title,
		Body:     fmt.Sprintf("已用 %s / %s。清理回收站或不要的文件,或请管理员调高配额。", humanBytes(u.UsedBytes), humanBytes(u.QuotaBytes)),
		GroupKey: group, Once: 7 * 24 * time.Hour,
	})
}

// noteJob 离线下载 / 解压结束。
func (n *Notifier) noteJob(ctx context.Context, job store.UserJob, ok bool, msg string) {
	if n == nil {
		return
	}
	what := "离线下载"
	switch job.Kind {
	case JobExtract:
		what = "解压"
	case JobClip:
		what = "剪藏"
	}
	if ok && job.Source == JobSourceFeed {
		return // 订阅自己发「有新内容」(doc/23 四.3)
	}
	ev := Event{Kind: EvJobDone, Level: LvInfo, Title: what + "完成:" + job.Title, Target: &Target{Kind: TgJobs}}
	if ok && job.Kind == JobClip && job.ResultNodeID != nil {
		t := &Target{Kind: TgNode, ID: job.ResultNodeID.String()}
		if node, err := n.q.GetNode(ctx, *job.ResultNodeID); err == nil && node.ParentID != nil {
			t.ParentID = node.ParentID.String()
		}
		ev.Target = t
	}
	if !ok {
		ev.Kind, ev.Level, ev.Title, ev.Body = EvJobFailed, LvWarn, what+"失败:"+job.Title, msg
	}
	n.Notify(ctx, job.OwnerID, ev)
}

// noteLogin 每次密码 / Yophon ID 登录都提醒(登录本来就少),带设备与 IP。
func (n *Notifier) noteLogin(ctx context.Context, owner uuid.UUID, ip, method string) {
	if n == nil {
		return
	}
	dev := DeviceLabel(ClientMetaFrom(ctx).UA)
	if ip == "" {
		ip = "未知 IP"
	}
	n.Notify(ctx, owner, Event{
		Kind: EvLogin, Level: LvWarn, Title: "新登录:" + dev,
		Body:   fmt.Sprintf("%s · %s · %s登录。不是你的话,去「登录设备」吊销它并改密码。", ip, time.Now().Local().Format("2006-01-02 15:04"), method),
		Target: &Target{Kind: TgSessions},
	})
}

// noteCredential 新建了应用密码、API Key、图床密钥,或给 Agent 授了权。
func (n *Notifier) noteCredential(ctx context.Context, owner uuid.UUID, kind, name string) {
	if n == nil {
		return
	}
	labels := map[string]string{"webdav": "WebDAV 应用密码", "mcp": "MCP API Key", "img": "图床密钥", "oauth": "Agent 授权"}
	target := kind
	if kind == "oauth" {
		target = "mcp"
	}
	m := ClientMetaFrom(ctx)
	body := "名称「" + name + "」"
	if m.IP != "" {
		body += ",来自 " + m.IP + " · " + DeviceLabel(m.UA)
	}
	n.Notify(ctx, owner, Event{
		Kind: EvCredential, Level: LvWarn, Title: "新的" + labels[kind],
		Body:   body + "。不是你建的话,马上吊销它。",
		Target: &Target{Kind: TgCredentials, ID: target},
	})
}

var (
	reAndroidApp = regexp.MustCompile(`gopan-android/[\d.]+ \(Android [\d.]+; ([^)]+)\)`)
	// 桌面端(doc/29):窗口里的网页与程序自己的请求都在 UA 末尾带 gopan-desktop/<版本>
	reDesktopApp = regexp.MustCompile(`gopan-desktop/[\d.]+`)
	reBrowsers   = []struct {
		re   *regexp.Regexp
		name string
	}{
		{regexp.MustCompile(`Edg(e|A|iOS)?/`), "Edge"},
		{regexp.MustCompile(`OPR/|Opera`), "Opera"},
		{regexp.MustCompile(`Firefox/|FxiOS/`), "Firefox"},
		{regexp.MustCompile(`MicroMessenger/`), "微信"},
		{regexp.MustCompile(`Chrome/|CriOS/`), "Chrome"},
		{regexp.MustCompile(`Safari/`), "Safari"},
	}
)

// DeviceLabel UA → 「Chrome · Windows」「安卓 App · Pixel 8」。认不出给「未知设备」。
func DeviceLabel(ua string) string {
	if m := reAndroidApp.FindStringSubmatch(ua); m != nil {
		return "安卓 App · " + strings.TrimSpace(m[1])
	}
	if reDesktopApp.MatchString(ua) {
		return "Windows 桌面端"
	}
	if ua == "" {
		return "未知设备"
	}
	browser := ""
	for _, b := range reBrowsers {
		if b.re.MatchString(ua) {
			browser = b.name
			break
		}
	}
	os := ""
	switch {
	case strings.Contains(ua, "Windows"):
		os = "Windows"
	case strings.Contains(ua, "iPhone"), strings.Contains(ua, "iPad"):
		os = "iOS"
	case strings.Contains(ua, "Mac OS X"), strings.Contains(ua, "Macintosh"):
		os = "macOS"
	case strings.Contains(ua, "Android"):
		os = "Android"
	case strings.Contains(ua, "Linux"):
		os = "Linux"
	}
	switch {
	case browser != "" && os != "":
		return browser + " · " + os
	case browser != "":
		return browser
	case os != "":
		return os
	}
	return truncateRunes(ua, 40)
}
