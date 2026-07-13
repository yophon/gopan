package service_test

// OAuth 授权请求校验的集成测试:InspectAuthorization / validateAuthorization 的各错误分支。
// happy path(授权码兑换、refresh、吊销)在 service_test.go 里已覆盖。

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"testing"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestOAuthInspectAuthorization(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	oauth := service.NewOAuth(store.New(pool))

	redirectURI := "http://127.0.0.1:49152/callback"
	client, err := oauth.RegisterClient(ctx, "Codex", []string{redirectURI})
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256([]byte("verifier-abcdefghijklmnopqrstuvwxyz0123456789"))
	challenge := base64.RawURLEncoding.EncodeToString(digest[:])
	valid := service.OAuthAuthorizationInput{
		ClientID: client.ClientID, RedirectURI: redirectURI, ResponseType: "code",
		CodeChallenge: challenge, CodeChallengeMethod: "S256",
	}

	// 合法请求:返回 client 名,空 scopes 默认 files:read
	view, err := oauth.InspectAuthorization(ctx, valid)
	if err != nil || view.ClientName != "Codex" || len(view.Scopes) != 1 || view.Scopes[0] != "files:read" {
		t.Fatalf("view=%+v err=%v", view, err)
	}

	// 各错误分支
	cases := []struct {
		name   string
		code   string
		mutate func(in *service.OAuthAuthorizationInput)
	}{
		{"response_type 非 code", "OAUTH_INVALID_REQUEST", func(in *service.OAuthAuthorizationInput) { in.ResponseType = "token" }},
		{"未知 client", "OAUTH_INVALID_CLIENT", func(in *service.OAuthAuthorizationInput) { in.ClientID = "gopan_client_unknown" }},
		{"redirect_uri 未注册", "OAUTH_INVALID_REQUEST", func(in *service.OAuthAuthorizationInput) { in.RedirectURI = "http://127.0.0.1:49153/other" }},
		{"PKCE 方法必须 S256", "OAUTH_INVALID_REQUEST", func(in *service.OAuthAuthorizationInput) { in.CodeChallengeMethod = "plain" }},
		{"code_challenge 格式非法", "OAUTH_INVALID_REQUEST", func(in *service.OAuthAuthorizationInput) { in.CodeChallenge = "short" }},
		{"scope 不合法", "INVALID_INPUT", func(in *service.OAuthAuthorizationInput) { in.Scopes = []string{"admin:*"} }},
	}
	for _, tc := range cases {
		in := valid
		tc.mutate(&in)
		if _, err := oauth.InspectAuthorization(ctx, in); svcErrCode(err) != tc.code {
			t.Fatalf("%s:want %s,got %v", tc.name, tc.code, err)
		}
	}

	// 显式 scopes:去重 + 排序后回显
	in := valid
	in.Scopes = []string{"files:write", "files:read", "files:write"}
	view, err = oauth.InspectAuthorization(ctx, in)
	if err != nil || len(view.Scopes) != 2 || view.Scopes[0] != "files:read" || view.Scopes[1] != "files:write" {
		t.Fatalf("scopes=%v err=%v", view.Scopes, err)
	}
}
