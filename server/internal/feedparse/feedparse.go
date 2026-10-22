// Package feedparse 解析 RSS 2.0(含 iTunes 播客扩展)、RSS 1.0(RDF)、Atom,以及 GitHub Releases API(doc/23 四.2)。
// 只取订阅需要的字段:标题、站点、每条的唯一标识、标题、链接、附件、发布时间。
package feedparse

import (
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"html"
	"io"
	"net/url"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"golang.org/x/net/html/charset"
)

type Feed struct {
	Title   string
	SiteURL string
	Items   []Item // 新的在前
	// Podcast 条目大多带音视频附件:添加订阅时建议「下载附件」
	Podcast bool
}

type Item struct {
	GUID          string
	Title         string
	Link          string
	EnclosureURL  string
	EnclosureType string
	EnclosureSize int64
	Published     time.Time // 零值 = 没给
	Tag           string    // GitHub:Release 的 tag(下载时放进同名子文件夹)
}

var ErrNotFeed = errors.New("不是 RSS / Atom 订阅地址")

type xmlLink struct {
	Href   string `xml:"href,attr"`
	Rel    string `xml:"rel,attr"`
	Type   string `xml:"type,attr"`
	Length string `xml:"length,attr"`
	Text   string `xml:",chardata"`
}

type xmlEnclosure struct {
	URL    string `xml:"url,attr"`
	Type   string `xml:"type,attr"`
	Length string `xml:"length,attr"`
	Size   string `xml:"fileSize,attr"`
}

type rssItem struct {
	Title    string         `xml:"title"`
	Links    []xmlLink      `xml:"link"`
	GUID     string         `xml:"guid"`
	PubDate  string         `xml:"pubDate"`
	DCDate   string         `xml:"http://purl.org/dc/elements/1.1/ date"`
	Encl     []xmlEnclosure `xml:"enclosure"`
	Media    []xmlEnclosure `xml:"http://search.yahoo.com/mrss/ content"`
	About    string         `xml:"http://www.w3.org/1999/02/22-rdf-syntax-ns# about,attr"`
	Duration string         `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd duration"`
}

type rssChannel struct {
	Title string    `xml:"title"`
	Links []xmlLink `xml:"link"`
	Items []rssItem `xml:"item"`
}

type rssRoot struct {
	Channel rssChannel `xml:"channel"`
	Items   []rssItem  `xml:"item"` // RSS 1.0:item 与 channel 平级
}

type atomEntry struct {
	ID        string    `xml:"id"`
	Title     string    `xml:"title"`
	Links     []xmlLink `xml:"link"`
	Published string    `xml:"published"`
	Updated   string    `xml:"updated"`
}

type atomFeed struct {
	Title   string      `xml:"title"`
	Links   []xmlLink   `xml:"link"`
	Entries []atomEntry `xml:"entry"`
}

// Parse 解析一份订阅文档;base 是订阅地址,用来补全相对链接。
func Parse(r io.Reader, base *url.URL) (*Feed, error) {
	d := xml.NewDecoder(r)
	d.CharsetReader = charset.NewReaderLabel
	// 不严格:容忍 &nbsp; 这类 HTML 实体与不规范的转义。不能开 AutoClose(HTML 的 <link> 是空元素,会吞掉 RSS 的 <link>)
	d.Strict = false
	d.Entity = xml.HTMLEntity
	for {
		tok, err := d.Token()
		if err == io.EOF {
			return nil, ErrNotFeed
		}
		if err != nil {
			return nil, fmt.Errorf("%w:%v", ErrNotFeed, err)
		}
		se, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		switch strings.ToLower(se.Name.Local) {
		case "rss", "rdf":
			var root rssRoot
			if err := d.DecodeElement(&root, &se); err != nil {
				return nil, fmt.Errorf("%w:%v", ErrNotFeed, err)
			}
			return fromRSS(root, base), nil
		case "feed":
			var f atomFeed
			if err := d.DecodeElement(&f, &se); err != nil {
				return nil, fmt.Errorf("%w:%v", ErrNotFeed, err)
			}
			return fromAtom(f, base), nil
		default:
			return nil, ErrNotFeed // 根元素不对(多半是网页)
		}
	}
}

func fromRSS(root rssRoot, base *url.URL) *Feed {
	f := &Feed{Title: cleanText(root.Channel.Title), SiteURL: resolve(base, pickLink(root.Channel.Links, false))}
	items := append(root.Channel.Items, root.Items...)
	for _, it := range items {
		x := Item{Title: cleanText(it.Title), Link: resolve(base, pickLink(it.Links, false))}
		if it.About != "" && x.Link == "" {
			x.Link = resolve(base, it.About)
		}
		for _, e := range append(it.Encl, it.Media...) {
			if e.URL == "" {
				continue
			}
			x.EnclosureURL, x.EnclosureType = resolve(base, e.URL), strings.ToLower(strings.TrimSpace(e.Type))
			x.EnclosureSize = parseSize(e.Length, e.Size)
			if isMedia(x.EnclosureType, x.EnclosureURL) {
				break // 有多个时优先音视频
			}
		}
		x.Published = parseTime(it.PubDate, it.DCDate)
		x.GUID = firstNonEmpty(strings.TrimSpace(it.GUID), x.Link, x.EnclosureURL, it.About)
		if x.GUID == "" {
			x.GUID = x.Title + "|" + x.Published.UTC().Format(time.RFC3339)
		}
		f.Items = append(f.Items, x)
	}
	finish(f)
	return f
}

func fromAtom(af atomFeed, base *url.URL) *Feed {
	f := &Feed{Title: cleanText(af.Title), SiteURL: resolve(base, pickLink(af.Links, true))}
	for _, e := range af.Entries {
		x := Item{Title: cleanText(e.Title), Link: resolve(base, pickLink(e.Links, true))}
		for _, l := range e.Links {
			if strings.EqualFold(l.Rel, "enclosure") && l.Href != "" {
				x.EnclosureURL, x.EnclosureType = resolve(base, l.Href), strings.ToLower(l.Type)
				x.EnclosureSize = parseSize(l.Length)
				break
			}
		}
		x.Published = parseTime(e.Published, e.Updated)
		x.GUID = firstNonEmpty(strings.TrimSpace(e.ID), x.Link, x.EnclosureURL)
		if x.GUID == "" {
			x.GUID = x.Title + "|" + x.Published.UTC().Format(time.RFC3339)
		}
		f.Items = append(f.Items, x)
	}
	finish(f)
	return f
}

// finish 新的在前(没给时间的保持文档顺序,排在有时间的后面),去掉同 guid 的重复,判断是不是播客。
func finish(f *Feed) {
	seen := map[string]bool{}
	out := f.Items[:0]
	for _, it := range f.Items {
		if seen[it.GUID] {
			continue
		}
		seen[it.GUID] = true
		out = append(out, it)
	}
	f.Items = out
	sort.SliceStable(f.Items, func(i, j int) bool {
		a, b := f.Items[i].Published, f.Items[j].Published
		if a.IsZero() || b.IsZero() {
			return !a.IsZero() && b.IsZero()
		}
		return a.After(b)
	})
	media := 0
	for _, it := range f.Items {
		if it.EnclosureURL != "" && isMedia(it.EnclosureType, it.EnclosureURL) {
			media++
		}
	}
	f.Podcast = len(f.Items) > 0 && media*2 >= len(f.Items)
}

// pickLink RSS 的 <link> 是正文文字;Atom 与 RSS 里的 atom:link 是属性。取 rel 为空或 alternate 的那个。
func pickLink(ls []xmlLink, atom bool) string {
	for _, l := range ls {
		if !atom && strings.TrimSpace(l.Text) != "" {
			return strings.TrimSpace(l.Text)
		}
	}
	for _, l := range ls {
		if l.Href != "" && (l.Rel == "" || strings.EqualFold(l.Rel, "alternate")) {
			return l.Href
		}
	}
	return ""
}

var mediaExt = map[string]bool{
	".mp3": true, ".m4a": true, ".aac": true, ".ogg": true, ".opus": true, ".flac": true, ".wav": true,
	".mp4": true, ".m4v": true, ".mov": true, ".webm": true, ".mkv": true,
}

func isMedia(typ, u string) bool {
	if strings.HasPrefix(typ, "audio/") || strings.HasPrefix(typ, "video/") {
		return true
	}
	if pu, err := url.Parse(u); err == nil {
		return mediaExt[strings.ToLower(path.Ext(pu.Path))]
	}
	return false
}

func resolve(base *url.URL, ref string) string {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return ""
	}
	u, err := url.Parse(ref)
	if err != nil {
		return ""
	}
	if base != nil {
		u = base.ResolveReference(u)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return ""
	}
	return u.String()
}

var tagRe = regexp.MustCompile(`<[^>]*>`)

// cleanText 标题里偶尔带 HTML 与实体,去掉标签、还原实体、压空白。
func cleanText(s string) string {
	s = tagRe.ReplaceAllString(s, "")
	s = html.UnescapeString(s)
	return strings.Join(strings.FieldsFunc(s, unicode.IsSpace), " ") // 连 &nbsp; 与全角空格一起压
}

func parseSize(vals ...string) int64 {
	for _, v := range vals {
		if n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64); err == nil && n > 0 {
			return n
		}
	}
	return 0
}

var timeLayouts = []string{
	time.RFC1123Z, time.RFC1123, time.RFC3339, time.RFC3339Nano, time.RFC822Z, time.RFC822,
	"Mon, 2 Jan 2006 15:04:05 -0700", "Mon, 2 Jan 2006 15:04:05 MST", "Mon, 02 Jan 2006 15:04:05 -0700",
	"2 Jan 2006 15:04:05 -0700", "Mon, 2 Jan 2006 15:04 -0700", "Mon, 2 Jan 2006 15:04:05",
	"2006-01-02T15:04:05", "2006-01-02 15:04:05", "2006-01-02",
}

func parseTime(vals ...string) time.Time {
	for _, v := range vals {
		v = strings.TrimSpace(v)
		if v == "" {
			continue
		}
		for _, l := range timeLayouts {
			if t, err := time.Parse(l, v); err == nil {
				return t
			}
		}
	}
	return time.Time{}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

// ---------- GitHub Releases ----------

var repoRe = regexp.MustCompile(`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`)

// GitHubRepo 认 owner/repo、https://github.com/owner/repo(带不带后面的路径都行)。
func GitHubRepo(s string) (string, bool) {
	s = strings.TrimSpace(s)
	if u, err := url.Parse(s); err == nil && (u.Host == "github.com" || u.Host == "www.github.com") {
		parts := strings.Split(strings.Trim(u.Path, "/"), "/")
		if len(parts) >= 2 {
			s = parts[0] + "/" + strings.TrimSuffix(parts[1], ".git")
		}
	}
	if !repoRe.MatchString(s) || strings.Contains(s, "..") {
		return "", false
	}
	return s, true
}

type ghRelease struct {
	TagName     string    `json:"tag_name"`
	Name        string    `json:"name"`
	HTMLURL     string    `json:"html_url"`
	Draft       bool      `json:"draft"`
	Prerelease  bool      `json:"prerelease"`
	PublishedAt time.Time `json:"published_at"`
	Assets      []struct {
		Name        string `json:"name"`
		Size        int64  `json:"size"`
		ContentType string `json:"content_type"`
		URL         string `json:"browser_download_url"`
	} `json:"assets"`
}

// SplitPatterns 资源文件名通配,逗号或空白分隔;空 = 全部。
func SplitPatterns(s string) []string {
	return strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == '\uFF0C' || r == ' ' || r == '\t' || r == '\n' })
}

// MatchAsset 不分大小写的通配匹配(* ? [..]);没有通配符时按包含算。
func MatchAsset(patterns []string, name string) bool {
	if len(patterns) == 0 {
		return true
	}
	ln := strings.ToLower(name)
	for _, p := range patterns {
		lp := strings.ToLower(p)
		if !strings.ContainsAny(lp, "*?[") {
			if strings.Contains(ln, lp) {
				return true
			}
			continue
		}
		if ok, _ := path.Match(lp, ln); ok {
			return true
		}
	}
	return false
}

// ParseGitHubReleases 解析 GET /repos/{repo}/releases 的回应:每个匹配的资源文件一个条目。
func ParseGitHubReleases(r io.Reader, repo string, patterns []string, prerelease bool) (*Feed, error) {
	var rels []ghRelease
	if err := json.NewDecoder(r).Decode(&rels); err != nil {
		return nil, fmt.Errorf("GitHub 回应解析失败:%v", err)
	}
	f := &Feed{Title: repo, SiteURL: "https://github.com/" + repo + "/releases"}
	for _, rel := range rels {
		if rel.Draft || rel.Prerelease && !prerelease || rel.TagName == "" {
			continue
		}
		title := firstNonEmpty(rel.Name, rel.TagName)
		for _, a := range rel.Assets {
			if a.URL == "" || !MatchAsset(patterns, a.Name) {
				continue
			}
			f.Items = append(f.Items, Item{
				GUID: rel.TagName + "/" + a.Name, Title: title + " · " + a.Name, Link: rel.HTMLURL,
				EnclosureURL: a.URL, EnclosureType: a.ContentType, EnclosureSize: a.Size,
				Published: rel.PublishedAt, Tag: rel.TagName,
			})
		}
	}
	finish(f)
	f.Podcast = false
	return f, nil
}
