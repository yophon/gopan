package clipper

import (
	"net/url"
	"strings"
	"testing"
	"time"
)

const page = `<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>一篇文章 - 某博客</title>
<meta property="og:site_name" content="某博客"><meta name="author" content="张三">
<meta property="article:published_time" content="2026-10-01T08:00:00+08:00"></head>
<body><nav><a href="/">首页</a><a href="/about">关于</a></nav>
<article><h1>一篇文章</h1>
<p>这是第一段正文,讲的是怎么在小服务器上自己搭一个网盘。内容足够长,才能被正文提取认出来,所以这里多写几句话。</p>
<p>第二段:<a href="/docs/setup">安装说明</a>里有更详细的步骤,还有 <strong>重点</strong> 和 <code>inline code</code>。</p>
<img src="data:image/gif;base64,R0lGOD" data-src="/img/a.png" alt="图一">
<p>再来一张图,和上一张一样:</p><img src="https://cdn.example.com/b.jpg" alt="图二"><img src="/img/a.png">
<table><thead><tr><th>项目</th><th>值</th></tr></thead><tbody><tr><td>内存</td><td>2G</td></tr></tbody></table>
<pre><code>docker compose up -d</code></pre>
<p>最后一段,总结一下,这篇文章写了很多字,用来测试字数统计和正文提取能不能正常工作。</p>
</article><footer>版权所有</footer><script>alert(1)</script></body></html>`

func TestExtract(t *testing.T) {
	u, _ := url.Parse("https://blog.example.com/posts/1?utm=x")
	a, err := Extract([]byte(page), u)
	if err != nil {
		t.Fatal(err)
	}
	if a.Title != "一篇文章" || a.Site != "某博客" || a.Byline != "张三" || a.Published.IsZero() {
		t.Fatalf("meta = %+v", a)
	}
	if len(a.Images) != 2 || a.Images[0].URL != "https://blog.example.com/img/a.png" || a.Images[1].URL != "https://cdn.example.com/b.jpg" {
		t.Fatalf("images = %+v", a.Images)
	}
	md := a.Markdown
	for _, want := range []string{"https://blog.example.com/docs/setup", "**重点**", "`inline code`", "| 项目", "docker compose up -d", a.Images[0].Placeholder} {
		if !strings.Contains(md, want) {
			t.Fatalf("markdown missing %q:\n%s", want, md)
		}
	}
	if strings.Contains(md, "alert(1)") || strings.Contains(md, "版权所有") {
		t.Fatalf("noise kept:\n%s", md)
	}
	if strings.Count(md, a.Images[0].Placeholder) != 2 {
		t.Fatalf("same image should reuse placeholder:\n%s", md)
	}
	out := ReplaceImages(md, a.Images, map[string]string{a.Images[0].Placeholder: "一篇文章.assets/01.png"})
	if !strings.Contains(out, "一篇文章.assets/01.png") || !strings.Contains(out, "https://cdn.example.com/b.jpg") || strings.Contains(out, "gopan.invalid") {
		t.Fatalf("replace:\n%s", out)
	}
	if a.Words < 60 {
		t.Fatalf("words = %d", a.Words)
	}
	doc := Document(a, u.String(), time.Date(2026, 10, 21, 15, 0, 0, 0, time.UTC), out)
	if !strings.HasPrefix(doc, "---\ntitle: \"一篇文章\"\nsource: \"https://blog.example.com/posts/1?utm=x\"\n") || !strings.Contains(doc, "published: \"2026-10-01\"") ||
		strings.Count(doc, "一篇文章\n") != 1 || !strings.Contains(doc, "\n# 一篇文章\n") {
		t.Fatalf("doc:\n%s", doc)
	}
}

func TestExtractFallback(t *testing.T) {
	u, _ := url.Parse("https://x.example.com/")
	a, err := Extract([]byte(`<html><head><title>短页</title></head><body><nav>菜单</nav><p>只有一句话。</p><script>x()</script></body></html>`), u)
	if err != nil {
		t.Fatal(err)
	}
	if !a.Fallback || a.Title != "短页" || !strings.Contains(a.Markdown, "只有一句话") || strings.Contains(a.Markdown, "x()") {
		t.Fatalf("fallback = %+v", a)
	}
}

func TestSplitTitleSite(t *testing.T) {
	for in, want := range map[string][2]string{
		"Go 1.23 is released - The Go Programming Language": {"Go 1.23 is released", "The Go Programming Language"},
		"派早报:iPod touch 停产 - 少数派":                       {"派早报:iPod touch 停产", "少数派"},
		"标题_某某网":                                         {"标题", "某某网"},
		"A | B | 站点":                                       {"A | B", "站点"},
	} {
		if h, s, ok := splitTitleSite(in); !ok || h != want[0] || s != want[1] {
			t.Fatalf("splitTitleSite(%q) = %q %q %v", in, h, s, ok)
		}
	}
	if _, _, ok := splitTitleSite("没有分隔符的标题"); ok {
		t.Fatal("no separator")
	}
}

func TestExtractSloganSiteName(t *testing.T) {
	u, _ := url.Parse("https://sspai.com/post/1")
	body := strings.Repeat("<p>早报正文,一段足够长的文字,让正文提取认得出来。</p>", 6)
	a, err := Extract([]byte(`<html><head><title>派早报:今天的新闻 - 少数派</title><meta property="og:site_name" content="少数派 - 高品质数字消费指南"></head><body><article>`+body+`</article></body></html>`), u)
	if err != nil || a.Title != "派早报:今天的新闻" || a.Site != "少数派" {
		t.Fatalf("title %q site %q %v", a.Title, a.Site, err)
	}
}

func TestCountWords(t *testing.T) {
	if n := CountWords("你好 world, hello 世界 42"); n != 7 {
		t.Fatalf("CountWords = %d", n)
	}
}
