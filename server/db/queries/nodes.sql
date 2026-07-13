-- name: CreateNode :one
INSERT INTO nodes (id, owner_id, parent_id, name, kind, blob_id)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING *;

-- name: GetNode :one
SELECT * FROM nodes WHERE id = $1;

-- name: GetActiveNodeOwned :one
SELECT * FROM nodes WHERE id = $1 AND owner_id = $2 AND deleted_at IS NULL;

-- name: GetNodeWithBlob :one
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256, b.verified AS blob_verified
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.id = $1 AND n.owner_id = $2;

-- name: ListChildrenAll :many
-- WebDAV PROPFIND:目录全量列表(协议无分页,客户端自己排序)
SELECT n.id, n.name, n.kind, n.updated_at, b.size AS blob_size
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.parent_id IS NOT DISTINCT FROM $2 AND n.deleted_at IS NULL
ORDER BY n.name;

-- name: GetActiveChildByName :one
-- WebDAV 路径解析:按名取活跃子节点
SELECT * FROM nodes
WHERE owner_id = $1 AND parent_id IS NOT DISTINCT FROM $2 AND name = $3 AND deleted_at IS NULL;

-- name: ReplaceNodeBlob :one
-- WebDAV PUT 覆盖:换 blob 指向,不产生回收站副本
UPDATE nodes SET blob_id = $3, updated_at = now()
WHERE id = $1 AND owner_id = $2 AND deleted_at IS NULL AND kind = 'file'
RETURNING *;

-- name: SiblingNameExists :one
SELECT EXISTS (
    SELECT 1 FROM nodes
    WHERE owner_id = $1
      AND parent_id IS NOT DISTINCT FROM $2
      AND name = $3
      AND deleted_at IS NULL
) AS exists;

-- name: ListChildren :many
-- 首页(无 cursor)。翻页走下面 6 条 keyset 查询,方向拆开写,不用 CASE 包 WHERE,留住索引通道。
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
LIMIT sqlc.arg(page_limit);

-- name: ListChildrenNameAsc :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
  AND (n.kind < sqlc.arg(c_kind)::text
       OR (n.kind = sqlc.arg(c_kind)::text
           AND (n.name > sqlc.arg(c_name)::text
                OR (n.name = sqlc.arg(c_name)::text AND n.id > sqlc.arg(c_id)))))
ORDER BY n.kind DESC, n.name ASC, n.id
LIMIT sqlc.arg(page_limit);

-- name: ListChildrenNameDesc :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
  AND (n.kind < sqlc.arg(c_kind)::text
       OR (n.kind = sqlc.arg(c_kind)::text
           AND (n.name < sqlc.arg(c_name)::text
                OR (n.name = sqlc.arg(c_name)::text AND n.id > sqlc.arg(c_id)))))
ORDER BY n.kind DESC, n.name DESC, n.id
LIMIT sqlc.arg(page_limit);

-- name: ListChildrenSizeAsc :many
-- SIZE 键可空(文件夹无 blob):ASC NULLS FIRST——cursor 键为空时,"之后" = 同为空且 id 更大,或键非空
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
  AND (n.kind < sqlc.arg(c_kind)::text
       OR (n.kind = sqlc.arg(c_kind)::text
           AND ((sqlc.arg(c_size_null)::boolean AND ((b.size IS NULL AND n.id > sqlc.arg(c_id)) OR b.size IS NOT NULL))
                OR (NOT sqlc.arg(c_size_null)::boolean
                    AND (b.size > sqlc.arg(c_size)::bigint
                         OR (b.size = sqlc.arg(c_size)::bigint AND n.id > sqlc.arg(c_id)))))))
ORDER BY n.kind DESC, b.size ASC NULLS FIRST, n.id
LIMIT sqlc.arg(page_limit);

-- name: ListChildrenSizeDesc :many
-- DESC NULLS LAST——cursor 键非空时,"之后" = 键更小,或同键 id 更大,或键为空
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
  AND (n.kind < sqlc.arg(c_kind)::text
       OR (n.kind = sqlc.arg(c_kind)::text
           AND ((sqlc.arg(c_size_null)::boolean AND b.size IS NULL AND n.id > sqlc.arg(c_id))
                OR (NOT sqlc.arg(c_size_null)::boolean
                    AND (b.size < sqlc.arg(c_size)::bigint
                         OR (b.size = sqlc.arg(c_size)::bigint AND n.id > sqlc.arg(c_id))
                         OR b.size IS NULL)))))
ORDER BY n.kind DESC, b.size DESC NULLS LAST, n.id
LIMIT sqlc.arg(page_limit);

-- name: ListChildrenUpdatedAsc :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
  AND (n.kind < sqlc.arg(c_kind)::text
       OR (n.kind = sqlc.arg(c_kind)::text
           AND (n.updated_at > sqlc.arg(c_updated)::timestamptz
                OR (n.updated_at = sqlc.arg(c_updated)::timestamptz AND n.id > sqlc.arg(c_id)))))
ORDER BY n.kind DESC, n.updated_at ASC, n.id
LIMIT sqlc.arg(page_limit);

-- name: ListChildrenUpdatedDesc :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = sqlc.arg(owner_id)
  AND n.parent_id IS NOT DISTINCT FROM sqlc.arg(parent_id)
  AND n.deleted_at IS NULL
  AND (n.kind < sqlc.arg(c_kind)::text
       OR (n.kind = sqlc.arg(c_kind)::text
           AND (n.updated_at < sqlc.arg(c_updated)::timestamptz
                OR (n.updated_at = sqlc.arg(c_updated)::timestamptz AND n.id > sqlc.arg(c_id)))))
ORDER BY n.kind DESC, n.updated_at DESC, n.id
LIMIT sqlc.arg(page_limit);

-- name: CountChildren :one
SELECT count(*) FROM nodes
WHERE owner_id = $1 AND parent_id IS NOT DISTINCT FROM $2 AND deleted_at IS NULL;

-- name: SearchNodes :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NULL AND n.name ILIKE '%' || $2 || '%'
ORDER BY n.updated_at DESC, n.id
LIMIT $3;

-- name: SearchNodesAfter :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NULL AND n.name ILIKE '%' || $2 || '%'
  AND (n.updated_at < sqlc.arg(c_updated)::timestamptz
       OR (n.updated_at = sqlc.arg(c_updated)::timestamptz AND n.id > sqlc.arg(c_id)))
ORDER BY n.updated_at DESC, n.id
LIMIT $3;

-- name: CountSearchNodes :one
SELECT count(*) FROM nodes
WHERE owner_id = $1 AND deleted_at IS NULL AND name ILIKE '%' || $2 || '%';

-- name: SearchNodesInSubtree :many
-- 访客搜索:范围限定在分享根($1)的子树内
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.deleted_at IS NULL
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
    WHERE n.deleted_at IS NULL
)
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.id IN (SELECT s.id FROM sub s) AND n.name ILIKE '%' || $2 || '%'
ORDER BY n.updated_at DESC, n.id
LIMIT $3;

-- name: SearchNodesInSubtreeAfter :many
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.deleted_at IS NULL
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
    WHERE n.deleted_at IS NULL
)
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.id IN (SELECT s.id FROM sub s) AND n.name ILIKE '%' || $2 || '%'
  AND (n.updated_at < sqlc.arg(c_updated)::timestamptz
       OR (n.updated_at = sqlc.arg(c_updated)::timestamptz AND n.id > sqlc.arg(c_id)))
ORDER BY n.updated_at DESC, n.id
LIMIT $3;

-- name: CountSearchNodesInSubtree :one
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.deleted_at IS NULL
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
    WHERE n.deleted_at IS NULL
)
SELECT count(*) FROM nodes n
WHERE n.id IN (SELECT s.id FROM sub s) AND n.name ILIKE '%' || $2 || '%';

-- name: ListActiveChildrenLite :many
-- 打包下载 / 复制的树遍历用,不分页
SELECT n.id, n.name, n.kind, n.blob_id, b.sha256 AS blob_sha256, b.size AS blob_size
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.parent_id = $1 AND n.deleted_at IS NULL
ORDER BY n.kind DESC, n.name;

-- name: ListTrash :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NOT NULL
  -- 只列回收站顶层:父不存在、父未删,或父先于自己被单独删除后还原过
  AND (n.parent_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM nodes p WHERE p.id = n.parent_id AND p.deleted_at IS NOT NULL))
ORDER BY n.deleted_at DESC, n.id
LIMIT $2;

-- name: ListTrashAfter :many
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.deleted_at IS NOT NULL
  AND (n.parent_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM nodes p WHERE p.id = n.parent_id AND p.deleted_at IS NOT NULL))
  AND (n.deleted_at < sqlc.arg(c_deleted)::timestamptz
       OR (n.deleted_at = sqlc.arg(c_deleted)::timestamptz AND n.id > sqlc.arg(c_id)))
ORDER BY n.deleted_at DESC, n.id
LIMIT $2;

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

-- name: ListExpiredTrashRoots :many
-- 全用户的过期回收站顶层(整树软删是同一时刻,只看顶层即可)
SELECT n.id, n.owner_id FROM nodes n
WHERE n.deleted_at IS NOT NULL AND n.deleted_at < $1
  AND (n.parent_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM nodes p WHERE p.id = n.parent_id AND p.deleted_at IS NOT NULL))
LIMIT 500;

-- name: DecrementBlobRefs :exec
UPDATE blobs SET
    ref_count = ref_count - 1,
    deref_at  = CASE WHEN ref_count - 1 = 0 THEN now() ELSE deref_at END
WHERE id = ANY($1::uuid[]);

-- name: SubtractUsedBytes :exec
UPDATE users SET used_bytes = greatest(used_bytes - $2, 0) WHERE id = $1;

-- ---- 子树统计(异步,最终一致) ----

-- name: MarkAncestorsStale :exec
-- 从 $1(含自身)沿父链向上,把途经的 folder 全部标脏
WITH RECURSIVE anc AS (
    SELECT n.id, n.parent_id FROM nodes n WHERE n.id = $1
    UNION ALL
    SELECT p.id, p.parent_id FROM nodes p JOIN anc a ON p.id = a.parent_id
)
UPDATE nodes SET stats_stale = true
WHERE nodes.id IN (SELECT a.id FROM anc a) AND nodes.kind = 'folder';

-- name: ListStaleFolders :many
SELECT id FROM nodes
WHERE kind = 'folder' AND stats_stale AND deleted_at IS NULL
LIMIT $1;

-- name: RecomputeFolderStats :exec
-- 单语句重算:行锁保证与并发标脏串行化;重算窗口内的新变更会再次置脏,下一轮修正
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = $1 AND r.deleted_at IS NULL
    UNION ALL
    SELECT n.id FROM nodes n JOIN sub s ON n.parent_id = s.id
    WHERE n.deleted_at IS NULL
), agg AS (
    SELECT COALESCE(sum(b.size), 0)::bigint AS bytes, count(b.id) AS files
    FROM nodes f
    LEFT JOIN blobs b ON b.id = f.blob_id
    WHERE f.id IN (SELECT s.id FROM sub s) AND f.kind = 'file'
)
UPDATE nodes SET
    subtree_bytes = agg.bytes,
    subtree_count = agg.files,
    stats_stale   = false
FROM agg
WHERE nodes.id = $1;
