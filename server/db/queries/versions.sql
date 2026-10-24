-- 历史版本(doc/24)。删除类查询一律 RETURNING *,由服务层 releaseVersions 统一退引用、退配额。

-- name: InsertNodeVersion :one
INSERT INTO node_versions (id, node_id, owner_id, blob_id, size, saved_at)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING *;

-- name: ListNodeVersions :many
-- 一个文件的历史版本,最近换下来的在前
SELECT sqlc.embed(v), b.sha256 AS blob_sha256, b.mime AS blob_mime
FROM node_versions v
JOIN blobs b ON b.id = v.blob_id
WHERE v.node_id = $1 AND v.owner_id = $2
ORDER BY v.replaced_at DESC, v.id DESC;

-- name: GetNodeVersion :one
SELECT sqlc.embed(v), b.sha256 AS blob_sha256, b.mime AS blob_mime
FROM node_versions v
JOIN blobs b ON b.id = v.blob_id
WHERE v.id = $1 AND v.owner_id = $2;

-- name: LockNodeVersion :one
SELECT * FROM node_versions WHERE id = $1 AND owner_id = $2 FOR UPDATE;

-- name: DeleteNodeVersionMoved :exec
-- 恢复:这一版的内容(连同它持有的引用)回到节点上,行删掉、不退账
DELETE FROM node_versions WHERE id = $1;

-- name: DeleteNodeVersions :many
DELETE FROM node_versions
WHERE owner_id = @owner_id AND id = ANY(@ids::uuid[])
RETURNING *;

-- name: DeleteVersionsOfNode :many
DELETE FROM node_versions
WHERE owner_id = $1 AND node_id = $2
RETURNING *;

-- name: DeleteVersionsOfOwner :many
DELETE FROM node_versions
WHERE owner_id = $1
RETURNING *;

-- name: TrimNodeVersions :many
-- 只留最近换下来的 keep 个
DELETE FROM node_versions
WHERE id IN (
    SELECT v.id FROM node_versions v
    WHERE v.node_id = @node_id
    ORDER BY v.replaced_at DESC, v.id DESC
    OFFSET @keep::int
)
RETURNING *;

-- name: DeleteExpiredVersions :many
DELETE FROM node_versions
WHERE id IN (
    SELECT v.id FROM node_versions v
    WHERE v.replaced_at < @before::timestamptz
    ORDER BY v.replaced_at
    LIMIT @lim::int
)
RETURNING *;

-- name: DeleteVersionsByBlob :many
-- 服务端 hash 校验失败的 blob:持有它的版本一起作废
DELETE FROM node_versions WHERE blob_id = $1
RETURNING *;

-- name: PurgeSubtreeVersions :many
-- 彻底删除子树之前先删其中所有文件的版本(节点行删掉后就找不到它们了)
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.owner_id = $2
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
)
DELETE FROM node_versions
WHERE node_id IN (SELECT s.id FROM sub s)
RETURNING *;

-- name: DecrementBlobRefN :exec
-- 同一个 blob 被释放 n 次(几个版本或几个节点指向同一份内容)
UPDATE blobs SET
    ref_count = ref_count - @n::int,
    deref_at  = CASE WHEN ref_count - @n::int <= 0 THEN now() ELSE deref_at END
WHERE id = @id;

-- name: SpaceVersions :one
SELECT count(*)::bigint AS files, COALESCE(sum(size), 0)::bigint AS bytes
FROM node_versions WHERE owner_id = $1;
