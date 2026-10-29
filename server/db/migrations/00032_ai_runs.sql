-- +goose Up
-- 助手在服务器上运行(doc/34):每一轮一行;同一个对话同时只能有一轮没结束。
CREATE TABLE ai_runs (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id uuid NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    status          text NOT NULL CHECK (status IN ('queued', 'running', 'waiting', 'done', 'failed', 'stopped')),
    -- 这一轮的参数:这句话、「你在看什么」、服务与模型(运行时 send() 的参数)
    input           jsonb NOT NULL,
    -- 等确认时的确认卡
    approval        jsonb,
    error           text NOT NULL DEFAULT '',
    created_at      timestamptz NOT NULL DEFAULT now(),
    started_at      timestamptz,
    finished_at     timestamptz
);
CREATE INDEX idx_ai_runs_conversation ON ai_runs (conversation_id, created_at DESC);
CREATE INDEX idx_ai_runs_queue ON ai_runs (created_at) WHERE status = 'queued';
CREATE UNIQUE INDEX uq_ai_runs_active ON ai_runs (conversation_id) WHERE status IN ('queued', 'running', 'waiting');

-- 对话在哪跑:device(客户端,doc/33)/ server(服务器,doc/34)
ALTER TABLE ai_conversations ADD COLUMN runner text NOT NULL DEFAULT 'device' CHECK (runner IN ('device', 'server'));

-- +goose Down
ALTER TABLE ai_conversations DROP COLUMN runner;
DROP TABLE ai_runs;
