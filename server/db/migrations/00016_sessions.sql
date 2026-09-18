-- +goose Up
-- 登录会话(一个 refresh family 一行)。
--
-- 为什么另建表而不是给 refresh_tokens 加列:那张表是**旋转式**的,每次刷新都插
-- 新行(access 15 分钟过期,一个活跃设备一天能产生近百行)。把 UA/IP/last_seen
-- 加在那里,行数会与请求数成正比,列个会话列表还得 DISTINCT ON 扫一大堆。
-- 这里一行 = 一个设备会话,行数与设备数成正比。
CREATE TABLE sessions (
    family_id    uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_agent   text NOT NULL DEFAULT '',
    ip           text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz
);

CREATE INDEX idx_sessions_user ON sessions (user_id, last_seen_at DESC);
-- 清理用:长时间没活动的整行删掉
CREATE INDEX idx_sessions_gc ON sessions (last_seen_at);

-- +goose Down
DROP TABLE sessions;
