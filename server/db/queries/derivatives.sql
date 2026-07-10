-- name: UpsertDerivative :exec
INSERT INTO derivatives (blob_id, kind, minio_key, size)
VALUES ($1, $2, $3, $4)
ON CONFLICT (blob_id, kind) DO UPDATE SET minio_key = EXCLUDED.minio_key, size = EXCLUDED.size;

-- name: GetDerivatives :many
SELECT * FROM derivatives WHERE blob_id = $1;

-- name: GetTask :one
SELECT * FROM tasks WHERE kind = $1 AND blob_id = $2;

-- name: SetBlobMediaMeta :exec
UPDATE blobs SET media_meta = $2 WHERE id = $1;
