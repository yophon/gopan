-- +goose Up
-- 动态与撤回(doc/23 二)、文件列表的自然顺序(doc/23 三.3)。
--
-- activities:谁(actor_*)在什么时候对哪个节点做了什么。一次请求 / 一次 Agent 工具调用 / 一个后台任务
-- 共用一个 batch_id,整批撤回按它来。node_id 不设外键:节点彻底删除后记录还要能看。
-- 同一次操作涉及一整棵子树时只记顶层节点,子树的文件数与字节在 detail 里。
CREATE TABLE activities (
    id          bigserial PRIMARY KEY,
    owner_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at          timestamptz NOT NULL DEFAULT now(),
    actor_kind  text NOT NULL,
    actor_ref   text NOT NULL DEFAULT '',
    actor_label text NOT NULL DEFAULT '',
    batch_id    uuid NOT NULL,
    action      text NOT NULL,
    node_id     uuid,
    node_kind   text NOT NULL DEFAULT '',
    name        text NOT NULL DEFAULT '',
    parent_path text NOT NULL DEFAULT '',
    detail      jsonb NOT NULL DEFAULT '{}',
    undone_at   timestamptz
);
CREATE INDEX idx_activities_owner ON activities (owner_id, id DESC);
CREATE INDEX idx_activities_actor ON activities (owner_id, actor_kind, actor_ref, id DESC);
CREATE INDEX idx_activities_node ON activities (node_id, id DESC) WHERE node_id IS NOT NULL;
CREATE INDEX idx_activities_batch ON activities (owner_id, batch_id);
CREATE INDEX idx_activities_at ON activities (at);

-- 浏览器上传与 Agent 上传在 worker 里定稿,开会话时把来源记下来
ALTER TABLE upload_sessions ADD COLUMN actor jsonb NOT NULL DEFAULT '{}';
ALTER TABLE transfer_sessions ADD COLUMN actor jsonb NOT NULL DEFAULT '{}';

-- 自然顺序:数字按数值比(第2集在第10集前);英文在前、不分大小写,中文在后按拼音(与 Windows 资源管理器一致)。
-- 没有 ICU 的 Postgres 回退成字节序(与原来一样)。
-- +goose StatementBegin
DO $$
BEGIN
    CREATE COLLATION IF NOT EXISTS natural_sort (provider = icu, locale = 'zh-u-kn-kr-latn-hani');
EXCEPTION WHEN OTHERS THEN
    CREATE COLLATION IF NOT EXISTS natural_sort FROM "C";
END
$$;
-- +goose StatementEnd

CREATE INDEX idx_nodes_children_natural ON nodes (parent_id, kind DESC, (name COLLATE natural_sort), id)
    WHERE deleted_at IS NULL;
DROP INDEX idx_nodes_children_name;

-- 空间分析的文件分类(doc/23 三.1):先看 MIME 大类,文档与压缩包按扩展名认(它们的 MIME 五花八门)
-- +goose StatementBegin
CREATE FUNCTION gopan_file_category(name text, mime text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN mime LIKE 'image/%' THEN 'image'
        WHEN mime LIKE 'video/%' THEN 'video'
        WHEN mime LIKE 'audio/%' THEN 'audio'
        WHEN name ~* '\.(pdf|docx?|xlsx?|pptx?|od[tsp]|rtf|epub|mobi|azw3|pages|numbers|key)$' THEN 'document'
        WHEN name ~* '\.(zip|7z|rar|tar|gz|tgz|bz2|tbz2?|xz|txz|zst|iso|dmg|apk|exe|msi|deb|rpm)$' THEN 'archive'
        WHEN mime LIKE 'text/%'
          OR name ~* '\.(md|markdown|json|xml|ya?ml|toml|csv|log|ini|conf|sh|py|go|js|ts|tsx|vue|java|kt|c|cc|cpp|h|hpp|rs|rb|php|css|html?|sql|srt|ass|vtt|lrc)$'
          THEN 'text'
        ELSE 'other'
    END
$$;
-- +goose StatementEnd

-- +goose Down
DROP FUNCTION IF EXISTS gopan_file_category(text, text);
CREATE INDEX idx_nodes_children_name ON nodes (parent_id, kind DESC, name, id)
    WHERE deleted_at IS NULL;
DROP INDEX idx_nodes_children_natural;
DROP COLLATION IF EXISTS natural_sort;
ALTER TABLE transfer_sessions DROP COLUMN actor;
ALTER TABLE upload_sessions DROP COLUMN actor;
DROP TABLE activities;
