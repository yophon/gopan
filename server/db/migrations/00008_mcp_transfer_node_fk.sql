-- +goose Up
-- 传输会话是审计记录，不能反向阻止用户永久删除文件。
ALTER TABLE transfer_sessions DROP CONSTRAINT transfer_sessions_node_id_fkey;
ALTER TABLE transfer_sessions
    ADD CONSTRAINT transfer_sessions_node_id_fkey
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE SET NULL;

-- +goose Down
ALTER TABLE transfer_sessions DROP CONSTRAINT transfer_sessions_node_id_fkey;
ALTER TABLE transfer_sessions
    ADD CONSTRAINT transfer_sessions_node_id_fkey
    FOREIGN KEY (node_id) REFERENCES nodes(id);
