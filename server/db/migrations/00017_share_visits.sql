-- +goose Up
-- 分享访问事件。
--
-- 刻意做成"事件日志"而不是给 shares 加计数列:
--   - GetShareByToken 用的是 SELECT s.*,加列会波及一片查询
--   - 只存计数就再也答不上"谁什么时候看的",而明细本来就顺带记下来了
--
-- ip / user_agent 允许 NULL:访客可能不带 UA,IP 也可能取不到(可信代理没配对),
-- 统计不该因此写失败。
--
-- 注意 ON DELETE CASCADE 在本项目几乎不会触发(shares 只吊销不删行),
-- 所以增长控制靠 worker 里按 created_at 清理,不能指望级联。
CREATE TABLE share_visits (
    id         bigserial PRIMARY KEY,
    share_id   uuid NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
    kind       text NOT NULL CHECK (kind IN ('verify', 'download', 'pack')),
    ip         text,
    user_agent text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_share_visits_share ON share_visits (share_id, id DESC);
CREATE INDEX idx_share_visits_gc ON share_visits (created_at);

-- +goose Down
DROP TABLE share_visits;
