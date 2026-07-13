-- name: CreateTransferSession :one
INSERT INTO transfer_sessions (
    id, owner_id, target_parent, target_name, size, mime, expected_sha256,
    object_key, transport, minio_upload_id, part_size, idempotency_key, expires_at
)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
RETURNING *;

-- name: GetTransferSession :one
SELECT * FROM transfer_sessions WHERE id = $1 AND owner_id = $2;

-- name: GetTransferSessionByIdempotencyKey :one
SELECT * FROM transfer_sessions
WHERE owner_id = $1 AND idempotency_key = $2;

-- name: CountActiveTransferSessions :one
SELECT count(*) FROM transfer_sessions
WHERE owner_id = $1 AND status IN ('uploading','completing','finalizing','processing','verifying');

-- name: SumActiveTransferBytes :one
SELECT COALESCE(sum(size), 0)::bigint FROM transfer_sessions
WHERE owner_id = $1 AND status IN ('uploading','completing','finalizing','processing','verifying');

-- name: MarkTransferCompleting :one
UPDATE transfer_sessions SET status = 'completing', fail_reason = NULL
WHERE id = $1 AND owner_id = $2 AND status = 'uploading'
RETURNING *;

-- name: ResetTransferUploading :exec
UPDATE transfer_sessions SET status = 'uploading'
WHERE id = $1 AND owner_id = $2 AND status = 'completing';

-- name: MarkTransferFinalizing :one
UPDATE transfer_sessions SET status = 'finalizing', fail_reason = NULL
WHERE id = $1 AND owner_id = $2 AND status IN ('uploading','completing')
RETURNING *;

-- name: ClaimFinalizingTransfer :one
UPDATE transfer_sessions SET status = 'processing'
WHERE id = (
    SELECT id FROM transfer_sessions
    WHERE status = 'finalizing'
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING *;

-- name: MarkTransferVerifying :exec
UPDATE transfer_sessions
SET status = 'verifying', computed_sha256 = $2, node_id = $3
WHERE id = $1;

-- name: MarkTransferReadyBySha :exec
UPDATE transfer_sessions SET status = 'ready', fail_reason = NULL
WHERE computed_sha256 = $1 AND status = 'verifying';

-- name: MarkTransferReady :exec
UPDATE transfer_sessions SET status = 'ready', fail_reason = NULL
WHERE id = $1;

-- name: MarkTransfersFailedBySha :exec
UPDATE transfer_sessions SET status = 'failed', fail_reason = $2
WHERE computed_sha256 = $1 AND status = 'verifying';

-- name: MarkTransferFailed :exec
UPDATE transfer_sessions SET status = 'failed', fail_reason = $2
WHERE id = $1;

-- name: MarkTransferAborted :exec
UPDATE transfer_sessions SET status = 'aborted', fail_reason = $2
WHERE id = $1 AND owner_id = $3;

-- name: ListExpiredTransferSessions :many
SELECT * FROM transfer_sessions
WHERE status IN ('uploading','completing','finalizing','processing') AND expires_at < now()
LIMIT 100;
