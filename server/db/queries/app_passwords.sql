-- name: CreateAppPassword :one
INSERT INTO app_passwords (id, user_id, name, token_hash)
VALUES ($1, $2, $3, $4)
RETURNING *;

-- name: GetAppPasswordByHash :one
-- 认证主路径:带出属主的禁用状态与用户名,一次查询定乾坤
SELECT ap.*, u.username, (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM app_passwords ap JOIN users u ON u.id = ap.user_id
WHERE ap.token_hash = $1;

-- name: ListAppPasswords :many
SELECT * FROM app_passwords WHERE user_id = $1 ORDER BY created_at DESC;

-- name: DeleteAppPassword :execrows
DELETE FROM app_passwords WHERE id = $1 AND user_id = $2;

-- name: TouchAppPassword :exec
-- last_used_at 五分钟才写一次,别让 rclone 的请求风暴变成写风暴
UPDATE app_passwords SET last_used_at = now()
WHERE id = $1 AND (last_used_at IS NULL OR last_used_at < now() - interval '5 minutes');
