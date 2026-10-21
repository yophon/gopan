-- +goose Up
-- 直链与图床(doc/22 四)。
--
-- links:一个文件一条永久公开地址 /x/{token}/{文件名},字节经 Go 中转(才能记流量、按上限拦、吊销即时生效)。
-- 每个文件同时只有一条有效直链;吊销的留着(审计),90 天后连同统计一起清掉。
-- referer_hosts / allow_no_referer / daily_bytes_cap 为 NULL = 用默认规则(link_prefs / 全站默认)。
CREATE TABLE links (
    id               uuid PRIMARY KEY,
    owner_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    node_id          uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    token            text NOT NULL UNIQUE,
    source           text NOT NULL DEFAULT 'web',
    created_ip       text,
    created_ua       text,
    api_key_id       uuid,
    referer_hosts    text[],
    allow_no_referer boolean,
    daily_bytes_cap  bigint,
    auto_pause       boolean NOT NULL DEFAULT false,
    has_gps          boolean NOT NULL DEFAULT false,
    expires_at       timestamptz,
    paused_at        timestamptz,
    pause_reason     text CHECK (pause_reason IN ('manual', 'daily_cap', 'anomaly')),
    revoked_at       timestamptz,
    hits_total       bigint NOT NULL DEFAULT 0,
    bytes_total      bigint NOT NULL DEFAULT 0,
    last_hit_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_links_node_active ON links (node_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_links_owner ON links (owner_id, created_at DESC, id DESC) WHERE revoked_at IS NULL;
CREATE INDEX idx_links_revoked ON links (revoked_at) WHERE revoked_at IS NOT NULL;

-- 每人一份的默认规则与图床设置。没有行 = 全用默认值。
CREATE TABLE link_prefs (
    owner_id         uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    referer_hosts    text[] NOT NULL DEFAULT '{}',
    allow_no_referer boolean NOT NULL DEFAULT true,
    image_folder     text NOT NULL DEFAULT '/图床',
    strip_gps        boolean NOT NULL DEFAULT true,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- 按小时的计数(进程内聚合,每 30 秒批量写一次)。ips 是这一小时的独立 IP 数。保留 90 天。
CREATE TABLE link_stats (
    link_id uuid NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    hour    timestamptz NOT NULL,
    hits    bigint NOT NULL DEFAULT 0,
    bytes   bigint NOT NULL DEFAULT 0,
    blocked bigint NOT NULL DEFAULT 0,
    ips     int NOT NULL DEFAULT 0,
    PRIMARY KEY (link_id, hour)
);
CREATE INDEX idx_link_stats_hour ON link_stats (hour);

-- 来源站点按天(day 按 GOPAN_TZ);每条每天最多 50 个,其余记在 host = '*'。没有来源的 host = ''。保留 90 天。
CREATE TABLE link_referers (
    link_id uuid NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    day     date NOT NULL,
    host    text NOT NULL,
    hits    bigint NOT NULL DEFAULT 0,
    bytes   bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (link_id, day, host)
);
CREATE INDEX idx_link_referers_day ON link_referers (day);

-- 访问明细:每条直链每小时最多 200 条,超出只计数。保留 30 天。
CREATE TABLE link_hits (
    id         bigserial PRIMARY KEY,
    link_id    uuid NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    at         timestamptz NOT NULL,
    ip         text NOT NULL,
    user_agent text NOT NULL DEFAULT '',
    referer    text NOT NULL DEFAULT '',
    status     int NOT NULL,
    bytes      bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_link_hits_link ON link_hits (link_id, id DESC);
CREATE INDEX idx_link_hits_at ON link_hits (at);

-- +goose Down
DROP TABLE link_hits;
DROP TABLE link_referers;
DROP TABLE link_stats;
DROP TABLE link_prefs;
DROP TABLE links;
