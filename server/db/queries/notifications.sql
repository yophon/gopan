-- 通知(doc/22 三)。

-- name: InsertNotification :one
INSERT INTO notifications (owner_id, kind, level, title, body, target, group_key)
VALUES ($1, $2, $3, $4, $5, $6, $7)
RETURNING id;

-- name: MergeNotification :one
-- 同组 30 分钟内有未读的就合并到最新那条,没有行返回 = 该新建。
UPDATE notifications SET count = count + 1, level = @level, title = @title, body = @body, target = @target, updated_at = now()
WHERE id = (
    SELECT n.id FROM notifications n
    WHERE n.owner_id = @owner_id AND n.group_key = @group_key AND n.read_at IS NULL
      AND n.updated_at > now() - interval '30 minutes'
    ORDER BY n.id DESC
    LIMIT 1
)
RETURNING id;

-- name: RecentNotificationExists :one
-- 同组在 since 之后有过(不管读没读),用于"每天一次""7 天一次"这类去重。
SELECT EXISTS (
    SELECT 1 FROM notifications
    WHERE owner_id = $1 AND group_key = $2 AND created_at > $3
);

-- name: ListNotifications :many
SELECT * FROM notifications
WHERE owner_id = @owner_id
  AND (NOT @unread_only::boolean OR read_at IS NULL)
  AND (@before_id::bigint = 0 OR id < @before_id::bigint)
ORDER BY id DESC
LIMIT @lim;

-- name: CountUnreadNotifications :one
SELECT count(*) FROM notifications WHERE owner_id = $1 AND read_at IS NULL;

-- name: MarkNotificationsRead :execrows
UPDATE notifications SET read_at = now()
WHERE owner_id = $1 AND read_at IS NULL AND id = ANY(@ids::bigint[]);

-- name: MarkAllNotificationsRead :execrows
UPDATE notifications SET read_at = now() WHERE owner_id = $1 AND read_at IS NULL;

-- name: DeleteAllNotifications :execrows
DELETE FROM notifications WHERE owner_id = $1;

-- name: GetNotification :one
SELECT * FROM notifications WHERE id = $1;

-- name: CleanupNotifications :execrows
-- 保留 90 天,且每人最多 1000 条
DELETE FROM notifications
WHERE created_at < now() - interval '90 days'
   OR id IN (
       SELECT x.id FROM (
           SELECT id, row_number() OVER (PARTITION BY owner_id ORDER BY id DESC) AS rn FROM notifications
       ) x WHERE x.rn > 1000
   );

-- name: ListAdminIDs :many
SELECT id FROM users WHERE is_admin AND disabled_at IS NULL ORDER BY created_at;

-- ---------- 渠道 ----------

-- name: ListNotifyChannels :many
SELECT * FROM notify_channels WHERE owner_id = $1 ORDER BY created_at, id;

-- name: CountNotifyChannels :one
SELECT count(*) FROM notify_channels WHERE owner_id = $1;

-- name: GetNotifyChannel :one
SELECT * FROM notify_channels WHERE id = $1 AND owner_id = $2;

-- name: GetNotifyChannelByID :one
SELECT * FROM notify_channels WHERE id = $1;

-- name: CreateNotifyChannel :one
INSERT INTO notify_channels (id, owner_id, kind, name, url, topic, secret, events, enabled)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING *;

-- name: UpdateNotifyChannel :one
UPDATE notify_channels SET kind = $3, name = $4, url = $5, topic = $6, secret = $7, events = $8, enabled = $9
WHERE id = $1 AND owner_id = $2
RETURNING *;

-- name: DeleteNotifyChannel :execrows
DELETE FROM notify_channels WHERE id = $1 AND owner_id = $2;

-- name: ListChannelsForEvent :many
SELECT * FROM notify_channels WHERE owner_id = $1 AND enabled AND @kind::text = ANY(events);

-- name: SetChannelOK :exec
UPDATE notify_channels SET last_ok_at = now() WHERE id = $1;

-- name: SetChannelError :exec
UPDATE notify_channels SET last_error = $2, last_error_at = now() WHERE id = $1;

-- ---------- 投递 ----------

-- name: CountChannelDeliveriesSince :one
SELECT count(*) FROM notify_deliveries WHERE channel_id = $1 AND created_at > $2;

-- name: InsertDelivery :exec
INSERT INTO notify_deliveries (notification_id, channel_id) VALUES ($1, $2);

-- name: ClaimDeliveries :many
-- 领取到期的投递:attempts 加一、next_at 推后 2 分钟当租约(进程中途崩了,租约过期后别的循环接着发)。
UPDATE notify_deliveries SET attempts = attempts + 1, next_at = now() + interval '2 minutes'
WHERE id IN (
    SELECT d.id FROM notify_deliveries d
    WHERE d.status = 'pending' AND d.next_at <= now()
    ORDER BY d.next_at
    FOR UPDATE SKIP LOCKED
    LIMIT @lim
)
RETURNING *;

-- name: MarkDeliverySent :exec
UPDATE notify_deliveries SET status = 'sent', sent_at = now(), error = NULL WHERE id = $1;

-- name: RetryDelivery :exec
UPDATE notify_deliveries SET next_at = $2, error = $3 WHERE id = $1;

-- name: FailDelivery :exec
UPDATE notify_deliveries SET status = 'failed', error = $2 WHERE id = $1;

-- name: CleanupDeliveries :execrows
DELETE FROM notify_deliveries WHERE created_at < now() - interval '30 days';

-- ---------- 全站状态 ----------

-- name: SetInstanceState :exec
INSERT INTO instance_state (key, value, updated_at) VALUES ($1, $2, now())
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

-- name: GetInstanceState :one
SELECT * FROM instance_state WHERE key = $1;
