package service

import (
	"context"
	"errors"
	"io"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/mdrender"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// Markdown 渲染(doc/23 五.2):读 .md、按它所在的文件夹解析里面的相对路径。渲染本身在 mdrender。
type Markdown struct {
	q   *store.Queries
	obj *objstore.Store
}

func NewMarkdown(pool *pgxpool.Pool, obj *objstore.Store) *Markdown {
	return &Markdown{q: store.New(pool), obj: obj}
}

var markdownExts = map[string]bool{".md": true, ".markdown": true, ".mdown": true, ".mkd": true}

// IsMarkdownName 按 Markdown 渲染的文件。
func IsMarkdownName(name string) bool { return markdownExts[strings.ToLower(filepath.Ext(name))] }

// Load 读一篇 Markdown(≤ 2 MB)。owner 对分享访客是分享的属主,访问范围由调用方先校验。
func (m *Markdown) Load(ctx context.Context, owner, nodeID uuid.UUID) (store.GetNodeWithBlobRow, []byte, error) {
	row, err := m.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return row, nil, ErrNotFound
	}
	if err != nil {
		return row, nil, err
	}
	if row.Kind != "file" || row.BlobSha256 == nil || row.BlobSize == nil || !IsMarkdownName(row.Name) {
		return row, nil, errf("UNSUPPORTED_TYPE", "不是 Markdown 文件")
	}
	if *row.BlobSize > mdrender.MaxSize {
		return row, nil, errf("TOO_LARGE", "文件超过 2 MB,请下载或看源文件")
	}
	r, err := m.obj.Open(ctx, objstore.BlobKey(*row.BlobSha256))
	if err != nil {
		return row, nil, err
	}
	defer r.Close()
	b, err := io.ReadAll(io.LimitReader(r, mdrender.MaxSize+1))
	return row, b, err
}

// Resolve 从 base(那篇 .md 所在的文件夹,nil = 根)出发找相对路径 rel 指向的节点:
// ".." 往上(到根为止),"/" 开头从根找。只找属主自己的、没进回收站的节点。
func (m *Markdown) Resolve(ctx context.Context, owner uuid.UUID, base *uuid.UUID, rel string) (store.Node, error) {
	cur := base
	if strings.HasPrefix(rel, "/") {
		cur = nil
	}
	var found store.Node
	segs := strings.Split(strings.Trim(rel, "/"), "/")
	for i, seg := range segs {
		switch seg {
		case "", ".":
			continue
		case "..":
			if cur != nil {
				n, err := m.q.GetNode(ctx, *cur)
				if err != nil {
					return store.Node{}, ErrNotFound
				}
				cur = n.ParentID
			}
			continue
		}
		n, err := m.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: cur, Name: seg})
		if errors.Is(err, pgx.ErrNoRows) {
			return store.Node{}, ErrNotFound
		}
		if err != nil {
			return store.Node{}, err
		}
		if i < len(segs)-1 && n.Kind != "folder" {
			return store.Node{}, ErrNotFound
		}
		found, cur = n, &n.ID
	}
	if found.ID == uuid.Nil {
		return store.Node{}, ErrNotFound
	}
	return found, nil
}

// AssetURL 相对路径指向的文件去哪:图片给 2048 缩略图(没生成就原图),其余给内联的签名地址。
func (m *Markdown) AssetURL(ctx context.Context, n store.Node) (string, error) {
	if n.Kind != "file" || n.BlobID == nil {
		return "", ErrNotFound
	}
	blob, err := m.q.GetBlob(ctx, *n.BlobID)
	if err != nil {
		return "", err
	}
	if strings.HasPrefix(blob.Mime, "image/") && blob.Size > 512<<10 {
		if ds, err := m.q.GetDerivatives(ctx, blob.ID); err == nil {
			for _, d := range ds {
				if d.Kind == "thumb2048" {
					return m.obj.PresignGetInline(ctx, d.MinioKey, derivedMime(d.Kind))
				}
			}
		}
	}
	return m.obj.PresignGetInline(ctx, objstore.BlobKey(blob.Sha256), blob.Mime)
}
