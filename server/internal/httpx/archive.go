package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
)

// archiveCSP 与反代给 /gopan/ 加的一致:包里的 html / svg 被顶层打开也执行不了脚本(doc/20 二.6)。
const archiveCSP = "sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'"

var archiveErrStatus = map[string]int{
	"NOT_FOUND":                 http.StatusNotFound,
	"NOT_ARCHIVE":               http.StatusNotFound,
	"FORBIDDEN":                 http.StatusForbidden,
	"SHARE_EXPIRED":             http.StatusGone,
	"INVALID_INPUT":             http.StatusBadRequest,
	"UNSUPPORTED_TYPE":          http.StatusUnsupportedMediaType,
	"ARCHIVE_NOT_READY":         http.StatusConflict,
	"ARCHIVE_UNAVAILABLE":       http.StatusUnprocessableEntity,
	"ARCHIVE_ENTRY_UNAVAILABLE": http.StatusUnprocessableEntity,
	"ARCHIVE_ENTRY_TOO_LARGE":   http.StatusUnprocessableEntity,
	"ARCHIVE_ENTRY_TOO_DEEP":    http.StatusUnprocessableEntity,
	"ARCHIVE_CORRUPT":           http.StatusUnprocessableEntity,
	"ARCHIVE_BUSY":              http.StatusServiceUnavailable,
}

// ArchiveEntryHandler 压缩包里的单个文件:GET /arc/{node}/{idx}?token=<access>[&download=1]。
// Go 解压后流式返回(与 /pack 一样是显式经过 Go 的字节流,全站并发 2 路)。
// token 可以是登录用户的,也可以是分享访客的(节点须在分享子树内);收集令牌被拒。
func ArchiveEntryHandler(auth *service.Auth, archives *service.Archives, shares *service.Shares) http.Handler {
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
		idx, err := strconv.ParseInt(r.PathValue("idx"), 10, 32)
		if err != nil || idx < 0 {
			writeArchiveErr(w, &service.Error{Code: "INVALID_INPUT", Message: "非法条目"}, http.StatusBadRequest)
			return
		}
		if err := shares.Authorize(r.Context(), ident, nodeID); err != nil {
			writeServiceErr(w, err)
			return
		}
		download := r.URL.Query().Get("download") == "1"
		st, err := archives.OpenEntry(r.Context(), ident.UserID, nodeID, int32(idx), download)
		if err != nil {
			writeServiceErr(w, err)
			return
		}
		defer st.Close()

		h := w.Header()
		if download {
			h.Set("Content-Type", "application/octet-stream")
			h.Set("Content-Disposition", `attachment; filename*=UTF-8''`+url.PathEscape(st.Name))
		} else {
			v := objstore.InlineOverrides(st.Mime) // 与内联预签名同一套:html / xml 一律按纯文本
			h.Set("Content-Type", v.Get("response-content-type"))
			if d := v.Get("response-content-disposition"); d != "" {
				h.Set("Content-Disposition", d)
			}
		}
		h.Set("Content-Length", strconv.FormatInt(st.Size, 10))
		h.Set("Content-Security-Policy", archiveCSP)
		h.Set("Cache-Control", "private, max-age=300")
		// 100 MB 走慢网可能超过 Server 级 60s 写超时
		_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(15 * time.Minute))
		if _, err := io.Copy(w, st); err != nil && r.Context().Err() == nil {
			slog.Warn("archive entry stream", "node", nodeID, "idx", idx, "err", err)
		}
	})
}

func writeServiceErr(w http.ResponseWriter, err error) {
	var se *service.Error
	if !errors.As(err, &se) {
		slog.Error("archive entry", "err", err)
		writeArchiveErr(w, &service.Error{Code: "INTERNAL", Message: "内部错误"}, http.StatusInternalServerError)
		return
	}
	status, ok := archiveErrStatus[se.Code]
	if !ok {
		status = http.StatusBadRequest
	}
	if se.Code == "ARCHIVE_BUSY" {
		w.Header().Set("Retry-After", "10")
	}
	writeArchiveErr(w, se, status)
}

func writeArchiveErr(w http.ResponseWriter, se *service.Error, status int) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": se.Code, "message": se.Message})
}
