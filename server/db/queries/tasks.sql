-- name: EnqueueTask :exec
-- 同 (kind, blob) 幂等;此前失败的终态任务重新入队(如同 hash 再次被上传)
INSERT INTO tasks (id, kind, blob_id)
VALUES ($1, $2, $3)
ON CONFLICT (kind, blob_id) DO UPDATE
SET status = 'pending', attempts = 0, last_error = NULL, updated_at = now()
WHERE tasks.status = 'failed';

-- name: ClaimTask :one
-- 单进程多 worker 抢任务;重启恢复 running 状态的滞留任务(5 分钟无更新视为死亡)
UPDATE tasks SET status = 'running', attempts = attempts + 1, updated_at = now()
WHERE id = (
    SELECT t.id FROM tasks t
    WHERE (t.status = 'pending' OR (t.status = 'running' AND t.updated_at < now() - interval '5 minutes'))
      AND t.attempts < 3
    ORDER BY t.created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING *;

-- name: FinishTask :exec
UPDATE tasks SET status = $2, last_error = $3, updated_at = now() WHERE id = $1;
