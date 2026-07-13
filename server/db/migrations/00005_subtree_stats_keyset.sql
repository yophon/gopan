-- +goose Up
-- 子树统计:仅 folder 有意义;stale 由写路径标记、worker 异步重算,最终一致
ALTER TABLE nodes ADD COLUMN subtree_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE nodes ADD COLUMN subtree_count bigint NOT NULL DEFAULT 0;
ALTER TABLE nodes ADD COLUMN stats_stale boolean NOT NULL DEFAULT true;
CREATE INDEX idx_nodes_stats_stale ON nodes (id) WHERE kind = 'folder' AND stats_stale;

-- keyset 分页的默认排序(folders-first + NAME 升序)覆盖索引
CREATE INDEX idx_nodes_children_name ON nodes (parent_id, kind DESC, name, id)
    WHERE deleted_at IS NULL;

-- +goose Down
DROP INDEX idx_nodes_children_name;
DROP INDEX idx_nodes_stats_stale;
ALTER TABLE nodes DROP COLUMN stats_stale;
ALTER TABLE nodes DROP COLUMN subtree_count;
ALTER TABLE nodes DROP COLUMN subtree_bytes;
