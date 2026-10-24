package mdrender

import (
	"strings"
	"testing"
)

func TestRenderSafeAndRewrites(t *testing.T) {
	src := "---\ntitle: \"剪藏 \\\"标题\\\"\"\nsource: \"https://example.com/a\"\nsite: \"Example\"\nclipped: \"2026-10-21T15:30:00+08:00\"\n---\n\n" +
		"# 标题\n\n正文 <script>alert(1)</script> <img src=x onerror=alert(2)>\n\n" +
		"![图](文章.assets/01%20a.png) ![远程](https://cdn.example.com/x.png)\n\n" +
		"[危险](javascript:alert(3)) [外链](https://example.org) [相对](../别的.md#第二节) [锚点](#标题)\n\n" +
		"| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] 任务\n\nhttps://auto.example.com\n"
	var got []string
	out, err := Render([]byte(src), Options{Theme: "dark", Title: "文件.md", Resolve: func(rel string, image bool) string {
		got = append(got, rel)
		if image {
			return "/md/n/f/" + rel
		}
		return "/md/n/f/" + rel
	}})
	if err != nil {
		t.Fatal(err)
	}
	h := string(out)
	for _, bad := range []string{"<script>", "onerror", "javascript:"} {
		if strings.Contains(h, bad) {
			t.Fatalf("unsafe %q in:\n%s", bad, h)
		}
	}
	for _, want := range []string{
		`<title>剪藏 &#34;标题&#34;</title>`, `data-theme="dark"`, `原文:<a href="https://example.com/a"`, `剪藏于 2026-10-21 15:30`,
		`src="/md/n/f/%E6%96%87%E7%AB%A0.assets/01%20a.png"`, `src="https://cdn.example.com/x.png"`, `<a href="">危险</a>`,
		`href="https://example.org" target="_blank" rel="noopener noreferrer"`, `href="/md/n/f/../%E5%88%AB%E7%9A%84.md#%E7%AC%AC%E4%BA%8C%E8%8A%82"`,
		`href="#%E6%A0%87%E9%A2%98"`, `<h1 id="标题">`,
		`<table>`, `checked`, `href="https://auto.example.com" target="_blank"`,
	} {
		if !strings.Contains(h, want) {
			t.Fatalf("missing %q in:\n%s", want, h)
		}
	}
	if strings.Join(got, "|") != "文章.assets/01 a.png|../别的.md" {
		t.Fatalf("resolved = %v", got)
	}
}

func TestNoFrontMatter(t *testing.T) {
	fm, body := SplitFrontMatter([]byte("# hi\n---\nx"))
	if fm != nil || string(body) != "# hi\n---\nx" {
		t.Fatalf("fm = %v body = %q", fm, body)
	}
	out, err := Render([]byte("plain"), Options{Title: "a.md"})
	if err != nil || !strings.Contains(string(out), "<title>a.md</title>") || strings.Contains(string(out), `class="card"`) {
		t.Fatalf("out = %s %v", out, err)
	}
}

func TestRenderPadBottom(t *testing.T) {
	out, err := Render([]byte("# a"), Options{Title: "a.md", PadTop: 88, PadBottom: 96})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), "body{padding-top:88px}") || !strings.Contains(string(out), "body{padding-bottom:96px}") {
		t.Fatal("没有留出前后空白")
	}
	plain, _ := Render([]byte("# a"), Options{Title: "a.md"})
	if strings.Contains(string(plain), "body{padding-") {
		t.Fatal("不给 PadBottom 不该加")
	}
}
