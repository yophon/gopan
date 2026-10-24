package service

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"strings"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// 在线编辑文本(网页 / App 里直接改 .md / .txt 这类文件,doc/25)。
// 读:整份读出来,只给不超过 MaxTextEdit 的 UTF-8 文本,带上内容的 sha256 作为「打开时的版本」;
// 存:按节点 id 覆盖(不是按名字),打开后在别处被改过就报 EDIT_CONFLICT;旧内容照常进历史版本,动态记一条覆盖。

// MaxTextEdit 能在线编辑的文本上限:再大的编辑器也卡,网页 / App 一律请下载后改。
const MaxTextEdit = 2 << 20

// TextFile 一份能编辑的文本。
type TextFile struct {
	Content string
	Sha256  string
	Size    int64
}

// ReadTextFile 整份读出一个文本文件。太大 TOO_LARGE,不是 UTF-8 文本 NOT_TEXT。
func (s *Uploads) ReadTextFile(ctx context.Context, owner, nodeID uuid.UUID) (*TextFile, error) {
	row, err := s.NodeInfo(ctx, owner, nodeID)
	if err != nil {
		return nil, err
	}
	if row.Kind != "file" || row.BlobSha256 == nil || row.BlobSize == nil {
		return nil, errf("NOT_A_FILE", "文件夹不能编辑")
	}
	if *row.BlobSize > MaxTextEdit {
		return nil, errf("TOO_LARGE", "超过 2 MB 的文件不能在线编辑,请下载后修改")
	}
	r, err := s.obj.Open(ctx, objstore.BlobKey(*row.BlobSha256))
	if err != nil {
		return nil, err
	}
	defer r.Close()
	b, err := io.ReadAll(io.LimitReader(r, MaxTextEdit+1))
	if err != nil {
		return nil, err
	}
	if !utf8.Valid(b) || bytes.IndexByte(b, 0) >= 0 {
		return nil, errf("NOT_TEXT", "不是 UTF-8 文本,不能在线编辑")
	}
	return &TextFile{Content: string(b), Sha256: *row.BlobSha256, Size: int64(len(b))}, nil
}

// SaveTextFile 把文本文件的内容换成 content。baseSha 是打开时的 sha256:对不上说明在别处改过了,
// 报 EDIT_CONFLICT(给空串 = 不检查,客户端选了「仍然保存」)。内容没变时什么都不做。
func (s *Uploads) SaveTextFile(ctx context.Context, owner, nodeID uuid.UUID, content, baseSha string) (*store.Node, error) {
	if len(content) > MaxTextEdit {
		return nil, errf("TOO_LARGE", "超过 2 MB 的文件不能在线编辑,请下载后修改")
	}
	row, err := s.NodeInfo(ctx, owner, nodeID)
	if err != nil {
		return nil, err
	}
	if row.Kind != "file" {
		return nil, errf("NOT_A_FILE", "文件夹不能编辑")
	}
	current := ""
	if row.BlobSha256 != nil {
		current = *row.BlobSha256
	}
	if baseSha != "" && baseSha != current {
		return nil, errEditConflict
	}
	data := []byte(content)
	sha := shaHex(data)
	if sha == current {
		n, err := s.q.GetNode(ctx, nodeID)
		return &n, err
	}
	if err := s.putBlobBytes(ctx, sha, data, mimeByName(row.Name)); err != nil {
		return nil, err
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	locked, err := q.GetUserByIDForUpdate(ctx, owner)
	if err != nil {
		return nil, err
	}
	existing, err := q.GetActiveNodeForUpdate(ctx, store.GetActiveNodeForUpdateParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	// 读到 row 之后、锁住节点之前又被别处写过:同样算冲突
	if baseSha != "" && !sameBlob(existing.BlobID, row.BlobID) {
		return nil, errEditConflict
	}
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: int64(len(data)), Mime: mimeByName(existing.Name)})
	if err != nil {
		return nil, err
	}
	res, err := overwriteFileTx(ctx, q, s.versionKeep, locked, existing, blob)
	if err != nil {
		return nil, err
	}
	if res.Changed {
		act := fileAct(OpOverwrite, res.Node, blob.Size)
		act.Detail.Version = res.Version
		act.Detail.Note = "在线编辑"
		if err := recordActs(ctx, q, owner, act); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	if !blob.Verified {
		s.enqueue(ctx, "verify_hash", blob.ID)
	}
	s.notify.CheckQuota(ctx, owner)
	return &res.Node, nil
}

// CreateTextFile 在 parentID 下新建一个文本文件(同名自动改名),内容可以为空。
func (s *Uploads) CreateTextFile(ctx context.Context, owner uuid.UUID, parentID *uuid.UUID, name, content string) (*store.Node, error) {
	if len(content) > MaxTextEdit {
		return nil, errf("TOO_LARGE", "超过 2 MB 的内容请上传文件")
	}
	if err := s.nodes.ensureFolder(ctx, owner, parentID); err != nil {
		return nil, err
	}
	name, err := s.nodes.cleanName(ctx, owner, parentID, strings.TrimSpace(name))
	if err != nil {
		return nil, err
	}
	data := []byte(content)
	sha := shaHex(data)
	mime := mimeByName(name)
	if err := s.putBlobBytes(ctx, sha, data, mime); err != nil {
		return nil, err
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	locked, err := q.GetUserByIDForUpdate(ctx, owner)
	if err != nil {
		return nil, err
	}
	if locked.UsedBytes+int64(len(data)) > locked.QuotaBytes {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足")
	}
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: int64(len(data)), Mime: mime})
	if err != nil {
		return nil, err
	}
	node, err := q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parentID, Name: name, Kind: "file", BlobID: &blob.ID,
	})
	if isUniqueViolation(err) {
		return nil, errf("NAME_CONFLICT", "同名文件已存在")
	}
	if err != nil {
		return nil, err
	}
	if err := q.IncrementBlobRef(ctx, blob.ID); err != nil {
		return nil, err
	}
	if err := q.AddUsedBytes(ctx, store.AddUsedBytesParams{ID: owner, UsedBytes: blob.Size}); err != nil {
		return nil, err
	}
	if err := q.MarkAncestorsStale(ctx, node.ID); err != nil {
		return nil, err
	}
	if err := recordActs(ctx, q, owner, fileAct(OpUpload, node, blob.Size)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	if !blob.Verified {
		s.enqueue(ctx, "verify_hash", blob.ID)
	}
	s.notify.CheckQuota(ctx, owner)
	return &node, nil
}

var errEditConflict = errf("EDIT_CONFLICT", "这个文件在你打开之后又被改过了")

// putBlobBytes 内容寻址:对象还没有才写(同一份内容并发写同一个 key,无害)。
func (s *Uploads) putBlobBytes(ctx context.Context, sha string, data []byte, mime string) error {
	if _, err := s.q.GetBlobBySha256(ctx, sha); err == nil {
		return nil
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	return s.obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader(data), int64(len(data)), mime)
}

func shaHex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func sameBlob(a, b *uuid.UUID) bool {
	if a == nil || b == nil {
		return a == b
	}
	return *a == *b
}
