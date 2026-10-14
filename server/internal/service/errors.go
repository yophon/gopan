package service

import (
	"errors"
	"fmt"
)

// Error 是带 code 的业务错误,GraphQL 层将 code 放进 extensions,前端按 code 分支。
type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

func errf(code, format string, a ...any) *Error {
	return &Error{Code: code, Message: fmt.Sprintf(format, a...)}
}

// IsUnauthenticated:凭证本身无效(过期、被吊销、被盗用后整族吊销、账号停用)。
// 数据库或账号中心暂时不可用之类的故障不算 —— 那种时候不能让客户端丢掉登录态。
func IsUnauthenticated(err error) bool {
	var se *Error
	return errors.As(err, &se) && se.Code == ErrUnauthenticated.Code
}

var (
	ErrUnauthenticated = &Error{Code: "UNAUTHENTICATED", Message: "登录已失效"}
	ErrForbidden       = &Error{Code: "FORBIDDEN", Message: "没有权限"}
	ErrNotFound        = &Error{Code: "NOT_FOUND", Message: "对象不存在"}
	ErrRateLimited     = &Error{Code: "RATE_LIMITED", Message: "操作过于频繁,稍后再试"}
	ErrNotImplemented  = &Error{Code: "NOT_IMPLEMENTED", Message: "该功能尚未实现(M2+)"}
)
