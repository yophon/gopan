package subtitle

import (
	"strings"
	"testing"
)

func TestSRT(t *testing.T) {
	in := "\uFEFF1\r\n00:00:01,500 --> 00:00:03,000\r\n<font color=\"#fff\">你好</font> & <i>世界</i>\r\n第二行 <3\r\n\r\n2\r\n0:01:02,5 --> 0:01:04,25\r\nsecond\r\n\r\n\r\nbroken block\r\n"
	got, err := ToVTT(".srt", in)
	if err != nil {
		t.Fatal(err)
	}
	want := "WEBVTT\n\n00:00:01.500 --> 00:00:03.000\n你好 &amp; <i>世界</i>\n第二行 &lt;3\n\n00:01:02.500 --> 00:01:04.250\nsecond\n\n"
	if got != want {
		t.Fatalf("got\n%q\nwant\n%q", got, want)
	}
}

func TestASS(t *testing.T) {
	in := `[Script Info]
Title: test

[V4+ Styles]
Format: Name, Fontname
Style: Default,Arial

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,注释不要
Dialogue: 0,0:00:01.20,0:00:03.05,Default,,0,0,0,,{\an8\c&H00FFFF&}你好,世界\N{\i1}第二行{\i0}
Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
Dialogue: 0,1:02:03.4,1:02:04.00,Default,,0,0,0,,a\hb & <c>
`
	got, err := ToVTT(".ass", in)
	if err != nil {
		t.Fatal(err)
	}
	want := "WEBVTT\n\n00:00:01.200 --> 00:00:03.050\n你好,世界\n第二行\n\n01:02:03.400 --> 01:02:04.000\na b &amp; &lt;c>\n\n"
	if got != want {
		t.Fatalf("got\n%q\nwant\n%q", got, want)
	}
	if _, err := ToVTT(".ssa", "[Events]\nFormat: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"); err == nil {
		t.Fatal("没有对白应报错")
	}
}

func TestVTTPassThrough(t *testing.T) {
	got, _ := ToVTT(".vtt", "WEBVTT\n\n00:01.000 --> 00:02.000\nhi\n")
	if !strings.HasPrefix(got, "WEBVTT") || !strings.Contains(got, "hi") {
		t.Fatal(got)
	}
	got, _ = ToVTT(".vtt", "00:01.000 --> 00:02.000\nhi\n")
	if !strings.HasPrefix(got, "WEBVTT\n\n") {
		t.Fatal("缺头时补上")
	}
	if _, err := ToVTT(".sub", ""); err == nil {
		t.Fatal("不支持的格式应报错")
	}
}

func TestLang(t *testing.T) {
	cases := map[string][2]string{
		"zh": {"简体中文", "zh-Hans"}, "CHS": {"简体中文", "zh-Hans"}, "简体": {"简体中文", "zh-Hans"},
		"cht": {"繁体中文", "zh-Hant"}, "zh-TW": {"繁体中文", "zh-Hant"},
		"eng": {"English", "en"}, "en.forced": {"English", "en"}, "forced.en": {"English", "en"},
		"chs.eng": {"中英双语", "zh"}, "chs&eng": {"中英双语", "zh"}, "简英": {"中英双语", "zh"},
		"jpn": {"日本語", "ja"}, "": {"", ""}, "1080p": {"", ""},
	}
	for in, want := range cases {
		l, g := Lang(in)
		if l != want[0] || g != want[1] {
			t.Errorf("Lang(%q) = %q %q, want %q %q", in, l, g, want[0], want[1])
		}
	}
}
