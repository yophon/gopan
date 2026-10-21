-- +goose Up
-- 影音(doc/22 二)。
--
-- 1. 存量 MIME 回填:运行镜像没有 /etc/mime.types,mkv / mov / avi 等一直记成 octet-stream,
--    .webm 被 Go 内置表记成 audio/webm。按引用节点的扩展名改正(表与 service/mimetype.go 的 extraMime 一致),
--    已校验的补排 media_probe / video_cover,由 worker 慢慢跑。
WITH ext(e, m) AS (VALUES
    ('.mkv', 'video/x-matroska'), ('.mov', 'video/quicktime'), ('.m4v', 'video/x-m4v'),
    ('.webm', 'video/webm'), ('.avi', 'video/x-msvideo'), ('.m2ts', 'video/mp2t'), ('.mts', 'video/mp2t'),
    ('.3gp', 'video/3gpp'), ('.flv', 'video/x-flv'), ('.wmv', 'video/x-ms-wmv'),
    ('.mpg', 'video/mpeg'), ('.mpeg', 'video/mpeg'),
    ('.aac', 'audio/aac'), ('.weba', 'audio/webm'), ('.wma', 'audio/x-ms-wma'), ('.ape', 'audio/x-ape'),
    ('.aif', 'audio/aiff'), ('.aiff', 'audio/aiff'), ('.mka', 'audio/x-matroska'), ('.m4b', 'audio/mp4'),
    ('.amr', 'audio/amr'),
    ('.srt', 'application/x-subrip'), ('.ass', 'text/x-ssa'), ('.ssa', 'text/x-ssa')
),
fix AS (
    SELECT DISTINCT ON (b.id) b.id, ext.m
    FROM blobs b
    JOIN nodes n ON n.blob_id = b.id
    JOIN ext ON lower(n.name) LIKE '%' || ext.e
    WHERE b.mime = 'application/octet-stream' OR (b.mime = 'audio/webm' AND ext.e = '.webm')
    ORDER BY b.id, n.created_at
),
upd AS (
    UPDATE blobs b SET mime = fix.m FROM fix WHERE b.id = fix.id
    RETURNING b.id, b.mime, b.verified
)
INSERT INTO tasks (id, kind, blob_id)
SELECT gen_random_uuid(), k.kind, upd.id
FROM upd
JOIN (VALUES ('media_probe'), ('video_cover')) k(kind)
  ON upd.mime LIKE 'video/%' OR (k.kind = 'media_probe' AND upd.mime LIKE 'audio/%')
WHERE upd.verified
ON CONFLICT (kind, blob_id) DO NOTHING;

-- 2. 播放进度:音视频是秒,PDF 是页码;节点彻底删除时一并删除。
CREATE TABLE play_progress (
    owner_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    node_id    uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    position   double precision NOT NULL,
    duration   double precision NOT NULL DEFAULT 0,
    finished   boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, node_id)
);
CREATE INDEX idx_play_progress_recent ON play_progress (owner_id, updated_at DESC);

-- +goose Down
-- MIME 回填不回退(旧二进制同样受益)。
DROP TABLE play_progress;
