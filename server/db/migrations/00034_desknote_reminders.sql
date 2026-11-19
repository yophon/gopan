-- +goose Up
-- DeskNote 便签的提醒(doc/39 七):服务器每分钟扫一遍改过的便签文件,把提醒记在这里,到点推到手机。
-- 便签里的时间是本地时间(没有时区),按服务器的 GOPAN_TZ 解释。
CREATE TABLE desknote_reminders (
    node_id    uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    item_key   text NOT NULL,              -- 条目文字:桌面端合并时也按文字认同一条
    owner_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note_title text NOT NULL,
    remind_at  timestamptz NOT NULL,       -- 下一次
    repeat     text NOT NULL DEFAULT '',   -- '' / daily / weekdays / weekly
    fired_at   timestamptz,                -- 推过的那一次的 remind_at;< remind_at 表示这一次还没推
    PRIMARY KEY (node_id, item_key)
);
CREATE INDEX idx_desknote_reminders_due ON desknote_reminders (remind_at);

-- 扫到哪儿了(只有一行)
CREATE TABLE desknote_scan (
    id            int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    scanned_until timestamptz NOT NULL
);

-- 已有的推送渠道也收这类事件(推送设置里可以去掉)
UPDATE notify_channels SET events = array_append(events, 'note_reminder')
WHERE NOT ('note_reminder' = ANY (events));

-- +goose Down
UPDATE notify_channels SET events = array_remove(events, 'note_reminder');
DROP TABLE desknote_scan;
DROP TABLE desknote_reminders;
