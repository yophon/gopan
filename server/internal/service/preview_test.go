package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func ptr[T any](v T) *T { return &v }

func TestClassifyPreview(t *testing.T) {
	kb := int64(1024)
	big := int64(2 << 20)
	cases := []struct {
		name string
		mime string
		size int64
		want string
	}{
		{"a.jpg", "image/jpeg", kb, service.PvImage},
		{"a.bin", "image/webp", kb, service.PvImage}, // mime 优先于扩展名
		{"a.pdf", "application/pdf", kb, service.PvPDF},
		{"a.mp4", "video/mp4", kb, service.PvVideo},
		{"a.mp3", "audio/mpeg", kb, service.PvAudio},
		{"a.docx", "application/octet-stream", kb, service.PvOffice},
		{"a.RTF", "application/octet-stream", kb, service.PvOffice}, // 扩展名大小写不敏感
		{"a.txt", "text/plain", kb, service.PvText},
		{"a.go", "application/octet-stream", kb, service.PvText}, // 代码扩展名
		{"a.txt", "text/plain", big, service.PvNone},             // 超 1MB 文本不预览
		{"a.exe", "application/octet-stream", kb, service.PvNone},
	}
	for _, c := range cases {
		got := service.ClassifyPreview(c.name, &c.mime, &c.size)
		if got != c.want {
			t.Errorf("%s(%s,%d): got %s want %s", c.name, c.mime, c.size, got, c.want)
		}
	}
}

// 预签名是纯本地 HMAC 计算,不打网络:preview 测试用假 endpoint 构造 objstore 即可。
func fakeObj(t *testing.T) *objstore.Store {
	t.Helper()
	obj, err := objstore.New(&config.Config{
		S3Endpoint: "127.0.0.1:19999", S3PublicEndpoint: "127.0.0.1:19999",
		S3Key: "k", S3Secret: "s", S3Bucket: "fake-bkt", S3Region: "us-east-1",
		PresignPutTTL: time.Hour, PresignGetTTL: 15 * time.Minute,
	})
	if err != nil {
		t.Fatal(err)
	}
	return obj
}

func TestPreviewPaths(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	previews := service.NewPreviews(pool, fakeObj(t))
	q := store.New(pool)

	sha := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	blob, err := q.UpsertBlob(ctx, store.UpsertBlobParams{
		ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: 512, Mime: "application/octet-stream",
	})
	if err != nil {
		t.Fatal(err)
	}

	// ForList:图片乐观签发 thumbUrl,零数据库依赖
	info := previews.ForList(ctx, "p.png", &sha, ptr("image/png"), ptr(int64(512)))
	if info.Kind != service.PvImage || info.ThumbURL == nil {
		t.Fatalf("列表路径应乐观签发缩略图:%+v", info)
	}
	// ForList:无 blob 的节点(文件夹/异常)→ NONE
	if got := previews.ForList(ctx, "x", nil, nil, nil); got.Kind != service.PvNone {
		t.Fatalf("无 sha 应 NONE,got %+v", got)
	}

	// ForNode 的 OFFICE 状态机:无任务无产物 → status 空(前端引导 requestPreview)
	odoc, err := previews.ForNode(ctx, "r.docx", &sha, ptr("application/octet-stream"), ptr(int64(512)))
	if err != nil || odoc.Kind != service.PvOffice || odoc.Status != nil {
		t.Fatalf("未触发转换 status 应为空:%+v err=%v", odoc, err)
	}
	// 有 pending 任务 → PENDING
	if err := q.EnqueueTask(ctx, store.EnqueueTaskParams{ID: uuid.Must(uuid.NewV7()), Kind: "office_pdf", BlobID: blob.ID}); err != nil {
		t.Fatal(err)
	}
	odoc, err = previews.ForNode(ctx, "r.docx", &sha, ptr("application/octet-stream"), ptr(int64(512)))
	if err != nil || odoc.Status == nil || *odoc.Status != "PENDING" {
		t.Fatalf("应 PENDING:%+v err=%v", odoc, err)
	}
	// 有产物 → DONE + contentUrl(不管任务状态)
	if err := q.UpsertDerivative(ctx, store.UpsertDerivativeParams{
		BlobID: blob.ID, Kind: "pdf", MinioKey: objstore.DerivedKey(sha, "pdf"), Size: 100,
	}); err != nil {
		t.Fatal(err)
	}
	odoc, err = previews.ForNode(ctx, "r.docx", &sha, ptr("application/octet-stream"), ptr(int64(512)))
	if err != nil || odoc.Status == nil || *odoc.Status != "DONE" || odoc.ContentURL == nil {
		t.Fatalf("有产物应 DONE 且带 contentUrl:%+v err=%v", odoc, err)
	}

	// 音频时长来自 media_meta
	if err := q.SetBlobMediaMeta(ctx, store.SetBlobMediaMetaParams{ID: blob.ID, MediaMeta: []byte(`{"duration_sec": 12.7}`)}); err != nil {
		t.Fatal(err)
	}
	aud, err := previews.ForNode(ctx, "s.mp3", &sha, ptr("audio/mpeg"), ptr(int64(512)))
	if err != nil || aud.DurationSec == nil || *aud.DurationSec != 12 {
		t.Fatalf("音频时长应 12:%+v err=%v", aud, err)
	}
}

func TestPreviewRequest(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	previews := service.NewPreviews(pool, fakeObj(t))
	q := store.New(pool)

	var enqueued []string
	previews.SetEnqueue(func(_ context.Context, kind string, _ uuid.UUID) {
		enqueued = append(enqueued, kind)
	})

	res, err := auth.Register(ctx, "pv_"+uuid.NewString()[:8], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	sha := "0000000000000000000000000000000000000000000000000000000000000001"
	blob, _ := q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: sha, Size: 64, Mime: "application/octet-stream"})
	node, err := q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, Name: "r.docx", Kind: "file", BlobID: &blob.ID,
	})
	if err != nil {
		t.Fatal(err)
	}

	// 首次 Request:入队 office_pdf 并乐观返回 PENDING
	info, err := previews.Request(ctx, owner, node.ID)
	if err != nil || info.Kind != service.PvOffice || info.Status == nil || *info.Status != "PENDING" {
		t.Fatalf("Request 应 PENDING:%+v err=%v", info, err)
	}
	if len(enqueued) != 1 || enqueued[0] != "office_pdf" {
		t.Fatalf("应入队 office_pdf,got %v", enqueued)
	}
	// 别人的节点 → NOT_FOUND
	if _, err := previews.Request(ctx, uuid.Must(uuid.NewV7()), node.ID); err != service.ErrNotFound {
		t.Fatalf("越权 Request 应 NOT_FOUND,got %v", err)
	}
}
