-- +goose Up
-- 助手(doc/33):模型服务与 key、用量、助手的权限、对话记录。

-- 模型服务:owner_id 为空是站点提供的(管理员配,大家用,只能经服务器转发);否则是个人自己的。
-- key 用 AES-GCM 加密(密钥由 JWT 密钥派生),只写不读;直连时只把自己的 key 下发给自己。
CREATE TABLE ai_providers (
    id           uuid PRIMARY KEY,
    owner_id     uuid REFERENCES users(id) ON DELETE CASCADE,
    name         text NOT NULL,
    kind         text NOT NULL CHECK (kind IN ('openai', 'anthropic', 'google', 'openai-responses')),
    base_url     text NOT NULL,
    models       text[] NOT NULL DEFAULT '{}',
    key_enc      bytea,
    daily_tokens bigint NOT NULL DEFAULT 0, -- 站点提供的:每人每天的 token 上限,0 = 不限
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_providers_owner ON ai_providers (owner_id, created_at);

-- 经服务器转发的用量,按天;站点额度按它判
CREATE TABLE ai_usage (
    user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_id   uuid NOT NULL REFERENCES ai_providers(id) ON DELETE CASCADE,
    day           date NOT NULL,
    requests      integer NOT NULL DEFAULT 0,
    input_tokens  bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, provider_id, day)
);

-- 助手的权限:按 scope 三态(allow / ask / deny),没写的按默认;没有这行就全按默认
CREATE TABLE assistant_access (
    user_id    uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    disabled   boolean NOT NULL DEFAULT false,
    policy     jsonb NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 助手也能像别的凭证一样限定目录(credential_id = 用户 id)
ALTER TABLE mcp_access_roots DROP CONSTRAINT mcp_access_roots_credential_type_check;
ALTER TABLE mcp_access_roots ADD CONSTRAINT mcp_access_roots_credential_type_check
    CHECK (credential_type IN ('api_key', 'oauth', 'assistant'));

-- 对话记录:tau 的会话是一条条追加的事件,原样按序号存;seq 从 0 起连续
CREATE TABLE ai_conversations (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       text NOT NULL DEFAULT '',
    provider_id uuid REFERENCES ai_providers(id) ON DELETE SET NULL,
    model       text NOT NULL DEFAULT '',
    entries     integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_conversations_user ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_entries (
    conversation_id uuid NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    seq             integer NOT NULL,
    entry           jsonb NOT NULL,
    PRIMARY KEY (conversation_id, seq)
);

-- +goose Down
DROP TABLE ai_entries;
DROP TABLE ai_conversations;
DELETE FROM mcp_access_roots WHERE credential_type = 'assistant';
ALTER TABLE mcp_access_roots DROP CONSTRAINT mcp_access_roots_credential_type_check;
ALTER TABLE mcp_access_roots ADD CONSTRAINT mcp_access_roots_credential_type_check
    CHECK (credential_type IN ('api_key', 'oauth'));
DROP TABLE assistant_access;
DROP TABLE ai_usage;
DROP TABLE ai_providers;
