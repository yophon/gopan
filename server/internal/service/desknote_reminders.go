package service

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// DeskNoteReminders 便签提醒推到手机(doc/39 七)。worker 每分钟调一次 Tick:
//  1. 先把到点、这一次还没推过的提醒推出去;
//  2. 再扫这段时间改过的 .json 文件,是便签就把提醒记下来。
//
// 先推后扫:桌面端开着时,到点它会把条目标成「已提醒」(或把重复的顺延到下一次)再同步上来,
// 先扫的话就看不到这一次了,手机收不到。重复的提醒推完由服务器自己顺延,电脑关着也照推。
//
// 这几条 SQL 直接写在这里(没走 sqlc):都是这一个功能自己用的。
type DeskNoteReminders struct {
	pool    *pgxpool.Pool
	uploads *Uploads
	notify  *Notifier
	loc     *time.Location
	now     func() time.Time
	read    func(ctx context.Context, owner, node uuid.UUID) (string, error) // 读文件内容;测试里换掉,省得要对象存储
}

func NewDeskNoteReminders(pool *pgxpool.Pool, uploads *Uploads, notify *Notifier, loc *time.Location) *DeskNoteReminders {
	if loc == nil {
		loc = time.Local
	}
	d := &DeskNoteReminders{pool: pool, uploads: uploads, notify: notify, loc: loc, now: time.Now}
	d.read = func(ctx context.Context, owner, node uuid.UUID) (string, error) {
		tf, err := d.uploads.ReadTextFile(ctx, owner, node)
		if err != nil {
			return "", err
		}
		return tf.Content, nil
	}
	return d
}

// SetClock、SetReader 测试用。
func (d *DeskNoteReminders) SetClock(now func() time.Time) { d.now = now }
func (d *DeskNoteReminders) SetReader(read func(ctx context.Context, owner, node uuid.UUID) (string, error)) {
	d.read = read
}

const (
	desknoteScanBatch = 200
	desknoteMaxLate   = 24 * time.Hour // 晚了一天以上的不推了(第一次扫到很旧的便签)
)

// 便签里的一条提醒
type deskNoteRemind struct {
	Key    string // 条目文字
	At     time.Time
	Repeat string
}

type deskNoteFile struct {
	Title string `json:"title"`
	Items []struct {
		Text     *string `json:"text"`
		Done     bool    `json:"done"`
		Remind   string  `json:"remind"`
		Reminded bool    `json:"reminded"`
		Repeat   string  `json:"repeat"`
	} `json:"items"`
}

// parseDeskNoteReminders 认出便签(和网页、App 同一个规则:有字符串 title 和 items,每条有字符串 text),
// 取出还要提醒的条目:不重复的要没提醒过、没做完;重复的总是要(到点会重新打开)。
func parseDeskNoteReminders(content string, loc *time.Location) (title string, out []deskNoteRemind, ok bool) {
	var raw map[string]json.RawMessage
	if json.Unmarshal([]byte(content), &raw) != nil {
		return "", nil, false
	}
	if _, has := raw["title"]; !has {
		return "", nil, false
	}
	var f deskNoteFile
	if json.Unmarshal([]byte(content), &f) != nil || f.Items == nil {
		return "", nil, false
	}
	for _, it := range f.Items {
		if it.Text == nil {
			return "", nil, false
		}
	}
	seen := map[string]bool{}
	for _, it := range f.Items {
		if it.Remind == "" || seen[*it.Text] {
			continue
		}
		rep := it.Repeat
		if rep != "daily" && rep != "weekdays" && rep != "weekly" {
			rep = ""
		}
		if rep == "" && (it.Reminded || it.Done) {
			continue
		}
		s := strings.TrimSpace(it.Remind)
		at, err := time.ParseInLocation("2006-01-02 15:04", s[:min(16, len(s))], loc)
		if err != nil {
			continue
		}
		seen[*it.Text] = true
		out = append(out, deskNoteRemind{Key: *it.Text, At: at, Repeat: rep})
	}
	return f.Title, out, true
}

// nextRepeat 和桌面端的 NextRepeat 一样:t 之后、晚于 after 的下一次,时刻不变(按 loc 的日历算)。
func nextRepeat(t time.Time, repeat string, after time.Time, loc *time.Location) time.Time {
	t = t.In(loc)
	step := 1
	if repeat == "weekly" {
		step = 7
	}
	if repeat == "" {
		return t
	}
	if !t.After(after) {
		days := int(after.Sub(t).Hours()/24) / step * step
		t = t.AddDate(0, 0, days)
	}
	for i := 0; i < 16; i++ {
		if t.After(after) {
			if repeat != "weekdays" || (t.Weekday() >= time.Monday && t.Weekday() <= time.Friday) {
				return t
			}
		}
		t = t.AddDate(0, 0, step)
	}
	return t
}

// Tick 一轮:先推到点的,再扫改过的文件。
func (d *DeskNoteReminders) Tick(ctx context.Context) error {
	if err := d.FireDue(ctx); err != nil {
		return err
	}
	return d.Scan(ctx)
}

// FireDue 推到点、这一次还没推过的;重复的顺延到下一次。文件在回收站里的不推。
func (d *DeskNoteReminders) FireDue(ctx context.Context) error {
	now := d.now()
	rows, err := d.pool.Query(ctx, `
		SELECT r.node_id, r.item_key, r.owner_id, r.note_title, r.remind_at, r.repeat, n.parent_id
		FROM desknote_reminders r JOIN nodes n ON n.id = r.node_id AND n.deleted_at IS NULL
		WHERE r.remind_at <= $1 AND (r.fired_at IS NULL OR r.fired_at < r.remind_at)
		ORDER BY r.remind_at LIMIT 200`, now)
	if err != nil {
		return err
	}
	type due struct {
		node, owner uuid.UUID
		key, title  string
		at          time.Time
		repeat      string
		parent      *uuid.UUID
	}
	var list []due
	for rows.Next() {
		var x due
		if err := rows.Scan(&x.node, &x.key, &x.owner, &x.title, &x.at, &x.repeat, &x.parent); err != nil {
			rows.Close()
			return err
		}
		list = append(list, x)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, x := range list {
		next := x.at
		if x.repeat != "" {
			next = nextRepeat(x.at, x.repeat, now, d.loc)
		}
		if _, err := d.pool.Exec(ctx, `UPDATE desknote_reminders SET fired_at = $3, remind_at = $4 WHERE node_id = $1 AND item_key = $2`,
			x.node, x.key, x.at, next); err != nil {
			return err
		}
		if now.Sub(x.at) > desknoteMaxLate || d.notify == nil {
			continue
		}
		body := x.key
		if utf8.RuneCountInString(body) > 100 {
			body = string([]rune(body)[:100]) + "…"
		}
		t := &Target{Kind: TgNode, ID: x.node.String()}
		if x.parent != nil {
			t.ParentID = x.parent.String()
		}
		d.notify.Notify(ctx, x.owner, Event{Kind: EvNoteReminder, Level: LvInfo, Title: "提醒 · " + x.title, Body: body, Target: t})
	}
	return nil
}

// Scan 扫上次以来改过的 .json 文件,更新记下的提醒(重复的保留「推到哪一次了」)。
func (d *DeskNoteReminders) Scan(ctx context.Context) error {
	var since time.Time
	err := d.pool.QueryRow(ctx, `SELECT scanned_until FROM desknote_scan WHERE id = 1`).Scan(&since)
	first := err == pgx.ErrNoRows
	if first {
		since = time.Time{} // 第一次:所有 .json 都认一遍(每分钟 200 个);太旧的提醒不会补推(desknoteMaxLate)
	} else if err != nil {
		return err
	}
	rows, err := d.pool.Query(ctx, `
		SELECT id, owner_id, updated_at FROM nodes
		WHERE kind = 'file' AND deleted_at IS NULL AND name ILIKE '%.json' AND updated_at > $1
		ORDER BY updated_at LIMIT $2`, since, desknoteScanBatch)
	if err != nil {
		return err
	}
	type changed struct {
		id, owner uuid.UUID
		at        time.Time
	}
	var list []changed
	for rows.Next() {
		var c changed
		if err := rows.Scan(&c.id, &c.owner, &c.at); err != nil {
			rows.Close()
			return err
		}
		list = append(list, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	until := since
	for _, c := range list {
		if err := d.index(ctx, c.id, c.owner); err != nil {
			slog.Warn("desknote reminders: index", "node", c.id, "err", err)
		}
		if c.at.After(until) {
			until = c.at
		}
	}
	if !until.Equal(since) || first {
		_, err := d.pool.Exec(ctx, `INSERT INTO desknote_scan (id, scanned_until) VALUES (1, $1)
			ON CONFLICT (id) DO UPDATE SET scanned_until = EXCLUDED.scanned_until`, until)
		return err
	}
	return nil
}

// index 读一个文件:是便签就把它的提醒换成最新的;不是便签(或读不了)就清掉它名下的。
func (d *DeskNoteReminders) index(ctx context.Context, node, owner uuid.UUID) error {
	var title string
	var list []deskNoteRemind
	ok := false
	if content, err := d.read(ctx, owner, node); err == nil {
		title, list, ok = parseDeskNoteReminders(content, d.loc)
	}
	tx, err := d.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	keys := []string{}
	if ok {
		for _, r := range list {
			keys = append(keys, r.Key)
			// 新的一次（时间变了）就重新算没推过;同一次保留 fired_at,免得重复推
			if _, err := tx.Exec(ctx, `
				INSERT INTO desknote_reminders (node_id, item_key, owner_id, note_title, remind_at, repeat)
				VALUES ($1, $2, $3, $4, $5, $6)
				ON CONFLICT (node_id, item_key) DO UPDATE SET note_title = EXCLUDED.note_title, repeat = EXCLUDED.repeat,
					remind_at = CASE
						-- 服务器已经替它顺延过、桌面端还没跟上(文件里还是推过的那一次):留着服务器算的下一次
						WHEN desknote_reminders.fired_at IS NOT NULL AND EXCLUDED.remind_at <= desknote_reminders.fired_at
						     AND EXCLUDED.repeat <> '' THEN desknote_reminders.remind_at
						ELSE EXCLUDED.remind_at END`,
				node, r.Key, owner, title, r.At, r.Repeat); err != nil {
				return err
			}
		}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM desknote_reminders WHERE node_id = $1 AND NOT (item_key = ANY ($2))`, node, keys); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
