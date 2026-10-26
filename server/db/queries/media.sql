-- 影音(doc/22 二)。

-- name: UpsertPlayProgress :one
INSERT INTO play_progress (owner_id, node_id, position, duration, finished, updated_at)
VALUES ($1, $2, $3, $4, $5, now())
ON CONFLICT (owner_id, node_id) DO UPDATE SET position = EXCLUDED.position, duration = EXCLUDED.duration,
    finished = EXCLUDED.finished, updated_at = now()
RETURNING *;

-- name: GetPlayProgress :one
SELECT * FROM play_progress WHERE owner_id = $1 AND node_id = $2;

-- name: DeletePlayProgress :execrows
DELETE FROM play_progress WHERE owner_id = $1 AND node_id = $2;

-- name: ListRecentPlays :many
-- 没看完的,文件还在的,最近的在前。
SELECT sqlc.embed(p), sqlc.embed(n), b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM play_progress p
JOIN nodes n ON n.id = p.node_id AND n.deleted_at IS NULL
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE p.owner_id = $1 AND NOT p.finished
ORDER BY p.updated_at DESC
LIMIT $2;

-- name: CleanupPlayProgress :execrows
-- 每人只留最近 500 条
DELETE FROM play_progress p
USING (
    SELECT owner_id, node_id, row_number() OVER (PARTITION BY owner_id ORDER BY updated_at DESC) AS rn FROM play_progress
) x
WHERE p.owner_id = x.owner_id AND p.node_id = x.node_id AND x.rn > 500;

-- name: ListFolderMedia :many
-- 同文件夹里某一类(mime 前缀,如 video/)的文件,连播与「下一集」用;调用方按自然顺序排。
SELECT sqlc.embed(n), b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = @owner_id AND n.parent_id IS NOT DISTINCT FROM sqlc.narg(parent_id)::uuid
  AND n.deleted_at IS NULL AND n.kind = 'file' AND b.mime LIKE @mime_prefix::text || '%'
LIMIT 2000;

-- name: ListFolderSubtitles :many
-- 同文件夹里的字幕文件(外挂字幕匹配)。
SELECT n.id, n.name, b.size AS blob_size
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = @owner_id AND n.parent_id IS NOT DISTINCT FROM sqlc.narg(parent_id)::uuid
  AND n.deleted_at IS NULL AND n.kind = 'file'
  AND (lower(n.name) LIKE '%.srt' OR lower(n.name) LIKE '%.ass' OR lower(n.name) LIKE '%.ssa' OR lower(n.name) LIKE '%.vtt')
ORDER BY n.name
LIMIT 200;

-- name: FindFolderFileByName :one
-- 同文件夹里按名字(不分大小写)找一个文件:歌的同名 .lrc(doc/31 二)。
SELECT n.id, n.name, b.size AS blob_size, b.sha256 AS blob_sha256
FROM nodes n
JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = @owner_id AND n.parent_id IS NOT DISTINCT FROM sqlc.narg(parent_id)::uuid
  AND n.deleted_at IS NULL AND n.kind = 'file' AND lower(n.name) = lower(@name::text)
ORDER BY n.name
LIMIT 1;

-- name: CountFolderVideos :one
SELECT count(*) FROM (
    SELECT 1 FROM nodes n JOIN blobs b ON b.id = n.blob_id
    WHERE n.owner_id = @owner_id AND n.parent_id IS NOT DISTINCT FROM sqlc.narg(parent_id)::uuid
      AND n.deleted_at IS NULL AND n.kind = 'file' AND b.mime LIKE 'video/%'
    LIMIT 2
) x;
