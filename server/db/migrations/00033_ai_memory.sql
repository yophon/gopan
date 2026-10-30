-- +goose Up
-- 助手的长期记忆(doc/38):tau 的记忆扩展把「文件」交给宿主存,gopan 存在这里。
-- 这一版每人只有一份「关于你」(USER.md);name 留着,以后有主题文件也放这张表。
CREATE TABLE ai_memory (
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       text NOT NULL,
    content    text NOT NULL DEFAULT '',
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, name)
);

-- +goose Down
DROP TABLE ai_memory;
