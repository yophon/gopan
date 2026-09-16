-- name: InsertChatMessage :one
INSERT INTO chat_messages (id, owner_id, body, node_id)
VALUES ($1, $2, $3, $4) RETURNING id, owner_id, body, node_id, created_at;

-- name: ListChatMessages :many
SELECT id, owner_id, body, node_id, created_at
FROM chat_messages
WHERE owner_id = $1
ORDER BY id DESC
LIMIT $2;

-- name: ListChatMessagesBefore :many
-- last_id 游标:uuid v7 时间有序, id < last_id 取"更旧"的消息。
SELECT id, owner_id, body, node_id, created_at
FROM chat_messages
WHERE owner_id = $1 AND id < $2
ORDER BY id DESC
LIMIT $3;

-- name: ReadUserChatFolderID :one
SELECT chat_folder_id FROM users WHERE id = $1;

-- name: SetUserChatFolderID :exec
UPDATE users SET chat_folder_id = $2 WHERE id = $1;