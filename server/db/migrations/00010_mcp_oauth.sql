-- +goose Up
-- Remote MCP OAuth 2.1 public clients. Clients are dynamically registered and
-- authenticate with Authorization Code + PKCE, so no client secret is stored.
CREATE TABLE oauth_clients (
    client_id      text PRIMARY KEY,
    client_name    text NOT NULL,
    redirect_uris  text[] NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE oauth_grants (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id),
    client_id   text NOT NULL REFERENCES oauth_clients(client_id),
    scopes      text[] NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    UNIQUE (user_id, client_id)
);
CREATE INDEX idx_oauth_grants_user ON oauth_grants (user_id) WHERE revoked_at IS NULL;

CREATE TABLE oauth_authorization_codes (
    code_hash       text PRIMARY KEY,
    grant_id        uuid NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    client_id       text NOT NULL REFERENCES oauth_clients(client_id),
    redirect_uri    text NOT NULL,
    scopes          text[] NOT NULL,
    code_challenge  text NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_oauth_codes_expiry ON oauth_authorization_codes (expires_at);

CREATE TABLE oauth_access_tokens (
    id            uuid PRIMARY KEY,
    grant_id      uuid NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    token_hash    text NOT NULL UNIQUE,
    scopes        text[] NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz
);
CREATE INDEX idx_oauth_access_expiry ON oauth_access_tokens (expires_at);

CREATE TABLE oauth_refresh_tokens (
    id          uuid PRIMARY KEY,
    grant_id    uuid NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    token_hash  text NOT NULL UNIQUE,
    scopes      text[] NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_oauth_refresh_expiry ON oauth_refresh_tokens (expires_at)
    WHERE used_at IS NULL AND revoked_at IS NULL;

-- +goose Down
DROP TABLE oauth_refresh_tokens;
DROP TABLE oauth_access_tokens;
DROP TABLE oauth_authorization_codes;
DROP TABLE oauth_grants;
DROP TABLE oauth_clients;
