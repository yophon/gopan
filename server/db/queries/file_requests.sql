-- name: CreateFileRequest :one
INSERT INTO file_requests (id, token, owner_id, folder_id, title, note, password_hash,
    require_name, group_by_uploader, max_file_size, max_total_bytes, max_files, expires_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
RETURNING *;

-- name: UpdateFileRequest :one
-- 整表单替换(密码与目标文件夹不能改,要改就吊销重建)。
UPDATE file_requests SET title = $3, note = $4, require_name = $5, group_by_uploader = $6,
    max_file_size = $7, max_total_bytes = $8, max_files = $9, expires_at = $10
WHERE id = $1 AND owner_id = $2 AND revoked_at IS NULL
RETURNING *;

-- name: RevokeFileRequest :execrows
UPDATE file_requests SET revoked_at = now()
WHERE id = $1 AND owner_id = $2 AND revoked_at IS NULL;

-- name: GetFileRequestByToken :one
SELECT r.*, n.deleted_at AS folder_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM file_requests r JOIN nodes n ON n.id = r.folder_id JOIN users u ON u.id = r.owner_id
WHERE r.token = $1;

-- name: GetFileRequestByID :one
SELECT r.*, n.deleted_at AS folder_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM file_requests r JOIN nodes n ON n.id = r.folder_id JOIN users u ON u.id = r.owner_id
WHERE r.id = $1;

-- name: LockFileRequest :one
-- 落库时锁住链接行,让同一链接的并发落库串行复核个数 / 总量上限。
SELECT r.*, n.deleted_at AS folder_deleted_at,
       (u.disabled_at IS NOT NULL)::boolean AS owner_disabled
FROM file_requests r JOIN nodes n ON n.id = r.folder_id JOIN users u ON u.id = r.owner_id
WHERE r.id = $1
FOR UPDATE OF r;

-- name: ListMyFileRequests :many
-- 顺带带出收件统计(LATERAL 一次扫完)。已过期的照常列出,吊销的和文件夹进了回收站的不列。
SELECT r.*,
       n.parent_id AS folder_parent_id, n.name AS folder_name,
       n.created_at AS folder_created_at, n.updated_at AS folder_updated_at,
       COALESCE(s.file_count, 0)::bigint     AS file_count,
       COALESCE(s.total_bytes, 0)::bigint    AS total_bytes,
       COALESCE(s.uploader_count, 0)::bigint AS uploader_count,
       COALESCE(s.new_count, 0)::bigint      AS new_count,
       s.last_at AS last_upload_at
FROM file_requests r
JOIN nodes n ON n.id = r.folder_id
LEFT JOIN LATERAL (
    SELECT count(*) AS file_count,
           sum(fu.size) AS total_bytes,
           count(DISTINCT COALESCE(fu.visitor_id::text, fu.uploader_name)) AS uploader_count,
           count(*) FILTER (WHERE fu.created_at > r.seen_at) AS new_count,
           max(fu.created_at)::timestamptz AS last_at
    FROM file_request_uploads fu
    WHERE fu.request_id = r.id
) s ON true
WHERE r.owner_id = $1 AND r.revoked_at IS NULL AND n.deleted_at IS NULL
ORDER BY r.created_at DESC, r.id;

-- name: MarkFileRequestSeen :execrows
UPDATE file_requests SET seen_at = now() WHERE id = $1 AND owner_id = $2;

-- name: CreateFileRequestVisitor :one
INSERT INTO file_request_visitors (id, request_id, name, ip, user_agent)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;

-- name: GetFileRequestVisitor :one
SELECT * FROM file_request_visitors WHERE id = $1;

-- name: DeleteStaleFileRequestVisitors :execrows
-- 访客令牌 12 小时、上传会话 48 小时,30 天前的访客行已经没有用处;收件记录里有名字快照。
DELETE FROM file_request_visitors WHERE created_at < now() - interval '30 days';

-- name: RecordFileRequestUpload :exec
INSERT INTO file_request_uploads (request_id, visitor_id, node_id, file_name, size, uploader_name, ip, user_agent)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8);

-- name: FileRequestUsage :one
SELECT count(*)::bigint AS files, COALESCE(sum(size), 0)::bigint AS bytes
FROM file_request_uploads WHERE request_id = $1;

-- name: FileRequestPending :one
-- 这条链接上进行中的会话:init 时与已收的合起来算上限(预留),防止并发访客一起冲过上限。
SELECT count(*)::bigint AS sessions, COALESCE(sum(size), 0)::bigint AS bytes
FROM upload_sessions
WHERE request_id = $1 AND status IN ('uploading', 'completing');

-- name: CountVisitorSessions :one
SELECT count(*)::bigint FROM upload_sessions
WHERE visitor_id = $1 AND status IN ('uploading', 'completing');

-- name: SumOwnerCollectPendingBytes :one
-- 属主配额要预留所有收集链接上进行中的会话(参照 Agent 直传的 SumActiveTransferBytes)。
SELECT COALESCE(sum(size), 0)::bigint FROM upload_sessions
WHERE owner_id = $1 AND request_id IS NOT NULL AND status IN ('uploading', 'completing');

-- name: ListFileRequestUploads :many
-- 收件记录,新的在前;id 游标。文件还在(未删除)时带出节点信息。
SELECT fu.id, fu.file_name, fu.size, fu.uploader_name, fu.ip, fu.user_agent, fu.created_at,
       n.id AS node_id, n.parent_id AS node_parent_id, n.name AS node_name,
       n.created_at AS node_created_at, n.updated_at AS node_updated_at,
       b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM file_request_uploads fu
LEFT JOIN nodes n ON n.id = fu.node_id AND n.deleted_at IS NULL
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE fu.request_id = $1 AND (sqlc.narg('before')::bigint IS NULL OR fu.id < sqlc.narg('before')::bigint)
ORDER BY fu.id DESC
LIMIT $2;

-- name: ListVisitorUploads :many
SELECT file_name, size, created_at FROM file_request_uploads
WHERE visitor_id = $1
ORDER BY id;

-- name: CreateCollectUploadSession :one
INSERT INTO upload_sessions (id, owner_id, size, target_parent, target_name, minio_upload_id, part_size,
    expires_at, object_key, request_id, visitor_id)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
RETURNING *;

-- name: GetCollectUploadSession :one
-- 访客只能碰自己开的会话:同一链接的其他访客、属主自己的会话都查不到。
SELECT * FROM upload_sessions WHERE id = $1 AND request_id = $2 AND visitor_id = $3;

-- name: SetUploadSessionSha :exec
UPDATE upload_sessions SET sha256 = $2 WHERE id = $1;
