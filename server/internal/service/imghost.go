package service

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"html"
	"io"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ImageUpload 图床接口(POST /api/img/upload)或网页粘贴上传的一张图(doc/22 四.3)。
type ImageUpload struct {
	Owner  uuid.UUID
	Name   string
	Body   io.Reader
	Meta   LinkMeta
	Origin string // 没配 GOPAN_PUBLIC_URL 时拼完整地址用(请求自己的协议 + 主机)
}

type ImageResult struct {
	URL, Markdown, HTML, BBCode, Name string
	Size                              int64
	NodeID, LinkID                    uuid.UUID
}

// imageTypes 文件头 → 规范扩展名。svg 另判(文本)。
var imageExtAliases = map[string][]string{
	".jpg": {".jpg", ".jpeg", ".jfif"}, ".png": {".png"}, ".gif": {".gif"}, ".webp": {".webp"},
	".avif": {".avif"}, ".bmp": {".bmp"}, ".svg": {".svg"},
}

// sniffImage 按文件头认图片,返回规范扩展名;不是图片返回空串。
func sniffImage(head []byte, name string) string {
	switch {
	case len(head) >= 3 && head[0] == 0xFF && head[1] == 0xD8 && head[2] == 0xFF:
		return ".jpg"
	case bytes.HasPrefix(head, []byte("\x89PNG\r\n\x1a\n")):
		return ".png"
	case bytes.HasPrefix(head, []byte("GIF87a")) || bytes.HasPrefix(head, []byte("GIF89a")):
		return ".gif"
	case len(head) >= 12 && string(head[:4]) == "RIFF" && string(head[8:12]) == "WEBP":
		return ".webp"
	case len(head) >= 12 && string(head[4:8]) == "ftyp" && (string(head[8:12]) == "avif" || string(head[8:12]) == "avis"):
		return ".avif"
	case len(head) >= 2 && head[0] == 'B' && head[1] == 'M':
		return ".bmp"
	}
	if strings.EqualFold(filepath.Ext(name), ".svg") {
		t := bytes.TrimLeft(bytes.TrimPrefix(head, []byte("\xEF\xBB\xBF")), " \t\r\n")
		if bytes.HasPrefix(t, []byte("<")) && bytes.Contains(head[:min(len(head), 8<<10)], []byte("<svg")) {
			return ".svg"
		}
	}
	return ""
}

// genericImageNames 剪贴板、截图工具常给的名字:换成时间戳,免得图床文件夹里一堆 image(12).png。
var genericImageNames = map[string]bool{
	"": true, "image": true, "blob": true, "clipboard": true, "file": true, "untitled": true, "pasted": true, "screenshot": true,
}

func imageName(name, ext string, now time.Time) string {
	name = strings.TrimSpace(filepath.Base(strings.ReplaceAll(name, "\\", "/")))
	if name == "." || name == "/" {
		name = ""
	}
	cur := filepath.Ext(name)
	stem := strings.TrimSuffix(name, cur)
	if genericImageNames[strings.ToLower(stem)] {
		var r [2]byte
		_, _ = rand.Read(r[:])
		return now.Format("20060102-150405") + "-" + hex.EncodeToString(r[:]) + ext
	}
	for _, a := range imageExtAliases[ext] {
		if strings.EqualFold(cur, a) {
			return name
		}
	}
	return stem + ext
}

// UploadImage 收一张图:认类型 → (按设置)抹 GPS → 流式写暂存、边写边算 sha → 落到图床文件夹的「年-月」子文件夹 → 生成直链。
func (s *Links) UploadImage(ctx context.Context, in ImageUpload) (*ImageResult, error) {
	if !s.cfg.Enabled {
		return nil, errf("LINKS_DISABLED", "本站没有开直链")
	}
	if !s.imgLimiter.Allow(in.Owner.String()) {
		return nil, ErrRateLimited
	}
	select {
	case s.imgGate <- struct{}{}:
		defer func() { <-s.imgGate }()
	default:
		return nil, errf("BUSY", "同时上传的太多了,稍后重试")
	}
	head := make([]byte, exifScanBytes)
	n, err := io.ReadFull(in.Body, head)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		return nil, errf("INVALID_INPUT", "读取上传内容失败")
	}
	head = head[:n]
	if n == 0 {
		return nil, errf("INVALID_INPUT", "没有收到图片")
	}
	ext := sniffImage(head, in.Name)
	if ext == "" {
		return nil, errf("UNSUPPORTED_TYPE", "只收图片:jpg、png、gif、webp、avif、bmp、svg")
	}
	prefs, err := s.Prefs(ctx, in.Owner)
	if err != nil {
		return nil, err
	}
	if ext == ".jpg" && prefs.StripGPS {
		JPEGGPS(head, true)
	}
	now := time.Now().In(s.cfg.Loc)
	name := imageName(in.Name, ext, now)

	tmpKey := "tmp/img/" + uuid.Must(uuid.NewV7()).String()
	h := sha256.New()
	limited := &io.LimitedReader{R: io.MultiReader(bytes.NewReader(head), in.Body), N: s.cfg.ImgMaxSize + 1}
	size, err := s.obj.PutStream(ctx, tmpKey, io.TeeReader(limited, h), mimeByName(name))
	if err != nil {
		_ = s.obj.Remove(context.WithoutCancel(ctx), tmpKey)
		return nil, err
	}
	if size > s.cfg.ImgMaxSize {
		_ = s.obj.Remove(context.WithoutCancel(ctx), tmpKey)
		return nil, errf("TOO_LARGE", "单张图片不能超过 %s", humanBytes(s.cfg.ImgMaxSize))
	}
	parent, err := s.imageFolder(ctx, in.Owner, prefs.ImageFolder, now)
	if err != nil {
		_ = s.obj.Remove(context.WithoutCancel(ctx), tmpKey)
		return nil, err
	}
	node, err := s.uploads.CommitStaged(ctx, StagedFile{
		Owner: in.Owner, Parent: &parent, Name: name, Sha: hex.EncodeToString(h.Sum(nil)), Size: size, TmpKey: tmpKey,
	})
	if err != nil {
		return nil, err
	}
	links, err := s.CreateLinks(ctx, in.Owner, []uuid.UUID{node.ID}, in.Meta)
	if err != nil {
		return nil, err
	}
	l := links[0]
	u := s.URL(l.Token, node.Name)
	if s.cfg.PublicURL == "" {
		u = strings.TrimRight(in.Origin, "/") + u
	}
	return &ImageResult{
		URL: u, Name: node.Name, Size: size, NodeID: node.ID, LinkID: l.ID,
		Markdown: fmt.Sprintf("![%s](%s)", markdownAlt(node.Name), u),
		HTML:     fmt.Sprintf(`<img src="%s" alt="%s">`, html.EscapeString(u), html.EscapeString(node.Name)),
		BBCode:   "[img]" + u + "[/img]",
	}, nil
}

func markdownAlt(name string) string {
	return strings.NewReplacer("[", "\\[", "]", "\\]").Replace(strings.TrimSuffix(name, filepath.Ext(name)))
}

// imageFolder 找到或建出「图床文件夹/年-月」。
func (s *Links) imageFolder(ctx context.Context, owner uuid.UUID, root string, now time.Time) (uuid.UUID, error) {
	_, segs, err := normalizeFolderPath(root)
	if err != nil {
		_, segs, _ = normalizeFolderPath(defaultImgFolder)
	}
	segs = append(segs, now.Format("2006-01"))
	var parent *uuid.UUID
	for _, seg := range segs {
		n, err := s.uploads.EnsureFolderIn(ctx, owner, parent, seg)
		if err != nil {
			return uuid.Nil, err
		}
		id := n.ID
		parent = &id
	}
	return *parent, nil
}
