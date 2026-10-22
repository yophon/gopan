// Package clipper 把网页正文提取出来转成 Markdown(doc/23 五.1)。
// 正文提取用 go-readability,转换用 html-to-markdown;正文里的图片先换成占位地址,
// 调用方下载完图片后用 ReplaceImages 换成本地相对路径(下不了的换回原地址)。
package clipper

import (
	"bytes"
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	readability "codeberg.org/readeck/go-readability/v2"
	"github.com/JohannesKaufmann/html-to-markdown/v2/converter"
	"github.com/JohannesKaufmann/html-to-markdown/v2/plugin/base"
	"github.com/JohannesKaufmann/html-to-markdown/v2/plugin/commonmark"
	"github.com/JohannesKaufmann/html-to-markdown/v2/plugin/strikethrough"
	"github.com/JohannesKaufmann/html-to-markdown/v2/plugin/table"
	"golang.org/x/net/html"
	"golang.org/x/net/html/atom"
)

// MaxImages 一篇最多下这么多张图,之后的保留原地址。
const MaxImages = 50

type Article struct {
	Title     string
	Byline    string
	Site      string
	Excerpt   string
	Lang      string
	Published time.Time
	Markdown  string  // 正文,不含开头的 YAML 与标题
	Images    []Image // 正文里的图片(按出现顺序,已去重)
	Words     int     // 中文按字、英文按词
	Fallback  bool    // 提取不出正文,存的是整页文字
}

type Image struct {
	URL         string // 原图的绝对地址
	Placeholder string // Markdown 里暂时用的地址
}

const placeholderPrefix = "https://gopan.invalid/clip-img/"

// Extract 提取正文。page 是网页的最终地址(跟随跳转之后),用来补全相对链接。
func Extract(doc []byte, page *url.URL) (*Article, error) {
	art, err := readability.FromReader(bytes.NewReader(doc), page)
	node := art.Node
	fallback := false
	if err != nil || node == nil || textLen(node) < 80 {
		root, perr := html.Parse(bytes.NewReader(doc))
		if perr != nil {
			return nil, fmt.Errorf("网页解析失败:%v", perr)
		}
		node = findElement(root, atom.Body)
		if node == nil {
			node = root
		}
		stripNoise(node)
		fallback = true
	}
	images := rewriteImages(node, page)
	conv := converter.NewConverter(converter.WithPlugins(
		base.NewBasePlugin(), commonmark.NewCommonmarkPlugin(), table.NewTablePlugin(), strikethrough.NewStrikethroughPlugin(),
	))
	md, err := conv.ConvertNode(node, converter.WithDomain(page.Scheme+"://"+page.Host))
	if err != nil {
		return nil, fmt.Errorf("转 Markdown 失败:%v", err)
	}
	a := &Article{
		Title: clean(art.Title()), Byline: clean(art.Byline()), Site: clean(art.SiteName()), Excerpt: clean(art.Excerpt()),
		Lang: art.Language(), Markdown: strings.TrimSpace(string(md)), Images: images, Fallback: fallback,
	}
	if t, err := art.PublishedTime(); err == nil && !t.IsZero() {
		a.Published = t
	}
	if a.Title == "" {
		if root, err := html.Parse(bytes.NewReader(doc)); err == nil {
			if t := findElement(root, atom.Title); t != nil {
				a.Title = clean(textOf(t))
			}
		}
	}
	if a.Title == "" {
		a.Title = page.Hostname()
	}
	if a.Site == "" {
		// 没写 og:site_name 的站:「文章 - 站名」里的站名就是它;认不出才用域名
		if t, site, ok := splitTitleSite(a.Title); ok {
			a.Title, a.Site = t, site
		} else {
			a.Site = strings.TrimPrefix(page.Hostname(), "www.")
		}
	}
	a.Title = trimSiteSuffix(a.Title, a.Site)
	// og:site_name 常带口号(「少数派 - 高品质数字消费指南」),标题末段是它的开头时两边都收短
	if h, seg, ok := splitTitleSite(a.Title); ok && strings.HasPrefix(a.Site, seg) {
		a.Title, a.Site = h, seg
	}
	a.Excerpt = truncate(a.Excerpt, 300)
	a.Words = CountWords(a.Markdown)
	return a, nil
}

// ReplaceImages 把占位地址换成 to[占位地址];没给的换回原地址。
func ReplaceImages(md string, images []Image, to map[string]string) string {
	pairs := make([]string, 0, len(images)*2)
	for _, im := range images {
		dst, ok := to[im.Placeholder]
		if !ok {
			dst = im.URL
		}
		pairs = append(pairs, im.Placeholder, dst)
	}
	return strings.NewReplacer(pairs...).Replace(md)
}

// Document 拼出完整的 .md:开头一段 YAML(来源卡片),然后是标题与正文。
func Document(a *Article, source string, clipped time.Time, body string) string {
	var b strings.Builder
	b.WriteString("---\n")
	field := func(k, v string) {
		if v != "" {
			b.WriteString(k + ": " + yamlQuote(v) + "\n")
		}
	}
	field("title", a.Title)
	field("source", source)
	field("author", a.Byline)
	field("site", a.Site)
	if !a.Published.IsZero() {
		field("published", a.Published.Format("2006-01-02"))
	}
	field("clipped", clipped.Format(time.RFC3339))
	b.WriteString("---\n\n")
	// 正文开头就是标题(提取时常被降成二级)就把它当一级标题,否则补一个
	first, rest, _ := strings.Cut(body, "\n")
	if h := strings.TrimLeft(first, "#"); h != first && strings.TrimSpace(h) == a.Title {
		body = "# " + a.Title + "\n" + rest
	} else {
		b.WriteString("# " + strings.ReplaceAll(a.Title, "\n", " ") + "\n\n")
	}
	b.WriteString(body)
	b.WriteString("\n")
	return b.String()
}

var titleSeps = []string{" - ", " | ", " – ", " — ", " · ", "_"}

// splitTitleSite 「文章 - 站名」拆成文章与站名:取最后一个分隔符,站名 2–30 个字。
func splitTitleSite(title string) (string, string, bool) {
	at, sep := -1, ""
	for _, s := range titleSeps {
		if i := strings.LastIndex(title, s); i > at {
			at, sep = i, s
		}
	}
	if at <= 0 {
		return "", "", false
	}
	head, site := strings.TrimSpace(title[:at]), strings.TrimSpace(title[at+len(sep):])
	if n := len([]rune(site)); n < 2 || n > 30 || len([]rune(head)) < 2 {
		return "", "", false
	}
	return head, site, true
}

// trimSiteSuffix 「文章 - 站名」「文章 | 站名」「文章_站名」去掉后面的站名。
func trimSiteSuffix(title, site string) string {
	if site == "" {
		return title
	}
	for _, sep := range []string{" - ", " | ", " – ", " — ", "_", " · "} {
		if t, ok := strings.CutSuffix(title, sep+site); ok && strings.TrimSpace(t) != "" {
			return strings.TrimSpace(t)
		}
	}
	return title
}

func yamlQuote(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	s = strings.ReplaceAll(s, "\n", " ")
	return `"` + s + `"`
}

// ---------- 图片 ----------

func rewriteImages(root *html.Node, page *url.URL) []Image {
	var imgs []Image
	seen := map[string]string{}
	walk(root, func(n *html.Node) {
		if n.Type != html.ElementNode || n.DataAtom != atom.Img {
			return
		}
		src := absURL(page, pickSrc(n))
		if src == "" {
			removeAttr(n, "src")
			return
		}
		removeAttr(n, "srcset")
		ph, ok := seen[src]
		if !ok {
			if len(imgs) >= MaxImages {
				setAttr(n, "src", src)
				return
			}
			ph = placeholderPrefix + strconv.Itoa(len(imgs)+1)
			seen[src] = ph
			imgs = append(imgs, Image{URL: src, Placeholder: ph})
		}
		setAttr(n, "src", ph)
	})
	return imgs
}

// pickSrc 懒加载的图真实地址常在 data-src / data-original 里,src 是一张占位图。
func pickSrc(n *html.Node) string {
	for _, k := range []string{"data-src", "data-original", "data-lazy-src", "data-actualsrc"} {
		if v := attr(n, k); v != "" && !strings.HasPrefix(v, "data:") {
			return v
		}
	}
	if v := attr(n, "src"); v != "" && !strings.HasPrefix(v, "data:") {
		return v
	}
	if ss := attr(n, "srcset"); ss != "" {
		var best string
		bestW := -1
		for _, part := range strings.Split(ss, ",") {
			f := strings.Fields(strings.TrimSpace(part))
			if len(f) == 0 {
				continue
			}
			w := 0
			if len(f) > 1 {
				w, _ = strconv.Atoi(strings.TrimRight(f[1], "wx"))
			}
			if w > bestW {
				best, bestW = f[0], w
			}
		}
		return best
	}
	return ""
}

func absURL(page *url.URL, ref string) string {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return ""
	}
	u, err := url.Parse(ref)
	if err != nil {
		return ""
	}
	if page != nil {
		u = page.ResolveReference(u)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return ""
	}
	return u.String()
}

// ---------- DOM 小工具 ----------

func walk(n *html.Node, fn func(*html.Node)) {
	fn(n)
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		walk(c, fn)
	}
}

func attr(n *html.Node, key string) string {
	for _, a := range n.Attr {
		if a.Key == key {
			return strings.TrimSpace(a.Val)
		}
	}
	return ""
}

func setAttr(n *html.Node, key, val string) {
	for i := range n.Attr {
		if n.Attr[i].Key == key {
			n.Attr[i].Val = val
			return
		}
	}
	n.Attr = append(n.Attr, html.Attribute{Key: key, Val: val})
}

func removeAttr(n *html.Node, key string) {
	out := n.Attr[:0]
	for _, a := range n.Attr {
		if a.Key != key {
			out = append(out, a)
		}
	}
	n.Attr = out
}

func findElement(n *html.Node, a atom.Atom) *html.Node {
	if n.Type == html.ElementNode && n.DataAtom == a {
		return n
	}
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if f := findElement(c, a); f != nil {
			return f
		}
	}
	return nil
}

// stripNoise 整页兜底时去掉脚本、样式、导航之类不是正文的东西。
func stripNoise(n *html.Node) {
	for c := n.FirstChild; c != nil; {
		next := c.NextSibling
		if c.Type == html.ElementNode {
			switch c.DataAtom {
			case atom.Script, atom.Style, atom.Noscript, atom.Iframe, atom.Form, atom.Nav, atom.Header, atom.Footer,
				atom.Aside, atom.Button, atom.Select, atom.Input, atom.Svg, atom.Template:
				n.RemoveChild(c)
			default:
				stripNoise(c)
			}
		} else if c.Type == html.CommentNode {
			n.RemoveChild(c)
		}
		c = next
	}
}

func textOf(n *html.Node) string {
	var b strings.Builder
	walk(n, func(x *html.Node) {
		if x.Type == html.TextNode {
			b.WriteString(x.Data)
		}
	})
	return b.String()
}

func textLen(n *html.Node) int { return len([]rune(strings.TrimSpace(textOf(n)))) }

var spaceRe = regexp.MustCompile(`\s+`)

func clean(s string) string {
	return strings.TrimSpace(spaceRe.ReplaceAllString(strings.Join(strings.FieldsFunc(s, unicode.IsSpace), " "), " "))
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// CountWords 中日韩文字一个字算一个,其余按空白分词。
func CountWords(s string) int {
	n, inWord := 0, false
	for _, r := range s {
		switch {
		case unicode.Is(unicode.Han, r) || unicode.Is(unicode.Hiragana, r) || unicode.Is(unicode.Katakana, r) || unicode.Is(unicode.Hangul, r):
			n++
			inWord = false
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			if !inWord {
				n++
				inWord = true
			}
		default:
			inWord = false
		}
	}
	return n
}
