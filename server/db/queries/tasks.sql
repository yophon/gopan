-- name: EnqueueTask :exec
-- 同 (kind, blob) 幂等;此前失败的终态任务重新入队(如同 hash 再次被上传)
INSERT INTO tasks (id, kind, blob_id)
VALUES ($1, $2, $3)
ON CONFLICT (kind, blob_id) DO UPDATE
SET status = 'pending', attempts = 0, last_error = NULL, updated_at = now()
WHERE tasks.status = 'failed';

-- name: ClaimTask :one
-- 单进程多 worker 抢任务;重启恢复 running 状态的滞留任务(5 分钟无更新视为死亡)。
-- verify_hash 优先:WebDAV / Agent 上传的文件要等它才可用,不能排在缩略图和内容提取后面。
-- 失败交还的任务按已试次数退避(30 秒 × attempts;交还时 FinishTask 刷新了 updated_at)。
UPDATE tasks SET status = 'running', attempts = attempts + 1, updated_at = now()
WHERE id = (
    SELECT t.id FROM tasks t
    WHERE ((t.status = 'pending' AND t.updated_at <= now() - interval '30 seconds' * t.attempts)
        OR (t.status = 'running' AND t.updated_at < now() - interval '5 minutes'))
      AND t.attempts < 3
    ORDER BY CASE t.kind WHEN 'verify_hash' THEN 0 WHEN 'extract_text' THEN 2 ELSE 1 END, t.created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING *;

-- name: FailExhaustedTasks :execrows
-- 第 3 次运行时进程崩溃或被重启的任务:attempts 已满,ClaimTask 不会再领,
-- 不收尾就永远停在 running。5 分钟没动静就落到 failed,管理端能看到、能重排。
UPDATE tasks SET status = 'failed', last_error = '运行中断,重试次数已用完', updated_at = now()
WHERE status = 'running' AND attempts >= 3 AND updated_at < now() - interval '5 minutes';

-- name: FinishTask :exec
UPDATE tasks SET status = $2, last_error = $3, updated_at = now() WHERE id = $1;

-- name: CountTasksByStatus :many
SELECT status, count(*) AS count FROM tasks GROUP BY status;

-- name: RetryFailedTasks :execrows
UPDATE tasks SET status = 'pending', attempts = 0, last_error = NULL, updated_at = now()
WHERE status = 'failed';
