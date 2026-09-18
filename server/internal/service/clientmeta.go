package service

import "context"

// ClientMeta 是请求级的客户端信息(IP 之外的东西,主要是 UA)。
//
// IP 一直由调用方显式传参(见 Login/Register),这里只管附加信息 —— 这样加字段
// 不必再改一串方法签名,老调用点(大量测试)也不受影响。
type ClientMeta struct {
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
