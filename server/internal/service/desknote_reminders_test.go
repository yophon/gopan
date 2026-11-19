package service_test

// 便签提醒推到手机(doc/39 七)的集成测试:要 TEST_DB_URL(不用对象存储,文件内容由测试给)。

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/service"
)

type deskItem struct {
	Text     string `json:"text"`
	Done     bool   `json:"done"`
	Remind   string `json:"remind,omitempty"`
	Reminded bool   `json:"reminded,omitempty"`
	Repeat   string `json:"repeat,omitempty"`
}

func deskJSON(title string, items ...deskItem) string {
	b, _ := json.Marshal(map[string]any{"title": title, "items": items, "style": map[string]any{"opacity": 0.85}})
	return string(b)
}

func TestDeskNoteReminders(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	res, err := newAuth(pool).Register(ctx, "dn_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	notifier := service.NewNotifier(pool, fetcher.Guard{}, "http://gopan.test")
	loc := time.FixedZone("CST", 8*3600)
	d := service.NewDeskNoteReminders(pool, nil, notifier, loc)
	now := time.Date(2026, 11, 19, 8, 59, 0, 0, loc)
	d.SetClock(func() time.Time { return now })
	content := map[uuid.UUID]string{}
	d.SetReader(func(_ context.Context, _, node uuid.UUID) (string, error) { return content[node], nil })

	note := uuid.New()
	other := uuid.New()
	put := func(id uuid.UUID, name, text string) {
		t.Helper()
		content[id] = text
		if _, err := pool.Exec(ctx, `INSERT INTO nodes (id, owner_id, name, kind, updated_at) VALUES ($1, $2, $3, 'file', $4)
			ON CONFLICT (id) DO UPDATE SET updated_at = EXCLUDED.updated_at`, id, owner, name, now); err != nil {
			t.Fatal(err)
		}
	}
	tick := func(at time.Time) {
		t.Helper()
		now = at
		if err := d.Tick(ctx); err != nil {
			t.Fatal(err)
		}
	}
	list := func() []string {
		t.Helper()
		ns, _, err := notifier.List(ctx, owner, nil, false)
		if err != nil {
			t.Fatal(err)
		}
		var out []string
		for _, n := range ns {
			out = append(out, n.Title+"|"+n.Body)
		}
		return out
	}
	at := func(h, m int) time.Time { return time.Date(2026, 11, 19, h, m, 30, 0, loc) }

	put(note, "20261119-080000-abcdef.json", deskJSON("今天",
		deskItem{Text: "开会", Remind: "2026-11-19 09:00"},
		deskItem{Text: "每天站会", Remind: "2026-11-19 09:30", Repeat: "daily"},
		deskItem{Text: "已经做完", Done: true, Remind: "2026-11-19 09:00"},
		deskItem{Text: "没提醒"}))
	put(other, "package.json", `{"name":"x","version":"1.0.0"}`)
	tick(time.Date(2026, 11, 19, 8, 59, 30, 0, loc))
	if got := list(); len(got) != 0 {
		t.Fatalf("没到点不该推:%v", got)
	}

	tick(at(9, 0))
	if got := list(); len(got) != 1 || got[0] != "提醒 · 今天|开会" {
		t.Fatalf("09:00 推「开会」(做完的不推):%v", got)
	}
	tick(at(9, 1))
	if got := list(); len(got) != 1 {
		t.Fatalf("同一次不重复推:%v", got)
	}

	// 桌面端到点把「开会」标成已提醒并同步上来:不再推,记录清掉
	now = at(9, 2)
	put(note, "20261119-080000-abcdef.json", deskJSON("今天",
		deskItem{Text: "开会", Remind: "2026-11-19 09:00", Reminded: true},
		deskItem{Text: "每天站会", Remind: "2026-11-19 09:30", Repeat: "daily"}))
	tick(at(9, 2))
	if got := list(); len(got) != 1 {
		t.Fatalf("已提醒的不再推:%v", got)
	}

	// 桌面端开着:到点它先把重复的顺延并同步上来,服务器这一轮先推再扫,手机照样收到
	tick(at(9, 29))
	now = at(9, 30)
	put(note, "20261119-080000-abcdef.json", deskJSON("今天",
		deskItem{Text: "开会", Remind: "2026-11-19 09:00", Reminded: true},
		deskItem{Text: "每天站会", Remind: "2026-11-20 09:30", Repeat: "daily"}))
	tick(at(9, 31))
	if got := list(); len(got) != 2 || got[0] != "提醒 · 今天|每天站会" {
		t.Fatalf("桌面端已顺延，手机也要收到这一次:%v", got)
	}

	// 电脑关着:文件不动,第二天照样推,推完服务器自己顺延
	tick(time.Date(2026, 11, 20, 9, 31, 0, 0, loc))
	tick(time.Date(2026, 11, 20, 9, 40, 0, 0, loc))
	if got := list(); len(got) != 3 {
		t.Fatalf("电脑关着第二天也推一次:%v", got)
	}
	var next time.Time
	if err := pool.QueryRow(ctx, `SELECT remind_at FROM desknote_reminders WHERE node_id = $1 AND item_key = '每天站会'`, note).Scan(&next); err != nil {
		t.Fatal(err)
	}
	if !next.Equal(time.Date(2026, 11, 21, 9, 30, 0, 0, loc)) {
		t.Fatalf("顺延到 11-21 09:30:%v", next.In(loc))
	}

	// 便签进了回收站:不推
	if _, err := pool.Exec(ctx, `UPDATE nodes SET deleted_at = now() WHERE id = $1`, note); err != nil {
		t.Fatal(err)
	}
	tick(time.Date(2026, 11, 21, 9, 31, 0, 0, loc))
	if got := list(); len(got) != 3 {
		t.Fatalf("回收站里的便签不推:%v", got)
	}
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM desknote_reminders WHERE node_id = $1`, other).Scan(&n); err != nil || n != 0 {
		t.Fatalf("不是便签的 json 不记:%d %v", n, err)
	}
	if !strings.HasPrefix(list()[0], "提醒 · ") {
		t.Fatal("标题")
	}
}
