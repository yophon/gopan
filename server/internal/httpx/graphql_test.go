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

// devMode 开关:生产禁 introspection(非业务错误统一打码成 INTERNAL/内部错误),
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
