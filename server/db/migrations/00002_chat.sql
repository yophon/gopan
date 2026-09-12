-- +goose Up
CREATE TABLE chat_messages (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body text NOT NULL DEFAULT '',
    node_id uuid REFERENCES nodes(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_messages_owner_created ON chat_messages(owner_id, created_at DESC, id DESC);
-- +goose Down
DROP TABLE chat_messages;
