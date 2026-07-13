-- name: CreateUser :one
INSERT INTO users (id, username, password_hash, quota_bytes)
VALUES ($1, $2, $3, $4)
RETURNING *;

-- name: GetUserByUsername :one
SELECT * FROM users WHERE username = $1;

-- name: GetUserByID :one
SELECT * FROM users WHERE id = $1;

-- name: GetUserByIDForUpdate :one
SELECT * FROM users WHERE id = $1 FOR UPDATE;

-- name: UpdateUserPassword :exec
UPDATE users SET password_hash = $2 WHERE id = $1;

-- name: PromoteAdminByUsername :execrows
UPDATE users SET is_admin = true WHERE username = $1;

-- name: AdminListUsers :many
SELECT * FROM users ORDER BY created_at;

-- name: AdminSetUserQuota :one
UPDATE users SET quota_bytes = $2 WHERE id = $1 RETURNING *;

-- name: AdminSetUserDisabled :one
UPDATE users
SET disabled_at = CASE WHEN sqlc.arg(disabled)::boolean THEN now() ELSE NULL END
WHERE id = $1
RETURNING *;

-- name: AdminOverviewUsers :one
SELECT count(*) AS user_count, COALESCE(sum(used_bytes), 0)::bigint AS total_used
FROM users;

-- name: AdminOverviewBlobs :one
SELECT count(*) AS blob_count, COALESCE(sum(size), 0)::bigint AS blob_bytes
FROM blobs;
