-- name: CreateShare :one
INSERT INTO shares (id, token, node_id, created_by, password_hash, expires_at)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING *;

-- name: GetShareByToken :one
SELECT s.*, n.name AS node_name, n.kind AS node_kind, n.deleted_at AS node_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM shares s JOIN nodes n ON n.id = s.node_id JOIN users u ON u.id = s.created_by
WHERE s.token = $1;

-- name: GetShareByID :one
SELECT s.*, n.name AS node_name, n.kind AS node_kind, n.deleted_at AS node_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM shares s JOIN nodes n ON n.id = s.node_id JOIN users u ON u.id = s.created_by
WHERE s.id = $1;

-- name: ListMyShares :many
-- 顺带带出访问统计。用 LATERAL 一次扫完,别让列表变成 N+1。
-- verify = 访客进入(验密成功);download 把单文件下载与打包下载合并计数。
SELECT s.id, s.token, s.password_hash, s.expires_at, s.created_at,
       n.id AS node_id, n.parent_id AS node_parent_id, n.name AS node_name,
       n.kind AS node_kind, n.created_at AS node_created_at, n.updated_at AS node_updated_at,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256,
       COALESCE(v.verify_count, 0)::bigint   AS verify_count,
       COALESCE(v.download_count, 0)::bigint AS download_count,
       v.last_at   AS last_visit_at,
       v.last_kind AS last_visit_kind
FROM shares s
JOIN nodes n ON n.id = s.node_id
LEFT JOIN blobs b ON b.id = n.blob_id
LEFT JOIN LATERAL (
    SELECT count(*) FILTER (WHERE sv.kind = 'verify')  AS verify_count,
           count(*) FILTER (WHERE sv.kind <> 'verify') AS download_count,
           max(sv.created_at)::timestamptz             AS last_at,
           (array_agg(sv.kind ORDER BY sv.created_at DESC, sv.id DESC))[1]::text AS last_kind
    FROM share_visits sv
    WHERE sv.share_id = s.id
) v ON true
WHERE s.created_by = $1 AND s.revoked_at IS NULL AND n.deleted_at IS NULL
ORDER BY s.created_at DESC, s.id;

-- name: RecordShareVisit :exec
-- 尽力而为:埋点失败绝不能影响访客的访问,调用方只记日志。
INSERT INTO share_visits (share_id, kind, ip, user_agent)
VALUES ($1, $2, $3, $4);

-- name: ListShareVisits :many
SELECT kind, ip, user_agent, created_at
FROM share_visits
WHERE share_id = $1
ORDER BY created_at DESC, id DESC
LIMIT $2;

-- name: DeleteExpiredShareVisits :execrows
-- 保留 180 天。所以对外说的"访问次数"是近 180 天的语义,别当成历史总量。
DELETE FROM share_visits WHERE created_at < now() - interval '180 days';

-- name: RevokeShare :execrows
UPDATE shares SET revoked_at = now()
WHERE id = $1 AND created_by = $2 AND revoked_at IS NULL;
