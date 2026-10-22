-- 空间分析(doc/23 三)。全部是只读查询,按需现算。

-- name: SpaceByType :many
SELECT gopan_file_category(n.name, b.mime)::text AS category,
       count(*)::bigint AS files, COALESCE(sum(b.size), 0)::bigint AS bytes
FROM nodes n JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NULL AND n.kind = 'file'
GROUP BY 1
ORDER BY 3 DESC;

-- name: SpaceTrash :one
SELECT count(*) FILTER (WHERE n.kind = 'file')::bigint AS files, COALESCE(sum(b.size), 0)::bigint AS bytes
FROM nodes n LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NOT NULL;

-- name: SpaceFolders :many
-- 某一层的子文件夹按子树大小排(子树统计异步重算,stats_stale 表示可能滞后)
SELECT n.*
FROM nodes n
WHERE n.owner_id = $1 AND n.parent_id IS NOT DISTINCT FROM $2 AND n.deleted_at IS NULL AND n.kind = 'folder'
ORDER BY n.subtree_bytes DESC, n.id
LIMIT 200;

-- name: SpaceLooseFiles :one
-- 某一层直接放着的文件(不在子文件夹里)
SELECT count(*)::bigint AS files, COALESCE(sum(b.size), 0)::bigint AS bytes
FROM nodes n JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.parent_id IS NOT DISTINCT FROM $2 AND n.deleted_at IS NULL AND n.kind = 'file';

-- name: SpaceLargeFiles :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, gopan_file_category(n.name, b.mime)::text AS category
FROM nodes n JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = @owner_id AND n.deleted_at IS NULL AND n.kind = 'file'
  AND (@category::text = '' OR gopan_file_category(n.name, b.mime) = @category::text)
ORDER BY b.size DESC, n.id
LIMIT @lim;

-- name: SpaceStaleFiles :many
-- 很久没改过的大文件
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, gopan_file_category(n.name, b.mime)::text AS category
FROM nodes n JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = @owner_id AND n.deleted_at IS NULL AND n.kind = 'file'
  AND n.updated_at < @before::timestamptz AND b.size >= @min_size::bigint
ORDER BY b.size DESC, n.id
LIMIT @lim;

-- name: SpaceDuplicateGroups :many
-- 同一份内容在自己盘里出现多次的,按「删掉多余的能省多少」排
SELECT n.blob_id::uuid AS blob_id, b.size, b.sha256, count(*)::bigint AS copies
FROM nodes n JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = @owner_id AND n.deleted_at IS NULL AND n.kind = 'file' AND b.size > 0
GROUP BY n.blob_id, b.size, b.sha256
HAVING count(*) > 1
ORDER BY b.size * (count(*) - 1) DESC, n.blob_id
LIMIT @lim OFFSET @off;

-- name: SpaceDuplicateTotals :one
SELECT count(*)::bigint AS groups, COALESCE(sum(t.size * (t.copies - 1)), 0)::bigint AS reclaimable
FROM (
    SELECT b.size, count(*) AS copies
    FROM nodes n JOIN blobs b ON b.id = n.blob_id
    WHERE n.owner_id = $1 AND n.deleted_at IS NULL AND n.kind = 'file' AND b.size > 0
    GROUP BY n.blob_id, b.size
    HAVING count(*) > 1
) t;

-- name: SpaceNodesByBlobs :many
SELECT n.* FROM nodes n
WHERE n.owner_id = @owner_id AND n.deleted_at IS NULL AND n.kind = 'file' AND n.blob_id = ANY(@blob_ids::uuid[])
ORDER BY n.created_at, n.id;

-- name: SpaceEmptyFolders :many
-- 子树里一个文件都没有的文件夹,只列最上面一层(它的上级不是空的);刚建的、收集链接 / 订阅 /
-- 传输助手用着的不算
SELECT n.* FROM nodes n
LEFT JOIN nodes p ON p.id = n.parent_id
WHERE n.owner_id = $1 AND n.deleted_at IS NULL AND n.kind = 'folder'
  AND n.subtree_count = 0 AND NOT n.stats_stale
  AND (p.id IS NULL OR p.subtree_count > 0 OR p.stats_stale)
  AND n.created_at < now() - interval '1 day'
  AND NOT EXISTS (SELECT 1 FROM file_requests fr WHERE fr.folder_id = n.id AND fr.revoked_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM feeds f WHERE f.folder_id = n.id)
  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.chat_folder_id = n.id)
ORDER BY n.updated_at, n.id
LIMIT $2;

-- name: SpaceIdleShares :many
-- 已过期的、或 90 天没人访问的分享(建了不到 90 天的不算没人访问)
SELECT s.id, s.token, s.node_id, s.expires_at, s.created_at, n.name, n.kind,
       (SELECT max(v.created_at) FROM share_visits v WHERE v.share_id = s.id)::timestamptz AS last_visit_at
FROM shares s JOIN nodes n ON n.id = s.node_id
WHERE s.created_by = $1 AND s.revoked_at IS NULL
  AND (s.expires_at < now()
       OR (s.created_at < now() - interval '90 days'
           AND NOT EXISTS (SELECT 1 FROM share_visits v WHERE v.share_id = s.id AND v.created_at > now() - interval '90 days')))
ORDER BY s.created_at
LIMIT 200;

-- name: SpaceIdleLinks :many
-- 已过期的、或 90 天没人访问的直链
SELECT l.id, l.node_id, l.expires_at, l.created_at, l.last_hit_at, l.hits_total, n.name
FROM links l JOIN nodes n ON n.id = l.node_id
WHERE l.owner_id = $1 AND l.revoked_at IS NULL
  AND (l.expires_at < now()
       OR (l.created_at < now() - interval '90 days' AND (l.last_hit_at IS NULL OR l.last_hit_at < now() - interval '90 days')))
ORDER BY l.created_at
LIMIT 200;

-- name: SpaceExpiredRequests :many
SELECT fr.id, fr.title, fr.folder_id, fr.expires_at, fr.created_at
FROM file_requests fr
WHERE fr.owner_id = $1 AND fr.revoked_at IS NULL AND fr.expires_at < now()
ORDER BY fr.expires_at
LIMIT 200;

-- name: AdminStorage :one
-- 全站:逻辑用量(各人加起来,同一份内容各算各的)与物理占用(去重后实际存的)
SELECT (SELECT COALESCE(sum(used_bytes), 0) FROM users)::bigint AS logical_bytes,
       (SELECT COALESCE(sum(size), 0) FROM blobs WHERE ref_count > 0)::bigint AS physical_bytes,
       (SELECT count(*) FROM blobs WHERE ref_count > 0)::bigint AS blobs,
       (SELECT COALESCE(sum(size), 0) FROM blobs WHERE ref_count <= 0)::bigint AS pending_gc_bytes,
       (SELECT COALESCE(sum(size), 0) FROM derivatives)::bigint AS derivative_bytes,
       (SELECT count(*) FROM derivatives)::bigint AS derivatives,
       pg_total_relation_size('blob_texts')::bigint AS text_bytes,
       pg_total_relation_size('activities')::bigint AS activity_bytes;
