-- name: CreateShare :one
INSERT INTO shares (id, token, node_id, created_by, password_hash, expires_at, allow_upload)
VALUES ($1, $2, $3, $4, $5, $6, $7)
RETURNING *;

-- name: GetShareByToken :one
-- 顺带带出大小 / 文件数与 blob 信息:访客页头部和链接预览卡片要用(有密码的分享由调用方自己藏起来)
SELECT s.*, n.name AS node_name, n.kind AS node_kind, n.deleted_at AS node_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled,
       n.subtree_bytes AS node_subtree_bytes, n.subtree_count AS node_subtree_count,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM shares s JOIN nodes n ON n.id = s.node_id JOIN users u ON u.id = s.created_by
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE s.token = $1;

-- name: GetShareByID :one
SELECT s.*, n.name AS node_name, n.kind AS node_kind, n.deleted_at AS node_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM shares s JOIN nodes n ON n.id = s.node_id JOIN users u ON u.id = s.created_by
WHERE s.id = $1;

-- name: ListMyShares :many
-- 顺带带出访问统计。用 LATERAL 一次扫完,别让列表变成 N+1。
-- verify = 访客进入(验密成功);download 把单文件下载与打包下载合并计数。
-- last_kind 没有访问记录时为空串(sqlc 对 LATERAL 列推不出可空,COALESCE 掉避免扫 NULL 报错)。
SELECT s.id, s.token, s.password_hash, s.expires_at, s.created_at, s.allow_upload,
       n.id AS node_id, n.parent_id AS node_parent_id, n.name AS node_name,
       n.kind AS node_kind, n.created_at AS node_created_at, n.updated_at AS node_updated_at,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256,
       COALESCE(v.verify_count, 0)::bigint   AS verify_count,
       COALESCE(v.download_count, 0)::bigint AS download_count,
       COALESCE(v.upload_count, 0)::bigint   AS upload_count,
       v.last_at   AS last_visit_at,
       v.last_kind AS last_visit_kind
FROM shares s
JOIN nodes n ON n.id = s.node_id
LEFT JOIN blobs b ON b.id = n.blob_id
LEFT JOIN LATERAL (
    SELECT count(*) FILTER (WHERE sv.kind = 'verify')  AS verify_count,
           count(*) FILTER (WHERE sv.kind IN ('download', 'pack')) AS download_count,
           count(*) FILTER (WHERE sv.kind = 'upload')  AS upload_count,
           max(sv.created_at)::timestamptz             AS last_at,
           COALESCE((array_agg(sv.kind ORDER BY sv.created_at DESC, sv.id DESC))[1], '')::text AS last_kind
    FROM share_visits sv
    WHERE sv.share_id = s.id
) v ON true
WHERE s.created_by = $1 AND s.revoked_at IS NULL AND n.deleted_at IS NULL
ORDER BY s.created_at DESC, s.id;

-- name: RecordShareVisit :exec
-- 尽力而为:埋点失败绝不能影响访客的访问,调用方只记日志。
INSERT INTO share_visits (share_id, kind, ip, user_agent, detail)
VALUES ($1, $2, $3, $4, $5);

-- name: ListShareVisits :many
SELECT kind, ip, user_agent, detail, created_at
FROM share_visits
WHERE share_id = $1
ORDER BY created_at DESC, id DESC
LIMIT $2;

-- name: DeleteExpiredShareVisits :execrows
-- 保留 180 天。所以对外说的"访问次数"是近 180 天的语义,别当成历史总量。
DELETE FROM share_visits WHERE created_at < now() - interval '180 days';

-- name: SetShareUpload :execrows
-- 只有文件夹分享能开上传
UPDATE shares s SET allow_upload = $3
FROM nodes n
WHERE s.id = $1 AND s.created_by = $2 AND s.revoked_at IS NULL AND n.id = s.node_id
  AND (n.kind = 'folder' OR NOT $3);

-- name: LockShare :one
-- 访客上传落库时锁住分享行:同一分享的并发落库串行挑名字,也串行复核分享还开着上传。
SELECT s.*, n.name AS node_name, n.deleted_at AS node_deleted_at, n.kind AS node_kind,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM shares s JOIN nodes n ON n.id = s.node_id JOIN users u ON u.id = s.created_by
WHERE s.id = $1
FOR UPDATE OF s;

-- name: CreateShareUploadSession :one
INSERT INTO upload_sessions (id, owner_id, size, target_parent, target_name, minio_upload_id, part_size,
    expires_at, object_key, share_id, actor)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
RETURNING *;

-- name: GetShareUploadSession :one
-- 访客只能碰这个分享上的会话
SELECT * FROM upload_sessions WHERE id = $1 AND share_id = $2;

-- name: ShareUploadPending :one
SELECT count(*)::bigint AS sessions, COALESCE(sum(size), 0)::bigint AS bytes
FROM upload_sessions
WHERE share_id = $1 AND status IN ('uploading', 'completing');

-- name: RevokeShare :execrows
UPDATE shares SET revoked_at = now()
WHERE id = $1 AND created_by = $2 AND revoked_at IS NULL;
