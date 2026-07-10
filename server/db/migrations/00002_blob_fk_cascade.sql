-- +goose Up
-- blob 被回收(hash 校验失败/GC)时,任务与派生物记录应随之消失,不应反过来挡住删除
ALTER TABLE tasks
    DROP CONSTRAINT tasks_blob_id_fkey,
    ADD CONSTRAINT tasks_blob_id_fkey
        FOREIGN KEY (blob_id) REFERENCES blobs(id) ON DELETE CASCADE;
ALTER TABLE derivatives
    DROP CONSTRAINT derivatives_blob_id_fkey,
    ADD CONSTRAINT derivatives_blob_id_fkey
        FOREIGN KEY (blob_id) REFERENCES blobs(id) ON DELETE CASCADE;

-- +goose Down
ALTER TABLE tasks
    DROP CONSTRAINT tasks_blob_id_fkey,
    ADD CONSTRAINT tasks_blob_id_fkey
        FOREIGN KEY (blob_id) REFERENCES blobs(id);
ALTER TABLE derivatives
    DROP CONSTRAINT derivatives_blob_id_fkey,
    ADD CONSTRAINT derivatives_blob_id_fkey
        FOREIGN KEY (blob_id) REFERENCES blobs(id);
