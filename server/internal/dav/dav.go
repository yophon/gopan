// Package dav 把 gopan 的 node/blob 模型适配到 golang.org/x/net/webdav。
// 协议状态机(PROPFIND XML、Depth、Overwrite、LOCK)交给 x/net/webdav,
// 这里只做三件事:Basic 认证(应用密码)、路径→节点解析、字节的读出与写入。
package dav

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"io/fs"
	"mime"
	"net"
	"net/http"
	"os"
	"path"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"golang.org/x/net/webdav"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// 单文件写入上限:CopyObject 单调用 5GiB。更大的文件请走 Web 端分片直传。
const maxWriteBytes = 5 << 30

type ctxKey int

const keyOwner ctxKey = iota

// Backend 实现 webdav.FileSystem,属主从请求上下文取(认证中间件注入)。
type Backend struct {
	q       *store.Queries
	obj     *objstore.Store
	nodes   *service.Nodes
	uploads *service.Uploads
	// 写入并发闸:流式转存每路占 16MB 缓冲 + 一路 MinIO 连接,满拒不排队
	writeSem chan struct{}
}

func NewBackend(q *store.Queries, obj *objstore.Store, nodes *service.Nodes, uploads *service.Uploads) *Backend {
	return &Backend{q: q, obj: obj, nodes: nodes, uploads: uploads, writeSem: make(chan struct{}, 2)}
}

// Handler 组装认证 + webdav 协议处理,挂 /dav 前缀。
func Handler(ap *service.AppPasswords, b *Backend) http.Handler {
	h := &webdav.Handler{
		Prefix:     "/dav",
		FileSystem: b,
		LockSystem: webdav.NewMemLS(),
	}
	return withBasicAuth(h, ap)
}

func withBasicAuth(next http.Handler, ap *service.AppPasswords) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		if !ok {
			w.Header().Set("WWW-Authenticate", `Basic realm="gopan WebDAV(应用密码,非账号密码)"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		owner, err := ap.Authenticate(r.Context(), user, pass, ip)
		if err != nil {
			status := http.StatusUnauthorized
			if errors.Is(err, service.ErrRateLimited) {
				status = http.StatusTooManyRequests
			} else {
				w.Header().Set("WWW-Authenticate", `Basic realm="gopan WebDAV(应用密码,非账号密码)"`)
			}
			http.Error(w, "unauthorized", status)
			return
		}
		// 认证通过后豁免 Server 级 60s 读写超时:大文件 PUT/GET 是分钟级请求。
		// 只对已认证请求放开,未认证打不进来,DoS 面由限速兜着。
		rc := http.NewResponseController(w)
		_ = rc.SetReadDeadline(time.Time{})
		_ = rc.SetWriteDeadline(time.Time{})
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), keyOwner, owner)))
	})
}

func ownerFrom(ctx context.Context) (uuid.UUID, error) {
	id, ok := ctx.Value(keyOwner).(uuid.UUID)
	if !ok {
		return uuid.Nil, os.ErrPermission
	}
	return id, nil
}

// ---- 路径解析 ----

// resolve 把 "/a/b/c" 解析成节点;根返回 (nil, nil)。
func (b *Backend) resolve(ctx context.Context, owner uuid.UUID, name string) (*store.Node, error) {
	parts := splitPath(name)
	var parent *uuid.UUID
	var cur *store.Node
	for _, p := range parts {
		n, err := b.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{
			OwnerID: owner, ParentID: parent, Name: p,
		})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				return nil, os.ErrNotExist
			}
			return nil, err
		}
		cur = &n
		parent = &n.ID
	}
	return cur, nil
}

// resolveParent 拆出父目录节点(nil = 根)与叶子名。
func (b *Backend) resolveParent(ctx context.Context, owner uuid.UUID, name string) (*uuid.UUID, string, error) {
	parts := splitPath(name)
	if len(parts) == 0 {
		return nil, "", os.ErrInvalid // 根本身不可作为创建目标
	}
	leaf := parts[len(parts)-1]
	dir, err := b.resolve(ctx, owner, "/"+strings.Join(parts[:len(parts)-1], "/"))
	if err != nil {
		return nil, "", err
	}
	if dir == nil {
		return nil, leaf, nil
	}
	if dir.Kind != "folder" {
		return nil, "", os.ErrNotExist
	}
	return &dir.ID, leaf, nil
}

func splitPath(name string) []string {
	clean := path.Clean("/" + name)
	if clean == "/" {
		return nil
	}
	return strings.Split(strings.TrimPrefix(clean, "/"), "/")
}

// ---- FileInfo ----

type fileInfo struct {
	name string
	size int64
	dir  bool
	mod  time.Time
}

func (f fileInfo) Name() string { return f.name }
func (f fileInfo) Size() int64  { return f.size }
func (f fileInfo) Mode() fs.FileMode {
	if f.dir {
		return fs.ModeDir | 0o755
	}
	return 0o644
}
func (f fileInfo) ModTime() time.Time { return f.mod }
func (f fileInfo) IsDir() bool        { return f.dir }
func (f fileInfo) Sys() any           { return nil }

func nodeInfo(n *store.Node, size int64) fileInfo {
	return fileInfo{name: n.Name, size: size, dir: n.Kind == "folder", mod: n.UpdatedAt.Time}
}

// ---- FileSystem ----

func (b *Backend) Stat(ctx context.Context, name string) (os.FileInfo, error) {
	owner, err := ownerFrom(ctx)
	if err != nil {
		return nil, err
	}
	n, err := b.resolve(ctx, owner, name)
	if err != nil {
		return nil, err
	}
	if n == nil {
		return fileInfo{name: "/", dir: true, mod: time.Now()}, nil
	}
	return nodeInfo(n, b.nodeSize(ctx, n)), nil
}

func (b *Backend) nodeSize(ctx context.Context, n *store.Node) int64 {
	if n.Kind != "file" || n.BlobID == nil {
		return 0
	}
	blob, err := b.q.GetBlob(ctx, *n.BlobID)
	if err != nil {
		return 0
	}
	return blob.Size
}

func (b *Backend) Mkdir(ctx context.Context, name string, _ os.FileMode) error {
	owner, err := ownerFrom(ctx)
	if err != nil {
		return err
	}
	parent, leaf, err := b.resolveParent(ctx, owner, name)
	if err != nil {
		return err
	}
	// WebDAV 语义要求精确名:冲突就是 405,不做 Web 端的自动改名
	_, err = b.q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent,
		Name: leaf, Kind: "folder",
	})
	if isUnique(err) {
		return os.ErrExist
	}
	return err
}

func (b *Backend) RemoveAll(ctx context.Context, name string) error {
	owner, err := ownerFrom(ctx)
	if err != nil {
		return err
	}
	n, err := b.resolve(ctx, owner, name)
	if err != nil {
		return err
	}
	if n == nil {
		return os.ErrInvalid // 不允许删根
	}
	// DELETE = 软删进回收站,与 Web 端语义一致(误删可救)
	return b.nodes.Delete(ctx, owner, []uuid.UUID{n.ID})
}

func (b *Backend) Rename(ctx context.Context, oldName, newName string) error {
	owner, err := ownerFrom(ctx)
	if err != nil {
		return err
	}
	n, err := b.resolve(ctx, owner, oldName)
	if err != nil {
		return err
	}
	if n == nil {
		return os.ErrInvalid
	}
	newParent, newLeaf, err := b.resolveParent(ctx, owner, newName)
	if err != nil {
		return err
	}
	sameParent := (n.ParentID == nil && newParent == nil) ||
		(n.ParentID != nil && newParent != nil && *n.ParentID == *newParent)
	if !sameParent {
		if _, err := b.nodes.Move(ctx, owner, []uuid.UUID{n.ID}, newParent); err != nil {
			return mapServiceErr(err)
		}
	}
	if newLeaf != n.Name {
		if _, err := b.nodes.Rename(ctx, owner, n.ID, newLeaf); err != nil {
			return mapServiceErr(err)
		}
	}
	return nil
}

func (b *Backend) OpenFile(ctx context.Context, name string, flag int, _ os.FileMode) (webdav.File, error) {
	owner, err := ownerFrom(ctx)
	if err != nil {
		return nil, err
	}
	if flag&(os.O_WRONLY|os.O_RDWR) != 0 {
		return b.openWrite(ctx, owner, name)
	}
	n, err := b.resolve(ctx, owner, name)
	if err != nil {
		return nil, err
	}
	if n == nil || n.Kind == "folder" {
		return &dirFile{b: b, ctx: ctx, owner: owner, node: n}, nil
	}
	if n.BlobID == nil {
		return nil, os.ErrNotExist
	}
	blob, err := b.q.GetBlob(ctx, *n.BlobID)
	if err != nil {
		return nil, err
	}
	rc, err := b.obj.OpenSeeker(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return nil, err
	}
	return &readFile{ReadSeekCloser: rc, fi: nodeInfo(n, blob.Size)}, nil
}

func mapServiceErr(err error) error {
	var se *service.Error
	if errors.As(err, &se) {
		switch se.Code {
		case "NAME_CONFLICT":
			return os.ErrExist
		case "NOT_FOUND":
			return os.ErrNotExist
		}
	}
	if errors.Is(err, service.ErrNotFound) {
		return os.ErrNotExist
	}
	return err
}

func isUnique(err error) bool {
	var se *service.Error
	if errors.As(err, &se) {
		return se.Code == "NAME_CONFLICT"
	}
	return err != nil && strings.Contains(err.Error(), "23505")
}

// ---- 读 ----

type readFile struct {
	io.ReadSeekCloser
	fi fileInfo
}

func (f *readFile) Stat() (os.FileInfo, error)         { return f.fi, nil }
func (f *readFile) Readdir(int) ([]os.FileInfo, error) { return nil, os.ErrInvalid }
func (f *readFile) Write([]byte) (int, error)          { return 0, os.ErrPermission }

// ---- 目录 ----

type dirFile struct {
	b     *Backend
	ctx   context.Context
	owner uuid.UUID
	node  *store.Node // nil = 根
	read  bool
}

func (d *dirFile) Stat() (os.FileInfo, error) {
	if d.node == nil {
		return fileInfo{name: "/", dir: true, mod: time.Now()}, nil
	}
	return nodeInfo(d.node, 0), nil
}

// Readdir 一次性全量返回(WebDAV PROPFIND 无分页语义)。
func (d *dirFile) Readdir(count int) ([]os.FileInfo, error) {
	if d.read {
		return nil, io.EOF
	}
	d.read = true
	var parent *uuid.UUID
	if d.node != nil {
		parent = &d.node.ID
	}
	rows, err := d.b.q.ListChildrenAll(d.ctx, store.ListChildrenAllParams{OwnerID: d.owner, ParentID: parent})
	if err != nil {
		return nil, err
	}
	out := make([]os.FileInfo, 0, len(rows))
	for _, r := range rows {
		size := int64(0)
		if r.BlobSize != nil {
			size = *r.BlobSize
		}
		out = append(out, fileInfo{name: r.Name, size: size, dir: r.Kind == "folder", mod: r.UpdatedAt.Time})
	}
	return out, nil
}

func (d *dirFile) Close() error                   { return nil }
func (d *dirFile) Read([]byte) (int, error)       { return 0, os.ErrInvalid }
func (d *dirFile) Seek(int64, int) (int64, error) { return 0, os.ErrInvalid }
func (d *dirFile) Write([]byte) (int, error)      { return 0, os.ErrPermission }

// ---- 写:管道流式转存到临时对象,Close 时定稿 ----

func (b *Backend) openWrite(ctx context.Context, owner uuid.UUID, name string) (webdav.File, error) {
	parent, leaf, err := b.resolveParent(ctx, owner, name)
	if err != nil {
		return nil, err
	}
	// 目标是已有文件夹 → 不能 PUT
	if n, err := b.resolve(ctx, owner, name); err == nil && n != nil && n.Kind == "folder" {
		return nil, os.ErrExist
	}
	select {
	case b.writeSem <- struct{}{}:
	default:
		return nil, fmt.Errorf("并发写入已满,稍后重试")
	}
	tmpKey := "tmp/webdav/" + uuid.Must(uuid.NewV7()).String()
	pr, pw := io.Pipe()
	f := &writeFile{
		b: b, ctx: ctx, owner: owner, parent: parent, leaf: leaf,
		pw: pw, hasher: sha256.New(), tmpKey: tmpKey, done: make(chan struct{}),
	}
	go func() {
		defer close(f.done)
		ct := mime.TypeByExtension(path.Ext(leaf))
		if ct == "" {
			ct = "application/octet-stream"
		}
		// PutStream 内部按 16MB 分片攒块,内存可控
		f.putSize, f.putErr = b.obj.PutStream(context.WithoutCancel(ctx), tmpKey, pr, ct)
	}()
	return f, nil
}

type writeFile struct {
	b      *Backend
	ctx    context.Context
	owner  uuid.UUID
	parent *uuid.UUID
	leaf   string

	pw      *io.PipeWriter
	hasher  hash.Hash
	tmpKey  string
	written int64

	done    chan struct{}
	putSize int64
	putErr  error
	closed  bool
	node    *store.Node
}

func (f *writeFile) Write(p []byte) (int, error) {
	if f.written+int64(len(p)) > maxWriteBytes {
		return 0, fmt.Errorf("WebDAV 单文件上限 %dGiB,大文件请走网页端", maxWriteBytes>>30)
	}
	f.hasher.Write(p)
	n, err := f.pw.Write(p)
	f.written += int64(n)
	return n, err
}

func (f *writeFile) Close() error {
	if f.closed {
		return nil
	}
	f.closed = true
	defer func() { <-f.b.writeSem }()
	_ = f.pw.Close()
	<-f.done
	ctx := context.WithoutCancel(f.ctx)
	if f.putErr != nil {
		_ = f.b.obj.Remove(ctx, f.tmpKey)
		return f.putErr
	}
	sha := hex.EncodeToString(f.hasher.Sum(nil))
	node, err := f.b.uploads.CommitStreamed(ctx, f.owner, f.parent, f.leaf, sha, f.putSize, f.tmpKey)
	if err != nil {
		return mapServiceErr(err)
	}
	f.node = node
	return nil
}

func (f *writeFile) Read([]byte) (int, error) { return 0, os.ErrInvalid }

// Seek 只支持无位移查询(部分客户端 PUT 前探当前位置);随机写不支持。
func (f *writeFile) Seek(offset int64, whence int) (int64, error) {
	if offset == 0 && whence == io.SeekCurrent {
		return f.written, nil
	}
	if offset == 0 && whence == io.SeekStart && f.written == 0 {
		return 0, nil
	}
	return 0, fmt.Errorf("WebDAV 写入不支持随机寻址")
}

func (f *writeFile) Readdir(int) ([]os.FileInfo, error) { return nil, os.ErrInvalid }

func (f *writeFile) Stat() (os.FileInfo, error) {
	return fileInfo{name: f.leaf, size: f.written, mod: time.Now()}, nil
}
