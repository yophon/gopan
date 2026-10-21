package fulltext

import (
	"archive/zip"
	"encoding/xml"
	"errors"
	"io"
	"path"
	"sort"
	"strconv"
	"strings"
)

// MaxXMLBytes 一个 Office 文件里读的 XML 解压后累计上限,防 zip 炸弹
const MaxXMLBytes = 64 << 20

// MaxRawText 提取出的原文上限(归一化之前),之后 Build 再截到 MaxContentBytes
const MaxRawText = 4 << 20

// ErrNotOffice zip 但不是认识的 Office / ODF 文档(普通压缩包)。
var ErrNotOffice = errors.New("not an office document")

// OfficeText 从 docx / xlsx / pptx / odt / ods / odp 里取正文。truncated:读到上限提前停了。
// 每张幻灯片一页,其余一页。
func OfficeText(zr *zip.Reader) (pages []string, truncated bool, err error) {
	files := map[string]*zip.File{}
	for _, f := range zr.File {
		files[f.Name] = f
	}
	budget := &xmlBudget{left: MaxXMLBytes}
	switch {
	case files["word/document.xml"] != nil:
		var b textBuf
		err = readXML(files["word/document.xml"], budget, &b, ooxmlText("t"), ooxmlBreaks("p", "br", "cr", "tab"))
		return []string{b.String()}, budget.hit || b.full, err
	case files["xl/sharedStrings.xml"] != nil || files["xl/workbook.xml"] != nil:
		// 单元格里的文字绝大多数在共享字符串表里;数字不收
		var b textBuf
		if f := files["xl/sharedStrings.xml"]; f != nil {
			err = readXML(f, budget, &b, ooxmlText("t"), ooxmlBreaks("si"))
		}
		return []string{b.String()}, budget.hit || b.full, err
	case hasPrefix(files, "ppt/slides/slide"):
		var out []string
		for _, f := range slides(files) {
			var b textBuf
			if err = readXML(f, budget, &b, ooxmlText("t"), ooxmlBreaks("p")); err != nil {
				return out, true, err
			}
			out = append(out, b.String())
			if budget.hit || b.full {
				return out, true, nil
			}
		}
		return out, false, nil
	case isODF(files):
		var b textBuf
		if f := files["content.xml"]; f != nil {
			err = readXML(f, budget, &b, odfText, ooxmlBreaks("p", "h", "line-break", "s", "tab"))
		}
		return []string{b.String()}, budget.hit || b.full, err
	}
	return nil, false, ErrNotOffice
}

func hasPrefix(files map[string]*zip.File, prefix string) bool {
	for name := range files {
		if strings.HasPrefix(name, prefix) {
			return true
		}
	}
	return false
}

// slides 按幻灯片编号排序(slide10 在 slide9 后面)
func slides(files map[string]*zip.File) []*zip.File {
	type s struct {
		n int
		f *zip.File
	}
	var list []s
	for name, f := range files {
		if !strings.HasPrefix(name, "ppt/slides/slide") || path.Ext(name) != ".xml" {
			continue
		}
		n, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(name, "ppt/slides/slide"), ".xml"))
		if err != nil {
			continue
		}
		list = append(list, s{n, f})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].n < list[j].n })
	out := make([]*zip.File, len(list))
	for i, x := range list {
		out[i] = x.f
	}
	return out
}

func isODF(files map[string]*zip.File) bool {
	f := files["mimetype"]
	if f == nil {
		return false
	}
	rc, err := f.Open()
	if err != nil {
		return false
	}
	defer rc.Close()
	b, _ := io.ReadAll(io.LimitReader(rc, 128))
	return strings.HasPrefix(string(b), "application/vnd.oasis.opendocument.")
}

type xmlBudget struct {
	left int64
	hit  bool
}

func (b *xmlBudget) Read(r io.Reader, p []byte) (int, error) {
	if b.left <= 0 {
		b.hit = true
		return 0, io.EOF
	}
	if int64(len(p)) > b.left {
		p = p[:b.left]
	}
	n, err := r.Read(p)
	b.left -= int64(n)
	return n, err
}

type budgetReader struct {
	r io.Reader
	b *xmlBudget
}

func (br budgetReader) Read(p []byte) (int, error) { return br.b.Read(br.r, p) }

type textBuf struct {
	strings.Builder
	full bool
}

func (t *textBuf) add(s string) {
	if t.full {
		return
	}
	if t.Len()+len(s) > MaxRawText {
		t.full = true
		return
	}
	t.WriteString(s)
}

// readXML 流式读一个 XML 条目:inText(当前元素栈)为真时收字符数据;breaks 里的元素结束时补一个换行。
func readXML(f *zip.File, budget *xmlBudget, out *textBuf, inText func(stack []string) bool, breaks map[string]bool) error {
	rc, err := f.Open()
	if err != nil {
		return err
	}
	defer rc.Close()
	dec := xml.NewDecoder(budgetReader{rc, budget})
	dec.Strict = false
	var stack []string
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			if budget.hit {
				return nil // 读到上限截断:已收的照用
			}
			return err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			stack = append(stack, t.Name.Local)
		case xml.EndElement:
			if len(stack) > 0 {
				stack = stack[:len(stack)-1]
			}
			if breaks[t.Name.Local] {
				out.add("\n")
			}
		case xml.CharData:
			if inText(stack) {
				out.add(string(t))
			}
		}
		if out.full {
			return nil
		}
	}
}

// ooxmlText:只收 <w:t> / <a:t> / <t> 里的字,其余(属性值、指令文本)都不要
func ooxmlText(elem string) func([]string) bool {
	return func(stack []string) bool { return len(stack) > 0 && stack[len(stack)-1] == elem }
}

func ooxmlBreaks(elems ...string) map[string]bool {
	m := make(map[string]bool, len(elems))
	for _, e := range elems {
		m[e] = true
	}
	return m
}

// odfText:office:body 下的字都收(段落、标题、表格单元格里的 text:p)
func odfText(stack []string) bool {
	for _, e := range stack {
		if e == "body" {
			return true
		}
	}
	return false
}
