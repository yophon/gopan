-- +goose Up
-- 文件被彻删(手动彻删、清空回收站、过期清理)时,node_id 经 ON DELETE SET NULL 置空,而文件消息的 body 恒为 '',
-- 00015 加的 CHECK (body <> '' OR node_id IS NOT NULL) 会让整个彻删事务失败(过期清理还会卡住后面的用户)。
-- 按 v2.2 的设计,文件删了消息保留、客户端显示「文件已不存在」;空消息由服务层在写入时拒绝
-- (SendText 要求正文非空,文件消息必带 node_id),所以去掉这条约束即可。
ALTER TABLE chat_messages DROP CONSTRAINT chat_messages_body_or_node;

-- +goose Down
-- 约束加回去之前先删掉已经失去附件的文件消息,否则 ADD CONSTRAINT 会失败
DELETE FROM chat_messages WHERE body = '' AND node_id IS NULL;
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_body_or_node
    CHECK (body <> '' OR node_id IS NOT NULL);
