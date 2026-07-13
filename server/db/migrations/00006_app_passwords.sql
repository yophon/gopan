-- +goose Up
-- WebDAV 应用密码:每设备一条,随机 token 只展示一次、存 sha256(高熵 token 无需慢哈希)
CREATE TABLE app_passwords (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id),
    name         text NOT NULL,
    token_hash   text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
CREATE INDEX idx_app_passwords_user ON app_passwords (user_id);

-- +goose Down
DROP TABLE app_passwords;
