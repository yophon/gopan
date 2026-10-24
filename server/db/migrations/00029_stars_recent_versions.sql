-- +goose Up
-- 收藏、最近打开与历史版本(doc/24)。
--
-- starred_at:收藏的时刻,NULL = 没收藏。opened_at:最后一次在网页 / App 里打开(预览、播放、下载)的时刻,
-- 由客户端显式上报(markOpened),WebDAV / Agent 读文件不算。节点进回收站时两者都留着,还原后照旧。
ALTER TABLE nodes ADD COLUMN starred_at timestamptz;
ALTER TABLE nodes ADD COLUMN opened_at timestamptz;
CREATE INDEX idx_nodes_starred ON nodes (owner_id, starred_at DESC, id)
    WHERE starred_at IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_nodes_opened ON nodes (owner_id, opened_at DESC, id)
    WHERE opened_at IS NOT NULL AND deleted_at IS NULL;

-- node_versions:文件被同名覆盖时留下的旧内容。每条持有 blob 一个引用、计入属主的 used_bytes,
-- 与节点本身同一套记账;删版本 / 节点彻底删除 / 到期清理时退引用退配额。
-- size 抄自 blob(内容寻址,大小不会变),退配额时不必再连表。
-- saved_at:这份内容当初写进来的时刻(覆盖前节点的 updated_at);replaced_at:被换下来的时刻,到期按它算。
-- 节点被彻底删除时,服务层先删它的版本并退账(PurgeSubtreeVersions),CASCADE 只是兜底。
CREATE TABLE node_versions (
    id          uuid PRIMARY KEY,
    node_id     uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    owner_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blob_id     uuid NOT NULL REFERENCES blobs(id),
    size        bigint NOT NULL,
    saved_at    timestamptz NOT NULL,
    replaced_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_node_versions_node ON node_versions (node_id, replaced_at DESC);
CREATE INDEX idx_node_versions_owner ON node_versions (owner_id);
CREATE INDEX idx_node_versions_replaced ON node_versions (replaced_at);
CREATE INDEX idx_node_versions_blob ON node_versions (blob_id);

-- 网页 / App 上传选了「替换」:定稿时目标位置有同名文件就覆盖它(旧内容进历史版本),而不是自动改名
ALTER TABLE upload_sessions ADD COLUMN replace_existing boolean NOT NULL DEFAULT false;

-- +goose Down
ALTER TABLE upload_sessions DROP COLUMN replace_existing;
DROP TABLE node_versions;
DROP INDEX idx_nodes_opened;
DROP INDEX idx_nodes_starred;
ALTER TABLE nodes DROP COLUMN opened_at;
ALTER TABLE nodes DROP COLUMN starred_at;
