-- +goose Up
-- 订阅(doc/23 四)与剪藏(doc/23 五)。

-- feeds:kind = rss 时 url 是 RSS / Atom 地址;kind = github 时是 owner/repo。
-- folder_id 被彻底删除后置空,检查时据此暂停并提示。
CREATE TABLE feeds (
    id               uuid PRIMARY KEY,
    owner_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind             text NOT NULL CHECK (kind IN ('rss', 'github')),
    url              text NOT NULL,
    title            text NOT NULL DEFAULT '',
    site_url         text NOT NULL DEFAULT '',
    folder_id        uuid REFERENCES nodes(id) ON DELETE SET NULL,
    mode             text NOT NULL DEFAULT 'download' CHECK (mode IN ('download', 'clip', 'none')),
    include_words    text NOT NULL DEFAULT '',
    exclude_words    text NOT NULL DEFAULT '',
    asset_pattern    text NOT NULL DEFAULT '',
    prerelease       boolean NOT NULL DEFAULT false,
    keep_last        int NOT NULL DEFAULT 0,
    interval_minutes int NOT NULL DEFAULT 360,
    etag             text NOT NULL DEFAULT '',
    last_modified    text NOT NULL DEFAULT '',
    next_check_at    timestamptz NOT NULL DEFAULT now(),
    last_checked_at  timestamptz,
    last_ok_at       timestamptz,
    last_error       text,
    fail_count       int NOT NULL DEFAULT 0,
    enabled          boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, kind, url)
);
CREATE INDEX idx_feeds_due ON feeds (next_check_at) WHERE enabled;

-- feed_items:每个订阅里见过的条目。GitHub 的一个条目是一个 Release 里的一个资源文件。
-- job_id 不设外键:任务记录会被清理,条目状态在任务结束时写回。
CREATE TABLE feed_items (
    id             uuid PRIMARY KEY,
    feed_id        uuid NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
    guid           text NOT NULL,
    title          text NOT NULL DEFAULT '',
    url            text NOT NULL DEFAULT '',
    enclosure_url  text NOT NULL DEFAULT '',
    enclosure_type text NOT NULL DEFAULT '',
    enclosure_size bigint,
    published_at   timestamptz,
    state          text NOT NULL DEFAULT 'new' CHECK (state IN ('new', 'skipped', 'queued', 'done', 'failed')),
    job_id         uuid,
    node_id        uuid REFERENCES nodes(id) ON DELETE SET NULL,
    error          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (feed_id, guid)
);
CREATE INDEX idx_feed_items_feed ON feed_items (feed_id, created_at DESC, id DESC);
CREATE INDEX idx_feed_items_job ON feed_items (job_id) WHERE job_id IS NOT NULL;

-- clips:剪藏下来的网页。node_id 是那篇 .md;彻底删除时一起删,在回收站里时列表不显示。
CREATE TABLE clips (
    id           uuid PRIMARY KEY,
    owner_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url          text NOT NULL,
    title        text NOT NULL DEFAULT '',
    site         text NOT NULL DEFAULT '',
    byline       text NOT NULL DEFAULT '',
    excerpt      text NOT NULL DEFAULT '',
    published_at timestamptz,
    node_id      uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    assets_id    uuid REFERENCES nodes(id) ON DELETE SET NULL,  -- 旁边存图片的「标题.assets」文件夹
    words        int NOT NULL DEFAULT 0,
    images       int NOT NULL DEFAULT 0,
    read_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_clips_owner ON clips (owner_id, created_at DESC, id DESC);
CREATE INDEX idx_clips_url ON clips (owner_id, url);

-- 剪藏存到哪(每人一行,没有行 = 默认 /剪藏,按月分文件夹)
CREATE TABLE clip_prefs (
    owner_id   uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    folder     text NOT NULL DEFAULT '/剪藏',
    by_month   boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE user_jobs DROP CONSTRAINT user_jobs_kind_check;
ALTER TABLE user_jobs ADD CONSTRAINT user_jobs_kind_check
    CHECK (kind IN ('fetch_url', 'extract_archive', 'clip_url'));

-- +goose Down
DELETE FROM user_jobs WHERE kind = 'clip_url';
ALTER TABLE user_jobs DROP CONSTRAINT user_jobs_kind_check;
ALTER TABLE user_jobs ADD CONSTRAINT user_jobs_kind_check
    CHECK (kind IN ('fetch_url', 'extract_archive'));
DROP TABLE clip_prefs;
DROP TABLE clips;
DROP TABLE feed_items;
DROP TABLE feeds;
