-- +goose Up
ALTER TABLE upload_sessions
    ADD COLUMN object_key text,
    ADD COLUMN node_id uuid REFERENCES nodes(id) ON DELETE SET NULL,
    ADD COLUMN fail_code text;

-- NULL object_key identifies pre-staging sessions. They must never be completed
-- by the new server: their presigned parts target a shared blob directly.
CREATE INDEX idx_uploads_completing ON upload_sessions (created_at)
    WHERE status = 'completing' AND object_key IS NOT NULL;

-- +goose Down
DROP INDEX idx_uploads_completing;
ALTER TABLE upload_sessions DROP COLUMN fail_code, DROP COLUMN node_id, DROP COLUMN object_key;
