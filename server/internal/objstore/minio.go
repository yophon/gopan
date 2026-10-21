// Package objstore 封装 MinIO/S3:分片上传编排与预签名。
// 两个 client:internal 走服务端可达地址(桶操作、校验读取),
// public 只用于生成预签名 URL(浏览器可达地址)。
package objstore

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"github.com/yophon/gopan/server/internal/config"
)

type Store struct {
	internal *minio.Client
	public   *minio.Client
	core     minio.Core
	bucket   string
	putTTL   time.Duration
	getTTL   time.Duration
}

func New(cfg *config.Config) (*Store, error) {
	creds := credentials.NewStaticV4(cfg.S3Key, cfg.S3Secret, "")
	// Region 必须显式给:否则 minio-go 签名前会向 endpoint 发 GetBucketLocation,
	// 而 public endpoint 对服务端通常不可达(公网域名 / 容器内网无出网)
	internal, err := minio.New(cfg.S3Endpoint, &minio.Options{
		Creds: creds, Secure: cfg.S3UseSSL, Region: cfg.S3Region,
	})
	if err != nil {
		return nil, fmt.Errorf("minio internal client: %w", err)
	}
	public, err := minio.New(cfg.S3PublicEndpoint, &minio.Options{
		Creds: creds, Secure: cfg.S3PublicUseSSL, Region: cfg.S3Region,
	})
	if err != nil {
		return nil, fmt.Errorf("minio public client: %w", err)
	}
	return &Store{
		internal: internal,
		public:   public,
		core:     minio.Core{Client: internal},
		bucket:   cfg.S3Bucket,
		putTTL:   cfg.PresignPutTTL,
		getTTL:   cfg.PresignGetTTL,
	}, nil
}

// EnsureBucket 启动时建桶(幂等)。
func (s *Store) EnsureBucket(ctx context.Context) error {
	ok, err := s.internal.BucketExists(ctx, s.bucket)
	if err != nil {
		return fmt.Errorf("bucket exists check: %w", err)
	}
	if ok {
		return nil
	}
	return s.internal.MakeBucket(ctx, s.bucket, minio.MakeBucketOptions{})
}

func BlobKey(sha256 string) string {
	return "blobs/" + sha256[:2] + "/" + sha256
}

// ---- 分片上传 ----

func (s *Store) NewMultipart(ctx context.Context, key, mime string) (string, error) {
	return s.core.NewMultipartUpload(ctx, s.bucket, key, minio.PutObjectOptions{ContentType: mime})
}

// PresignPart 为指定分片生成 PUT 预签名 URL(用 public client 签,浏览器直传)。
func (s *Store) PresignPart(ctx context.Context, key, uploadID string, partNumber int) (string, error) {
	v := url.Values{}
	v.Set("uploadId", uploadID)
	v.Set("partNumber", strconv.Itoa(partNumber))
	u, err := s.public.Presign(ctx, "PUT", s.bucket, key, s.putTTL, v)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// PresignPartSized 同 PresignPart,但把 Content-Length 签进去:地址只接受恰好 length 字节的请求体。
// 给收集链接的匿名访客用,免得一片声称 16MB 的分片被塞进几个 G。
func (s *Store) PresignPartSized(ctx context.Context, key, uploadID string, partNumber int, length int64) (string, error) {
	v := url.Values{}
	v.Set("uploadId", uploadID)
	v.Set("partNumber", strconv.Itoa(partNumber))
	h := http.Header{}
	h.Set("Content-Length", strconv.FormatInt(length, 10))
	u, err := s.public.PresignHeader(ctx, "PUT", s.bucket, key, s.putTTL, v, h)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// PresignPut 为单对象直传生成 PUT URL。服务端完成时会以 Stat 的实际大小为准，
// Content-Type 只作对象元数据，不作为信任边界。
func (s *Store) PresignPut(ctx context.Context, key string) (string, error) {
	u, err := s.public.PresignedPutObject(ctx, s.bucket, key, s.putTTL)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// ListParts 返回已完成分片号→ETag(断点续传唯一事实源)。
func (s *Store) ListParts(ctx context.Context, key, uploadID string) (map[int]string, error) {
	parts := make(map[int]string)
	marker := 0
	for {
		res, err := s.core.ListObjectParts(ctx, s.bucket, key, uploadID, marker, 1000)
		if err != nil {
			return nil, err
		}
		for _, p := range res.ObjectParts {
			parts[p.PartNumber] = p.ETag
		}
		if !res.IsTruncated {
			return parts, nil
		}
		marker = res.NextPartNumberMarker
	}
}

type Part struct {
	Number int
	ETag   string
}

func (s *Store) CompleteMultipart(ctx context.Context, key, uploadID string, parts []Part) error {
	cp := make([]minio.CompletePart, 0, len(parts))
	for _, p := range parts {
		cp = append(cp, minio.CompletePart{PartNumber: p.Number, ETag: p.ETag})
	}
	_, err := s.core.CompleteMultipartUpload(ctx, s.bucket, key, uploadID, cp, minio.PutObjectOptions{})
	return err
}

func (s *Store) AbortMultipart(ctx context.Context, key, uploadID string) error {
	return s.core.AbortMultipartUpload(ctx, s.bucket, key, uploadID)
}

// ---- 读取 / 下载 / 删除 ----

// PresignGet 生成下载 URL,filename 控制浏览器保存名(对象 key 是 hash,没有原名)。
func (s *Store) PresignGet(ctx context.Context, key, filename string) (string, error) {
	v := url.Values{}
	if filename != "" {
		v.Set("response-content-disposition", `attachment; filename*=UTF-8''`+url.PathEscape(filename))
	}
	u, err := s.public.Presign(ctx, "GET", s.bucket, key, s.getTTL, v)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// PresignGetInline 预览用:不带 attachment,浏览器内联打开。mime 是对象内容的类型,
// 响应类型由 InlineOverrides 按它改写。
func (s *Store) PresignGetInline(ctx context.Context, key, mime string) (string, error) {
	u, err := s.public.Presign(ctx, "GET", s.bucket, key, s.getTTL, InlineOverrides(mime))
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// InlineOverrides 决定内联预签名的响应头。预签名地址与站点同源(/gopan/* 反代到 MinIO),
// 响应不经过 Go、没有站点的 CSP,而对象的类型又来自上传者起的文件名:html / svg 若按原类型返回,
// 被顶层打开时脚本就以站点身份运行(doc/20 二.6)。所以响应类型一律显式给出:
// 图片 / 音视频 / PDF 保留;SVG 保留类型(<img> 靠它渲染)但加 attachment,顶层打开只会下载;
// 其余(文本、html、xml、未知)都按纯文本返回。
func InlineOverrides(mime string) url.Values {
	m := strings.ToLower(strings.TrimSpace(strings.SplitN(mime, ";", 2)[0]))
	v := url.Values{}
	switch {
	case m == "image/svg+xml":
		v.Set("response-content-type", m)
		v.Set("response-content-disposition", "attachment")
	case strings.HasPrefix(m, "image/"), strings.HasPrefix(m, "video/"), strings.HasPrefix(m, "audio/"),
		m == "application/pdf":
		v.Set("response-content-type", m)
	default:
		v.Set("response-content-type", "text/plain; charset=utf-8")
	}
	return v
}

// Open 流式读对象(hash 校验、生成派生物用),记得 Close。
func (s *Store) Open(ctx context.Context, key string) (io.ReadCloser, error) {
	return s.internal.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
}

func (s *Store) Remove(ctx context.Context, key string) error {
	return s.internal.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
}

func (s *Store) Stat(ctx context.Context, key string) (int64, error) {
	info, err := s.internal.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		return 0, err
	}
	return info.Size, nil
}

func (s *Store) Ping(ctx context.Context) error {
	_, err := s.internal.BucketExists(ctx, s.bucket)
	return err
}

// Put 写对象(派生物用)。size 未知传 -1。
func (s *Store) Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error {
	_, err := s.internal.PutObject(ctx, s.bucket, key, r, size, minio.PutObjectOptions{ContentType: contentType})
	return err
}

// OpenSeeker 可 Seek 的流式读(WebDAV 的 http.ServeContent 需要 Seek 探大小与处理 Range)。
// minio.Object 的 Seek 是惰性的,不会真的把对象拉回来。
func (s *Store) OpenSeeker(ctx context.Context, key string) (io.ReadSeekCloser, error) {
	return s.internal.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
}

// PutStream 未知长度流式写(WebDAV PUT 转存)。分片压到 16MB:
// minio-go 对 size=-1 按 PartSize 在内存攒块,默认 128MB 会把小内存机器打爆。
// 返回实际写入字节数。
func (s *Store) PutStream(ctx context.Context, key string, r io.Reader, contentType string) (int64, error) {
	info, err := s.internal.PutObject(ctx, s.bucket, key, r, -1, minio.PutObjectOptions{
		ContentType: contentType, PartSize: 16 << 20,
	})
	if err != nil {
		return 0, err
	}
	return info.Size, nil
}

// Copy 服务端拷贝(临时 key → 内容寻址 key)。单调用上限 5GiB,调用方负责限制。
func (s *Store) Copy(ctx context.Context, dstKey, srcKey string) error {
	_, err := s.internal.CopyObject(ctx,
		minio.CopyDestOptions{Bucket: s.bucket, Object: dstKey},
		minio.CopySrcOptions{Bucket: s.bucket, Object: srcKey})
	return err
}

// Promote 把 staging 对象提升到内容寻址 key。S3 CopyObject 单调用只支持 5GiB，
// 更大的对象重新走内网流式 PUT；字节仍不经过 MCP 或 Agent 上下文。
func (s *Store) Promote(ctx context.Context, dstKey, srcKey string, size int64, contentType string) error {
	if size <= 5<<30 {
		return s.Copy(ctx, dstKey, srcKey)
	}
	r, err := s.Open(ctx, srcKey)
	if err != nil {
		return err
	}
	defer r.Close()
	return s.Put(ctx, dstKey, r, size, contentType)
}

// PresignInternalGet 用内网地址签 GET,给 ffmpeg/ffprobe 这类需要 URL 输入的工具。
func (s *Store) PresignInternalGet(ctx context.Context, key string) (string, error) {
	u, err := s.internal.Presign(ctx, "GET", s.bucket, key, 10*time.Minute, nil)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func DerivedKey(sha256, kind string) string {
	return "derived/" + sha256[:2] + "/" + sha256 + "/" + kind
}
