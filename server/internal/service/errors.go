package service

import "fmt"

// Error 是带 code 的业务错误,GraphQL 层将 code 放进 extensions,前端按 code 分支。
type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

func errf(code, format string, a ...any) *Error {
	return &Error{Code: code, Message: fmt.Sprintf(format, a...)}
}

var (
	ErrUnauthenticated = &Error{Code: "UNAUTHENTICATED", Message: "登录已失效"}
	ErrForbidden       = &Error{Code: "FORBIDDEN", Message: "没有权限"}
	ErrNotFound        = &Error{Code: "NOT_FOUND", Message: "对象不存在"}
	ErrRateLimited     = &Error{Code: "RATE_LIMITED", Message: "操作过于频繁,稍后再试"}
	ErrNotImplemented  = &Error{Code: "NOT_IMPLEMENTED", Message: "该功能尚未实现(M2+)"}
)
