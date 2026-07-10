package httpx

import (
	"context"
	"net/http"

	"github.com/yophon/gopan/server/internal/service"
)

type ctxKey int

const (
	keyIdentity ctxKey = iota
	keyHTTP
)

// httpCarrier 让 resolver 能读 refresh cookie、写 Set-Cookie、取客户端 IP。
type httpCarrier struct {
	w  http.ResponseWriter
	r  *http.Request
	ip string
}

func IdentityFrom(ctx context.Context) (*service.Identity, error) {
	id, _ := ctx.Value(keyIdentity).(*service.Identity)
	if id == nil {
		return nil, service.ErrUnauthenticated
	}
	return id, nil
}

// UserFrom 只接受完整用户身份(拒绝访客 scope)。
func UserFrom(ctx context.Context) (*service.Identity, error) {
	id, err := IdentityFrom(ctx)
	if err != nil {
		return nil, err
	}
	if id.Scope != service.ScopeUser {
		return nil, service.ErrForbidden
	}
	return id, nil
}

func ClientIP(ctx context.Context) string {
	if c, ok := ctx.Value(keyHTTP).(*httpCarrier); ok {
		return c.ip
	}
	return "unknown"
}

const refreshCookie = "gopan_rt"

func RefreshTokenFrom(ctx context.Context) string {
	c, ok := ctx.Value(keyHTTP).(*httpCarrier)
	if !ok {
		return ""
	}
	ck, err := c.r.Cookie(refreshCookie)
	if err != nil {
		return ""
	}
	return ck.Value
}

func SetRefreshCookie(ctx context.Context, token string, maxAge int, secure bool) {
	c, ok := ctx.Value(keyHTTP).(*httpCarrier)
	if !ok {
		return
	}
	http.SetCookie(c.w, &http.Cookie{
		Name: refreshCookie, Value: token,
		Path: "/query", MaxAge: maxAge,
		HttpOnly: true, Secure: secure, SameSite: http.SameSiteLaxMode,
	})
}

func ClearRefreshCookie(ctx context.Context, secure bool) {
	SetRefreshCookie(ctx, "", -1, secure)
}
