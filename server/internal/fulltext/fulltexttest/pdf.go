// Package fulltexttest 测试用的小工具:手写最小 PDF,不依赖任何库。
package fulltexttest

import (
	"bytes"
	"fmt"
)

// PDF 每个参数一页,标准 14 字体 Helvetica,带正确的 xref 偏移。只用 ASCII 文字。
func PDF(pages ...string) []byte {
	n := len(pages)
	stream := func(s string) string { return fmt.Sprintf("BT /F1 18 Tf 72 720 Td (%s) Tj ET", s) }
	// 对象编号:1 Catalog,2 Pages,3..2+n 页,3+n..2+2n 内容流,3+2n 字体
	font := 3 + 2*n
	kids := ""
	for i := range n {
		kids += fmt.Sprintf("%d 0 R ", 3+i)
	}
	objs := []string{
		"<< /Type /Catalog /Pages 2 0 R >>",
		fmt.Sprintf("<< /Type /Pages /Kids [%s] /Count %d >>", kids, n),
	}
	for i := range n {
		objs = append(objs, fmt.Sprintf("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents %d 0 R /Resources << /Font << /F1 %d 0 R >> >> >>", 3+n+i, font))
	}
	for _, p := range pages {
		objs = append(objs, fmt.Sprintf("<< /Length %d >>\nstream\n%s\nendstream", len(stream(p)), stream(p)))
	}
	objs = append(objs, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

	var b bytes.Buffer
	b.WriteString("%PDF-1.4\n")
	offsets := make([]int, len(objs))
	for i, o := range objs {
		offsets[i] = b.Len()
		fmt.Fprintf(&b, "%d 0 obj\n%s\nendobj\n", i+1, o)
	}
	xref := b.Len()
	fmt.Fprintf(&b, "xref\n0 %d\n0000000000 65535 f \n", len(objs)+1)
	for _, off := range offsets {
		fmt.Fprintf(&b, "%010d 00000 n \n", off)
	}
	fmt.Fprintf(&b, "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", len(objs)+1, xref)
	return b.Bytes()
}
