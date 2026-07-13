-- name: CreateOAuthClient :one
INSERT INTO oauth_clients (client_id, client_name, redirect_uris)
VALUES ($1, $2, $3)
RETURNING *;

-- name: GetOAuthClient :one
SELECT * FROM oauth_clients WHERE client_id = $1;

-- name: UpsertOAuthGrant :one
INSERT INTO oauth_grants (id, user_id, client_id, scopes)
VALUES ($1, $2, $3, $4)
ON CONFLICT (user_id, client_id) DO UPDATE
SET scopes = EXCLUDED.scopes, updated_at = now(), revoked_at = NULL
RETURNING *;

-- name: GetOAuthGrant :one
SELECT og.*, oc.client_name, oc.redirect_uris,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM oauth_grants og
JOIN oauth_clients oc ON oc.client_id = og.client_id
JOIN users u ON u.id = og.user_id
WHERE og.id = $1;

-- name: ListOAuthGrants :many
SELECT og.id, og.client_id, oc.client_name, og.scopes, og.created_at, og.updated_at
FROM oauth_grants og
JOIN oauth_clients oc ON oc.client_id = og.client_id
WHERE og.user_id = $1 AND og.revoked_at IS NULL
ORDER BY og.updated_at DESC;

-- name: RevokeOAuthGrant :execrows
UPDATE oauth_grants SET revoked_at = now(), updated_at = now()
WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL;

-- name: CreateOAuthAuthorizationCode :exec
INSERT INTO oauth_authorization_codes
    (code_hash, grant_id, client_id, redirect_uri, scopes, code_challenge, expires_at)
VALUES ($1, $2, $3, $4, $5, $6, $7);

-- name: ConsumeOAuthAuthorizationCode :one
DELETE FROM oauth_authorization_codes
WHERE code_hash = $1 AND expires_at > now()
RETURNING *;

-- name: CreateOAuthAccessToken :one
INSERT INTO oauth_access_tokens (id, grant_id, token_hash, scopes, expires_at)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;

-- name: GetOAuthAccessTokenByHash :one
SELECT oat.*, og.user_id, og.scopes AS grant_scopes, og.revoked_at AS grant_revoked_at,
       oc.client_name, u.username,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM oauth_access_tokens oat
JOIN oauth_grants og ON og.id = oat.grant_id
JOIN oauth_clients oc ON oc.client_id = og.client_id
JOIN users u ON u.id = og.user_id
WHERE oat.token_hash = $1 AND oat.expires_at > now();

-- name: TouchOAuthAccessToken :exec
UPDATE oauth_access_tokens SET last_used_at = now()
WHERE id = $1 AND (last_used_at IS NULL OR last_used_at < now() - interval '5 minutes');

-- name: CreateOAuthRefreshToken :one
INSERT INTO oauth_refresh_tokens (id, grant_id, token_hash, scopes, expires_at)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;

-- name: ConsumeOAuthRefreshToken :one
UPDATE oauth_refresh_tokens SET used_at = now()
WHERE token_hash = $1 AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now()
RETURNING *;
