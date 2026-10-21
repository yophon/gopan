-- name: UpsertBlobText :exec
INSERT INTO blob_texts (blob_id, status, extractor, content, terms, page_starts, updated_at)
VALUES ($1, $2, $3, $4, $5, $6, now())
ON CONFLICT (blob_id) DO UPDATE
SET status = EXCLUDED.status, extractor = EXCLUDED.extractor, content = EXCLUDED.content,
    terms = EXCLUDED.terms, page_starts = EXCLUDED.page_starts, updated_at = now();

-- name: ListBlobsNeedingText :many
-- 回填:还没提过、或提取器版本旧了的内容。已在队列里的、提取任务最终失败的(等管理员重排)不挑。
SELECT b.id FROM blobs b
LEFT JOIN blob_texts bt ON bt.blob_id = b.id
LEFT JOIN tasks t ON t.blob_id = b.id AND t.kind = 'extract_text'
WHERE b.verified AND b.ref_count > 0
  AND (bt.blob_id IS NULL OR bt.extractor < $1)
  AND (t.id IS NULL OR t.status = 'done')
ORDER BY b.created_at DESC
LIMIT $2;

-- name: RequeueTask :exec
-- 同 EnqueueTask,但已经 done 的也重新排上(提取器升级、Office 转出 PDF 后重提文字)
INSERT INTO tasks (id, kind, blob_id)
VALUES ($1, $2, $3)
ON CONFLICT (kind, blob_id) DO UPDATE
SET status = 'pending', attempts = 0, last_error = NULL, updated_at = now()
WHERE tasks.status IN ('done', 'failed');

-- name: ContentIndexStats :many
SELECT status, count(*)::bigint AS count, COALESCE(sum(octet_length(content)), 0)::bigint AS bytes
FROM blob_texts GROUP BY status;

-- name: CountBlobsNeedingText :one
SELECT count(*)::bigint FROM blobs b
LEFT JOIN blob_texts bt ON bt.blob_id = b.id
WHERE b.verified AND b.ref_count > 0 AND (bt.blob_id IS NULL OR bt.extractor < $1);

-- name: GetBlobText :one
SELECT * FROM blob_texts WHERE blob_id = $1;

-- name: SearchContent :many
-- 内容搜索(doc/20 三.2):GIN 词项取交集得候选,再按原文子串复核(大小写不敏感);
-- 摘要取第一处命中前 60 字到后 100 字。排序与文件名搜索相同:修改时间倒序、id 决胜。
SELECT n.id, n.parent_id, n.name, n.created_at, n.updated_at,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256,
       substr(bt.content, greatest(h.pos - 60, 1), least(h.pos, 61) - 1 + char_length(sqlc.arg(q)::text) + 100)::text AS snippet,
       greatest(h.pos - 60, 1)::int AS snippet_start,
       char_length(bt.content)::int AS content_len,
       h.pos::int AS pos,
       bt.page_starts
FROM blob_texts bt
JOIN nodes n ON n.blob_id = bt.blob_id
JOIN blobs b ON b.id = bt.blob_id
CROSS JOIN LATERAL (SELECT strpos(lower(bt.content), lower(sqlc.arg(q)::text)) AS pos) h
WHERE bt.terms @> sqlc.arg(terms)::text[]
  AND n.owner_id = sqlc.arg(owner_id) AND n.deleted_at IS NULL AND h.pos > 0
  AND (sqlc.narg(c_updated)::timestamptz IS NULL
       OR n.updated_at < sqlc.narg(c_updated)::timestamptz
       OR (n.updated_at = sqlc.narg(c_updated)::timestamptz AND n.id > sqlc.narg(c_id)::uuid))
ORDER BY n.updated_at DESC, n.id
LIMIT sqlc.arg(lim);

-- name: SearchContentInSubtree :many
-- 同上,范围限定在 root 的子树内(MCP 凭据限定了访问目录时)
WITH RECURSIVE sub AS (
    SELECT r.id FROM nodes r WHERE r.id = sqlc.arg(root) AND r.deleted_at IS NULL
    UNION ALL
    SELECT c.id FROM nodes c JOIN sub s ON c.parent_id = s.id
    WHERE c.deleted_at IS NULL
)
SELECT n.id, n.parent_id, n.name, n.created_at, n.updated_at,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256,
       substr(bt.content, greatest(h.pos - 60, 1), least(h.pos, 61) - 1 + char_length(sqlc.arg(q)::text) + 100)::text AS snippet,
       greatest(h.pos - 60, 1)::int AS snippet_start,
       char_length(bt.content)::int AS content_len,
       h.pos::int AS pos,
       bt.page_starts
FROM blob_texts bt
JOIN nodes n ON n.blob_id = bt.blob_id
JOIN blobs b ON b.id = bt.blob_id
CROSS JOIN LATERAL (SELECT strpos(lower(bt.content), lower(sqlc.arg(q)::text)) AS pos) h
WHERE bt.terms @> sqlc.arg(terms)::text[]
  AND n.id IN (SELECT s.id FROM sub s) AND n.owner_id = sqlc.arg(owner_id) AND h.pos > 0
  AND (sqlc.narg(c_updated)::timestamptz IS NULL
       OR n.updated_at < sqlc.narg(c_updated)::timestamptz
       OR (n.updated_at = sqlc.narg(c_updated)::timestamptz AND n.id > sqlc.narg(c_id)::uuid))
ORDER BY n.updated_at DESC, n.id
LIMIT sqlc.arg(lim);

-- name: ReadNodeText :one
-- 按字符偏移分段读一个文件提取出的文字(MCP read_document_text)
SELECT n.id, n.name, bt.status,
       substr(bt.content, sqlc.arg(off)::int + 1, sqlc.arg(len)::int)::text AS chunk,
       char_length(bt.content)::int AS total,
       bt.page_starts
FROM nodes n JOIN blob_texts bt ON bt.blob_id = n.blob_id
WHERE n.id = sqlc.arg(id) AND n.owner_id = sqlc.arg(owner_id) AND n.deleted_at IS NULL;
