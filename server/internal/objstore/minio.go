// Package objstore 封装 MinIO/S3:分片上传编排与预签名。
// 两个 client:internal 走服务端可达地址(桶操作、校验读取),
// public 只用于生成预签名 URL(浏览器可达地址)。
package objstore

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"strconv"
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
	internal, err := minio.New(cfg.S3Endpoint, &minio.Options{Creds: creds, Secure: cfg.S3UseSSL})
	if err != nil {
		return nil, fmt.Errorf("minio internal client: %w", err)
	}
	public, err := minio.New(cfg.S3PublicEndpoint, &minio.Options{Creds: creds, Secure: cfg.S3UseSSL})
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

// PresignGetInline 预览用:不带 attachment,浏览器内联打开。
func (s *Store) PresignGetInline(ctx context.Context, key string) (string, error) {
	u, err := s.public.Presign(ctx, "GET", s.bucket, key, s.getTTL, nil)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// Open 流式读对象(hash 校验、生成派生物用),记得 Close。
func (s *Store) Open(ctx context.Context, key string) (io.ReadCloser, error) {
	return s.internal.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
}

func (s *Store) Remove(ctx context.Context, key string) error {
	return s.internal.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
}

func (s *Store) Ping(ctx context.Context) error {
	_, err := s.internal.BucketExists(ctx, s.bucket)
	return err
}
