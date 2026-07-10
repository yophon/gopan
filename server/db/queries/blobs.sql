-- name: GetBlobBySha256 :one
SELECT * FROM blobs WHERE sha256 = $1;

-- name: GetBlob :one
SELECT * FROM blobs WHERE id = $1;

-- name: UpsertBlob :one
-- 并发同 hash 上传竞争时收敛到同一行
INSERT INTO blobs (id, sha256, size, mime)
VALUES ($1, $2, $3, $4)
ON CONFLICT (sha256) DO UPDATE SET sha256 = EXCLUDED.sha256
RETURNING *;

-- name: IncrementBlobRef :exec
UPDATE blobs SET ref_count = ref_count + 1, deref_at = NULL WHERE id = $1;

-- name: MarkBlobVerified :exec
UPDATE blobs SET verified = true WHERE id = $1;

-- name: DeleteBlob :exec
DELETE FROM blobs WHERE id = $1;

-- name: ListNodesByBlob :many
SELECT * FROM nodes WHERE blob_id = $1;

-- name: ListGCableBlobs :many
SELECT * FROM blobs
WHERE ref_count = 0 AND deref_at IS NOT NULL AND deref_at < now() - interval '24 hours'
LIMIT 100;

-- name: SumBlobSizes :one
SELECT COALESCE(sum(size), 0)::bigint AS total FROM blobs WHERE id = ANY($1::uuid[]);

-- name: AddUsedBytes :exec
UPDATE users SET used_bytes = used_bytes + $2 WHERE id = $1;

-- name: GetBlobSizes :many
SELECT id, size FROM blobs WHERE id = ANY($1::uuid[]);
