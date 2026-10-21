package worker

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"io"
	"mime/multipart"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/disintegration/imaging"
	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

const maxImageBytes = 100 << 20 // 解码上限,防超大图打爆内存

// 解码后每像素约 5.5 字节(YCbCr 原图 + 缩放用的 NRGBA 副本):60MP 约 330MB,
// 已覆盖 48 / 50MP 的手机原图;再大就不做缩略图。
const (
	maxImagePixels = 60_000_000
	bigImagePixels = 16_000_000
)

var bigDecode sync.Mutex

// enqueueDerivatives 在 hash 校验通过后按类型派生(只看 mime,够用)。
func (w *Pool) enqueueDerivatives(ctx context.Context, blob store.Blob) {
	kind := service.ClassifyPreview("", &blob.Mime, &blob.Size)
	switch kind {
	case service.PvImage:
		w.Enqueue(ctx, "thumb", blob.ID)
	case service.PvVideo:
		w.Enqueue(ctx, "media_probe", blob.ID)
		w.Enqueue(ctx, "video_cover", blob.ID)
	case service.PvAudio:
		w.Enqueue(ctx, "media_probe", blob.ID)
	}
}

// ---- 图片缩略图 ----

func (w *Pool) makeThumbs(ctx context.Context, blobID uuid.UUID) error {
	blob, err := w.q.GetBlob(ctx, blobID)
	if err != nil {
		return err
	}
	if blob.Size > maxImageBytes {
		return nil // 超大图不做缩略图,前端用图标
	}
	// 文件大小管不住解码内存:几万×几万的 PNG 压缩后可以很小。先只读头部拿尺寸。
	px, err := w.imagePixels(ctx, blob.Sha256)
	if err != nil {
		return fmt.Errorf("图片解码失败: %w", err)
	}
	if px > maxImagePixels {
		return nil
	}
	if px > bigImagePixels {
		// 大图一次只解一张:两个 worker 同时解两张 50MP 的图,精简档的容器就满了
		bigDecode.Lock()
		defer bigDecode.Unlock()
	}
	rc, err := w.obj.Open(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return err
	}
	defer rc.Close()
	src, err := imaging.Decode(rc, imaging.AutoOrientation(true))
	if err != nil {
		return fmt.Errorf("图片解码失败: %w", err)
	}
	for _, spec := range []struct {
		kind string
		px   int
	}{{"thumb256", 256}, {"thumb2048", 2048}} {
		img := src
		b := src.Bounds()
		if b.Dx() > spec.px || b.Dy() > spec.px {
			img = imaging.Fit(src, spec.px, spec.px, imaging.Lanczos)
		}
		var buf bytes.Buffer
		if err := imaging.Encode(&buf, img, imaging.JPEG, imaging.JPEGQuality(82)); err != nil {
			return err
		}
		if err := w.saveDerivative(ctx, blob, spec.kind, buf.Bytes(), "image/jpeg"); err != nil {
			return err
		}
	}
	return nil
}

// imagePixels 只解析图片头部,返回像素数。
func (w *Pool) imagePixels(ctx context.Context, sha string) (int64, error) {
	rc, err := w.obj.Open(ctx, objstore.BlobKey(sha))
	if err != nil {
		return 0, err
	}
	defer rc.Close()
	cfg, _, err := image.DecodeConfig(rc)
	if err != nil {
		return 0, err
	}
	return int64(cfg.Width) * int64(cfg.Height), nil
}

// ---- 音视频 ----

func (w *Pool) probeMedia(ctx context.Context, blobID uuid.UUID) error {
	blob, err := w.q.GetBlob(ctx, blobID)
	if err != nil {
		return err
	}
	u, err := w.obj.PresignInternalGet(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return err
	}
	cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, w.ffprobe,
		"-v", "quiet", "-show_format", "-of", "json", u).Output()
	if err != nil {
		return fmt.Errorf("ffprobe: %w", err)
	}
	var probe struct {
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out, &probe); err != nil {
		return err
	}
	dur, _ := strconv.ParseFloat(probe.Format.Duration, 64)
	meta, _ := json.Marshal(map[string]any{"duration_sec": dur})
	return w.q.SetBlobMediaMeta(ctx, store.SetBlobMediaMetaParams{ID: blobID, MediaMeta: meta})
}

func (w *Pool) makeVideoCover(ctx context.Context, blobID uuid.UUID) error {
	blob, err := w.q.GetBlob(ctx, blobID)
	if err != nil {
		return err
	}
	u, err := w.obj.PresignInternalGet(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return err
	}
	cctx, cancel := context.WithTimeout(ctx, 120*time.Second)
	defer cancel()
	var out bytes.Buffer
	cmd := exec.CommandContext(cctx, w.ffmpeg,
		"-ss", "1", "-i", u, "-frames:v", "1",
		"-vf", "scale='min(640,iw)':-2",
		"-f", "image2", "-c:v", "mjpeg", "-q:v", "4", "pipe:1")
	cmd.Stdout = &out
	var errBuf bytes.Buffer
	cmd.Stderr = &errBuf
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("ffmpeg cover: %v: %s", err, lastLine(errBuf.String()))
	}
	if out.Len() == 0 {
		return fmt.Errorf("ffmpeg 未输出封面")
	}
	return w.saveDerivative(ctx, blob, "cover", out.Bytes(), "image/jpeg")
}

// ---- Office → PDF(Gotenberg)----

func (w *Pool) makeOfficePDF(ctx context.Context, blobID uuid.UUID) error {
	if w.gotenberg == "" {
		// service 层不会再入队,这里兜住历史遗留任务(关掉 Gotenberg 之前排上的)
		return fmt.Errorf("未配置 GOPAN_GOTENBERG_URL,Office 转换已关闭")
	}
	blob, err := w.q.GetBlob(ctx, blobID)
	if err != nil {
		return err
	}
	// 找一个引用该 blob 的节点名,Gotenberg 靠扩展名识别格式
	nodes, err := w.q.ListNodesByBlob(ctx, &blobID)
	if err != nil || len(nodes) == 0 {
		return fmt.Errorf("blob 无引用节点,跳过转换")
	}
	filename := nodes[0].Name

	rc, err := w.obj.Open(ctx, objstore.BlobKey(blob.Sha256))
	if err != nil {
		return err
	}
	defer rc.Close()

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, err := mw.CreateFormFile("files", filename)
	if err != nil {
		return err
	}
	if _, err := io.Copy(fw, rc); err != nil {
		return err
	}
	mw.Close()

	cctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodPost,
		w.gotenberg+"/forms/libreoffice/convert", &body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("gotenberg: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("gotenberg %d: %s", resp.StatusCode, msg)
	}
	pdf, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	return w.saveDerivative(ctx, blob, "pdf", pdf, "application/pdf")
}

// ---- 公共 ----

func (w *Pool) saveDerivative(ctx context.Context, blob store.Blob, kind string, data []byte, contentType string) error {
	key := objstore.DerivedKey(blob.Sha256, kind)
	if err := w.obj.Put(ctx, key, bytes.NewReader(data), int64(len(data)), contentType); err != nil {
		return err
	}
	return w.q.UpsertDerivative(ctx, store.UpsertDerivativeParams{
		BlobID: blob.ID, Kind: kind, MinioKey: key, Size: int64(len(data)),
	})
}

func lastLine(s string) string {
	s = strings.TrimSpace(s)
	return s[strings.LastIndexByte(s, '\n')+1:]
}
