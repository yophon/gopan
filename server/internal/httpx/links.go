package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
)

// linkSVGCSP 直链的 SVG 内联显示,但直接打开也跑不了脚本(doc/22 四.1)。
const linkSVGCSP = "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'"

// LinkHandler 公开直链:GET /x/{token}/{name}(name 只为好看,查找只看 token)。
// 字节经 Go 中转:http.ServeContent 处理 Range 与 If-None-Match,边发边计数。
func LinkHandler(links *service.Links, obj *objstore.Store, trusted []*net.IPNet) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ref := r.Header.Get("Referer")
		hit := service.HitInfo{
			IP: ClientIPFrom(r, trusted), UA: r.UserAgent(), Referer: ref, RefererHost: service.RefererHost(ref),
		}
		f, denied := links.Open(r.Context(), r.PathValue("token"), r.URL.Query().Get("s"), hit)
		if denied != nil {
			if denied.RetryAfter > 0 {
				w.Header().Set("Retry-After", strconv.Itoa(denied.RetryAfter))
			}
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("X-Robots-Tag", "noindex")
			http.Error(w, denied.Message, denied.Status)
			links.Record(denied.LinkID, denied.OwnerID, hit, denied.Status, 0)
			return
		}
		defer f.Release()
		rs, err := obj.OpenSeeker(r.Context(), f.Key)
		if err != nil {
			http.Error(w, "暂时不可用,稍后再试", http.StatusServiceUnavailable)
			links.Record(f.LinkID, f.OwnerID, hit, http.StatusServiceUnavailable, 0)
			return
		}
		defer rs.Close()

		h := w.Header()
		ct, disp := linkContentType(f.Mime)
		h.Set("Content-Type", ct)
		h.Set("Content-Disposition", disp+"; filename*=UTF-8''"+url.PathEscape(f.Name))
		if f.Mime == "image/svg+xml" {
			h.Set("Content-Security-Policy", linkSVGCSP)
		}
		h.Set("ETag", f.ETag)
		h.Set("Cache-Control", "public, max-age=3600")
		h.Set("X-Robots-Tag", "noindex")
		h.Set("Access-Control-Allow-Origin", "*")
		h.Del("X-Frame-Options")
		// 大文件走慢网会超过 Server 级 60s 写超时
		_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
		cw := &countingWriter{ResponseWriter: w, status: http.StatusOK}
		http.ServeContent(cw, r, f.Name, f.ModTime, rs)
		links.Record(f.LinkID, f.OwnerID, hit, cw.status, cw.n)
	})
}

// linkContentType 图片、音视频、PDF 内联;HTML、XML 之类永远不内联,其余一律下载。
func linkContentType(m string) (contentType, disposition string) {
	switch {
	case m == "image/svg+xml":
		return m, "inline"
	case strings.HasPrefix(m, "image/"), strings.HasPrefix(m, "video/"), strings.HasPrefix(m, "audio/"), m == "application/pdf":
		return m, "inline"
	case m == "":
		return "application/octet-stream", "attachment"
	}
	if t, _, err := mime.ParseMediaType(m); err == nil && (strings.Contains(t, "html") || strings.Contains(t, "xml") || strings.Contains(t, "javascript")) {
		return "application/octet-stream", "attachment"
	}
	return m, "attachment"
}

type countingWriter struct {
	http.ResponseWriter
	status int
	n      int64
}

func (c *countingWriter) WriteHeader(code int) {
	c.status = code
	c.ResponseWriter.WriteHeader(code)
}

func (c *countingWriter) Write(b []byte) (int, error) {
	n, err := c.ResponseWriter.Write(b)
	c.n += int64(n)
	return n, err
}

func (c *countingWriter) Unwrap() http.ResponseWriter { return c.ResponseWriter }

// SubtitleHandler 外挂字幕:GET /sub/{node}?token=<access>(也接受 Authorization 头),一律回 WebVTT。
// 分享访客在分享范围内可用,收集令牌被拒。
func SubtitleHandler(auth *service.Auth, media *service.Media, shares *service.Shares) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := r.URL.Query().Get("token")
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			token = strings.TrimPrefix(h, "Bearer ")
		}
		ident, err := auth.ParseAccessContext(r.Context(), token)
		if err != nil {
			writeArchiveErr(w, &service.Error{Code: "UNAUTHENTICATED", Message: "未登录或凭证已过期"}, http.StatusUnauthorized)
			return
		}
		nodeID, err := uuid.Parse(r.PathValue("node"))
		if err != nil {
			writeArchiveErr(w, &service.Error{Code: "INVALID_INPUT", Message: "非法节点 ID"}, http.StatusBadRequest)
			return
		}
		if err := shares.Authorize(r.Context(), ident, nodeID); err != nil {
			writeServiceErr(w, err)
			return
		}
		vtt, err := media.SubtitleVTT(r.Context(), ident.UserID, nodeID)
		if err != nil {
			writeServiceErr(w, err)
			return
		}
		h := w.Header()
		h.Set("Content-Type", "text/vtt; charset=utf-8")
		h.Set("Content-Security-Policy", archiveCSP)
		h.Set("Cache-Control", "private, max-age=3600")
		_, _ = io.WriteString(w, vtt)
	})
}

// ImageUploadHandler 图床接口:POST /api/img/upload,Authorization: Bearer <图床密钥 或 登录凭证>。
// 收 multipart(取第一个文件)或直接把图片当请求体(?name= 给文件名);回 JSON。
func ImageUploadHandler(auth *service.Auth, tokens *service.MCPTokens, links *service.Links, trusted []*net.IPNet) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		bearer := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if bearer == "" || bearer == r.Header.Get("Authorization") {
			writeImgErr(w, http.StatusUnauthorized, "UNAUTHENTICATED", "要带 Authorization: Bearer <图床密钥>")
			return
		}
		meta := service.LinkMeta{IP: ClientIPFrom(r, trusted), UA: r.UserAgent()}
		var owner uuid.UUID
		var actor service.Actor
		if strings.HasPrefix(bearer, "gopan_key_") {
			p, err := tokens.Authenticate(r.Context(), bearer)
			if err != nil {
				writeImgErr(w, http.StatusUnauthorized, "UNAUTHENTICATED", "密钥无效或已吊销")
				return
			}
			if _, ok := p.Scopes[service.ScopeImgUpload]; !ok {
				writeImgErr(w, http.StatusForbidden, "FORBIDDEN", "这把密钥不能上传图床")
				return
			}
			owner, meta.Source = p.UserID, "api"
			id := p.TokenID
			meta.APIKeyID = &id
			actor = service.Actor{Kind: service.ActorImg, Ref: p.TokenID.String(), Label: p.Name}
		} else {
			ident, err := auth.ParseAccessContext(r.Context(), bearer)
			if err != nil || ident.Scope != service.ScopeUser {
				writeImgErr(w, http.StatusUnauthorized, "UNAUTHENTICATED", "未登录或凭证已过期")
				return
			}
			owner, meta.Source = ident.UserID, "web"
			actor = service.SessionActor(ident.FamilyID, r.UserAgent())
		}
		// 20 MB 走慢网可能超过 Server 级 60s 读超时
		rc := http.NewResponseController(w)
		_ = rc.SetReadDeadline(time.Now().Add(10 * time.Minute))
		_ = rc.SetWriteDeadline(time.Now().Add(10 * time.Minute))

		name, body, err := imageBody(r)
		if err != nil {
			writeImgErr(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
			return
		}
		res, err := links.UploadImage(service.WithActor(r.Context(), actor), service.ImageUpload{
			Owner: owner, Name: name, Body: body, Meta: meta, Origin: requestOrigin(r),
		})
		if err != nil {
			var se *service.Error
			if !errors.As(err, &se) {
				slog.Error("image upload", "err", err)
				writeImgErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
				return
			}
			status := map[string]int{
				"UNSUPPORTED_TYPE": http.StatusUnsupportedMediaType, "TOO_LARGE": http.StatusRequestEntityTooLarge,
				"QUOTA_EXCEEDED": http.StatusInsufficientStorage, "RATE_LIMITED": http.StatusTooManyRequests,
				"BUSY": http.StatusServiceUnavailable, "LINKS_DISABLED": http.StatusNotFound,
			}[se.Code]
			if status == 0 {
				status = http.StatusBadRequest
			}
			writeImgErr(w, status, se.Code, se.Message)
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"url": res.URL, "markdown": res.Markdown, "html": res.HTML, "bbcode": res.BBCode,
			"name": res.Name, "size": res.Size, "node_id": res.NodeID.String(), "link_id": res.LinkID.String(),
		})
	})
}

// imageBody multipart 里第一个带文件名的部分,或整个请求体。
func imageBody(r *http.Request) (string, io.Reader, error) {
	ct, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if ct == "multipart/form-data" {
		mr, err := r.MultipartReader()
		if err != nil {
			return "", nil, errors.New("multipart 格式不对")
		}
		for {
			part, err := mr.NextPart()
			if err == io.EOF {
				return "", nil, errors.New("没有找到文件")
			}
			if err != nil {
				return "", nil, errors.New("multipart 格式不对")
			}
			if part.FileName() != "" {
				return part.FileName(), part, nil
			}
			_, _ = io.Copy(io.Discard, io.LimitReader(part, 64<<10)) // 普通表单字段跳过
			_ = part.Close()
		}
	}
	name := r.URL.Query().Get("name")
	if name == "" {
		if _, params, err := mime.ParseMediaType(r.Header.Get("Content-Disposition")); err == nil {
			name = params["filename"]
		}
	}
	return name, r.Body, nil
}

func requestOrigin(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	host := r.Host
	if h := r.Header.Get("X-Forwarded-Host"); h != "" {
		host = h
	}
	return scheme + "://" + host
}

func writeImgErr(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"code": code, "message": msg}})
}
