-- +goose Up
-- 离线下载与压缩包(doc/21)。
--
-- user_jobs:某个用户发起的一次长活(离线下载一个链接 / 解压一个压缩包),与按 blob 的 tasks 分开:
-- 有属主、有进度、能取消。目标文件夹被彻底删除时任务一并删除;parent_id 为空 = 根目录。
CREATE TABLE user_jobs (
    id             uuid PRIMARY KEY,
    owner_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind           text NOT NULL CHECK (kind IN ('fetch_url', 'extract_archive')),
    status         text NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'done', 'failed', 'canceled')),
    params         jsonb NOT NULL DEFAULT '{}',
    parent_id      uuid REFERENCES nodes(id) ON DELETE CASCADE,
    result_node_id uuid REFERENCES nodes(id) ON DELETE SET NULL,
    title          text NOT NULL DEFAULT '',  -- 列表上显示的名字:文件名,未知时是链接主机名
    bytes_done     bigint NOT NULL DEFAULT 0,
    bytes_total    bigint,                    -- 未知为空
    files_done     int NOT NULL DEFAULT 0,
    files_total    int,
    files_skipped  int NOT NULL DEFAULT 0,
    error_code     text,
    error_message  text,
    attempts       int NOT NULL DEFAULT 0,
    source         text NOT NULL DEFAULT 'web',
    heartbeat_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz
);
CREATE INDEX idx_user_jobs_owner ON user_jobs (owner_id, created_at DESC, id DESC);
CREATE INDEX idx_user_jobs_queued ON user_jobs (created_at) WHERE status = 'queued';
CREATE INDEX idx_user_jobs_running ON user_jobs (heartbeat_at) WHERE status = 'running';

-- 压缩包目录索引:每份内容(blob)建一次,所有引用它的文件共享;blob 回收时级联删除。
CREATE TABLE blob_archives (
    blob_id     uuid PRIMARY KEY REFERENCES blobs(id) ON DELETE CASCADE,
    format      text NOT NULL DEFAULT '',  -- zip / 7z / rar / tar / tar.gz / tar.bz2 / tar.xz
    status      text NOT NULL CHECK (status IN ('done', 'truncated', 'unsupported', 'failed')),
    solid       boolean NOT NULL DEFAULT false,
    encrypted   boolean NOT NULL DEFAULT false,
    entry_count int NOT NULL DEFAULT 0,
    file_count  int NOT NULL DEFAULT 0,
    total_size  bigint NOT NULL DEFAULT 0,     -- 解压后总大小(按条目声明)
    indexer     int NOT NULL,                  -- 索引器版本,升级后逐步重建
    error       text,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- idx 是行号(浏览、预览地址用);src 是读取库里的原生序号,补出来的目录行为 -1。
-- parent / name 按 "/" 切开的路径,根目录的 parent 为 ''。
-- flag 非空的不能预览、不能解压:encrypted / unsafe(路径带 ..)/ link(软硬链接、设备)/ unsupported。
-- cost 读到这个条目之前要先解掉的字节数(固实包、压缩 tar),预览按它判断够不够浅。
CREATE TABLE archive_entries (
    blob_id     uuid NOT NULL REFERENCES blob_archives(blob_id) ON DELETE CASCADE,
    idx         int NOT NULL,
    src         int NOT NULL,
    path        text NOT NULL,
    parent      text NOT NULL,
    name        text NOT NULL,
    is_dir      boolean NOT NULL,
    size        bigint NOT NULL DEFAULT 0,
    packed_size bigint NOT NULL DEFAULT 0,
    mtime       timestamptz,
    flag        text NOT NULL DEFAULT '',
    cost        bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (blob_id, idx)
);
CREATE INDEX idx_archive_entries_dir ON archive_entries (blob_id, parent, is_dir DESC, name, idx);

ALTER TABLE tasks DROP CONSTRAINT tasks_kind_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_kind_check
    CHECK (kind IN ('verify_hash','thumb','video_cover','office_pdf','media_probe','extract_text','archive_index'));

-- +goose Down
DELETE FROM tasks WHERE kind = 'archive_index';
ALTER TABLE tasks DROP CONSTRAINT tasks_kind_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_kind_check
    CHECK (kind IN ('verify_hash','thumb','video_cover','office_pdf','media_probe','extract_text'));
DROP TABLE archive_entries;
DROP TABLE blob_archives;
DROP TABLE user_jobs;
