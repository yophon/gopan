// Package fulltext 内容搜索的纯逻辑:归一化、切词、编码识别、正文提取(doc/20 第三节)。
// 不碰数据库与对象存储,worker 负责取字节、存结果。
//
// 中文不靠数据库扩展:内容与关键词按同一规则切成词项——连续的汉字 / 假名 / 谚文切成重叠的
// 二元组,字母数字切成重叠的三元组——存成 text[] 建 GIN 索引。查询时词项取交集得到候选,
// 再按原文子串复核,所以语义就是"包含这个关键词",与文件名搜索一致。
// 代价:关键词至少要有两个汉字或三个字母,否则无从走索引。
package fulltext

import (
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

// Version 提取器版本。提取规则改了就加一,worker 会逐步把旧版本的结果重提一遍。
const Version = 1

const (
	// MaxContentBytes 每份内容最多存这么多字(UTF-8 字节,约 35 万汉字),超出截断
	MaxContentBytes = 1 << 20
	// MaxTerms 每份内容最多这么多个不同词项,超出不再收(内容照存,只是后半段搜不到)
	MaxTerms = 200_000
)

func isCJK(r rune) bool {
	return unicode.In(r, unicode.Han, unicode.Hiragana, unicode.Katakana, unicode.Hangul)
}

func isWord(r rune) bool {
	return (unicode.IsLetter(r) || unicode.IsDigit(r)) && !isCJK(r)
}

// Normalize 存储与查询共用的归一化:NFKC(全角转半角等)、去掉 NUL 与控制字符、
// 空白折成一个空格;两个 CJK 字之间的空白直接去掉——PDF 按行断开的"合\n同"要能被"合同"搜到。
// 不改大小写:摘要要按原样显示,大小写由查询时两边各自 lower 处理。
func Normalize(s string) string {
	s = norm.NFKC.String(s)
	var b strings.Builder
	b.Grow(len(s))
	prev := rune(-1)
	gap := false
	for _, r := range s {
		if unicode.IsSpace(r) {
			gap = prev != -1
			continue
		}
		if r == utf8.RuneError || unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			continue // NUL、响铃、零宽字符之类:直接丢,不算空白
		}
		if gap && !(isCJK(prev) && isCJK(r)) {
			b.WriteByte(' ')
		}
		gap = false
		b.WriteRune(r)
		prev = r
	}
	return b.String()
}

// Build 把分页的原文拼成要存的内容:每页归一化,页与页之间一个换行;
// starts[i] 是第 i+1 页在内容里的起点(按字符计,与 Postgres strpos 同口径,从 0 起)。
// 超过 maxBytes 截断,truncated 为真。
func Build(pages []string, maxBytes int) (content string, starts []int32, truncated bool) {
	var b strings.Builder
	runes := 0
	for i, p := range pages {
		p = Normalize(p)
		if i > 0 {
			if b.Len()+1 > maxBytes {
				truncated = true
				break
			}
			b.WriteByte('\n')
			runes++
		}
		starts = append(starts, int32(runes))
		if b.Len()+len(p) > maxBytes {
			p = truncateUTF8(p, maxBytes-b.Len())
			truncated = true
		}
		b.WriteString(p)
		runes += utf8.RuneCountInString(p)
		if truncated {
			break
		}
	}
	return b.String(), starts, truncated
}

// PageOf pos 是 strpos 返回的位置(从 1 起);返回所在页码(从 1 起)。starts 为空返回 0(不分页)。
func PageOf(starts []int32, pos int) int {
	if len(starts) == 0 || pos <= 0 {
		return 0
	}
	off := int32(pos - 1)
	i := sort.Search(len(starts), func(i int) bool { return starts[i] > off })
	return max(i, 1)
}

// Terms 内容的词项(去重)。超过 max 个后不再收,truncated 为真。
func Terms(content string, max int) (terms []string, truncated bool) {
	seen := make(map[string]struct{})
	terms = make([]string, 0, 256)
	full := false
	add := func(t string) {
		if full {
			return
		}
		if _, ok := seen[t]; ok {
			return
		}
		if len(terms) >= max {
			full = true
			return
		}
		seen[t] = struct{}{}
		terms = append(terms, t)
	}
	eachTerm(strings.ToLower(content), add)
	return terms, full
}

// QueryTerms 关键词的词项:与 Terms 同一规则,太短的片段(单个汉字、一两个字母)不产生词项。
// 返回空表示关键词太短,无法走索引。
func QueryTerms(q string) []string {
	var out []string
	seen := make(map[string]struct{})
	eachTerm(strings.ToLower(Normalize(q)), func(t string) {
		if _, ok := seen[t]; !ok {
			seen[t] = struct{}{}
			out = append(out, t)
		}
	})
	return out
}

// eachTerm CJK 连续段 ≥2 字切二元组,字母数字连续段 ≥3 字切三元组,其余字符是分隔。
func eachTerm(s string, emit func(string)) {
	runes := []rune(s)
	for i := 0; i < len(runes); {
		r := runes[i]
		switch {
		case isCJK(r):
			j := i
			for j < len(runes) && isCJK(runes[j]) {
				j++
			}
			for k := i; k+2 <= j; k++ {
				emit(string(runes[k : k+2]))
			}
			i = j
		case isWord(r):
			j := i
			for j < len(runes) && isWord(runes[j]) {
				j++
			}
			for k := i; k+3 <= j; k++ {
				emit(string(runes[k : k+3]))
			}
			i = j
		default:
			i++
		}
	}
}

// Range 摘要里的一处命中(按字符计)。
type Range struct{ Start, Length int }

// Highlights 在摘要里找出关键词的所有出现位置(不分大小写),给前端高亮。
func Highlights(snippet, q string) []Range {
	q = strings.ToLower(Normalize(q))
	if q == "" {
		return nil
	}
	hay := []rune(strings.ToLower(snippet))
	needle := []rune(q)
	var out []Range
	for i := 0; i+len(needle) <= len(hay); {
		if string(hay[i:i+len(needle)]) == q {
			out = append(out, Range{Start: i, Length: len(needle)})
			i += len(needle)
			continue
		}
		i++
	}
	return out
}

func truncateUTF8(s string, n int) string {
	if n <= 0 {
		return ""
	}
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}
