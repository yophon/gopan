-- +goose Up
-- 内容搜索(doc/20 第三节):每份内容(blob)提一次文字,所有引用它的文件共享;blob 回收时级联删除。
--
-- terms 是应用层切好的词项(CJK 二元组 + 字母数字三元组,见 internal/fulltext),
-- 用 text[] + GIN 而不是 tsvector:不受 tsvector 1MB 上限约束,也用不上它的分词。
-- 查询 = 词项取交集(@>)得候选 + 原文子串复核。
-- 图片、视频这类也写一行 unsupported,回填时不用反复判断。
CREATE TABLE blob_texts (
    blob_id     uuid PRIMARY KEY REFERENCES blobs(id) ON DELETE CASCADE,
    status      text NOT NULL CHECK (status IN ('done', 'empty', 'truncated', 'unsupported', 'failed')),
    extractor   int NOT NULL,           -- 提取器版本,升级后逐步重提
    content     text NOT NULL DEFAULT '',
    terms       text[] NOT NULL DEFAULT '{}',
    page_starts int[],                  -- PDF 每页在 content 里的起点(按字符,从 0 起)
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_blob_texts_terms ON blob_texts USING gin (terms);

ALTER TABLE tasks DROP CONSTRAINT tasks_kind_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_kind_check
    CHECK (kind IN ('verify_hash','thumb','video_cover','office_pdf','media_probe','extract_text'));

-- +goose Down
DELETE FROM tasks WHERE kind = 'extract_text';
ALTER TABLE tasks DROP CONSTRAINT tasks_kind_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_kind_check
    CHECK (kind IN ('verify_hash','thumb','video_cover','office_pdf','media_probe'));
DROP TABLE blob_texts;
