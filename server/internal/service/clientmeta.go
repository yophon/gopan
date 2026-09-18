package service

import "context"

// ClientMeta 是请求级的客户端信息。HTTP 层塞进 context,service 层按需取 ——
// 这样加字段不必再改一串方法签名,大量老调用点也不受影响。
//
// IP 在 Login/Register 那边仍是显式参数(那里本来就要用它做限速键),值同源。
type ClientMeta struct {
	IP string
	UA string
}

type clientMetaKey struct{}

func WithClientMeta(ctx context.Context, m ClientMeta) context.Context {
	return context.WithValue(ctx, clientMetaKey{}, m)
}

func ClientMetaFrom(ctx context.Context) ClientMeta {
	m, _ := ctx.Value(clientMetaKey{}).(ClientMeta)
	return m
}
