// Package lyrics 解析 LRC 歌词(doc/31 二):[mm:ss.xx] 时间标签(一行可以有几个)、[offset:±毫秒],
// 逐字时间 <mm:ss.xx> 去掉只留整行;[ar:] [ti:] 这类信息标签跳过。没有时间标签的当成纯文本歌词。
package lyrics

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
)

type Line struct {
	T    float64 `json:"t"` // 秒;纯文本歌词为 -1
	Text string  `json:"text"`
}

type Lyrics struct {
	Synced bool   `json:"synced"`
	Lines  []Line `json:"lines"`
}

var (
	timeTag   = regexp.MustCompile(`^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]`)
	infoTag   = regexp.MustCompile(`^\[([a-zA-Z#]+):(.*)\]\s*$`)
	wordStamp = regexp.MustCompile(`<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>`)
)

const maxLines = 2000

func Parse(text string) Lyrics {
	var synced []Line
	var plain []Line
	offset := 0.0
	for _, raw := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		l := strings.TrimSpace(strings.TrimPrefix(raw, "\ufeff"))
		var stamps []float64
		for {
			m := timeTag.FindStringSubmatch(l)
			if m == nil {
				break
			}
			stamps = append(stamps, seconds(m[1], m[2], m[3]))
			l = l[len(m[0]):]
		}
		if len(stamps) == 0 {
			if m := infoTag.FindStringSubmatch(l); m != nil {
				if strings.EqualFold(m[1], "offset") {
					if ms, err := strconv.Atoi(strings.TrimSpace(m[2])); err == nil {
						offset = float64(ms) / 1000
					}
				}
				continue
			}
			if l != "" && len(plain) < maxLines {
				plain = append(plain, Line{T: -1, Text: l})
			}
			continue
		}
		body := strings.TrimSpace(wordStamp.ReplaceAllString(l, ""))
		for _, t := range stamps {
			if len(synced) < maxLines {
				synced = append(synced, Line{T: t, Text: body})
			}
		}
	}
	if len(synced) == 0 {
		return Lyrics{Synced: false, Lines: plain}
	}
	// offset 为正 = 歌词提前出现
	for i := range synced {
		synced[i].T = max(0, round3(synced[i].T-offset))
	}
	sort.SliceStable(synced, func(i, j int) bool { return synced[i].T < synced[j].T })
	return Lyrics{Synced: true, Lines: synced}
}

func seconds(mm, ss, frac string) float64 {
	m, _ := strconv.Atoi(mm)
	s, _ := strconv.Atoi(ss)
	f := 0.0
	if frac != "" {
		n, _ := strconv.Atoi(frac)
		switch len(frac) {
		case 1:
			f = float64(n) / 10
		case 2:
			f = float64(n) / 100
		default:
			f = float64(n) / 1000
		}
	}
	return round3(float64(m*60+s) + f)
}

func round3(v float64) float64 {
	return float64(int64(v*1000+0.5)) / 1000
}
