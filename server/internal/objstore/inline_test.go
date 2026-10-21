package objstore

import "testing"

// 内联预签名的响应类型一律显式给出:能在站点同源执行脚本的类型不能按原样返回。
func TestInlineOverrides(t *testing.T) {
	cases := []struct {
		mime, wantType, wantDisp string
	}{
		{"image/png", "image/png", ""},
		{"image/jpeg", "image/jpeg", ""},
		{"video/mp4", "video/mp4", ""},
		{"audio/mpeg", "audio/mpeg", ""},
		{"application/pdf", "application/pdf", ""},
		{"Image/SVG+XML", "image/svg+xml", "attachment"},
		{"text/html; charset=utf-8", "text/plain; charset=utf-8", ""},
		{"application/xhtml+xml", "text/plain; charset=utf-8", ""},
		{"text/xml", "text/plain; charset=utf-8", ""},
		{"application/javascript", "text/plain; charset=utf-8", ""},
		{"text/plain; charset=utf-8", "text/plain; charset=utf-8", ""},
		{"application/octet-stream", "text/plain; charset=utf-8", ""},
		{"", "text/plain; charset=utf-8", ""},
	}
	for _, c := range cases {
		v := InlineOverrides(c.mime)
		if got := v.Get("response-content-type"); got != c.wantType {
			t.Errorf("%q: content-type = %q, want %q", c.mime, got, c.wantType)
		}
		if got := v.Get("response-content-disposition"); got != c.wantDisp {
			t.Errorf("%q: disposition = %q, want %q", c.mime, got, c.wantDisp)
		}
	}
}
