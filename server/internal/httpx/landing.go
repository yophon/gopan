package httpx

import (
	"fmt"
	"html"
	"math"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/yophon/gopan/server/internal/service"
)

// 分享 / 收集链接发进微信、Telegram 这类聊天时的预览卡片(og 标签)。
// 有密码的分享不透露名字和规模,卡片上只说「有人给你分享了文件」。

type landingMeta struct {
	Title string
	Desc  string
	// 绝对地址;LargeImage 为 true 时卡片用大图样式
	Image      string
	LargeImage bool
}

var previewKindLabel = map[string]string{
	service.PvImage:   "图片",
	service.PvVideo:   "视频",
	service.PvAudio:   "音频",
	service.PvPDF:     "PDF",
	service.PvOffice:  "文档",
	service.PvText:    "文本",
	service.PvArchive: "压缩包",
}

func fmtBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit && exp < 4; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTP"[exp])
}

// expiryText 「3 天后过期」「今天过期」;没有期限为空
func expiryText(exp *time.Time, now time.Time) string {
	if exp == nil {
		return ""
	}
	left := exp.Sub(now)
	if left <= 0 {
		return ""
	}
	days := int(math.Ceil(left.Hours() / 24))
	if days <= 1 && exp.Format("2006-01-02") == now.Format("2006-01-02") {
		return "今天过期"
	}
	return fmt.Sprintf("%d 天后过期", days)
}

// shareMeta 分享链接的卡片。origin 形如 https://drive.example.com
func shareMeta(info *service.ShareInfoView, origin, token string, now time.Time) landingMeta {
	icon := origin + "/icons/icon-512.png"
	noun := "文件"
	if info.Kind == "folder" {
		noun = "文件夹"
	}
	if info.Expired {
		return landingMeta{Title: "分享已失效", Desc: "这个分享链接已过期或被取消", Image: icon}
	}
	if info.NeedPassword {
		return landingMeta{Title: "有人给你分享了一个" + noun, Desc: "需要密码才能查看 · gopan 云盘", Image: icon}
	}
	parts := []string{}
	if info.Kind == "folder" {
		parts = append(parts, "文件夹", fmt.Sprintf("%d 个文件", info.Files), fmtBytes(info.Size))
	} else {
		size := info.Size
		kind := service.ClassifyPreview(info.Name, info.BlobMime, &size)
		label := previewKindLabel[kind]
		if label == "" {
			label = "文件"
		}
		parts = append(parts, label, fmtBytes(info.Size))
	}
	if e := expiryText(info.ExpiresAt, now); e != "" {
		parts = append(parts, e)
	}
	parts = append(parts, "gopan 云盘")
	m := landingMeta{Title: info.Name, Desc: strings.Join(parts, " · "), Image: icon}
	if info.Kind == "file" && info.BlobSha != nil {
		size := info.Size
		if k := service.ClassifyPreview(info.Name, info.BlobMime, &size); k == service.PvImage || k == service.PvVideo {
			m.Image, m.LargeImage = origin+"/s/"+token+"/og", true
		}
	}
	return m
}

// collectMeta 收集链接的卡片:属主写的标题,说明的第一行,截止时间
func collectMeta(info *service.FileRequestInfoView, origin string, now time.Time) landingMeta {
	icon := origin + "/icons/icon-512.png"
	if info.Closed {
		return landingMeta{Title: "收集已结束", Desc: "这个收集链接已截止或被取消", Image: icon}
	}
	desc := "点开就能上传文件,不用注册"
	if note := strings.TrimSpace(strings.SplitN(info.Note, "\n", 2)[0]); note != "" {
		desc = note
	}
	if e := expiryText(info.ExpiresAt, now); e != "" {
		desc += " · " + strings.Replace(e, "过期", "截止", 1)
	}
	return landingMeta{Title: info.Title, Desc: desc + " · gopan 云盘", Image: icon}
}

var titleTag = regexp.MustCompile(`(?s)<title>.*?</title>`)

// injectMeta 把卡片信息放进 index.html 的 <head>,<title> 一并换掉(微信等只认标题和描述)
func injectMeta(page string, m landingMeta) string {
	esc := html.EscapeString
	card := "summary"
	if m.LargeImage {
		card = "summary_large_image"
	}
	meta := fmt.Sprintf(
		`<meta property="og:title" content="%s"><meta property="og:description" content="%s"><meta property="og:type" content="website">`+
			`<meta property="og:site_name" content="gopan 云盘"><meta property="og:image" content="%s"><meta name="twitter:card" content="%s">`+
			`<meta name="description" content="%s">`,
		esc(m.Title), esc(m.Desc), esc(m.Image), card, esc(m.Desc))
	page = titleTag.ReplaceAllLiteralString(page, "<title>"+esc(m.Title)+"</title>")
	return strings.Replace(page, "<head>", "<head>"+meta, 1)
}

// ShareOGImage GET /s/{token}/og:单个图片 / 视频的分享,卡片上的那张图。
// 302 到预签名的大图(没有就缩略图);有密码、已失效、不是图片视频的一律 404。
func ShareOGImage(shares *service.Shares, previews *service.Previews) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		info, err := shares.Info(r.Context(), r.PathValue("token"))
		if err != nil || info.Expired || info.NeedPassword || info.Kind != "file" || info.BlobSha == nil {
			http.NotFound(w, r)
			return
		}
		size := info.Size
		pv, err := previews.ForNode(r.Context(), info.Name, info.BlobSha, info.BlobMime, &size)
		if err != nil || (pv.Kind != service.PvImage && pv.Kind != service.PvVideo) {
			http.NotFound(w, r)
			return
		}
		target := pv.LargeURL
		if target == nil {
			target = pv.ThumbURL
		}
		if target == nil {
			http.NotFound(w, r)
			return
		}
		// 预签名 15 分钟有效:让爬虫别缓存这个跳转本身
		w.Header().Set("Cache-Control", "no-store")
		http.Redirect(w, r, *target, http.StatusFound)
	})
}
