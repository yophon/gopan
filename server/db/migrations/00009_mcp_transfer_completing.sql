-- +goose Up
-- multipart 合并需要单独状态，进程若在 MinIO 合并后崩溃，重试可通过 Stat 恢复。
ALTER TABLE transfer_sessions DROP CONSTRAINT transfer_sessions_status_check;
ALTER TABLE transfer_sessions ADD CONSTRAINT transfer_sessions_status_check
    CHECK (status IN ('uploading','completing','finalizing','processing','verifying','ready','failed','aborted'));
DROP INDEX idx_transfer_sessions_cleanup;
CREATE INDEX idx_transfer_sessions_cleanup ON transfer_sessions (expires_at)
    WHERE status IN ('uploading','completing','finalizing','processing');

-- +goose Down
ALTER TABLE transfer_sessions DROP CONSTRAINT transfer_sessions_status_check;
ALTER TABLE transfer_sessions ADD CONSTRAINT transfer_sessions_status_check
    CHECK (status IN ('uploading','finalizing','processing','verifying','ready','failed','aborted'));
DROP INDEX idx_transfer_sessions_cleanup;
CREATE INDEX idx_transfer_sessions_cleanup ON transfer_sessions (expires_at)
    WHERE status IN ('uploading','finalizing','processing');
