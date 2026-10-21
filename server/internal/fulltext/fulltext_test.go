package fulltext

import (
	"archive/zip"
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"golang.org/x/text/encoding/simplifiedchinese"

	"github.com/yophon/gopan/server/internal/fulltext/fulltexttest"
)

func TestNormalize(t *testing.T) {
	cases := map[string]string{
		"  租房\n合同  ":     "租房合同", // CJK 之间的断行去掉
		"第一章 总则":         "第一章总则",
		"Hello\n\tworld": "Hello world",   // 其余空白折成一个空格
		"中文 English 混排":  "中文 English 混排", // CJK 与字母之间的空格保留
		"ＡＢＣ１２３":         "ABC123",        // 全角转半角
		"a\x00b\x07c":    "abc",           // NUL 与控制字符去掉(Postgres text 存不了 NUL)
		"":               "",
		"　全角空格　后":        "全角空格后",
	}
	for in, want := range cases {
		if got := Normalize(in); got != want {
			t.Errorf("Normalize(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestBuildAndPageOf(t *testing.T) {
	content, starts, truncated := Build([]string{"第一页 内容", "", "第三页"}, MaxContentBytes)
	if content != "第一页内容\n\n第三页" || truncated {
		t.Fatalf("content %q truncated %v", content, truncated)
	}
	if !slices.Equal(starts, []int32{0, 6, 7}) {
		t.Fatalf("starts %v", starts)
	}
	// strpos 从 1 起:第 1 个字在第 1 页,"第三页"的"第"在第 3 页
	if PageOf(starts, 1) != 1 || PageOf(starts, 5) != 1 || PageOf(starts, 8) != 3 {
		t.Fatal("page lookup wrong")
	}
	if PageOf(nil, 3) != 0 {
		t.Fatal("no pages → 0")
	}
	// 截断落在字符边界上
	c, _, tr := Build([]string{strings.Repeat("中", 10)}, 10)
	if !tr || c != "中中中" {
		t.Fatalf("truncate: %q %v", c, tr)
	}
}

func TestTermsAndQuery(t *testing.T) {
	terms, truncated := Terms("租房合同 Invoice-2024 的 AI", MaxTerms)
	want := []string{"租房", "房合", "合同", "inv", "nvo", "voi", "oic", "ice", "202", "024"}
	if truncated || !slices.Equal(terms, want) {
		t.Fatalf("terms %v", terms)
	}
	// 查询词项是内容词项的子集,才能用 @> 走索引
	for _, q := range []string{"合同", "租房合同", "invoice", "VOICE", "2024", "invoice 合同"} {
		qt := QueryTerms(q)
		if len(qt) == 0 {
			t.Fatalf("%q 不应太短", q)
		}
		for _, x := range qt {
			if !slices.Contains(terms, x) {
				t.Fatalf("%q 的词项 %q 不在内容里", q, x)
			}
		}
	}
	for _, q := range []string{"合", "ai", "的", "  ", "a 合"} {
		if qt := QueryTerms(q); len(qt) != 0 {
			t.Fatalf("%q 太短,不该有词项: %v", q, qt)
		}
	}
	// 词项去重、封顶
	many, tr := Terms(strings.Repeat("甲乙丙丁戊己庚辛", 3), 3)
	if !tr || len(many) != 3 {
		t.Fatalf("cap: %v %v", many, tr)
	}
}

func TestHighlights(t *testing.T) {
	got := Highlights("…签订的租房合同,合同期一年 Contract contract", "合同")
	if len(got) != 2 || got[0] != (Range{6, 2}) || got[1] != (Range{9, 2}) {
		t.Fatalf("highlights %v", got)
	}
	if got := Highlights("Contract contract", "CONTRACT"); len(got) != 2 {
		t.Fatalf("case-insensitive: %v", got)
	}
}

func TestDecodeText(t *testing.T) {
	gbk, _ := simplifiedchinese.GBK.NewEncoder().Bytes([]byte("这是一段 GBK 编码的中文"))
	cases := []struct {
		name string
		in   []byte
		want string
		ok   bool
	}{
		{"utf8", []byte("你好 world"), "你好 world", true},
		{"utf8 bom", append([]byte{0xEF, 0xBB, 0xBF}, []byte("带 BOM")...), "带 BOM", true},
		{"gbk", gbk, "这是一段 GBK 编码的中文", true},
		{"utf16le", []byte{0xFF, 0xFE, 'h', 0, 'i', 0, 0x2d, 0x4e}, "hi中", true},
		{"cut mid rune", []byte("截断")[:5], "截", true},
		{"binary", []byte{'a', 0, 'b', 0, 0, 1, 2}, "", false},
	}
	for _, c := range cases {
		got, ok := DecodeText(c.in)
		if ok != c.ok || (ok && got != c.want) {
			t.Errorf("%s: got %q %v", c.name, got, ok)
		}
	}
}

func TestSniff(t *testing.T) {
	cases := []struct {
		head  string
		names []string
		mime  string
		want  Kind
	}{
		{"%PDF-1.7\n", []string{"a.bin"}, "application/octet-stream", KindPDF},
		{"PK\x03\x04xxxx", []string{"a.docx"}, "", KindZip},
		{"\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1", []string{"a.doc"}, "", KindOLE},
		{"\xFF\xD8\xFF\xE0", []string{"a.txt"}, "text/plain", KindUnsupported}, // 名字是 txt 但其实是 jpeg
		{"\x00\x00\x00\x18ftypmp42", nil, "", KindUnsupported},
		{"package main\n", []string{"main.go"}, "", KindText},
		{"plain words", []string{"README"}, "", KindText}, // 没扩展名的干净文本
		{"\x01\x00\x02\x00", []string{"data"}, "", KindUnsupported},
	}
	for _, c := range cases {
		if got := Sniff([]byte(c.head), c.names, c.mime); got != c.want {
			t.Errorf("Sniff(%q, %v) = %v, want %v", c.head, c.names, got, c.want)
		}
	}
}

func zipOf(t *testing.T, files map[string]string) *zip.Reader {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	names := make([]string, 0, len(files))
	for n := range files {
		names = append(names, n)
	}
	slices.Sort(names)
	for _, n := range names {
		w, err := zw.Create(n)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = w.Write([]byte(files[n]))
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	return zr
}

func TestOfficeText(t *testing.T) {
	docx := zipOf(t, map[string]string{
		"[Content_Types].xml": "<Types/>",
		"word/document.xml": `<w:document xmlns:w="w"><w:body>
			<w:p><w:r><w:t>租房</w:t></w:r><w:r><w:t>合同</w:t></w:r></w:p>
			<w:p><w:r><w:instrText>PAGE</w:instrText><w:t xml:space="preserve">甲方 张三</w:t></w:r></w:p>
		</w:body></w:document>`,
	})
	pages, tr, err := OfficeText(docx)
	if err != nil || tr || len(pages) != 1 || Normalize(pages[0]) != "租房合同甲方张三" {
		t.Fatalf("docx: %q %v %v", pages, tr, err)
	}

	xlsx := zipOf(t, map[string]string{
		"xl/workbook.xml":      "<workbook/>",
		"xl/sharedStrings.xml": `<sst><si><t>发票号</t></si><si><r><t>金</t></r><r><t>额</t></r></si></sst>`,
	})
	pages, _, err = OfficeText(xlsx)
	if err != nil || Normalize(pages[0]) != "发票号金额" {
		t.Fatalf("xlsx: %q %v", pages, err)
	}

	pptx := zipOf(t, map[string]string{
		"ppt/presentation.xml":   "<p/>",
		"ppt/slides/slide2.xml":  `<p:sld><a:p><a:r><a:t>第二页</a:t></a:r></a:p></p:sld>`,
		"ppt/slides/slide10.xml": `<p:sld><a:p><a:r><a:t>第十页</a:t></a:r></a:p></p:sld>`,
		"ppt/slides/slide1.xml":  `<p:sld><a:p><a:r><a:t>第一页</a:t></a:r></a:p></p:sld>`,
	})
	pages, _, err = OfficeText(pptx)
	if err != nil || len(pages) != 3 || Normalize(pages[0]) != "第一页" || Normalize(pages[2]) != "第十页" {
		t.Fatalf("pptx 应按编号排序: %q %v", pages, err)
	}

	odt := zipOf(t, map[string]string{
		"mimetype":    "application/vnd.oasis.opendocument.text",
		"content.xml": `<office:document-content><office:meta>不要</office:meta><office:body><office:text><text:h>标题</text:h><text:p>正文<text:s/>内容</text:p></office:text></office:body></office:document-content>`,
	})
	pages, _, err = OfficeText(odt)
	if err != nil || Normalize(pages[0]) != "标题正文内容" {
		t.Fatalf("odt: %q %v", pages, err)
	}

	if _, _, err := OfficeText(zipOf(t, map[string]string{"photo.jpg": "x"})); err != ErrNotOffice {
		t.Fatalf("普通 zip 应 ErrNotOffice,got %v", err)
	}
}

// zip 炸弹:解压后几百 MB 的 XML,读到上限就停,已收的照用。
func TestOfficeTextBudget(t *testing.T) {
	var big strings.Builder
	big.WriteString("<w:document><w:body>")
	para := "<w:p><w:r><w:t>" + strings.Repeat("字", 1000) + "</w:t></w:r></w:p>"
	for big.Len() < MaxXMLBytes+(1<<20) {
		big.WriteString(para)
	}
	big.WriteString("</w:body></w:document>")
	pages, truncated, err := OfficeText(zipOf(t, map[string]string{"word/document.xml": big.String()}))
	if err != nil || !truncated || len(pages[0]) == 0 || len(pages[0]) > MaxRawText {
		t.Fatalf("budget: len=%d truncated=%v err=%v", len(pages[0]), truncated, err)
	}
}

// 真跑 pdftotext(本机或 CI 装了才测)。PDF 用标准 14 字体手写,不依赖任何库。
func TestPDFText(t *testing.T) {
	bin, err := exec.LookPath("pdftotext")
	if err != nil {
		t.Skip("没有 pdftotext,跳过")
	}
	file := filepath.Join(t.TempDir(), "two-pages.pdf")
	if err := os.WriteFile(file, fulltexttest.PDF("Rental contract", "Party B pays rent"), 0o600); err != nil {
		t.Fatal(err)
	}
	pages, truncated, err := PDFText(context.Background(), bin, file)
	if err != nil || truncated {
		t.Fatalf("pdftotext: %v %v", err, truncated)
	}
	if len(pages) != 2 || !strings.Contains(pages[0], "Rental contract") || !strings.Contains(pages[1], "Party B pays rent") {
		t.Fatalf("pages %q", pages)
	}
	// 坏文件:报错而不是卡住
	bad := filepath.Join(t.TempDir(), "bad.pdf")
	_ = os.WriteFile(bad, []byte("%PDF-1.4\nnot really"), 0o600)
	if _, _, err := PDFText(context.Background(), bin, bad); err == nil {
		t.Fatal("坏 PDF 应报错")
	}
}
