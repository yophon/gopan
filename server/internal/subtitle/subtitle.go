// Package subtitle 把外挂字幕转成浏览器 <track> 认的 WebVTT(doc/22 二.2)。
// SRT 改时间戳的逗号、去序号;ASS / SSA 只取 [Events] 里 Dialogue 的时间与文字,样式与特效丢掉。
package subtitle

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// ToVTT ext 是 .srt / .ass / .ssa / .vtt(小写)。text 已解码成 UTF-8。
func ToVTT(ext, text string) (string, error) {
	text = strings.TrimPrefix(text, "\uFEFF")
	text = strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
	switch ext {
	case ".vtt":
		if !strings.HasPrefix(strings.TrimLeft(text, " \t\n"), "WEBVTT") {
			return "WEBVTT\n\n" + text, nil
		}
		return text, nil
	case ".srt":
		return srtToVTT(text), nil
	case ".ass", ".ssa":
		return assToVTT(text)
	}
	return "", fmt.Errorf("不支持的字幕格式 %s", ext)
}

var srtTime = regexp.MustCompile(`(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})`)

func srtToVTT(text string) string {
	var b strings.Builder
	b.WriteString("WEBVTT\n\n")
	for _, block := range splitBlocks(text) {
		lines := strings.Split(block, "\n")
		at := -1
		for i, l := range lines {
			if srtTime.MatchString(l) {
				at = i
				break
			}
		}
		if at < 0 || at+1 >= len(lines) {
			continue
		}
		m := srtTime.FindStringSubmatch(lines[at])
		b.WriteString(vttStamp(m[1], m[2], m[3], m[4]) + " --> " + vttStamp(m[5], m[6], m[7], m[8]) + "\n")
		for _, l := range lines[at+1:] {
			b.WriteString(escapeCue(srtTags(l)) + "\n")
		}
		b.WriteString("\n")
	}
	return b.String()
}

func splitBlocks(text string) []string {
	var out []string
	for _, b := range regexp.MustCompile(`\n\s*\n`).Split(strings.TrimSpace(text), -1) {
		if b = strings.TrimSpace(b); b != "" {
			out = append(out, b)
		}
	}
	return out
}

func vttStamp(h, m, s, ms string) string {
	hh, _ := strconv.Atoi(h)
	for len(ms) < 3 {
		ms += "0"
	}
	return fmt.Sprintf("%02d:%s:%s.%s", hh, m, s, ms)
}

// srtTags SRT 里常见的 <i> <b> <u> 在 VTT 里同样有效;<font> 一类去掉。
var srtFont = regexp.MustCompile(`(?i)</?font[^>]*>`)

func srtTags(l string) string { return srtFont.ReplaceAllString(l, "") }

// escapeCue VTT 正文里 & 与不属于标签的 < 要转义;保留 i / b / u 标签。
var cueTag = regexp.MustCompile(`(?i)^</?(i|b|u)>`)

func escapeCue(l string) string {
	var b strings.Builder
	for i := 0; i < len(l); i++ {
		switch l[i] {
		case '&':
			b.WriteString("&amp;")
		case '<':
			if m := cueTag.FindString(l[i:]); m != "" {
				b.WriteString(strings.ToLower(m))
				i += len(m) - 1
			} else {
				b.WriteString("&lt;")
			}
		default:
			b.WriteByte(l[i])
		}
	}
	return strings.TrimRight(b.String(), " \t")
}

var (
	assOverride = regexp.MustCompile(`\{[^}]*\}`)
	assDrawing  = regexp.MustCompile(`(?s)\{[^}]*\\p[1-9][^}]*\}.*?(\{[^}]*\\p0[^}]*\}|$)`)
)

func assToVTT(text string) (string, error) {
	var b strings.Builder
	b.WriteString("WEBVTT\n\n")
	inEvents := false
	format := []string{"layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"}
	cues := 0
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "[") {
			inEvents = strings.EqualFold(line, "[Events]")
			continue
		}
		if !inEvents {
			continue
		}
		key, val, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(key)) {
		case "format":
			format = nil
			for _, f := range strings.Split(val, ",") {
				format = append(format, strings.ToLower(strings.TrimSpace(f)))
			}
		case "dialogue":
			// Text 是最后一列,里面可以有逗号
			parts := strings.SplitN(strings.TrimSpace(val), ",", len(format))
			if len(parts) != len(format) {
				continue
			}
			var start, end, body string
			for i, f := range format {
				switch f {
				case "start":
					start = parts[i]
				case "end":
					end = parts[i]
				case "text":
					body = parts[i]
				}
			}
			st, ok1 := assStamp(start)
			et, ok2 := assStamp(end)
			if !ok1 || !ok2 {
				continue
			}
			body = assDrawing.ReplaceAllString(body, "")
			body = assOverride.ReplaceAllString(body, "")
			body = strings.NewReplacer(`\N`, "\n", `\n`, "\n", `\h`, " ").Replace(body)
			var lines []string
			for _, l := range strings.Split(body, "\n") {
				if l = strings.TrimSpace(l); l != "" {
					lines = append(lines, escapeCue(l))
				}
			}
			if len(lines) == 0 {
				continue
			}
			b.WriteString(st + " --> " + et + "\n" + strings.Join(lines, "\n") + "\n\n")
			cues++
		}
	}
	if cues == 0 {
		return "", fmt.Errorf("没有找到字幕内容")
	}
	return b.String(), nil
}

// assStamp "0:01:02.34"(百分之一秒)→ "00:01:02.340"。
func assStamp(s string) (string, bool) {
	s = strings.TrimSpace(s)
	h, rest, ok := strings.Cut(s, ":")
	if !ok {
		return "", false
	}
	m, rest, ok := strings.Cut(rest, ":")
	if !ok {
		return "", false
	}
	sec, cs, _ := strings.Cut(rest, ".")
	hh, err1 := strconv.Atoi(h)
	mm, err2 := strconv.Atoi(m)
	ss, err3 := strconv.Atoi(sec)
	if err1 != nil || err2 != nil || err3 != nil {
		return "", false
	}
	ms := 0
	if cs != "" {
		for len(cs) < 3 {
			cs += "0"
		}
		ms, _ = strconv.Atoi(cs[:3])
	}
	return fmt.Sprintf("%02d:%02d:%02d.%03d", hh, mm, ss, ms), true
}

// Lang 字幕文件名里的语言标记 → 显示名与 BCP 47。认不出返回 ("", "")。
func Lang(tag string) (label, lang string) {
	t := strings.ToLower(strings.TrimSpace(tag))
	if t == "" {
		return "", ""
	}
	if l, g := langOne(t); l != "" {
		return l, g
	}
	// chs.eng / chs&eng / zh-en / 简英 这类双语
	parts := strings.FieldsFunc(t, func(r rune) bool { return r == '.' || r == '&' || r == '_' || r == '+' || r == '-' || r == ' ' })
	zh, en := false, false
	for _, p := range parts {
		l, g := langOne(p)
		if l == "" {
			continue
		}
		if label == "" {
			label, lang = l, g
		}
		zh = zh || strings.HasPrefix(g, "zh")
		en = en || g == "en"
	}
	if zh && en || t == "简英" || t == "双语" || t == "中英" || t == "中英双语" {
		return "中英双语", "zh"
	}
	return label, lang
}

var langTable = map[string][2]string{
	"zh": {"简体中文", "zh-Hans"}, "chs": {"简体中文", "zh-Hans"}, "sc": {"简体中文", "zh-Hans"}, "gb": {"简体中文", "zh-Hans"},
	"zh-cn": {"简体中文", "zh-Hans"}, "zh-hans": {"简体中文", "zh-Hans"}, "chi": {"简体中文", "zh-Hans"}, "cn": {"简体中文", "zh-Hans"},
	"简体": {"简体中文", "zh-Hans"}, "简": {"简体中文", "zh-Hans"}, "简中": {"简体中文", "zh-Hans"}, "中文": {"简体中文", "zh-Hans"},
	"cht": {"繁体中文", "zh-Hant"}, "tc": {"繁体中文", "zh-Hant"}, "big5": {"繁体中文", "zh-Hant"}, "zh-tw": {"繁体中文", "zh-Hant"},
	"zh-hk": {"繁体中文", "zh-Hant"}, "zh-hant": {"繁体中文", "zh-Hant"}, "繁體": {"繁体中文", "zh-Hant"}, "繁体": {"繁体中文", "zh-Hant"},
	"繁": {"繁体中文", "zh-Hant"}, "繁中": {"繁体中文", "zh-Hant"},
	"en": {"English", "en"}, "eng": {"English", "en"}, "english": {"English", "en"}, "英": {"English", "en"}, "英文": {"English", "en"},
	"ja": {"日本語", "ja"}, "jp": {"日本語", "ja"}, "jpn": {"日本語", "ja"}, "japanese": {"日本語", "ja"}, "日": {"日本語", "ja"},
	"ko": {"한국어", "ko"}, "kor": {"한국어", "ko"}, "kr": {"한국어", "ko"}, "korean": {"한국어", "ko"},
	"fr": {"Français", "fr"}, "fre": {"Français", "fr"}, "de": {"Deutsch", "de"}, "ger": {"Deutsch", "de"},
	"es": {"Español", "es"}, "spa": {"Español", "es"}, "ru": {"Русский", "ru"}, "rus": {"Русский", "ru"},
}

func langOne(t string) (string, string) {
	if v, ok := langTable[t]; ok {
		return v[0], v[1]
	}
	return "", ""
}
