package feedparse

import (
	"bytes"
	"net/url"
	"strings"
	"testing"

	"golang.org/x/text/encoding/simplifiedchinese"
)

func base(t *testing.T, s string) *url.URL {
	u, err := url.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return u
}

func TestParseRSSPodcast(t *testing.T) {
	doc := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom">
<channel>
  <title>  测试 &amp; 播客 </title>
  <link>https://pod.example.com/</link>
  <atom:link href="https://pod.example.com/feed.xml" rel="self" type="application/rss+xml"/>
  <item>
    <title><![CDATA[第2期:<b>开场</b>]]></title>
    <guid isPermaLink="false">ep-2</guid>
    <pubDate>Tue, 20 Oct 2026 08:00:00 +0800</pubDate>
    <enclosure url="/media/ep2.mp3" length="12345" type="audio/mpeg"/>
    <itunes:duration>45:00</itunes:duration>
  </item>
  <item>
    <title>第1期</title>
    <link>https://pod.example.com/ep1</link>
    <pubDate>Mon, 5 Oct 2026 08:00:00 GMT</pubDate>
    <enclosure url="https://cdn.example.com/ep1.m4a" length="99" type="audio/x-m4a"/>
  </item>
  <item><title>第3期 &nbsp;最新</title><guid>ep-3</guid><pubDate>Wed, 21 Oct 2026 08:00:00 +0800</pubDate>
    <enclosure url="https://cdn.example.com/ep3.mp3" type="audio/mpeg"/></item>
</channel></rss>`
	f, err := Parse(strings.NewReader(doc), base(t, "https://pod.example.com/feed.xml"))
	if err != nil {
		t.Fatal(err)
	}
	if f.Title != "测试 & 播客" || f.SiteURL != "https://pod.example.com/" || !f.Podcast {
		t.Fatalf("feed = %+v", f)
	}
	if len(f.Items) != 3 || f.Items[0].GUID != "ep-3" || f.Items[2].GUID != "https://pod.example.com/ep1" {
		t.Fatalf("order/guid = %+v", f.Items)
	}
	ep2 := f.Items[1]
	if ep2.Title != "第2期:开场" || ep2.EnclosureURL != "https://pod.example.com/media/ep2.mp3" || ep2.EnclosureSize != 12345 || ep2.EnclosureType != "audio/mpeg" {
		t.Fatalf("ep2 = %+v", ep2)
	}
	if f.Items[0].Title != "第3期 最新" {
		t.Fatalf("nbsp title = %q", f.Items[0].Title)
	}
}

func TestParseGBKAndAtom(t *testing.T) {
	gbk, _ := simplifiedchinese.GBK.NewEncoder().String(`<?xml version="1.0" encoding="GBK"?><rss><channel><title>中文站</title><item><title>标题一</title><link>http://a.cn/1</link></item></channel></rss>`)
	f, err := Parse(bytes.NewReader([]byte(gbk)), nil)
	if err != nil {
		t.Fatal(err)
	}
	if f.Title != "中文站" || len(f.Items) != 1 || f.Items[0].Title != "标题一" || f.Podcast {
		t.Fatalf("gbk = %+v", f)
	}

	atom := `<feed xmlns="http://www.w3.org/2005/Atom"><title>Blog</title>
<link href="https://blog.example.com/" rel="alternate"/><link href="https://blog.example.com/atom.xml" rel="self"/>
<entry><id>tag:1</id><title>Post A</title><link href="/a"/><published>2026-10-01T10:00:00Z</published></entry>
<entry><id>tag:2</id><title type="html">Post &lt;B&gt;</title><link rel="alternate" href="https://blog.example.com/b"/>
<link rel="enclosure" href="https://blog.example.com/b.mp4" type="video/mp4" length="10"/><updated>2026-10-02T10:00:00Z</updated></entry>
</feed>`
	f, err = Parse(strings.NewReader(atom), base(t, "https://blog.example.com/atom.xml"))
	if err != nil {
		t.Fatal(err)
	}
	if f.Title != "Blog" || f.SiteURL != "https://blog.example.com/" || len(f.Items) != 2 {
		t.Fatalf("atom = %+v", f)
	}
	if f.Items[0].GUID != "tag:2" || f.Items[0].EnclosureURL != "https://blog.example.com/b.mp4" || f.Items[1].Link != "https://blog.example.com/a" {
		t.Fatalf("atom items = %+v", f.Items)
	}
	if f.Items[0].Title != "Post" {
		t.Fatalf("html title = %q", f.Items[0].Title)
	}
}

func TestParseRejectsHTML(t *testing.T) {
	if _, err := Parse(strings.NewReader("<!doctype html><html><body>hi</body></html>"), nil); err == nil {
		t.Fatal("html accepted")
	}
}

func TestGitHub(t *testing.T) {
	for in, want := range map[string]string{
		"yophon/gopan": "yophon/gopan", "https://github.com/yophon/gopan/releases": "yophon/gopan",
		"https://github.com/a/b.git": "a/b",
	} {
		if got, ok := GitHubRepo(in); !ok || got != want {
			t.Fatalf("GitHubRepo(%q) = %q %v", in, got, ok)
		}
	}
	if _, ok := GitHubRepo("not a repo"); ok {
		t.Fatal("bad repo accepted")
	}
	js := `[{"tag_name":"v2.0.0-rc1","prerelease":true,"published_at":"2026-10-20T00:00:00Z","assets":[{"name":"app.apk","browser_download_url":"https://x/rc.apk","size":5}]},
{"tag_name":"v1.9.0","name":"1.9","html_url":"https://github.com/a/b/releases/tag/v1.9.0","published_at":"2026-10-10T00:00:00Z",
 "assets":[{"name":"app-release.apk","browser_download_url":"https://x/a.apk","size":10,"content_type":"application/vnd.android.package-archive"},
           {"name":"tool-linux-amd64.tar.gz","browser_download_url":"https://x/t.tgz","size":20}]},
{"tag_name":"v1.8.0","draft":true,"assets":[{"name":"x.apk","browser_download_url":"https://x/d.apk"}]}]`
	f, err := ParseGitHubReleases(strings.NewReader(js), "a/b", SplitPatterns("*.apk, linux-amd64"), false)
	if err != nil {
		t.Fatal(err)
	}
	if len(f.Items) != 2 || f.Items[0].Tag != "v1.9.0" || f.Items[0].GUID != "v1.9.0/app-release.apk" && f.Items[1].GUID != "v1.9.0/app-release.apk" {
		t.Fatalf("github = %+v", f.Items)
	}
	f, _ = ParseGitHubReleases(strings.NewReader(js), "a/b", SplitPatterns("*.APK"), true)
	if len(f.Items) != 2 || f.Items[0].Tag != "v2.0.0-rc1" {
		t.Fatalf("prerelease = %+v", f.Items)
	}
	if !MatchAsset(nil, "anything") || MatchAsset([]string{"*.exe"}, "a.apk") {
		t.Fatal("MatchAsset")
	}
}
