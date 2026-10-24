// Package mdrender 把 Markdown 渲染成一页完整的 HTML(doc/23 五.2),给 /md/ 用。
//
// 安全:不渲染 Markdown 里的原始 HTML(goldmark 默认),javascript: 之类的危险链接丢掉;
// 响应头另有 CSP sandbox,脚本无论如何跑不起来。相对路径的图片与链接交给调用方改写。
package mdrender

import (
	"bytes"
	"html"
	"net/url"
	"strconv"
	"strings"
	"unicode"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/parser"
	"github.com/yuin/goldmark/text"
	"github.com/yuin/goldmark/util"
)

// MaxSize 超过就不渲染(调用方提示下载或看源文件)。
const MaxSize = 2 << 20

type Options struct {
	// Theme "light" / "dark";空 = 跟随系统
	Theme string
	// Title 没有 YAML 标题时用的页面标题(文件名)
	Title string
	// PadTop / PadBottom 正文前后多留的空白(CSS px):嵌进 App 的页面铺满整屏时让出浮在上面的顶栏、下面的底部导航
	PadTop, PadBottom int
	// Resolve 改写相对地址(image 区分图片和链接);返回空串保留原样
	Resolve func(rel string, image bool) string
}

// FrontMatter 开头 --- 之间的 key: value(只认一层,剪藏写的就是这种)。
type FrontMatter map[string]string

// SplitFrontMatter 拆出开头的 YAML;没有就返回 nil 与原文。
func SplitFrontMatter(src []byte) (FrontMatter, []byte) {
	s := string(src)
	s = strings.TrimPrefix(s, "\uFEFF")
	norm := strings.ReplaceAll(s, "\r\n", "\n")
	if !strings.HasPrefix(norm, "---\n") {
		return nil, src
	}
	end := strings.Index(norm[4:], "\n---")
	if end < 0 || end > 8<<10 {
		return nil, src
	}
	head := norm[4 : 4+end]
	rest := norm[4+end+4:]
	rest = strings.TrimPrefix(strings.TrimPrefix(rest, "-"), "\n")
	fm := FrontMatter{}
	for _, line := range strings.Split(head, "\n") {
		k, v, ok := strings.Cut(line, ":")
		if !ok || strings.HasPrefix(strings.TrimSpace(line), "#") {
			continue
		}
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if len(v) >= 2 && (v[0] == '"' && v[len(v)-1] == '"' || v[0] == '\'' && v[len(v)-1] == '\'') {
			q := v[0]
			v = v[1 : len(v)-1]
			if q == '"' {
				v = strings.ReplaceAll(strings.ReplaceAll(v, `\"`, `"`), `\\`, `\`)
			}
		}
		if k != "" {
			fm[k] = v
		}
	}
	return fm, []byte(rest)
}

type rewriter struct{ opt Options }

func (r *rewriter) Transform(doc *ast.Document, _ text.Reader, _ parser.Context) {
	_ = ast.Walk(doc, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if !entering {
			return ast.WalkContinue, nil
		}
		switch v := n.(type) {
		case *ast.Image:
			// 不懒加载:图片地址带的是当次的访问令牌(15 分钟),读得久了再滚到的图会取不到
			if dst := r.rewrite(string(v.Destination), true); dst != "" {
				v.Destination = []byte(dst)
			}
		case *ast.Link:
			dest := string(v.Destination)
			if isExternal(dest) {
				external(v)
			} else if dst := r.rewrite(dest, false); dst != "" {
				v.Destination = []byte(dst)
			}
		case *ast.AutoLink:
			external(v)
		}
		return ast.WalkContinue, nil
	})
}

func external(n ast.Node) {
	n.SetAttributeString("target", []byte("_blank"))
	n.SetAttributeString("rel", []byte("noopener noreferrer"))
}

func isExternal(dest string) bool {
	l := strings.ToLower(dest)
	return strings.HasPrefix(l, "http://") || strings.HasPrefix(l, "https://") || strings.HasPrefix(l, "mailto:")
}

// rewrite 只管相对地址(没有协议、不是 // 开头、不是锚点);# 后面的锚点留着。
func (r *rewriter) rewrite(dest string, image bool) string {
	if r.opt.Resolve == nil || dest == "" || strings.HasPrefix(dest, "#") || strings.HasPrefix(dest, "//") {
		return ""
	}
	if u, err := url.Parse(dest); err != nil || u.Scheme != "" {
		return ""
	}
	path, frag, _ := strings.Cut(dest, "#")
	if q := strings.IndexByte(path, '?'); q >= 0 {
		path = path[:q]
	}
	if p, err := url.PathUnescape(path); err == nil {
		path = p
	}
	out := r.opt.Resolve(path, image)
	if out != "" && frag != "" && !image {
		out += "#" + frag
	}
	return out
}

// Render 渲染成一页完整的 HTML 文档。
func Render(src []byte, opt Options) ([]byte, error) {
	fm, body := SplitFrontMatter(src)
	md := goldmark.New(
		goldmark.WithExtensions(extension.GFM, extension.Footnote),
		goldmark.WithParserOptions(
			parser.WithAutoHeadingID(),
			parser.WithASTTransformers(util.Prioritized(&rewriter{opt: opt}, 100)),
		),
	)
	var content bytes.Buffer
	pctx := parser.NewContext(parser.WithIDs(&headingIDs{seen: map[string]bool{}}))
	if err := md.Convert(body, &content, parser.WithContext(pctx)); err != nil {
		return nil, err
	}
	title := opt.Title
	if t := fm["title"]; t != "" {
		title = t
	}
	var out bytes.Buffer
	out.WriteString("<!doctype html>\n<html lang=\"zh\"")
	if opt.Theme == "dark" || opt.Theme == "light" {
		out.WriteString(` data-theme="` + opt.Theme + `"`)
	}
	out.WriteString("><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
	out.WriteString("<meta name=\"referrer\" content=\"no-referrer\"><title>" + html.EscapeString(title) + "</title><style>")
	out.WriteString(css)
	if opt.PadTop > 0 {
		out.WriteString("body{padding-top:" + strconv.Itoa(opt.PadTop) + "px}")
	}
	if opt.PadBottom > 0 {
		out.WriteString("body{padding-bottom:" + strconv.Itoa(opt.PadBottom) + "px}")
	}
	out.WriteString("</style></head><body><main class=\"md\">")
	if len(fm) > 0 {
		writeCard(&out, fm)
	}
	out.Write(content.Bytes())
	out.WriteString("</main></body></html>\n")
	return out.Bytes(), nil
}

// headingIDs 标题锚点:保留中文等文字(默认的生成器只留 ASCII,中文标题全成了 heading)。
type headingIDs struct{ seen map[string]bool }

func (h *headingIDs) Generate(value []byte, _ ast.NodeKind) []byte {
	var b strings.Builder
	for _, r := range strings.ToLower(strings.TrimSpace(string(value))) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' || r == '_':
			b.WriteRune(r)
		case unicode.IsSpace(r):
			b.WriteByte('-')
		}
	}
	id := b.String()
	if id == "" {
		id = "section"
	}
	base := id
	for i := 1; h.seen[id]; i++ {
		id = base + "-" + strconv.Itoa(i)
	}
	h.seen[id] = true
	return []byte(id)
}

func (h *headingIDs) Put(value []byte) { h.seen[string(value)] = true }

func writeCard(w *bytes.Buffer, fm FrontMatter) {
	src := fm["source"]
	if src == "" {
		src = fm["url"]
	}
	var parts []string
	for _, k := range []string{"site", "author", "published"} {
		if v := fm[k]; v != "" {
			parts = append(parts, html.EscapeString(v))
		}
	}
	if len(parts) == 0 && src == "" && fm["clipped"] == "" {
		return
	}
	w.WriteString(`<div class="card">`)
	if len(parts) > 0 {
		w.WriteString(`<div>` + strings.Join(parts, " · ") + `</div>`)
	}
	if isExternal(src) && !strings.HasPrefix(strings.ToLower(src), "mailto:") {
		esc := html.EscapeString(src)
		w.WriteString(`<div class="src">原文:<a href="` + esc + `" target="_blank" rel="noopener noreferrer">` + esc + `</a></div>`)
	}
	if c := fm["clipped"]; c != "" {
		if len(c) >= 16 {
			c = strings.Replace(c[:16], "T", " ", 1)
		}
		w.WriteString(`<div class="muted">剪藏于 ` + html.EscapeString(c) + `</div>`)
	}
	w.WriteString(`</div>`)
}

const css = `
:root{--fg:#1f2328;--muted:#59636e;--bg:#fff;--line:#d1d9e0;--code:#f6f8fa;--link:#0969da;--card:#f6f8fa}
:root[data-theme="dark"]{--fg:#e6edf3;--muted:#9198a1;--bg:#0d1117;--line:#3d444d;--code:#151b23;--link:#4493f8;--card:#151b23}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){--fg:#e6edf3;--muted:#9198a1;--bg:#0d1117;--line:#3d444d;--code:#151b23;--link:#4493f8;--card:#151b23}}
html{background:var(--bg);color:var(--fg)}
body{margin:0;font:16px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;word-wrap:break-word}
.md{max-width:820px;margin:0 auto;padding:24px 20px 64px}
.md a{color:var(--link);text-decoration:none}.md a:hover{text-decoration:underline}
.md h1,.md h2,.md h3,.md h4{line-height:1.3;margin:1.6em 0 .6em}
.md h1{font-size:1.9em;margin-top:.4em}.md h2{font-size:1.45em;border-bottom:1px solid var(--line);padding-bottom:.25em}
.md p,.md ul,.md ol,.md blockquote,.md pre,.md table{margin:0 0 1em}
.md img{max-width:100%;height:auto;border-radius:4px}
.md blockquote{margin-left:0;padding:0 1em;color:var(--muted);border-left:.25em solid var(--line)}
.md code{font:.875em/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--code);padding:.15em .35em;border-radius:4px}
.md pre{background:var(--code);padding:14px 16px;border-radius:6px;overflow:auto}.md pre code{background:none;padding:0}
.md table{border-collapse:collapse;display:block;overflow:auto;max-width:100%}
.md th,.md td{border:1px solid var(--line);padding:6px 12px}.md tr:nth-child(2n){background:var(--code)}
.md hr{border:0;border-top:1px solid var(--line);margin:2em 0}
.md li+li{margin-top:.25em}.md input[type=checkbox]{margin-right:.4em}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 14px;margin-bottom:1.2em;font-size:.9em}
.card .src{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.muted,.card .muted{color:var(--muted)}
.footnotes{font-size:.9em;color:var(--muted)}
`
