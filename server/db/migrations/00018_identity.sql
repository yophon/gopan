-- +goose Up
CREATE TABLE id_logins (
    state text PRIMARY KEY,
    verifier text NOT NULL,
    nonce text NOT NULL,
    return_to text NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE TABLE id_sessions (
    family_id uuid PRIMARY KEY REFERENCES sessions(family_id) ON DELETE CASCADE,
    tokens bytea NOT NULL,
    expires_at timestamptz NOT NULL
);
-- +goose Down
DROP TABLE id_sessions;
DROP TABLE id_logins;
