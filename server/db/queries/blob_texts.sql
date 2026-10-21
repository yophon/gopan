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
