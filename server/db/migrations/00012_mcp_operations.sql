-- +goose Up
CREATE TABLE mcp_operations (
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key text NOT NULL CHECK (octet_length(key) BETWEEN 1 AND 128),
    request_hash text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, key)
);

-- +goose Down
DROP TABLE mcp_operations;
