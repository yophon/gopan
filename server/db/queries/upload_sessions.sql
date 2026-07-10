-- name: CreateUploadSession :one
INSERT INTO upload_sessions (id, owner_id, sha256, size, target_parent, target_name, minio_upload_id, part_size, expires_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING *;

-- name: GetUploadSession :one
SELECT * FROM upload_sessions WHERE id = $1 AND owner_id = $2;

-- name: SetUploadSessionStatus :exec
UPDATE upload_sessions SET status = $3, fail_reason = $4
WHERE id = $1 AND owner_id = $2;

-- name: CountActiveSessions :one
SELECT count(*) FROM upload_sessions
WHERE owner_id = $1 AND status IN ('uploading','completing','verifying');

-- name: ListExpiredSessions :many
SELECT * FROM upload_sessions
WHERE status = 'uploading' AND expires_at < now()
LIMIT 100;

-- name: MarkSessionsVerifyResult :exec
-- hash 校验结束后,把该 blob 上所有 verifying 会话标成终态
UPDATE upload_sessions SET status = $2, fail_reason = $3
WHERE sha256 = $1 AND status = 'verifying';
