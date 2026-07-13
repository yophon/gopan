package worker

// derive.go 的集成测试:缩略图、媒体探测、视频封面、Office 转 PDF、派生入队。
// 基础依赖同 worker_test.go(TEST_DB_URL + TEST_S3_ENDPOINT,缺一跳过);
// ffmpeg/ffprobe、Gotenberg 这类外部工具缺失时单独优雅 skip,不让 CI 挂掉。

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/disintegration/imaging"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// seedBlobWithObject 算内容 sha256,对象传 MinIO,blobs 插行,返回 (blobID, sha)。
func seedBlobWithObject(t *testing.T, pool *pgxpool.Pool, obj *objstore.Store, content []byte, mime string) (uuid.UUID, string) {
	t.Helper()
	ctx := context.Background()
	sum := sha256.Sum256(content)
	sha := hex.EncodeToString(sum[:])
	if err := obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader(content), int64(len(content)), mime); err != nil {
		t.Fatal(err)
	}
	id := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO blobs (id, sha256, size, mime, ref_count) VALUES ($1, $2, $3, $4, 1)`,
		id, sha, len(content), mime); err != nil {
		t.Fatal(err)
	}
	return id, sha
}

// pngBytes 用标准库现场画一张真实 PNG(渐变填充,保证可解码)。
func pngBytes(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x * 255 / w), uint8(y * 255 / h), 128, 255})
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// readObject 从 MinIO 读回整个对象(Open 惰性,读到才见错)。
func readObject(t *testing.T, obj *objstore.Store, key string) []byte {
	t.Helper()
	rc, err := obj.Open(context.Background(), key)
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Close()
	data, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("读对象 %s 失败: %v", key, err)
	}
	return data
}

func TestMakeThumbs(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	// 1200x800:thumb256 要缩,thumb2048 不超限应保持原尺寸,两条分支都踩到
	blob, sha := seedBlobWithObject(t, pool, obj, pngBytes(t, 1200, 800), "image/png")

	if err := w.makeThumbs(ctx, blob); err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"thumb256", "thumb2048"} {
		var key string
		var size int64
		if err := pool.QueryRow(ctx,
			`SELECT minio_key, size FROM derivatives WHERE blob_id = $1 AND kind = $2`, blob, kind).
			Scan(&key, &size); err != nil {
			t.Fatalf("%s 派生行缺失: %v", kind, err)
		}
		if key != objstore.DerivedKey(sha, kind) || size <= 0 {
			t.Fatalf("%s 派生行异常: key=%s size=%d", kind, key, size)
		}
		img, err := imaging.Decode(bytes.NewReader(readObject(t, obj, key)))
		if err != nil {
			t.Fatalf("%s 应是可解码的 JPEG: %v", kind, err)
		}
		b := img.Bounds()
		switch kind {
		case "thumb256":
			if b.Dx() > 256 || b.Dy() > 256 {
				t.Fatalf("thumb256 应缩到 256 内,got %dx%d", b.Dx(), b.Dy())
			}
		case "thumb2048":
			if b.Dx() != 1200 || b.Dy() != 800 {
				t.Fatalf("未超 2048 的原图应保持原尺寸,got %dx%d", b.Dx(), b.Dy())
			}
		}
	}
	// 幂等:重跑走 UpsertDerivative,不应报错
	if err := w.makeThumbs(ctx, blob); err != nil {
		t.Fatal(err)
	}
}

func TestMakeThumbsSkipsHuge(t *testing.T) {
	w, pool, _ := setup(t)
	ctx := context.Background()
	// 只插 blob 行不传对象:超过解码上限应直接跳过,根本不碰 MinIO
	id := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO blobs (id, sha256, size, mime) VALUES ($1, $2, $3, 'image/png')`,
		id, hex.EncodeToString(bytes.Repeat([]byte{0x20}, 32)), int64(maxImageBytes)+1); err != nil {
		t.Fatal(err)
	}
	if err := w.makeThumbs(ctx, id); err != nil {
		t.Fatalf("超大图应静默跳过,got %v", err)
	}
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM derivatives WHERE blob_id = $1`, id).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("超大图不应产出派生物")
	}
}

func TestMakeThumbsBadImage(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	blob, _ := seedBlobWithObject(t, pool, obj, []byte("这不是一张图"), "image/png")
	err := w.makeThumbs(ctx, blob)
	if err == nil || !strings.Contains(err.Error(), "图片解码失败") {
		t.Fatalf("坏图应报解码失败,got %v", err)
	}
}

func TestProbeMediaAndVideoCover(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	ffmpegPath, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("本机无 ffmpeg,跳过音视频派生测试")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("本机无 ffprobe,跳过音视频派生测试")
	}
	// 现场生成 2 秒 64x64 测试视频(makeVideoCover 从第 1 秒取帧,时长要够)
	dst := filepath.Join(t.TempDir(), "test.mp4")
	gen := exec.Command(ffmpegPath, "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x64:rate=10",
		"-pix_fmt", "yuv420p", "-movflags", "+faststart", dst)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Skipf("ffmpeg 生成测试视频失败,跳过: %v: %s", err, lastLine(string(out)))
	}
	content, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	blob, sha := seedBlobWithObject(t, pool, obj, content, "video/mp4")

	// probeMedia:ffprobe 走预签名 URL 探测时长,写入 media_meta
	if err := w.probeMedia(ctx, blob); err != nil {
		t.Fatal(err)
	}
	var dur float64
	if err := pool.QueryRow(ctx,
		`SELECT (media_meta->>'duration_sec')::float8 FROM blobs WHERE id = $1`, blob).Scan(&dur); err != nil {
		t.Fatal(err)
	}
	if dur < 1.5 || dur > 2.5 {
		t.Fatalf("时长应约 2 秒,got %v", dur)
	}

	// makeVideoCover:ffmpeg 抽帧出 JPEG 封面
	if err := w.makeVideoCover(ctx, blob); err != nil {
		t.Fatal(err)
	}
	key := objstore.DerivedKey(sha, "cover")
	var gotKey string
	if err := pool.QueryRow(ctx,
		`SELECT minio_key FROM derivatives WHERE blob_id = $1 AND kind = 'cover'`, blob).Scan(&gotKey); err != nil {
		t.Fatalf("cover 派生行缺失: %v", err)
	}
	if gotKey != key {
		t.Fatalf("cover key 不符: %s", gotKey)
	}
	data := readObject(t, obj, key)
	if len(data) < 2 || data[0] != 0xFF || data[1] != 0xD8 {
		t.Fatal("封面应是 JPEG(FFD8 魔数)")
	}
}

func TestMakeOfficePDF(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()
	content := []byte(`{\rtf1\ansi Hello Gopan}`)
	blob, sha := seedBlobWithObject(t, pool, obj, content, "application/rtf")

	// setup 的 Pool 未配 Gotenberg:历史遗留任务应兜底报错
	if err := w.makeOfficePDF(ctx, blob); err == nil || !strings.Contains(err.Error(), "GOPAN_GOTENBERG_URL") {
		t.Fatalf("未配置 Gotenberg 应报错,got %v", err)
	}

	// blob 无引用节点:在发 HTTP 之前就该报错,假 URL 即可覆盖
	wfake := New(pool, obj, service.NewNodes(pool), 30*24*time.Hour, "ffmpeg", "ffprobe", "http://127.0.0.1:1")
	if err := wfake.makeOfficePDF(ctx, blob); err == nil || !strings.Contains(err.Error(), "无引用节点") {
		t.Fatalf("无引用节点应报错,got %v", err)
	}

	gburl := os.Getenv("TEST_GOTENBERG_URL")
	if gburl == "" {
		t.Skip("TEST_GOTENBERG_URL 未设置,跳过 Gotenberg 转换测试")
	}
	wg := New(pool, obj, service.NewNodes(pool), 30*24*time.Hour, "ffmpeg", "ffprobe", gburl)

	// 挂一个 .rtf 节点(Gotenberg 靠扩展名识别格式)后应转换成功
	user := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO users (id, username, password_hash) VALUES ($1, 'office', 'x')`, user); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO nodes (id, owner_id, name, kind, blob_id) VALUES ($1, $2, 'hello.rtf', 'file', $3)`,
		uuid.Must(uuid.NewV7()), user, blob); err != nil {
		t.Fatal(err)
	}
	if err := wg.makeOfficePDF(ctx, blob); err != nil {
		t.Fatal(err)
	}
	var key string
	if err := pool.QueryRow(ctx,
		`SELECT minio_key FROM derivatives WHERE blob_id = $1 AND kind = 'pdf'`, blob).Scan(&key); err != nil {
		t.Fatalf("pdf 派生行缺失: %v", err)
	}
	if key != objstore.DerivedKey(sha, "pdf") {
		t.Fatalf("pdf key 不符: %s", key)
	}
	if data := readObject(t, obj, key); !bytes.HasPrefix(data, []byte("%PDF")) {
		t.Fatal("产物应是 PDF(%PDF 魔数)")
	}
}

func TestEnqueueDerivativesKinds(t *testing.T) {
	w, pool, _ := setup(t)
	ctx := context.Background()
	mk := func(mime string, tag byte) store.Blob {
		id := uuid.Must(uuid.NewV7())
		sha := hex.EncodeToString(bytes.Repeat([]byte{tag}, 32))
		if _, err := pool.Exec(ctx,
			`INSERT INTO blobs (id, sha256, size, mime) VALUES ($1, $2, 10, $3)`, id, sha, mime); err != nil {
			t.Fatal(err)
		}
		return store.Blob{ID: id, Sha256: sha, Size: 10, Mime: mime}
	}
	video := mk("video/mp4", 0x31)
	audio := mk("audio/mpeg", 0x32)
	other := mk("application/zip", 0x33)
	for _, b := range []store.Blob{video, audio, other} {
		w.enqueueDerivatives(ctx, b)
	}
	kinds := func(id uuid.UUID) map[string]bool {
		rows, err := pool.Query(ctx, `SELECT kind FROM tasks WHERE blob_id = $1`, id)
		if err != nil {
			t.Fatal(err)
		}
		defer rows.Close()
		got := map[string]bool{}
		for rows.Next() {
			var k string
			if err := rows.Scan(&k); err != nil {
				t.Fatal(err)
			}
			got[k] = true
		}
		return got
	}
	if got := kinds(video.ID); len(got) != 2 || !got["media_probe"] || !got["video_cover"] {
		t.Fatalf("视频应入队 media_probe + video_cover,got %v", got)
	}
	if got := kinds(audio.ID); len(got) != 1 || !got["media_probe"] {
		t.Fatalf("音频应只入队 media_probe,got %v", got)
	}
	if got := kinds(other.ID); len(got) != 0 {
		t.Fatalf("其他类型不应入队派生任务,got %v", got)
	}
}

func TestLastLine(t *testing.T) {
	// 纯函数,不依赖外部环境
	for _, c := range []struct{ in, want string }{
		{"", ""},
		{"only", "only"},
		{"a\nb\nc\n", "c"},
		{"first\n\nlast line here\n\n", "last line here"},
	} {
		if got := lastLine(c.in); got != c.want {
			t.Fatalf("lastLine(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
