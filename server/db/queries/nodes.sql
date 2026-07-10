-- name: CreateNode :one
INSERT INTO nodes (id, owner_id, parent_id, name, kind, blob_id)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING *;

-- name: GetNode :one
SELECT * FROM nodes WHERE id = $1;

-- name: GetActiveNodeOwned :one
SELECT * FROM nodes WHERE id = $1 AND owner_id = $2 AND deleted_at IS NULL;

-- name: SiblingNameExists :one
SELECT EXISTS (
    SELECT 1 FROM nodes
    WHERE owner_id = $1
      AND parent_id IS NOT DISTINCT FROM $2
      AND name = $3
      AND deleted_at IS NULL
) AS exists;

-- name: ListChildren :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
ORDER BY
  n.kind DESC, -- folder 在前
  CASE WHEN sqlc.arg(order_by)::text = 'NAME'       AND NOT sqlc.arg(descending)::boolean THEN n.name END ASC,
  CASE WHEN sqlc.arg(order_by)::text = 'NAME'       AND     sqlc.arg(descending)::boolean THEN n.name END DESC,
  CASE WHEN sqlc.arg(order_by)::text = 'SIZE'       AND NOT sqlc.arg(descending)::boolean THEN b.size END ASC NULLS FIRST,
  CASE WHEN sqlc.arg(order_by)::text = 'SIZE'       AND     sqlc.arg(descending)::boolean THEN b.size END DESC NULLS LAST,
  CASE WHEN sqlc.arg(order_by)::text = 'UPDATED_AT' AND NOT sqlc.arg(descending)::boolean THEN n.updated_at END ASC,
  CASE WHEN sqlc.arg(order_by)::text = 'UPDATED_AT' AND     sqlc.arg(descending)::boolean THEN n.updated_at END DESC,
  n.id
LIMIT sqlc.arg(page_limit) OFFSET sqlc.arg(page_offset);

-- name: CountChildren :one
SELECT count(*) FROM nodes
WHERE owner_id = $1 AND parent_id IS NOT DISTINCT FROM $2 AND deleted_at IS NULL;

-- name: SearchNodes :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NULL AND n.name ILIKE '%' || $2 || '%'
ORDER BY n.updated_at DESC, n.id
LIMIT $3 OFFSET $4;

-- name: CountSearchNodes :one
SELECT count(*) FROM nodes
WHERE owner_id = $1 AND deleted_at IS NULL AND name ILIKE '%' || $2 || '%';

-- name: ListTrash :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NOT NULL
  -- 只列回收站顶层:父不存在、父未删,或父先于自己被单独删除后还原过
  AND (n.parent_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM nodes p WHERE p.id = n.parent_id AND p.deleted_at IS NOT NULL))
ORDER BY n.deleted_at DESC, n.id
LIMIT $2 OFFSET $3;

-- name: CountTrash :one
SELECT count(*) FROM nodes n
WHERE n.owner_id = $1 AND n.deleted_at IS NOT NULL
  AND (n.parent_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM nodes p WHERE p.id = n.parent_id AND p.deleted_at IS NOT NULL));

-- name: RenameNode :one
UPDATE nodes SET name = $3, updated_at = now()
WHERE id = $1 AND owner_id = $2 AND deleted_at IS NULL
RETURNING *;

-- name: RenameNodeAnyState :exec
-- 供回收站还原前解冲突用,不限制 deleted_at
UPDATE nodes SET name = $3, updated_at = now()
WHERE id = $1 AND owner_id = $2;

-- name: MoveNode :one
UPDATE nodes SET parent_id = $3, updated_at = now()
WHERE id = $1 AND owner_id = $2 AND deleted_at IS NULL
RETURNING *;

-- name: IsDescendant :one
-- $2(candidate)是否在 $1(ancestor)的子树内(含自身)
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
)
SELECT count(*) > 0 AS is_descendant
FROM nodes x
WHERE x.id = $2 AND x.id IN (SELECT s.id FROM sub s);

-- name: SoftDeleteSubtree :exec
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.owner_id = $2 AND r.deleted_at IS NULL
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
    WHERE n.deleted_at IS NULL
)
UPDATE nodes SET deleted_at = now()
WHERE nodes.id IN (SELECT s.id FROM sub s);

-- name: RestoreSubtree :exec
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.owner_id = $2 AND r.deleted_at IS NOT NULL
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
    WHERE n.deleted_at IS NOT NULL
)
UPDATE nodes SET deleted_at = NULL
WHERE nodes.id IN (SELECT s.id FROM sub s);

-- name: PurgeSubtree :many
-- 硬删子树,返回被删文件节点引用的 blob_id 供引用计数递减
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.owner_id = $2
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
), deleted AS (
    DELETE FROM nodes WHERE nodes.id IN (SELECT s.id FROM sub s)
    RETURNING blob_id
)
SELECT d.blob_id FROM deleted d WHERE d.blob_id IS NOT NULL;

-- name: ListTrashRootsForPurge :many
SELECT id FROM nodes n
WHERE n.owner_id = $1 AND n.deleted_at IS NOT NULL
  AND (n.parent_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM nodes p WHERE p.id = n.parent_id AND p.deleted_at IS NOT NULL));

-- name: DecrementBlobRefs :exec
UPDATE blobs SET
    ref_count = ref_count - 1,
    deref_at  = CASE WHEN ref_count - 1 = 0 THEN now() ELSE deref_at END
WHERE id = ANY($1::uuid[]);

-- name: SubtractUsedBytes :exec
UPDATE users SET used_bytes = greatest(used_bytes - $2, 0) WHERE id = $1;
