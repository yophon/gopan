package service_test

// upload / pack 的集成测试连真 MinIO(TEST_S3_ENDPOINT,未设则跳过):
// multipart、预签名、etag 回填这些行为 mock 不出来,测真的。
//   TEST_S3_ENDPOINT=127.0.0.1:9000 TEST_DB_URL=... go test ./...

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"os"
	"sort"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func setupS3(t *testing.T) *objstore.Store {
	t.Helper()
	ep := os.Getenv("TEST_S3_ENDPOINT")
	if ep == "" {
		t.Skip("TEST_S3_ENDPOINT 未设置,跳过 MinIO 集成测试")
	}
	obj, err := objstore.New(&config.Config{
		S3Endpoint: ep, S3PublicEndpoint: ep,
		S3Key: envOr("TEST_S3_KEY", "gopan"), S3Secret: envOr("TEST_S3_SECRET", "gopan-minio-dev"),
		S3Bucket: "gopan-test", S3Region: "us-east-1",
		PresignPutTTL: time.Hour, PresignGetTTL: 15 * time.Minute,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := obj.EnsureBucket(context.Background()); err != nil {
		t.Fatal(err)
	}
	return obj
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func newUploads(t *testing.T, pool *pgxpool.Pool, obj *objstore.Store) (*service.Uploads, *service.Nodes, uuid.UUID) {
	t.Helper()
	ctx := context.Background()
	auth := newAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	res, err := auth.Register(ctx, "up_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	return uploads, nodes, res.User.ID
}

func shaHex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func httpPut(t *testing.T, url string, body []byte) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPut, url, bytes.NewReader(body))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("分片 PUT 状态码 %d", resp.StatusCode)
	}
}

func TestUploadValidation(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)

	if _, err := uploads.Init(ctx, owner, nil, "a.bin", "XYZ", 100); err == nil {
		t.Fatal("非法 sha 应被拒")
	}
	if _, err := uploads.Init(ctx, owner, nil, "a.bin", shaHex([]byte("x")), 0); err == nil {
		t.Fatal("size 0 应被拒")
	}
	// 超配额(默认配额 1GB,见 newAuth)
	if _, err := uploads.Init(ctx, owner, nil, "a.bin", shaHex([]byte("x")), 2<<30); err == nil {
		t.Fatal("超配额应被拒")
	}
}

func TestUploadCompleteAndInstant(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)
	q := store.New(pool)

	data := bytes.Repeat([]byte("gopan-m6!"), 1000) // 9KB,单片
	sha := shaHex(data)

	// 首传:开会话 → 直传 → complete(空 etags,走服务端回填)
	init, err := uploads.Init(ctx, owner, nil, "a.bin", sha, int64(len(data)))
	if err != nil || init.Instant || init.Session == nil {
		t.Fatalf("首传应开会话:%+v err=%v", init, err)
	}
	for n, u := range init.Session.PartURLs {
		ps := int(init.Session.Session.PartSize)
		end := min(n*ps, len(data))
		httpPut(t, u, data[(n-1)*ps:end])
	}
	node, err := uploads.Complete(ctx, owner, init.Session.Session.ID, nil)
	if err != nil {
		t.Fatal(err)
	}
	// 重复 complete → 状态机拒绝
	if _, err := uploads.Complete(ctx, owner, init.Session.Session.ID, nil); err == nil {
		t.Fatal("重复 complete 应被拒")
	}

	// 未 verified 不给秒传
	init2, err := uploads.Init(ctx, owner, nil, "b.bin", sha, int64(len(data)))
	if err != nil || init2.Instant {
		t.Fatalf("未 verified 不应秒传:%+v err=%v", init2, err)
	}
	_ = uploads.Abort(ctx, owner, init2.Session.Session.ID)

	// 标 verified → 秒传,ref_count 与配额随之走
	row, err := q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: node.ID, OwnerID: owner})
	if err != nil || row.BlobID == nil {
		t.Fatal(err)
	}
	if err := q.MarkBlobVerified(ctx, *row.BlobID); err != nil {
		t.Fatal(err)
	}
	before, _ := q.GetUserByID(ctx, owner)
	init3, err := uploads.Init(ctx, owner, nil, "c.bin", sha, int64(len(data)))
	if err != nil || !init3.Instant || init3.Node == nil {
		t.Fatalf("verified 后应秒传:%+v err=%v", init3, err)
	}
	after, _ := q.GetUserByID(ctx, owner)
	if after.UsedBytes-before.UsedBytes != int64(len(data)) {
		t.Fatalf("秒传应照扣配额,got +%d", after.UsedBytes-before.UsedBytes)
	}
	b, _ := q.GetBlob(ctx, *row.BlobID)
	if b.RefCount != 2 {
		t.Fatalf("ref_count 应为 2,got %d", b.RefCount)
	}

	// 秒传但 size 与 blob 不符 → 拒绝(hash 与大小不匹配)
	if _, err := uploads.Init(ctx, owner, nil, "d.bin", sha, int64(len(data))+1); err == nil {
		t.Fatal("同 hash 不同 size 应被拒")
	}

	// 下载:预签名直取,字节一致;文件夹无下载
	du, err := uploads.DownloadURL(ctx, owner, node.ID)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.Get(du)
	if err != nil {
		t.Fatal(err)
	}
	gotData, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(gotData, data) {
		t.Fatal("下载字节不符")
	}
	if _, err := uploads.DownloadURL(ctx, uuid.Must(uuid.NewV7()), node.ID); err != service.ErrNotFound {
		t.Fatalf("越权下载应 NOT_FOUND,got %v", err)
	}
}

func TestUploadMissingPartAndResume(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	uploads, _, owner := newUploads(t, pool, obj)

	data := bytes.Repeat([]byte{0xAB}, 6<<20) // 6MiB → 5MiB+1MiB 两片
	sha := shaHex(data)
	init, err := uploads.Init(ctx, owner, nil, "big.bin", sha, int64(len(data)))
	if err != nil || len(init.Session.PartURLs) != 2 {
		t.Fatalf("应两片:%+v err=%v", init, err)
	}
	sid := init.Session.Session.ID

	// 只传第 1 片 → complete 报缺片
	httpPut(t, init.Session.PartURLs[1], data[:5<<20])
	if _, err := uploads.Complete(ctx, owner, sid, nil); err == nil {
		t.Fatal("缺片 complete 应被拒")
	}

	// 断点续传:Session 视图应只签缺失的第 2 片
	view, err := uploads.Session(ctx, owner, sid)
	if err != nil {
		t.Fatal(err)
	}
	if len(view.Uploaded) != 1 || view.Uploaded[0] != 1 {
		t.Fatalf("已传分片应为 [1],got %v", view.Uploaded)
	}
	u2, ok := view.PartURLs[2]
	if !ok || len(view.PartURLs) != 1 {
		t.Fatalf("应只签第 2 片,got %v", view.PartURLs)
	}
	httpPut(t, u2, data[5<<20:])
	if _, err := uploads.Complete(ctx, owner, sid, nil); err != nil {
		t.Fatalf("补齐后 complete 应成功:%v", err)
	}
}

func TestPackCollectStreamAndGate(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	auth := newAuth(pool)
	shares := service.NewShares(store.New(pool), auth)
	packer := service.NewPacker(store.New(pool), obj, shares)
	q := store.New(pool)

	res, _ := auth.Register(ctx, "pk_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	owner := res.User.ID
	ident := &service.Identity{UserID: owner, Scope: service.ScopeUser}

	// 造树:root/{a.txt, empty/, sub/b.txt},对象直写 MinIO
	mkblob := func(content []byte) (uuid.UUID, string) {
		sha := shaHex(content)
		if err := obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader(content), int64(len(content)), "text/plain"); err != nil {
			t.Fatal(err)
		}
		b, err := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: int64(len(content)), Mime: "text/plain"})
		if err != nil {
			t.Fatal(err)
		}
		return b.ID, sha
	}
	mknode := func(parent *uuid.UUID, name, kind string, blobID *uuid.UUID) store.Node {
		n, err := q.CreateNode(ctx, store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: parent, Name: name, Kind: kind, BlobID: blobID})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	aData, bData := []byte("content-a"), []byte("content-bb")
	aBlob, _ := mkblob(aData)
	bBlob, _ := mkblob(bData)
	root := mknode(nil, "root", "folder", nil)
	mknode(&root.ID, "a.txt", "file", &aBlob)
	mknode(&root.ID, "empty", "folder", nil)
	sub := mknode(&root.ID, "sub", "folder", nil)
	mknode(&sub.ID, "b.txt", "file", &bBlob)

	name, entries, err := packer.Collect(ctx, ident, []uuid.UUID{root.ID})
	if err != nil || name != "root.zip" {
		t.Fatalf("collect: name=%q err=%v", name, err)
	}
	var buf bytes.Buffer
	if err := packer.Stream(ctx, entries, &buf); err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, f := range zr.File {
		got = append(got, f.Name)
	}
	sort.Strings(got)
	want := []string{"root/", "root/a.txt", "root/empty/", "root/sub/", "root/sub/b.txt"}
	if len(got) != len(want) {
		t.Fatalf("zip 内容 %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("zip 内容 %v want %v", got, want)
		}
	}
	rc, _ := zr.Open("root/sub/b.txt")
	gotB, _ := io.ReadAll(rc)
	rc.Close()
	if !bytes.Equal(gotB, bData) {
		t.Fatal("zip 内文件字节不符")
	}

	// 越权:别人的身份 collect → NOT_FOUND
	other := &service.Identity{UserID: uuid.Must(uuid.NewV7()), Scope: service.ScopeUser}
	if _, _, err := packer.Collect(ctx, other, []uuid.UUID{root.ID}); err != service.ErrNotFound {
		t.Fatalf("越权应 NOT_FOUND,got %v", err)
	}

	// Gate:限频 + 并发上限
	packer.SetRateLimit(time.Hour, 2)
	r1, err := packer.Gate(ident)
	if err != nil {
		t.Fatal(err)
	}
	r2, err := packer.Gate(ident)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := packer.Gate(ident); err == nil {
		t.Fatal("超突发应限频") // 第 3 次:令牌桶(burst 2)先拦
	}
	packer.SetRateLimit(time.Hour, 100)
	if _, err := packer.Gate(ident); err == nil {
		t.Fatal("两个并发槽占满后应 PACK_BUSY")
	}
	r1()
	r3, err := packer.Gate(ident)
	if err != nil {
		t.Fatalf("释放后应可再获取:%v", err)
	}
	r3()
	r2()
}
