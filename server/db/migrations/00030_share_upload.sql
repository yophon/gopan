-- +goose Up
-- 分享允许对方上传(doc/32 一):文件夹分享打开后,访客能往分享的文件夹及其子文件夹里传文件、建文件夹,
-- 不能删、改名、覆盖。访客的上传沿用浏览器分片会话(和收集链接一样),分享跟着会话行走,落库时复核。
ALTER TABLE shares ADD COLUMN allow_upload boolean NOT NULL DEFAULT false;

ALTER TABLE upload_sessions ADD COLUMN share_id uuid REFERENCES shares(id) ON DELETE SET NULL;
CREATE INDEX idx_uploads_share ON upload_sessions (share_id)
    WHERE share_id IS NOT NULL AND status IN ('uploading', 'completing');

-- 访问记录多一种:upload(访客传了一个文件);detail 记文件名与访客留的名字
ALTER TABLE share_visits DROP CONSTRAINT share_visits_kind_check;
ALTER TABLE share_visits ADD CONSTRAINT share_visits_kind_check
    CHECK (kind IN ('verify', 'download', 'pack', 'upload'));
ALTER TABLE share_visits ADD COLUMN detail text;

-- +goose Down
ALTER TABLE share_visits DROP COLUMN detail;
DELETE FROM share_visits WHERE kind = 'upload';
ALTER TABLE share_visits DROP CONSTRAINT share_visits_kind_check;
ALTER TABLE share_visits ADD CONSTRAINT share_visits_kind_check
    CHECK (kind IN ('verify', 'download', 'pack'));
DROP INDEX idx_uploads_share;
ALTER TABLE upload_sessions DROP COLUMN share_id;
ALTER TABLE shares DROP COLUMN allow_upload;
