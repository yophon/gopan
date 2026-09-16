-- +goose Up
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_body_or_node
    CHECK (body <> '' OR node_id IS NOT NULL);

ALTER TABLE users ADD COLUMN chat_folder_id uuid REFERENCES nodes(id) ON DELETE SET NULL;

-- +goose Down
ALTER TABLE users DROP COLUMN chat_folder_id;
ALTER TABLE chat_messages DROP CONSTRAINT chat_messages_body_or_node;