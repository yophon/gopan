-- name: CreateUploadSession :one
INSERT INTO upload_sessions (id, owner_id, sha256, size, target_parent, target_name, minio_upload_id, part_size, expires_at, object_key)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
RETURNING *;

-- name: GetUploadSession :one
SELECT * FROM upload_sessions WHERE id = $1 AND owner_id = $2;

-- name: SetUploadSessionStatus :exec
UPDATE upload_sessions SET status = $3, fail_reason = $4
WHERE id = $1 AND owner_id = $2;

-- name: CountActiveSessions :one
-- 收集链接、分享上访客开的会话不占属主自己的名额(另有每链接 / 每分享的上限)
SELECT count(*) FROM upload_sessions
WHERE owner_id = $1 AND request_id IS NULL AND share_id IS NULL AND status IN ('uploading','completing','verifying');

-- name: ListExpiredSessions :many
SELECT * FROM upload_sessions
WHERE (status IN ('uploading', 'completing') OR object_key IS NOT NULL) AND expires_at < now()
LIMIT 100;

-- name: GetUploadSessionForUpdate :one
SELECT * FROM upload_sessions WHERE id = $1 AND owner_id = $2 FOR UPDATE;

-- name: TryLockUploadSession :one
SELECT * FROM upload_sessions WHERE id = $1 AND owner_id = $2 FOR UPDATE NOWAIT;

-- name: ClaimCompletingUpload :one
SELECT * FROM upload_sessions
WHERE status = 'completing' AND object_key IS NOT NULL AND expires_at > now()
ORDER BY created_at
LIMIT 1 FOR UPDATE SKIP LOCKED;

-- name: FinishUploadSession :exec
UPDATE upload_sessions SET status = 'done', node_id = $2, fail_reason = NULL, fail_code = NULL
WHERE id = $1;

-- name: FailUploadSession :exec
UPDATE upload_sessions SET status = 'failed', fail_code = $2, fail_reason = $3
WHERE id = $1;

-- name: ClearUploadObjectKey :exec
UPDATE upload_sessions SET object_key = NULL WHERE id = $1;

-- name: MarkSessionsVerifyResult :exec
-- hash 校验结束后,把该 blob 上所有 verifying 会话标成终态
UPDATE upload_sessions SET status = $2, fail_reason = $3
WHERE sha256 = $1 AND status = 'verifying';

-- name: SetUploadSessionReplace :exec
-- 上传选了「替换」:定稿时覆盖目标位置的同名文件(doc/24)
UPDATE upload_sessions SET replace_existing = true WHERE id = $1 AND owner_id = $2;
