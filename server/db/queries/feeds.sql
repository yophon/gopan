-- 订阅(doc/23 四)。

-- name: CreateFeed :one
INSERT INTO feeds (id, owner_id, kind, url, title, site_url, folder_id, mode, include_words, exclude_words,
                   asset_pattern, prerelease, keep_last, interval_minutes)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
RETURNING *;

-- name: UpdateFeed :one
UPDATE feeds SET title = $3, folder_id = $4, mode = $5, include_words = $6, exclude_words = $7,
    asset_pattern = $8, prerelease = $9, keep_last = $10, interval_minutes = $11, enabled = $12,
    next_check_at = CASE WHEN $12::boolean AND NOT enabled THEN now() ELSE next_check_at END,
    fail_count = CASE WHEN $12::boolean AND NOT enabled THEN 0 ELSE fail_count END
WHERE id = $1 AND owner_id = $2
RETURNING *;

-- name: DeleteFeed :execrows
DELETE FROM feeds WHERE id = $1 AND owner_id = $2;

-- name: GetFeed :one
SELECT * FROM feeds WHERE id = $1 AND owner_id = $2;

-- name: CountFeeds :one
SELECT count(*) FROM feeds WHERE owner_id = $1;

-- name: ListFeeds :many
-- 带每个订阅的条目数、新条目数(状态 new)、最近一条的时间
SELECT f.*,
       (SELECT count(*) FROM feed_items i WHERE i.feed_id = f.id)::bigint AS item_count,
       (SELECT count(*) FROM feed_items i WHERE i.feed_id = f.id AND i.state = 'new')::bigint AS new_count,
       (SELECT max(COALESCE(i.published_at, i.created_at)) FROM feed_items i WHERE i.feed_id = f.id)::timestamptz AS latest_at
FROM feeds f
WHERE f.owner_id = $1
ORDER BY f.created_at DESC, f.id DESC;

-- name: ClaimDueFeeds :many
-- 领到期的订阅:先把下次检查推后 10 分钟当租约,检查完再写真正的时间
UPDATE feeds SET next_check_at = now() + interval '10 minutes'
WHERE id IN (
    SELECT f.id FROM feeds f
    WHERE f.enabled AND f.next_check_at <= now()
    ORDER BY f.next_check_at
    FOR UPDATE SKIP LOCKED
    LIMIT $1
)
RETURNING *;

-- name: FeedCheckOK :exec
UPDATE feeds SET etag = @etag, last_modified = @last_modified,
    title = CASE WHEN title = '' THEN @title::text ELSE title END,
    site_url = CASE WHEN @site_url::text <> '' THEN @site_url::text ELSE site_url END,
    last_checked_at = now(), last_ok_at = now(), last_error = NULL, fail_count = 0,
    next_check_at = now() + make_interval(mins => interval_minutes)
WHERE id = @id;

-- name: FeedCheckFailed :one
-- 失败一次检查间隔翻一倍,最长 24 小时
UPDATE feeds SET last_checked_at = now(), last_error = $2, fail_count = fail_count + 1,
    next_check_at = now() + least(make_interval(mins => interval_minutes) * power(2, fail_count)::int, interval '24 hours')
WHERE id = $1
RETURNING fail_count;

-- name: DisableFeed :exec
UPDATE feeds SET enabled = false, last_error = $2, last_checked_at = now() WHERE id = $1;

-- name: InsertFeedItem :one
-- 已见过的(同 guid)不再插入:没有行返回
INSERT INTO feed_items (id, feed_id, guid, title, url, enclosure_url, enclosure_type, enclosure_size, published_at, state)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
ON CONFLICT (feed_id, guid) DO NOTHING
RETURNING *;

-- name: ListFeedItems :many
SELECT * FROM feed_items
WHERE feed_id = @feed_id
  AND (@before_id::uuid = '00000000-0000-0000-0000-000000000000'::uuid
       OR (created_at, id) < (@before_at::timestamptz, @before_id::uuid))
ORDER BY created_at DESC, id DESC
LIMIT @lim;

-- name: GetFeedItem :one
SELECT i.* FROM feed_items i JOIN feeds f ON f.id = i.feed_id
WHERE i.id = $1 AND f.owner_id = $2;

-- name: ListPendingFeedItems :many
-- 等着建任务的(状态 queued、还没有任务),旧的先来
SELECT * FROM feed_items
WHERE feed_id = $1 AND state = 'queued' AND job_id IS NULL
ORDER BY COALESCE(published_at, created_at), id
LIMIT 20;

-- name: ListRunningFeedItems :many
SELECT * FROM feed_items WHERE feed_id = $1 AND state = 'queued' AND job_id IS NOT NULL;

-- name: SetFeedItemState :exec
UPDATE feed_items SET state = $2, job_id = $3, node_id = $4, error = $5 WHERE id = $1;

-- name: FeedItemByJob :one
SELECT * FROM feed_items WHERE job_id = $1;

-- name: ListPrunableFeedItems :many
-- 只留最近 N 条:按发布时间从新到旧数,第 N 条之后、还有文件的
SELECT * FROM feed_items
WHERE feed_id = $1 AND state = 'done' AND node_id IS NOT NULL
ORDER BY COALESCE(published_at, created_at) DESC, id DESC
OFFSET $2;

-- name: ClearFeedItemNode :exec
UPDATE feed_items SET node_id = NULL WHERE id = $1;

-- name: TrimFeedItems :exec
-- 每个订阅留最近 300 个条目(还在等任务的不删)
DELETE FROM feed_items d
WHERE d.feed_id = @feed_id::uuid AND d.state <> 'queued' AND d.id NOT IN (
    SELECT i.id FROM feed_items i WHERE i.feed_id = @feed_id::uuid ORDER BY i.created_at DESC, i.id DESC LIMIT 300
);

-- name: GetFeedByID :one
SELECT * FROM feeds WHERE id = $1;

-- name: SetFeedNextCheck :exec
UPDATE feeds SET next_check_at = $2 WHERE id = $1;
