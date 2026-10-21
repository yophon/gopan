-- 直链与图床(doc/22 四)。

-- name: CreateLink :one
INSERT INTO links (id, owner_id, node_id, token, source, created_ip, created_ua, api_key_id, has_gps)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING *;

-- name: GetActiveLinkByNode :one
SELECT * FROM links WHERE node_id = $1 AND owner_id = $2 AND revoked_at IS NULL;

-- name: GetLinkRow :one
SELECT sqlc.embed(l), n.name AS node_name, n.deleted_at AS node_deleted_at, b.mime AS blob_mime, b.size AS blob_size,
    u.disabled_at AS owner_disabled_at
FROM links l
JOIN nodes n ON n.id = l.node_id
LEFT JOIN blobs b ON b.id = n.blob_id
JOIN users u ON u.id = l.owner_id
WHERE l.id = $1 AND l.revoked_at IS NULL;

-- name: ResolveLink :one
-- /x/{token}:一次查出直链、文件、内容与属主状态。
SELECT sqlc.embed(l), n.name AS node_name, n.deleted_at AS node_deleted_at, n.updated_at AS node_updated_at,
    b.sha256 AS blob_sha256, b.size AS blob_size, b.mime AS blob_mime,
    u.disabled_at AS owner_disabled_at,
    p.referer_hosts AS pref_referer_hosts, p.allow_no_referer AS pref_allow_no_referer
FROM links l
JOIN nodes n ON n.id = l.node_id
LEFT JOIN blobs b ON b.id = n.blob_id
JOIN users u ON u.id = l.owner_id
LEFT JOIN link_prefs p ON p.owner_id = l.owner_id
WHERE l.token = $1 AND l.revoked_at IS NULL;

-- name: ListLinks :many
-- 新的在前;游标是上一页最后一条的 (created_at, id)。
SELECT sqlc.embed(l), n.name AS node_name, n.deleted_at AS node_deleted_at, b.mime AS blob_mime, b.size AS blob_size,
    u.disabled_at AS owner_disabled_at
FROM links l
JOIN nodes n ON n.id = l.node_id
LEFT JOIN blobs b ON b.id = n.blob_id
JOIN users u ON u.id = l.owner_id
WHERE l.owner_id = @owner_id AND l.revoked_at IS NULL
  AND (@before_id::uuid = '00000000-0000-0000-0000-000000000000'::uuid
       OR (l.created_at, l.id) < (@before_at::timestamptz, @before_id::uuid))
ORDER BY l.created_at DESC, l.id DESC
LIMIT @lim;

-- name: CountLinks :one
SELECT count(*) FROM links WHERE owner_id = $1 AND revoked_at IS NULL;

-- name: LinkUsage :many
-- 今天与近 30 天的计数(today、since 由调用方按 GOPAN_TZ 算好)。
SELECT link_id,
    COALESCE(sum(hits) FILTER (WHERE hour >= @today::timestamptz), 0)::bigint AS today_hits,
    COALESCE(sum(bytes) FILTER (WHERE hour >= @today::timestamptz), 0)::bigint AS today_bytes,
    COALESCE(sum(blocked) FILTER (WHERE hour >= @today::timestamptz), 0)::bigint AS today_blocked,
    COALESCE(sum(hits), 0)::bigint AS hits,
    COALESCE(sum(bytes), 0)::bigint AS bytes,
    COALESCE(sum(blocked), 0)::bigint AS blocked
FROM link_stats
WHERE link_id = ANY(@ids::uuid[]) AND hour >= @since::timestamptz
GROUP BY link_id;

-- name: UpdateLinkSettings :one
UPDATE links SET referer_hosts = $3, allow_no_referer = $4, daily_bytes_cap = $5, expires_at = $6, auto_pause = $7
WHERE id = $1 AND owner_id = $2 AND revoked_at IS NULL
RETURNING *;

-- name: SetLinkPaused :one
UPDATE links SET paused_at = CASE WHEN @paused::boolean THEN now() END,
    pause_reason = CASE WHEN @paused::boolean THEN 'manual' END
WHERE id = @id AND owner_id = @owner_id AND revoked_at IS NULL
RETURNING *;

-- name: AdminSetLinkPaused :execrows
UPDATE links SET paused_at = CASE WHEN @paused::boolean THEN now() END,
    pause_reason = CASE WHEN @paused::boolean THEN 'manual' END
WHERE id = @id AND revoked_at IS NULL;

-- name: AutoPauseLink :execrows
-- 系统暂停(超日上限、异常):已经暂停的不动。
UPDATE links SET paused_at = now(), pause_reason = $2 WHERE id = $1 AND revoked_at IS NULL AND paused_at IS NULL;

-- name: ResumeDailyCapLinks :many
-- 超日上限暂停的,过了当天就恢复。
UPDATE links SET paused_at = NULL, pause_reason = NULL
WHERE pause_reason = 'daily_cap' AND paused_at < @day_start::timestamptz AND revoked_at IS NULL
RETURNING token;

-- name: RevokeLink :one
UPDATE links SET revoked_at = now() WHERE id = $1 AND owner_id = $2 AND revoked_at IS NULL RETURNING token;

-- name: AdminRevokeLink :one
UPDATE links SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL RETURNING token;

-- name: AddLinkTotals :exec
UPDATE links SET hits_total = hits_total + @hits, bytes_total = bytes_total + @bytes,
    last_hit_at = GREATEST(COALESCE(last_hit_at, @last_hit_at::timestamptz), @last_hit_at::timestamptz)
WHERE id = @id;

-- ---------- 默认规则 ----------

-- name: GetLinkPrefs :one
SELECT * FROM link_prefs WHERE owner_id = $1;

-- name: UpsertLinkPrefs :one
INSERT INTO link_prefs (owner_id, referer_hosts, allow_no_referer, image_folder, strip_gps, updated_at)
VALUES ($1, $2, $3, $4, $5, now())
ON CONFLICT (owner_id) DO UPDATE SET referer_hosts = EXCLUDED.referer_hosts, allow_no_referer = EXCLUDED.allow_no_referer,
    image_folder = EXCLUDED.image_folder, strip_gps = EXCLUDED.strip_gps, updated_at = now()
RETURNING *;

-- ---------- 计数落库(每 30 秒一批) ----------

-- name: UpsertLinkStat :exec
INSERT INTO link_stats (link_id, hour, hits, bytes, blocked, ips) VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (link_id, hour) DO UPDATE SET hits = link_stats.hits + EXCLUDED.hits, bytes = link_stats.bytes + EXCLUDED.bytes,
    blocked = link_stats.blocked + EXCLUDED.blocked, ips = GREATEST(link_stats.ips, EXCLUDED.ips);

-- name: UpsertLinkReferer :exec
INSERT INTO link_referers (link_id, day, host, hits, bytes) VALUES ($1, $2, $3, $4, $5)
ON CONFLICT (link_id, day, host) DO UPDATE SET hits = link_referers.hits + EXCLUDED.hits, bytes = link_referers.bytes + EXCLUDED.bytes;

-- name: ListRefererHostsForDay :many
SELECT host FROM link_referers WHERE link_id = $1 AND day = $2;

-- name: InsertLinkHits :copyfrom
INSERT INTO link_hits (link_id, at, ip, user_agent, referer, status, bytes) VALUES ($1, $2, $3, $4, $5, $6, $7);

-- ---------- 统计读取 ----------

-- name: ListLinkStats :many
SELECT * FROM link_stats WHERE link_id = $1 AND hour >= $2 ORDER BY hour;

-- name: ListLinkReferers :many
SELECT host, sum(hits)::bigint AS hits, sum(bytes)::bigint AS bytes
FROM link_referers WHERE link_id = $1 AND day >= $2
GROUP BY host ORDER BY 2 DESC, host LIMIT 50;

-- name: ListLinkHits :many
SELECT * FROM link_hits
WHERE link_id = @link_id AND (@before_id::bigint = 0 OR id < @before_id::bigint)
ORDER BY id DESC LIMIT @lim;

-- name: LinkBytesSince :one
SELECT COALESCE(sum(bytes), 0)::bigint FROM link_stats WHERE link_id = $1 AND hour >= $2;

-- name: SiteLinkUsageSince :one
SELECT COALESCE(sum(hits), 0)::bigint AS hits, COALESCE(sum(bytes), 0)::bigint AS bytes FROM link_stats WHERE hour >= $1;

-- name: AdminTopLinks :many
SELECT l.id, l.token, l.paused_at, l.expires_at, n.name AS node_name, n.deleted_at AS node_deleted_at,
    u.username, u.disabled_at AS owner_disabled_at, s.hits, s.bytes
FROM (
    SELECT link_id, sum(hits)::bigint AS hits, sum(bytes)::bigint AS bytes
    FROM link_stats WHERE hour >= @since::timestamptz
    GROUP BY link_id ORDER BY 3 DESC LIMIT @lim
) s
JOIN links l ON l.id = s.link_id
JOIN nodes n ON n.id = l.node_id
JOIN users u ON u.id = l.owner_id
WHERE l.revoked_at IS NULL
ORDER BY s.bytes DESC;

-- ---------- 异常检测 ----------

-- name: LinkRecentHours :many
-- 当前小时与上一小时的次数,调用方折算成「最近 60 分钟」。
SELECT link_id, hour, hits FROM link_stats WHERE hour >= $1;

-- name: LinkHitsBetween :many
SELECT link_id, COALESCE(sum(hits), 0)::bigint AS hits
FROM link_stats WHERE link_id = ANY(@ids::uuid[]) AND hour >= @since::timestamptz AND hour < @until::timestamptz
GROUP BY link_id;

-- name: LinkTopIPSince :one
SELECT ip, count(*) AS hits FROM link_hits WHERE link_id = $1 AND at >= $2 GROUP BY ip ORDER BY 2 DESC LIMIT 1;

-- name: LinkTopRefererForDay :one
SELECT host, hits FROM link_referers WHERE link_id = $1 AND day = $2 AND host NOT IN ('', '*') ORDER BY hits DESC LIMIT 1;

-- name: GetLinkOwnerAndName :one
SELECT l.owner_id, l.auto_pause, l.token, n.name AS node_name
FROM links l JOIN nodes n ON n.id = l.node_id WHERE l.id = $1 AND l.revoked_at IS NULL;

-- ---------- 清理 ----------

-- name: CleanupLinkStats :execrows
DELETE FROM link_stats WHERE hour < now() - interval '90 days';

-- name: CleanupLinkReferers :execrows
DELETE FROM link_referers WHERE day < current_date - 90;

-- name: CleanupLinkHits :execrows
DELETE FROM link_hits WHERE at < now() - interval '30 days';

-- name: CleanupRevokedLinks :execrows
DELETE FROM links WHERE revoked_at < now() - interval '90 days';
