-- 后台任务(doc/21 二.1):离线下载、解压。

-- name: CreateJob :one
INSERT INTO user_jobs (id, owner_id, kind, params, parent_id, title, bytes_total, files_total, source)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING *;

-- name: CountActiveJobs :one
SELECT count(*) FROM user_jobs WHERE owner_id = $1 AND status IN ('queued', 'running');

-- name: ClaimJob :one
-- 交还队列的任务按已试次数退避(30 秒 × attempts;交还时刷新了 heartbeat_at)。
UPDATE user_jobs SET status = 'running', attempts = attempts + 1,
    started_at = COALESCE(started_at, now()), heartbeat_at = now()
WHERE id = (
    SELECT j.id FROM user_jobs j
    WHERE j.status = 'queued'
      AND (j.heartbeat_at IS NULL OR j.heartbeat_at <= now() - interval '30 seconds' * j.attempts)
    ORDER BY j.created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING *;

-- name: HeartbeatJob :one
-- 写进度并续命;任务已被取消(或被收尾退回队列)时没有行返回,执行方据此停下。
UPDATE user_jobs SET heartbeat_at = now(), title = $2, bytes_done = $3, bytes_total = $4,
    files_done = $5, files_total = $6, files_skipped = $7
WHERE id = $1 AND status = 'running'
RETURNING status;

-- name: FinishJob :execrows
UPDATE user_jobs SET status = $2, result_node_id = $3, error_code = $4, error_message = $5,
    title = $6, bytes_done = $7, bytes_total = $8, files_done = $9, files_total = $10, files_skipped = $11,
    finished_at = now(), heartbeat_at = now()
WHERE id = $1 AND status = 'running';

-- name: ReleaseJob :execrows
-- 基础设施错误:交还队列,稍后重试(错误留着给界面看)。停机时交还的不算一次尝试(refund = 1)。
UPDATE user_jobs SET status = 'queued', error_code = @error_code, error_message = @error_message, heartbeat_at = now(),
    bytes_done = 0, files_done = 0, files_skipped = 0, attempts = attempts - @refund::int
WHERE id = @id AND status = 'running';

-- name: RequeueStaleJobs :execrows
-- 进程崩溃或重启:心跳停了 2 分钟的 running 任务退回队列;第 3 次仍中断的落到 failed。
UPDATE user_jobs SET status = 'queued', heartbeat_at = now(), bytes_done = 0, files_done = 0, files_skipped = 0
WHERE status = 'running' AND attempts < 3 AND heartbeat_at < now() - interval '2 minutes';

-- name: FailStaleJobs :many
UPDATE user_jobs SET status = 'failed', error_code = 'INTERRUPTED', error_message = '运行中断,重试次数已用完',
    finished_at = now()
WHERE status = 'running' AND attempts >= 3 AND heartbeat_at < now() - interval '2 minutes'
RETURNING id;

-- name: CancelJob :one
UPDATE user_jobs SET status = 'canceled', finished_at = now()
WHERE id = $1 AND owner_id = $2 AND status IN ('queued', 'running')
RETURNING *;

-- name: RetryJob :one
UPDATE user_jobs SET status = 'queued', attempts = 0, error_code = NULL, error_message = NULL,
    bytes_done = 0, files_done = 0, files_skipped = 0, result_node_id = NULL,
    heartbeat_at = NULL, started_at = NULL, finished_at = NULL
WHERE id = $1 AND owner_id = $2 AND status IN ('failed', 'canceled')
RETURNING *;

-- name: GetJob :one
SELECT * FROM user_jobs WHERE id = $1 AND owner_id = $2;

-- name: GetJobStatus :one
SELECT status FROM user_jobs WHERE id = $1;

-- name: ListJobs :many
-- 新的在前;active 只列排队与进行中的。游标是上一页最后一条的 (created_at, id)。
SELECT * FROM user_jobs
WHERE owner_id = @owner_id
  AND (NOT @active_only::boolean OR status IN ('queued', 'running'))
  AND (@before_id::uuid = '00000000-0000-0000-0000-000000000000'::uuid
       OR (created_at, id) < (@before_at::timestamptz, @before_id::uuid))
ORDER BY created_at DESC, id DESC
LIMIT @lim;

-- name: DeleteFinishedJobs :execrows
DELETE FROM user_jobs WHERE owner_id = $1 AND status IN ('done', 'failed', 'canceled');

-- name: CleanupOldJobs :execrows
DELETE FROM user_jobs WHERE status IN ('done', 'failed', 'canceled') AND finished_at < now() - interval '7 days';
