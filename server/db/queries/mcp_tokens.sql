-- name: CreateMCPToken :one
INSERT INTO mcp_tokens (id, user_id, name, token_hash, scopes)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;

-- name: GetMCPTokenByHash :one
SELECT mt.*, u.username, (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM mcp_tokens mt JOIN users u ON u.id = mt.user_id
WHERE mt.token_hash = $1 AND mt.revoked_at IS NULL;

-- name: ListMCPTokens :many
SELECT * FROM mcp_tokens
WHERE user_id = $1 AND revoked_at IS NULL
ORDER BY created_at DESC;

-- name: RevokeMCPToken :execrows
UPDATE mcp_tokens SET revoked_at = now()
WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL;

-- name: TouchMCPToken :exec
UPDATE mcp_tokens SET last_used_at = now()
WHERE id = $1 AND (last_used_at IS NULL OR last_used_at < now() - interval '5 minutes');
