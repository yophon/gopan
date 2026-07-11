package service

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// PackMaxBytes 打包下载总量上限(zip 只打包不压缩,流式经过 Go)。
const PackMaxBytes int64 = 2 << 30

type PackEntry struct {
	Path  string // zip 内相对路径
	Sha   string // 空 = 目录项
	Size  int64
	IsDir bool
}

type Packer struct {
	q      *store.Queries
	obj    *objstore.Store
	shares *Shares
}

func NewPacker(q *store.Queries, obj *objstore.Store, shares *Shares) *Packer {
	return &Packer{q: q, obj: obj, shares: shares}
}

// Collect 鉴权 + 遍历子树,产出打包清单。错误在写响应头之前全部暴露。
func (p *Packer) Collect(ctx context.Context, ident *Identity, ids []uuid.UUID) (string, []PackEntry, error) {
	if len(ids) == 0 {
		return "", nil, errf("INVALID_INPUT", "未指定要打包的内容")
	}
	var entries []PackEntry
	var total int64
	used := map[string]int{} // 多选根可能重名(来自不同目录),打包内解冲突
	zipName := "gopan-打包"

	for i, id := range ids {
		if err := p.shares.Authorize(ctx, ident, id); err != nil {
			return "", nil, err
		}
		root, err := p.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: id, OwnerID: ident.UserID})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				return "", nil, ErrNotFound
			}
			return "", nil, err
		}
		name := root.Name
		if n := used[name]; n > 0 {
			ext := filepath.Ext(name)
			name = fmt.Sprintf("%s (%d)%s", strings.TrimSuffix(name, ext), n, ext)
		}
		used[root.Name]++
		if i == 0 && len(ids) == 1 {
			zipName = root.Name
		}

		if root.Kind == "file" {
			if root.BlobSha256 == nil {
				continue
			}
			size := int64(0)
			if root.BlobSize != nil {
				size = *root.BlobSize
			}
			total += size
			entries = append(entries, PackEntry{Path: name, Sha: *root.BlobSha256, Size: size})
		} else {
			sub, subTotal, err := p.walk(ctx, id, name)
			if err != nil {
				return "", nil, err
			}
			total += subTotal
			entries = append(entries, PackEntry{Path: name, IsDir: true})
			entries = append(entries, sub...)
		}
		if total > PackMaxBytes {
			return "", nil, errf("PACK_TOO_LARGE", "打包内容超过 2GB 上限,请分批下载")
		}
	}
	return zipName + ".zip", entries, nil
}

func (p *Packer) walk(ctx context.Context, folderID uuid.UUID, prefix string) ([]PackEntry, int64, error) {
	children, err := p.q.ListActiveChildrenLite(ctx, &folderID)
	if err != nil {
		return nil, 0, err
	}
	var entries []PackEntry
	var total int64
	for _, c := range children {
		path := prefix + "/" + c.Name
		if c.Kind == "folder" {
			entries = append(entries, PackEntry{Path: path, IsDir: true})
			sub, subTotal, err := p.walk(ctx, c.ID, path)
			if err != nil {
				return nil, 0, err
			}
			entries = append(entries, sub...)
			total += subTotal
			continue
		}
		if c.BlobSha256 == nil {
			continue
		}
		size := int64(0)
		if c.BlobSize != nil {
			size = *c.BlobSize
		}
		entries = append(entries, PackEntry{Path: path, Sha: *c.BlobSha256, Size: size})
		total += size
	}
	return entries, total, nil
}

// Stream 流式写 zip(Store 不压缩,CPU 便宜、可顺序流出)。
func (p *Packer) Stream(ctx context.Context, entries []PackEntry, w io.Writer) error {
	zw := zip.NewWriter(w)
	now := time.Now()
	for _, e := range entries {
		if e.IsDir {
			if _, err := zw.CreateHeader(&zip.FileHeader{
				Name: e.Path + "/", Modified: now,
			}); err != nil {
				return err
			}
			continue
		}
		fw, err := zw.CreateHeader(&zip.FileHeader{
			Name: e.Path, Method: zip.Store, Modified: now,
		})
		if err != nil {
			return err
		}
		rc, err := p.obj.Open(ctx, objstore.BlobKey(e.Sha))
		if err != nil {
			return err
		}
		_, err = io.Copy(fw, rc)
		rc.Close()
		if err != nil {
			return err
		}
	}
	return zw.Close()
}
