-- +goose Up
-- 通知(doc/22 三)。
--
-- notifications:站内消息。带 group_key 的事件(如某条收集链接收到文件)30 分钟内有未读的同组消息就合并:
-- count 加一、更新正文与 updated_at,不另起一条,也不再推送。target 是点开后跳到哪(见 GraphQL NotificationTarget)。
CREATE TABLE notifications (
    id         bigserial PRIMARY KEY,
    owner_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind       text NOT NULL,
    level      text NOT NULL CHECK (level IN ('info', 'warn', 'alert')),
    title      text NOT NULL,
    body       text NOT NULL DEFAULT '',
    target     jsonb,
    group_key  text,
    count      int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz
);
CREATE INDEX idx_notifications_owner ON notifications (owner_id, id DESC);
CREATE INDEX idx_notifications_unread ON notifications (owner_id) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_group ON notifications (owner_id, group_key, id DESC)
    WHERE read_at IS NULL AND group_key IS NOT NULL;
CREATE INDEX idx_notifications_created ON notifications (created_at);

-- 推送渠道。secret 是 webhook 签名密钥 / ntfy 访问令牌 / bark device key / telegram bot token:
-- 发送时要用原值,只能明文存;GraphQL 只写不读(doc/06)。
CREATE TABLE notify_channels (
    id            uuid PRIMARY KEY,
    owner_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind          text NOT NULL CHECK (kind IN ('webhook', 'ntfy', 'bark', 'telegram')),
    name          text NOT NULL,
    url           text NOT NULL DEFAULT '',
    topic         text NOT NULL DEFAULT '',
    secret        text NOT NULL DEFAULT '',
    events        text[] NOT NULL DEFAULT '{}',
    enabled       boolean NOT NULL DEFAULT true,
    last_ok_at    timestamptz,
    last_error    text,
    last_error_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notify_channels_owner ON notify_channels (owner_id, created_at);

-- 一条通知 × 一个渠道 = 一次投递。pending 的按 next_at 领取(领取时把 next_at 推后当租约),失败按 30 秒 / 2 分钟 / 10 分钟重试。
CREATE TABLE notify_deliveries (
    id              bigserial PRIMARY KEY,
    notification_id bigint NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    channel_id      uuid NOT NULL REFERENCES notify_channels(id) ON DELETE CASCADE,
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    attempts        int NOT NULL DEFAULT 0,
    next_at         timestamptz NOT NULL DEFAULT now(),
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);
CREATE INDEX idx_notify_deliveries_due ON notify_deliveries (next_at) WHERE status = 'pending';
CREATE INDEX idx_notify_deliveries_channel ON notify_deliveries (channel_id, created_at DESC);

-- 全站状态(备份心跳等),key 自由取。
CREATE TABLE instance_state (
    key        text PRIMARY KEY,
    value      jsonb NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE instance_state;
DROP TABLE notify_deliveries;
DROP TABLE notify_channels;
DROP TABLE notifications;
