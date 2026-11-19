package service

import (
	"testing"
	"time"
)

func TestParseDeskNoteReminders(t *testing.T) {
	loc := time.FixedZone("CST", 8*3600)
	title, list, ok := parseDeskNoteReminders(`{"title":"T","items":[
		{"text":"a","done":false,"remind":"2026-11-19 09:00","reminded":false},
		{"text":"b","done":false,"remind":"2026-11-19 09:00","reminded":true},
		{"text":"c","done":true,"remind":"2026-11-19 09:00"},
		{"text":"d","done":true,"remind":"2026-11-19 10:00","reminded":true,"repeat":"weekly"},
		{"text":"e","done":false,"remind":"坏的"},
		{"text":"f","done":false}
	]}`, loc)
	if !ok || title != "T" || len(list) != 2 || list[0].Key != "a" || list[1].Key != "d" || list[1].Repeat != "weekly" {
		t.Fatalf("取提醒:%v %q %+v", ok, title, list)
	}
	if !list[0].At.Equal(time.Date(2026, 11, 19, 9, 0, 0, 0, loc)) {
		t.Fatalf("按服务器时区解释:%v", list[0].At)
	}
	for _, s := range []string{`{"name":"x"}`, `{"title":"x","items":[{"t":1}]}`, `[1]`, `oops`} {
		if _, _, ok := parseDeskNoteReminders(s, loc); ok {
			t.Fatalf("不是便签:%s", s)
		}
	}
}

func TestNextRepeat(t *testing.T) {
	loc := time.FixedZone("CST", 8*3600)
	thu := time.Date(2026, 11, 19, 9, 0, 0, 0, loc) // 周四
	after := thu.Add(30 * time.Second)
	cases := []struct {
		rep   string
		after time.Time
		want  time.Time
	}{
		{"daily", after, time.Date(2026, 11, 20, 9, 0, 0, 0, loc)},
		{"weekly", after, time.Date(2026, 11, 26, 9, 0, 0, 0, loc)},
		{"weekdays", time.Date(2026, 11, 20, 9, 0, 30, 0, loc), time.Date(2026, 11, 23, 9, 0, 0, 0, loc)}, // 周五之后是周一
		{"daily", time.Date(2027, 3, 3, 12, 0, 0, 0, loc), time.Date(2027, 3, 4, 9, 0, 0, 0, loc)},        // 关机很久
	}
	for _, c := range cases {
		start := thu
		if c.rep == "weekdays" {
			start = time.Date(2026, 11, 20, 9, 0, 0, 0, loc)
		}
		if got := nextRepeat(start, c.rep, c.after, loc); !got.Equal(c.want) {
			t.Fatalf("%s 之后 %v:得到 %v,应为 %v", c.rep, c.after, got, c.want)
		}
	}
	w := nextRepeat(thu, "weekly", time.Date(2027, 3, 3, 12, 0, 0, 0, loc), loc)
	if w.Weekday() != time.Thursday || w.Hour() != 9 {
		t.Fatalf("每周:还是周四 9 点:%v", w)
	}
}
