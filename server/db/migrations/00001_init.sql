-- +goose Up
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    username      text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    quota_bytes   bigint NOT NULL DEFAULT 107374182400,
    used_bytes    bigint NOT NULL DEFAULT 0,
    is_admin      boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE blobs (
    id         uuid PRIMARY KEY,
    sha256     text NOT NULL UNIQUE,
    size       bigint NOT NULL,
    mime       text NOT NULL DEFAULT 'application/octet-stream',
    ref_count  int NOT NULL DEFAULT 0,
    verified   boolean NOT NULL DEFAULT false,
    media_meta jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    deref_at   timestamptz
);
CREATE INDEX idx_blobs_gc ON blobs (deref_at) WHERE ref_count = 0;

CREATE TABLE nodes (
    id         uuid PRIMARY KEY,
    owner_id   uuid NOT NULL REFERENCES users(id),
    parent_id  uuid REFERENCES nodes(id),
    name       text NOT NULL,
    kind       text NOT NULL CHECK (kind IN ('file', 'folder')),
    blob_id    uuid REFERENCES blobs(id),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_nodes_sibling
    ON nodes (owner_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), name)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_nodes_parent ON nodes (parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_nodes_trash  ON nodes (owner_id, deleted_at) WHERE deleted_at IS NOT NULL;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_nodes_name_trgm ON nodes USING gin (name gin_trgm_ops);

CREATE TABLE upload_sessions (
    id              uuid PRIMARY KEY,
    owner_id        uuid NOT NULL REFERENCES users(id),
    sha256          text NOT NULL,
    size            bigint NOT NULL,
    target_parent   uuid,
    target_name     text NOT NULL,
    minio_upload_id text NOT NULL,
    part_size       int NOT NULL DEFAULT 16777216,
    status          text NOT NULL DEFAULT 'uploading'
        CHECK (status IN ('uploading','completing','verifying','done','failed','aborted')),
    fail_reason     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL
);
CREATE INDEX idx_sessions_cleanup ON upload_sessions (expires_at)
    WHERE status = 'uploading';

CREATE TABLE shares (
    id            uuid PRIMARY KEY,
    token         text NOT NULL UNIQUE,
    node_id       uuid NOT NULL REFERENCES nodes(id),
    created_by    uuid NOT NULL REFERENCES users(id),
    password_hash text,
    expires_at    timestamptz,
    revoked_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_shares_owner ON shares (created_by) WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id),
    token_hash text NOT NULL UNIQUE,
    family_id  uuid NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_family ON refresh_tokens (family_id);

CREATE TABLE tasks (
    id         uuid PRIMARY KEY,
    kind       text NOT NULL
        CHECK (kind IN ('verify_hash','thumb','video_cover','office_pdf','media_probe')),
    blob_id    uuid NOT NULL REFERENCES blobs(id),
    status     text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','running','done','failed')),
    attempts   int NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kind, blob_id)
);
CREATE INDEX idx_tasks_pending ON tasks (status, created_at)
    WHERE status IN ('pending','running');

CREATE TABLE derivatives (
    blob_id    uuid NOT NULL REFERENCES blobs(id),
    kind       text NOT NULL CHECK (kind IN ('thumb256','thumb2048','pdf','cover')),
    minio_key  text NOT NULL,
    size       bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blob_id, kind)
);

-- +goose Down
DROP TABLE derivatives;
DROP TABLE tasks;
DROP TABLE refresh_tokens;
DROP TABLE shares;
DROP TABLE upload_sessions;
DROP TABLE nodes;
DROP TABLE blobs;
DROP TABLE users;
