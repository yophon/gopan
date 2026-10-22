-- 动态与撤回(doc/23 二)。

-- name: InsertActivities :exec
-- 一次操作的多条记录一条语句写完:rows 是 [{action, node_id, node_kind, name, parent_path, detail}]
INSERT INTO activities (owner_id, actor_kind, actor_ref, actor_label, batch_id, action, node_id, node_kind, name, parent_path, detail)
SELECT @owner_id::uuid, @actor_kind::text, @actor_ref::text, @actor_label::text, @batch_id::uuid,
       r.action, r.node_id, COALESCE(r.node_kind, ''), COALESCE(r.name, ''), COALESCE(r.parent_path, ''), COALESCE(r.detail, '{}'::jsonb)
FROM jsonb_to_recordset(@rows::jsonb) AS r(action text, node_id uuid, node_kind text, name text, parent_path text, detail jsonb);

-- name: NodePath :one
-- 节点(含自身)从根开始的路径,如 /照片/2024;深度封顶 64 防环
WITH RECURSIVE anc AS (
    SELECT n.id, n.parent_id, n.name, 0 AS depth FROM nodes n WHERE n.id = $1
    UNION ALL
    SELECT p.id, p.parent_id, p.name, a.depth + 1 FROM nodes p JOIN anc a ON p.id = a.parent_id WHERE a.depth < 64
)
SELECT ('/' || COALESCE(string_agg(anc.name, '/' ORDER BY anc.depth DESC), ''))::text AS path FROM anc;

-- name: ListActivities :many
-- 新的在前;各筛选条件为空值时不限。before_id 是游标(上一页最后一条的 id)
SELECT * FROM activities
WHERE owner_id = @owner_id
  AND (@actor_kind::text = '' OR actor_kind = @actor_kind::text)
  AND (@actor_ref::text = '' OR actor_ref = @actor_ref::text)
  AND (cardinality(@actions::text[]) = 0 OR action = ANY(@actions::text[]))
  AND (@node_id::uuid = '00000000-0000-0000-0000-000000000000'::uuid OR node_id = @node_id::uuid)
  AND (@batch_id::uuid = '00000000-0000-0000-0000-000000000000'::uuid OR batch_id = @batch_id::uuid)
  AND (sqlc.narg(since)::timestamptz IS NULL OR at >= sqlc.narg(since)::timestamptz)
  AND (sqlc.narg(until)::timestamptz IS NULL OR at < sqlc.narg(until)::timestamptz)
  AND (@before_id::bigint = 0 OR id < @before_id::bigint)
ORDER BY id DESC
LIMIT @lim;

-- name: ListActivitiesByIDs :many
SELECT * FROM activities WHERE owner_id = @owner_id AND id = ANY(@ids::bigint[]) ORDER BY id DESC;

-- name: ListActivityActors :many
-- 近 90 天出现过的来源,最近活跃的在前;名字取最新的一条
SELECT actor_kind, actor_ref, (array_agg(actor_label ORDER BY id DESC))[1]::text AS actor_label,
       count(*)::bigint AS total, max(at)::timestamptz AS last_at
FROM activities
WHERE owner_id = $1
GROUP BY actor_kind, actor_ref
ORDER BY max(at) DESC
LIMIT 200;

-- name: MarkActivityUndone :exec
UPDATE activities SET undone_at = now() WHERE id = $1 AND owner_id = $2 AND undone_at IS NULL;

-- name: SumRecentDeletes :one
-- 某个来源最近一段时间删进回收站的文件数与字节(大量删除提醒)
SELECT COALESCE(sum((detail->>'files')::bigint), 0)::bigint AS files,
       COALESCE(sum((detail->>'bytes')::bigint), 0)::bigint AS bytes
FROM activities
WHERE owner_id = $1 AND actor_kind = $2 AND actor_ref = $3 AND action = 'delete' AND at > $4;

-- name: CleanupActivities :execrows
DELETE FROM activities WHERE at < now() - interval '90 days';

-- name: SetUploadSessionActor :exec
UPDATE upload_sessions SET actor = $2 WHERE id = $1;

-- name: SetTransferSessionActor :exec
UPDATE transfer_sessions SET actor = $2 WHERE id = $1;
