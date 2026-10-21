-- +goose Up
-- 收集文件(doc/20 第二节):属主对一个文件夹开收集链接,访客不登录往里传。
--
-- 刻意不复用 shares:分享的访客令牌能读整棵子树,收集链接只写不读。
-- 链接只吊销不删行;目标文件夹被彻删时随之级联删除(与分享一致)。
CREATE TABLE file_requests (
    id                uuid PRIMARY KEY,
    token             text NOT NULL UNIQUE,
    owner_id          uuid NOT NULL REFERENCES users(id),
    folder_id         uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    title             text NOT NULL,
    note              text NOT NULL DEFAULT '',
    password_hash     text,
    require_name      boolean NOT NULL DEFAULT false,
    group_by_uploader boolean NOT NULL DEFAULT false,
    -- 上限,NULL = 不限
    max_file_size     bigint,
    max_total_bytes   bigint,
    max_files         int,
    expires_at        timestamptz,
    revoked_at        timestamptz,
    -- 属主上次查看收件记录的时刻,之后送达的算"新收到"
    seen_at           timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_requests_owner ON file_requests (owner_id, created_at DESC) WHERE revoked_at IS NULL;

-- 每次访客打开链接(验过密码、填过名字)记一行,令牌里带着它的 id。
-- 上传会话与收件记录靠它关联到"是谁传的";收件记录另存名字快照,这里可以定期清掉。
CREATE TABLE file_request_visitors (
    id         uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES file_requests(id) ON DELETE CASCADE,
    name       text NOT NULL DEFAULT '',
    ip         text,
    user_agent text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_request_visitors_request ON file_request_visitors (request_id);
CREATE INDEX idx_file_request_visitors_gc ON file_request_visitors (created_at);

-- 收件记录:每个送达的文件一行。个数与总量上限按它算,文件删了记录还在、不退额度。
CREATE TABLE file_request_uploads (
    id            bigserial PRIMARY KEY,
    request_id    uuid NOT NULL REFERENCES file_requests(id) ON DELETE CASCADE,
    visitor_id    uuid REFERENCES file_request_visitors(id) ON DELETE SET NULL,
    node_id       uuid REFERENCES nodes(id) ON DELETE SET NULL,
    file_name     text NOT NULL,
    size          bigint NOT NULL,
    uploader_name text NOT NULL DEFAULT '',
    ip            text,
    user_agent    text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_request_uploads_request ON file_request_uploads (request_id, id DESC);
CREATE INDEX idx_file_request_uploads_visitor ON file_request_uploads (visitor_id);

-- 访客的上传沿用浏览器分片会话(doc/15 的暂存隔离与异步落库)。
-- 落库在 worker 里做,请求上下文到不了那里,所以链接与访客跟着会话行走。
-- 访客不传 sha256(没有秒传,见 doc/20 二.4),落库时由服务端算出再回填。
ALTER TABLE upload_sessions
    ALTER COLUMN sha256 DROP NOT NULL,
    ADD COLUMN request_id uuid REFERENCES file_requests(id) ON DELETE SET NULL,
    ADD COLUMN visitor_id uuid REFERENCES file_request_visitors(id) ON DELETE SET NULL;
CREATE INDEX idx_uploads_request ON upload_sessions (request_id)
    WHERE request_id IS NOT NULL AND status IN ('uploading', 'completing');

-- +goose Down
DROP INDEX idx_uploads_request;
-- 回填前没算出 sha 的收集会话无法恢复 NOT NULL,一并删掉(都是未完成的访客上传)
DELETE FROM upload_sessions WHERE sha256 IS NULL;
ALTER TABLE upload_sessions
    DROP COLUMN visitor_id,
    DROP COLUMN request_id,
    ALTER COLUMN sha256 SET NOT NULL;
DROP TABLE file_request_uploads;
DROP TABLE file_request_visitors;
DROP TABLE file_requests;
