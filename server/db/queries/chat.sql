-- name: InsertChatMessage :one
INSERT INTO chat_messages (id, owner_id, body, node_id, source)
VALUES ($1, $2, $3, $4, $5) RETURNING id, owner_id, body, node_id, created_at, source;

-- name: ListChatMessages :many
SELECT id, owner_id, body, node_id, created_at, source
FROM chat_messages
WHERE owner_id = $1
ORDER BY id DESC
LIMIT $2;

-- name: ListChatMessagesBefore :many
-- last_id 游标:uuid v7 时间有序, id < last_id 取"更旧"的消息。
SELECT id, owner_id, body, node_id, created_at, source
FROM chat_messages
WHERE owner_id = $1 AND id < $2
ORDER BY id DESC
LIMIT $3;

-- name: ListChatMessagesAfter :many
-- after_id 游标:id > after_id 取"更新"的消息,从锚点往后数 limit 条,直接升序。
SELECT id, owner_id, body, node_id, created_at, source
FROM chat_messages
WHERE owner_id = $1 AND id > $2
ORDER BY id ASC
LIMIT $3;

-- name: ReadUserChatFolderID :one
SELECT chat_folder_id FROM users WHERE id = $1;

-- name: SetUserChatFolderID :exec
UPDATE users SET chat_folder_id = $2 WHERE id = $1;

-- name: GetChatMessageByNode :one
-- 同一节点在会话里只保留一条:SendNode 与自动落消息都先查这一条做幂等。
SELECT id, owner_id, body, node_id, created_at, source
FROM chat_messages
WHERE owner_id = $1 AND node_id = $2
ORDER BY id
LIMIT 1;

-- name: InsertChatMessageForChatFolder :one
-- 文件落进「传输助手」文件夹即自动成为一条消息。
-- 指针为空 / parent 不是该文件夹 / 该节点已有消息 → 不插入(调用方按 ErrNoRows 忽略)。
INSERT INTO chat_messages (id, owner_id, node_id, source)
SELECT sqlc.arg(id), sqlc.arg(owner_id), sqlc.arg(node_id), sqlc.arg(source)
FROM users u
WHERE u.id = sqlc.arg(owner_id)
  AND u.chat_folder_id IS NOT NULL
  AND u.chat_folder_id = sqlc.arg(parent_id)
  AND NOT EXISTS (
    SELECT 1 FROM chat_messages c
    WHERE c.owner_id = sqlc.arg(owner_id) AND c.node_id = sqlc.arg(node_id)
  )
RETURNING id, owner_id, body, node_id, created_at, source;

-- name: DeleteChatMessage :execrows
DELETE FROM chat_messages WHERE id = $1 AND owner_id = $2;

-- name: SearchChatMessages :many
-- 正文或附件文件名匹配 pattern(调用方拼好 %...%,并转义 % _),新→旧。单用户会话规模小,不建索引。
SELECT c.id, c.owner_id, c.body, c.node_id, c.created_at, c.source
FROM chat_messages c
LEFT JOIN nodes n ON n.id = c.node_id
WHERE c.owner_id = $1
  AND (c.body ILIKE $2::text OR n.name ILIKE $2::text)
ORDER BY c.id DESC
LIMIT $3;
