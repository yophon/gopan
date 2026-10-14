-- +goose Up
-- 消息来源:web(浏览器)/ mcp(Agent)/ webdav。只是展示用的标记,不参与权限。
ALTER TABLE chat_messages ADD COLUMN source text NOT NULL DEFAULT 'web';

-- +goose Down
ALTER TABLE chat_messages DROP COLUMN source;
