-- 剪藏(doc/23 五)。

-- name: CreateClip :one
INSERT INTO clips (id, owner_id, url, title, site, byline, excerpt, published_at, node_id, words, images)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
RETURNING *;

-- name: FindClipByURL :one
-- 同一个地址剪过、那篇还在(没进回收站)的
SELECT c.* FROM clips c JOIN nodes n ON n.id = c.node_id
WHERE c.owner_id = $1 AND c.url = $2 AND n.deleted_at IS NULL
ORDER BY c.created_at DESC
LIMIT 1;

-- name: GetClip :one
SELECT * FROM clips WHERE id = $1 AND owner_id = $2;

-- name: GetClipByNode :one
SELECT * FROM clips WHERE node_id = $1 AND owner_id = $2;

-- name: ListClips :many
-- 新的在前;进了回收站的不列。unread_only 只列没读过的;site 为空不限
SELECT c.* FROM clips c JOIN nodes n ON n.id = c.node_id
WHERE c.owner_id = @owner_id AND n.deleted_at IS NULL
  AND (NOT @unread_only::boolean OR c.read_at IS NULL)
  AND (@site::text = '' OR c.site = @site::text)
  AND (@before_id::uuid = '00000000-0000-0000-0000-000000000000'::uuid
       OR (c.created_at, c.id) < (@before_at::timestamptz, @before_id::uuid))
ORDER BY c.created_at DESC, c.id DESC
LIMIT @lim;

-- name: ListClipSites :many
SELECT c.site, count(*)::bigint AS total FROM clips c JOIN nodes n ON n.id = c.node_id
WHERE c.owner_id = $1 AND n.deleted_at IS NULL AND c.site <> ''
GROUP BY c.site ORDER BY count(*) DESC, c.site LIMIT 100;

-- name: CountUnreadClips :one
SELECT count(*) FROM clips c JOIN nodes n ON n.id = c.node_id
WHERE c.owner_id = $1 AND n.deleted_at IS NULL AND c.read_at IS NULL;

-- name: SetClipRead :one
UPDATE clips SET read_at = CASE WHEN @read::boolean THEN COALESCE(read_at, now()) ELSE NULL END
WHERE id = @id AND owner_id = @owner_id
RETURNING *;

-- name: GetClipPrefs :one
SELECT * FROM clip_prefs WHERE owner_id = $1;

-- name: SetClipPrefs :one
INSERT INTO clip_prefs (owner_id, folder, by_month) VALUES ($1, $2, $3)
ON CONFLICT (owner_id) DO UPDATE SET folder = EXCLUDED.folder, by_month = EXCLUDED.by_month, updated_at = now()
RETURNING *;
