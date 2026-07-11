-- +goose Up
-- 分享随节点彻底删除级联消失,否则 PurgeSubtree 撞外键
ALTER TABLE shares DROP CONSTRAINT shares_node_id_fkey;
ALTER TABLE shares ADD CONSTRAINT shares_node_id_fkey
    FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE CASCADE;

-- +goose Down
ALTER TABLE shares DROP CONSTRAINT shares_node_id_fkey;
ALTER TABLE shares ADD CONSTRAINT shares_node_id_fkey
    FOREIGN KEY (node_id) REFERENCES nodes(id);
