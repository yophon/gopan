-- name: CreateSession :exec
INSERT INTO sessions (family_id, user_id, user_agent, ip)
VALUES ($1, $2, $3, $4);

-- name: ListSessions :many
-- active 表示这族 refresh token 还能用(没被吊销、没过期)。被挤下线的会话
-- (改密、管理员禁用、重放检测)会以 active=false 的形式留在列表里,让用户看得见
-- "某个设备刚刚被强制下线"——这正是重放检测唯一能被用户感知的地方。
SELECT s.family_id, s.user_agent, s.ip, s.created_at, s.last_seen_at, s.revoked_at,
       EXISTS (
           SELECT 1 FROM refresh_tokens r
           WHERE r.family_id = s.family_id
             AND r.revoked_at IS NULL
             AND r.expires_at > now()
       ) AS active
FROM sessions s
WHERE s.user_id = $1
ORDER BY s.last_seen_at DESC
LIMIT 50;

-- name: TouchSession :exec
-- 5 分钟才写一次(照 TouchAppPassword 的做法)。活跃会话不该把每次请求都变成一次写。
UPDATE sessions SET last_seen_at = now()
WHERE family_id = $1
  AND last_seen_at < now() - interval '5 minutes';

-- name: RevokeSession :execrows
-- 必须与 RevokeRefreshFamily 成对调用,否则只是标记了展示状态、token 仍能用。
UPDATE sessions SET revoked_at = now()
WHERE family_id = $1 AND user_id = $2 AND revoked_at IS NULL;

-- name: RevokeAllSessions :exec
UPDATE sessions SET revoked_at = now()
WHERE user_id = $1 AND revoked_at IS NULL;

-- name: DeleteStaleSessions :execrows
-- 90 天没活动的记录清掉。会话列表只对近期有意义,不需要永久留痕。
DELETE FROM sessions WHERE last_seen_at < now() - interval '90 days';
