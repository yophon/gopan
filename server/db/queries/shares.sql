-- name: CreateShare :one
INSERT INTO shares (id, token, node_id, created_by, password_hash, expires_at)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING *;

-- name: GetShareByToken :one
SELECT s.*, n.name AS node_name, n.kind AS node_kind, n.deleted_at AS node_deleted_at
FROM shares s JOIN nodes n ON n.id = s.node_id
WHERE s.token = $1;

-- name: GetShareByID :one
SELECT s.*, n.name AS node_name, n.kind AS node_kind, n.deleted_at AS node_deleted_at
FROM shares s JOIN nodes n ON n.id = s.node_id
WHERE s.id = $1;

-- name: ListMyShares :many
SELECT s.id, s.token, s.password_hash, s.expires_at, s.created_at,
       n.id AS node_id, n.parent_id AS node_parent_id, n.name AS node_name,
       n.kind AS node_kind, n.created_at AS node_created_at, n.updated_at AS node_updated_at,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM shares s
JOIN nodes n ON n.id = s.node_id
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE s.created_by = $1 AND s.revoked_at IS NULL AND n.deleted_at IS NULL
ORDER BY s.created_at DESC, s.id;

-- name: RevokeShare :execrows
UPDATE shares SET revoked_at = now()
WHERE id = $1 AND created_by = $2 AND revoked_at IS NULL;
