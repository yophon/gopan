package mcpserver

// 助手走 /mcp(doc/33 三):登录凭证 + X-Gopan-Assistant;tools/list 按权限过滤、工具带 scope 与提示标注;
// 同一轮回答的改动合成一批,动态来源是助手;停用后连不上。

import (
	"context"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

type assistantTransport struct {
	token, conv, turn string
}

func (a assistantTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	r.Header.Set("Authorization", "Bearer "+a.token)
	if a.conv != "" {
		r.Header.Set(assistantHeader, a.conv)
	}
	if a.turn != "" {
		r.Header.Set(assistantTurnHeader, a.turn)
	}
	return http.DefaultTransport.RoundTrip(r)
}

func connectAssistant(t *testing.T, url string, tr assistantTransport) (*mcp.ClientSession, error) {
	t.Helper()
	client := mcp.NewClient(&mcp.Implementation{Name: "tau-test", Version: "0.0.1"}, nil)
	sess, err := client.Connect(context.Background(), &mcp.StreamableClientTransport{
		Endpoint: url, HTTPClient: &http.Client{Transport: tr}, MaxRetries: -1, DisableStandaloneSSE: true,
	}, nil)
	if err == nil {
		t.Cleanup(func() { sess.Close() })
	}
	return sess, err
}

func toolNames(t *testing.T, sess *mcp.ClientSession) map[string]*mcp.Tool {
	t.Helper()
	res, err := sess.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]*mcp.Tool{}
	for _, tool := range res.Tools {
		out[tool.Name] = tool
	}
	return out
}

func TestAssistantOverMCP(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	auth := service.NewAuth(e.q, []byte("test-secret-test-secret-test-secret"), 15*time.Minute, 14*24*time.Hour, true, 1<<30)
	assistant := service.NewAssistant(e.pool)
	srv := httptest.NewServer(NewHandler(e.nodes, e.uploads, e.tokens, e.oauth, nil, e.tickets, e.chats, nil, e.jobs, e.archives, nil, service.NewActivities(e.pool, e.nodes), nil, WithAssistant(auth, assistant)))
	t.Cleanup(srv.Close)
	login, err := auth.Login(ctx, "mcp_alice", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	conv, turn := uuid.New(), uuid.New()
	tr := assistantTransport{token: login.AccessToken, conv: conv.String(), turn: turn.String()}

	// 没带对话 id 的登录凭证不认
	if _, err := connectAssistant(t, srv.URL, assistantTransport{token: login.AccessToken}); err == nil {
		t.Fatal("login token without assistant header was accepted")
	}

	sess, err := connectAssistant(t, srv.URL, tr)
	if err != nil {
		t.Fatal(err)
	}
	tools := toolNames(t, sess)
	for _, name := range []string{"list_files", "read_text_file", "create_folder", "trash_nodes", "batch_nodes", "create_share", "chat_send"} {
		if tools[name] == nil {
			t.Fatalf("default policy hides %s", name)
		}
	}
	for _, name := range []string{"prepare_upload", "wait_upload", "prepare_download"} {
		if tools[name] != nil {
			t.Fatalf("assistant sees %s", name)
		}
	}
	if lf := tools["list_files"]; lf.Annotations == nil || !lf.Annotations.ReadOnlyHint || !slices.Equal(anyStrings(lf.Meta[metaScopes]), []string{"files:read"}) {
		t.Fatalf("list_files annotations: %+v %+v", lf.Annotations, lf.Meta)
	}
	if tn := tools["trash_nodes"]; tn.Annotations == nil || tn.Annotations.ReadOnlyHint || tn.Annotations.DestructiveHint == nil || !*tn.Annotations.DestructiveHint {
		t.Fatalf("trash_nodes annotations: %+v", tn.Annotations)
	}
	if bn := tools["batch_nodes"]; !slices.Equal(anyStrings(bn.Meta[metaScopes]), []string{"files:write", "files:delete"}) {
		t.Fatalf("batch_nodes scopes: %+v", bn.Meta)
	}

	// 同一轮回答的两次改动合成一批,来源是助手
	var a, b struct {
		ID string `json:"id"`
	}
	callTool(t, sess, "create_folder", map[string]any{"name": "报销"}, &a)
	callTool(t, sess, "create_folder", map[string]any{"name": "发票"}, &b)
	rows, err := e.pool.Query(ctx, `SELECT actor_kind, actor_ref, actor_label, batch_id FROM activities WHERE owner_id = $1 AND action = 'mkdir' ORDER BY id`, e.alice)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for rows.Next() {
		var kind, ref, label string
		var batch uuid.UUID
		if err := rows.Scan(&kind, &ref, &label, &batch); err != nil {
			t.Fatal(err)
		}
		if kind != service.ActorAssistant || ref != conv.String() || label != "助手" || batch != turn {
			t.Fatalf("activity: %s %s %s %s", kind, ref, label, batch)
		}
		n++
	}
	rows.Close()
	if n != 2 {
		t.Fatalf("mkdir activities: %d", n)
	}

	// 拒绝删除:回收站那两个不再出现,硬调被拒;批量操作还在(修改还给)
	if _, err := assistant.SetAccess(ctx, e.alice, false, map[string]string{"files:delete": "deny"}); err != nil {
		t.Fatal(err)
	}
	tools = toolNames(t, sess)
	if tools["trash_nodes"] != nil || tools["restore_nodes"] != nil || tools["batch_nodes"] == nil {
		t.Fatalf("deny files:delete: trash=%v restore=%v batch=%v", tools["trash_nodes"] != nil, tools["restore_nodes"] != nil, tools["batch_nodes"] != nil)
	}
	res, err := sess.CallTool(ctx, &mcp.CallToolParams{Name: "trash_nodes", Arguments: map[string]any{"node_ids": []string{a.ID}}})
	if err == nil && !res.IsError {
		t.Fatal("denied trash_nodes succeeded")
	}

	// 目录范围:限定到「报销」后看不到「发票」
	root := "/报销"
	if err := e.nodes.SetMCPAccessRoot(ctx, e.alice, e.alice, service.CredentialAssistant, &root); err != nil {
		t.Fatal(err)
	}
	var info map[string]any
	res, err = sess.CallTool(ctx, &mcp.CallToolParams{Name: "get_file_info", Arguments: map[string]any{"node_id": b.ID}})
	if err == nil && !res.IsError {
		t.Fatalf("assistant outside its root: %s", resultText(res))
	}
	callTool(t, sess, "get_file_info", map[string]any{"node_id": a.ID}, &info)

	// 停用:连不上
	if _, err := assistant.SetAccess(ctx, e.alice, true, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := connectAssistant(t, srv.URL, tr); err == nil {
		t.Fatal("disabled assistant connected")
	}
}

func anyStrings(v any) []string {
	switch x := v.(type) {
	case []string:
		return x
	case []any:
		out := make([]string, 0, len(x))
		for _, s := range x {
			str, _ := s.(string)
			out = append(out, str)
		}
		return out
	}
	return nil
}

// 每个文件端工具都要在 toolScopes 里,否则助手看不到、客户端也不知道该问不该问
func TestEveryToolHasScopes(t *testing.T) {
	s := &Server{}
	protocol := mcp.NewServer(&mcp.Implementation{Name: "gopan", Version: "test"}, nil)
	s.registerTools(protocol)
	ct, st := mcp.NewInMemoryTransports()
	if _, err := protocol.Connect(context.Background(), st, nil); err != nil {
		t.Fatal(err)
	}
	client := mcp.NewClient(&mcp.Implementation{Name: "c", Version: "0"}, nil)
	sess, err := client.Connect(context.Background(), ct, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()
	res, err := sess.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, tool := range res.Tools {
		if _, ok := toolScopes[tool.Name]; !ok {
			t.Errorf("tool %s has no entry in toolScopes", tool.Name)
		}
	}
	if len(res.Tools) < 40 {
		t.Fatalf("only %d tools registered", len(res.Tools))
	}
}
