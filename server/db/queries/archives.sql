-- 压缩包目录索引(doc/21 四.2)。

-- name: DeleteBlobArchive :exec
DELETE FROM blob_archives WHERE blob_id = $1;

-- name: InsertBlobArchive :exec
INSERT INTO blob_archives (blob_id, format, status, solid, encrypted, entry_count, file_count, total_size, indexer, error, updated_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, now());

-- name: InsertArchiveEntries :copyfrom
INSERT INTO archive_entries (blob_id, idx, src, path, parent, name, is_dir, size, packed_size, mtime, flag, cost)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12);

-- name: GetBlobArchive :one
SELECT * FROM blob_archives WHERE blob_id = $1;

-- name: ListArchiveDir :many
-- 某个目录下的条目:目录在前、按名字,游标是上一页最后一条的 (is_dir, name, idx)。
SELECT * FROM archive_entries
WHERE blob_id = @blob_id AND parent = @parent
  AND (@after_idx::int < 0
       OR (NOT is_dir, name, idx) > (NOT @after_dir::boolean, @after_name::text, @after_idx::int))
ORDER BY is_dir DESC, name, idx
LIMIT @lim;

-- name: CountArchiveDir :one
SELECT count(*) FROM archive_entries WHERE blob_id = $1 AND parent = $2;

-- name: GetArchiveEntry :one
SELECT * FROM archive_entries WHERE blob_id = $1 AND idx = $2;

-- name: ListArchiveEntries :many
-- 解压用:全部条目,按原生序号。
SELECT * FROM archive_entries WHERE blob_id = $1 ORDER BY src, idx;

-- name: ListBlobsNeedingArchiveIndex :many
-- 回填:引用它的文件名是压缩包,还没建过索引或索引器版本旧了;已在队列里的、最终失败的(等管理员重排)不挑。
SELECT b.id FROM blobs b
LEFT JOIN blob_archives ba ON ba.blob_id = b.id
LEFT JOIN tasks t ON t.blob_id = b.id AND t.kind = 'archive_index'
WHERE b.verified AND b.ref_count > 0
  AND (ba.blob_id IS NULL OR ba.indexer < $1)
  AND (t.id IS NULL OR t.status = 'done')
  AND EXISTS (SELECT 1 FROM nodes n WHERE n.blob_id = b.id AND n.deleted_at IS NULL
              AND lower(n.name) ~ '\.(zip|7z|rar|tar|tgz|tbz2?|txz|tar\.(gz|bz2|xz))$')
ORDER BY b.created_at DESC
LIMIT $2;
