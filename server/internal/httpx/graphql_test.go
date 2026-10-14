package httpx_test

// NewGraphQLHandler 单测:不碰 DB。resolver 全 nil 也安全,因为所选查询要么在
// 执行前就被拦(深度限制/introspection 开关),要么在触达 service 之前就因
// 未登录返回业务错误。graph 包 import 了 httpx,这里必须用外部测试包避免循环。

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/yophon/gopan/server/internal/graph"
	"github.com/yophon/gopan/server/internal/httpx"
)

type gqlResp struct {
	Data   json.RawMessage `json:"data"`
	Errors []struct {
		Message    string         `json:"message"`
		Extensions map[string]any `json:"extensions"`
	} `json:"errors"`
}

func newGQL(devMode bool) http.Handler {
	es := graph.NewExecutableSchema(graph.Config{Resolvers: &graph.Resolver{}})
	return httpx.NewGraphQLHandler(es, devMode)
}

func gqlPost(t *testing.T, h http.Handler, query string) gqlResp {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"query": query})
	req := httptest.NewRequest("POST", "/query", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var out gqlResp
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("响应不是 JSON:%v body=%q", err, rec.Body.String())
	}
	return out
}

func errCode(t *testing.T, r gqlResp) (string, string) {
	t.Helper()
	if len(r.Errors) == 0 {
		t.Fatalf("应有错误,got data=%s", r.Data)
	}
	code, _ := r.Errors[0].Extensions["code"].(string)
	return code, r.Errors[0].Message
}

// service.Error → extensions.code 透出,消息保留业务文案。
func TestGraphQLServiceErrorPresenter(t *testing.T) {
	r := gqlPost(t, newGQL(false), `{ me { id } }`)
	code, msg := errCode(t, r)
	if code != "UNAUTHENTICATED" || msg != "登录已失效" {
		t.Fatalf("未登录查 me 应 UNAUTHENTICATED/登录已失效,got %s/%s", code, msg)
	}
}

// 深度 9 的合法查询(introspection 类型天然可无限嵌套)→ QUERY_TOO_DEEP。
// 深度限制在执行前拦截,与 introspection 是否开启无关。
func TestGraphQLDepthLimit(t *testing.T) {
	deep := `{ __schema { types { fields { type { ofType { ofType { ofType { ofType { name } } } } } } } } }`
	r := gqlPost(t, newGQL(false), deep)
	code, msg := errCode(t, r)
	if code != "QUERY_TOO_DEEP" || !strings.Contains(msg, "嵌套过深") {
		t.Fatalf("超深查询应 QUERY_TOO_DEEP,got %s/%s", code, msg)
	}
	if r.Data != nil && string(r.Data) != "null" {
		t.Fatalf("超深查询不应有 data:%s", r.Data)
	}
}

// 客户端把查询写错(字段拼错 / 语法错):gqlgen 的校验、语法错误原文透出,
// code 分别是 GRAPHQL_VALIDATION_FAILED / GRAPHQL_PARSE_FAILED,不再被打码成"内部错误"。
func TestGraphQLClientQueryErrorsPassThrough(t *testing.T) {
	h := newGQL(false)

	code, msg := errCode(t, gqlPost(t, h, `{ me { idd } }`))
	if code != "GRAPHQL_VALIDATION_FAILED" {
		t.Fatalf("字段拼错应 GRAPHQL_VALIDATION_FAILED,got %s/%s", code, msg)
	}
	if !strings.Contains(msg, "idd") {
		t.Fatalf("校验错误应原文透出并点名字段,got %q", msg)
	}

	code, msg = errCode(t, gqlPost(t, h, `{ me { id `))
	if code != "GRAPHQL_PARSE_FAILED" || msg == "内部错误" {
		t.Fatalf("语法错误应 GRAPHQL_PARSE_FAILED 并保留原文,got %s/%s", code, msg)
	}
}

// 未预期的错误仍然打码:resolver 用 nil 依赖跑出 panic,gqlgen 恢复后必须对外是
// INTERNAL/内部错误,不能把 Go 的 panic 文案漏出去。shareInfo 是公开查询,不需要登录。
func TestGraphQLUnknownErrorMasked(t *testing.T) {
	code, msg := errCode(t, gqlPost(t, newGQL(false), `{ shareInfo(token: "abc") { token } }`))
	if code != "INTERNAL" || msg != "内部错误" {
		t.Fatalf("resolver panic 应打码为 INTERNAL/内部错误,got %s/%s", code, msg)
	}
}

// devMode 开关:生产禁 introspection(禁用提示不是校验错误,仍打码成 INTERNAL/内部错误),
// dev 放行且能查到 Query 类型。
func TestGraphQLIntrospectionToggle(t *testing.T) {
	q := `{ __schema { queryType { name } } }`

	code, msg := errCode(t, gqlPost(t, newGQL(false), q))
	if code != "INTERNAL" || msg != "内部错误" {
		t.Fatalf("生产模式 introspection 应被打码为 INTERNAL/内部错误,got %s/%s", code, msg)
	}

	r := gqlPost(t, newGQL(true), q)
	if len(r.Errors) != 0 || !strings.Contains(string(r.Data), `"Query"`) {
		t.Fatalf("dev 模式 introspection 应可用:errors=%+v data=%s", r.Errors, r.Data)
	}
}
