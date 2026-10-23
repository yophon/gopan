package httpx

import (
	"strings"
	"testing"
	"time"

	"github.com/yophon/gopan/server/internal/service"
)

func strp(s string) *string { return &s }

func TestShareMeta(t *testing.T) {
	now := time.Date(2026, 10, 23, 10, 0, 0, 0, time.Local)
	origin := "https://drive.example.com"
	in3 := now.Add(60 * time.Hour)

	cases := []struct {
		name string
		info service.ShareInfoView
		want landingMeta
	}{
		{
			name: "文件夹:文件数、体积、期限",
			info: service.ShareInfoView{Name: "照片 2026", Kind: "folder", Files: 24, Size: 3 << 20, ExpiresAt: &in3},
			want: landingMeta{Title: "照片 2026", Desc: "文件夹 · 24 个文件 · 3.0 MB · 3 天后过期 · gopan 云盘", Image: origin + "/icons/icon-512.png"},
		},
		{
			name: "图片:大图卡片走 /og",
			info: service.ShareInfoView{Name: "IMG_2041.jpg", Kind: "file", Files: 1, Size: 2 << 20, BlobSha: strp("abc"), BlobMime: strp("image/jpeg")},
			want: landingMeta{Title: "IMG_2041.jpg", Desc: "图片 · 2.0 MB · gopan 云盘", Image: origin + "/s/tok/og", LargeImage: true},
		},
		{
			name: "PDF:不是图片视频,用图标",
			info: service.ShareInfoView{Name: "论文.pdf", Kind: "file", Files: 1, Size: 1024, BlobSha: strp("abc"), BlobMime: strp("application/pdf")},
			want: landingMeta{Title: "论文.pdf", Desc: "PDF · 1.0 KB · gopan 云盘", Image: origin + "/icons/icon-512.png"},
		},
		{
			name: "有密码:不露名字",
			info: service.ShareInfoView{Name: "工资条.xlsx", Kind: "file", NeedPassword: true, Size: 1024},
			want: landingMeta{Title: "有人给你分享了一个文件", Desc: "需要密码才能查看 · gopan 云盘", Image: origin + "/icons/icon-512.png"},
		},
		{
			name: "已失效",
			info: service.ShareInfoView{Name: "x", Kind: "folder", Expired: true},
			want: landingMeta{Title: "分享已失效", Desc: "这个分享链接已过期或被取消", Image: origin + "/icons/icon-512.png"},
		},
	}
	for _, c := range cases {
		info := c.info
		if got := shareMeta(&info, origin, "tok", now); got != c.want {
			t.Errorf("%s:\n got  %+v\n want %+v", c.name, got, c.want)
		}
	}
}

func TestExpiryText(t *testing.T) {
	now := time.Date(2026, 10, 23, 10, 0, 0, 0, time.Local)
	at := func(d time.Duration) *time.Time { v := now.Add(d); return &v }
	if got := expiryText(nil, now); got != "" {
		t.Errorf("没有期限应为空,got %q", got)
	}
	if got := expiryText(at(-time.Hour), now); got != "" {
		t.Errorf("已过期应为空(交给 Expired 处理),got %q", got)
	}
	if got := expiryText(at(5*time.Hour), now); got != "今天过期" {
		t.Errorf("当天:got %q", got)
	}
	if got := expiryText(at(20*time.Hour), now); got != "1 天后过期" {
		t.Errorf("明天:got %q", got)
	}
	if got := expiryText(at(7*24*time.Hour), now); got != "7 天后过期" {
		t.Errorf("7 天:got %q", got)
	}
}

func TestCollectMeta(t *testing.T) {
	now := time.Date(2026, 10, 23, 10, 0, 0, 0, time.Local)
	in2 := now.Add(40 * time.Hour)
	got := collectMeta(&service.FileRequestInfoView{Title: "交照片", Note: "运动会的照片都传这里\n谢谢", ExpiresAt: &in2}, "https://d", now)
	if got.Title != "交照片" || got.Desc != "运动会的照片都传这里 · 2 天后截止 · gopan 云盘" {
		t.Errorf("collectMeta = %+v", got)
	}
	got = collectMeta(&service.FileRequestInfoView{Title: "交照片"}, "https://d", now)
	if got.Desc != "点开就能上传文件,不用注册 · gopan 云盘" {
		t.Errorf("没有说明时:%q", got.Desc)
	}
	got = collectMeta(&service.FileRequestInfoView{Title: "交照片", Closed: true}, "https://d", now)
	if got.Title != "收集已结束" {
		t.Errorf("已结束:%+v", got)
	}
}

func TestInjectMeta(t *testing.T) {
	page := `<!doctype html><html><head><meta charset="UTF-8"><title>gopan 云盘</title></head><body></body></html>`
	out := injectMeta(page, landingMeta{Title: `a<b>"c`, Desc: "d", Image: "https://x/og", LargeImage: true})
	for _, want := range []string{
		`<title>a&lt;b&gt;&#34;c</title>`,
		`<meta property="og:title" content="a&lt;b&gt;&#34;c">`,
		`<meta property="og:image" content="https://x/og">`,
		`<meta name="twitter:card" content="summary_large_image">`,
		`<meta name="description" content="d">`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("缺少 %s\n%s", want, out)
		}
	}
	if strings.Count(out, "<title>") != 1 {
		t.Errorf("title 应该只有一个:%s", out)
	}
}
