-- +goose Up
CREATE TABLE mcp_access_roots (
 credential_id uuid NOT NULL,
 credential_type text NOT NULL CHECK (credential_type IN ('api_key','oauth')),
 owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 root_id uuid NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(credential_id,credential_type)
);
-- root_id deliberately survives a deleted folder: missing roots deny access.
CREATE TABLE mcp_audit (
 id bigserial PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 credential_id uuid NOT NULL,
 credential_type text NOT NULL,
 endpoint text NOT NULL,
 tool text NOT NULL,
 targets jsonb NOT NULL DEFAULT '{}',
 status text NOT NULL DEFAULT 'started',
 error_code text,
 created_at timestamptz NOT NULL DEFAULT now(),
 finished_at timestamptz
);
CREATE INDEX mcp_audit_owner_page ON mcp_audit(owner_id,id DESC);
CREATE TABLE mcp_upload_requests (
 owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 key text NOT NULL,
 request_hash text NOT NULL,
 node_id uuid,
 transfer_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,key),
 CHECK ((node_id IS NULL) <> (transfer_id IS NULL))
);
-- +goose Down
DROP TABLE mcp_upload_requests;
DROP TABLE mcp_audit;
DROP TABLE mcp_access_roots;
