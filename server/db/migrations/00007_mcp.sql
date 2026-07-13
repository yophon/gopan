-- +goose Up
-- MCP 专用长期凭据。明文只在创建时返回，库里只存高熵 token 的 SHA-256。
CREATE TABLE mcp_tokens (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id),
    name         text NOT NULL,
    token_hash   text NOT NULL UNIQUE,
    scopes       text[] NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);
CREATE INDEX idx_mcp_tokens_user ON mcp_tokens (user_id) WHERE revoked_at IS NULL;

-- Agent 直传会话。与浏览器 upload_sessions 分开：这里允许无 sha256、单 PUT
-- 和 staging key，避免放宽现有内容寻址上传的不变量。
CREATE TABLE transfer_sessions (
    id                uuid PRIMARY KEY,
    owner_id          uuid NOT NULL REFERENCES users(id),
    target_parent     uuid,
    target_name       text NOT NULL,
    size              bigint NOT NULL,
    mime              text NOT NULL DEFAULT 'application/octet-stream',
    expected_sha256   text,
    computed_sha256   text,
    object_key        text NOT NULL UNIQUE,
    transport         text NOT NULL CHECK (transport IN ('single_put','multipart')),
    minio_upload_id   text,
    part_size         int NOT NULL DEFAULT 16777216,
    status            text NOT NULL DEFAULT 'uploading'
        CHECK (status IN ('uploading','finalizing','processing','verifying','ready','failed','aborted')),
    fail_reason       text,
    node_id           uuid REFERENCES nodes(id),
    idempotency_key   text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL
);
CREATE INDEX idx_transfer_sessions_cleanup ON transfer_sessions (expires_at)
    WHERE status IN ('uploading','finalizing','processing');
CREATE UNIQUE INDEX idx_transfer_sessions_idempotency
    ON transfer_sessions (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- +goose Down
DROP TABLE transfer_sessions;
DROP TABLE mcp_tokens;
