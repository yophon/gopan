package httpx_test

// 收集文件链接在 HTTP 层的边界:真 GraphQL handler + 真 Postgres / MinIO。
// 收集令牌只够往链接里传文件,其余读接口、属主接口、/pack 一律被拒;落地页带链接标题。

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/graph"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func gqlAuthed(t *testing.T, h http.Handler, token, query string, vars map[string]any) gqlResp {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"query": query, "variables": vars})
	req := httptest.NewRequest("POST", "/query", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var out gqlResp
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("响应不是 JSON:%v body=%q", err, rec.Body.String())
	}
	return out
}

func TestCollectTokenBoundaries(t *testing.T) {
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := t.Context()
	q := store.New(pool)
	auth := newTestAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	shares := service.NewShares(q, auth)
	collects := service.NewCollects(pool, auth, uploads)
	cfg := &config.Config{TrashTTL: 30 * 24 * time.Hour}
	es := graph.NewExecutableSchema(graph.Config{Resolvers: &graph.Resolver{
		Cfg: cfg, Auth: auth, Nodes: nodes, Uploads: uploads, Previews: service.NewPreviews(pool, obj),
		Shares: shares, Admin: service.NewAdmin(q, 1<<30), Chats: service.NewChats(pool, nodes), Collects: collects,
	}})
	h := httpx.WithAuth(httpx.NewGraphQLHandler(es, false), auth, nil)

	res, err := auth.Register(ctx, "collect_owner", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	userToken, err := auth.IssueAccess(owner, service.ScopeUser, uuid.Nil)
	if err != nil {
		t.Fatal(err)
	}
	folder, err := nodes.CreateFolder(ctx, owner, nil, "收件")
	if err != nil {
		t.Fatal(err)
	}

	// 属主建链接(GraphQL)
	r := gqlAuthed(t, h, userToken, `mutation($f: ID!) { createFileRequest(folderId: $f, input: {title: "交照片", maxFiles: 10}) { id token title maxFiles fileCount folder { id name } } }`,
		map[string]any{"f": folder.ID.String()})
	if len(r.Errors) > 0 {
		t.Fatalf("createFileRequest: %+v", r.Errors)
	}
	var created struct {
		CreateFileRequest struct {
			ID, Token, Title string
			MaxFiles         int
			Folder           struct{ ID, Name string }
		}
	}
	_ = json.Unmarshal(r.Data, &created)
	fr := created.CreateFileRequest
	if fr.Title != "交照片" || fr.MaxFiles != 10 || fr.Folder.ID != folder.ID.String() {
		t.Fatalf("created: %+v", fr)
	}

	// 访客:公开信息 → 打开 → 拿令牌
	r = gqlAuthed(t, h, "", `query($t: String!) { fileRequestInfo(token: $t) { title needPassword needName closed } }`, map[string]any{"t": fr.Token})
	if len(r.Errors) > 0 || !strings.Contains(string(r.Data), `"title":"交照片"`) {
		t.Fatalf("fileRequestInfo: %s %+v", r.Data, r.Errors)
	}
	r = gqlAuthed(t, h, "", `mutation($t: String!) { openFileRequest(token: $t, uploaderName: "王五") { accessToken } }`, map[string]any{"t": fr.Token})
	var opened struct{ OpenFileRequest struct{ AccessToken string } }
	_ = json.Unmarshal(r.Data, &opened)
	guest := opened.OpenFileRequest.AccessToken
	if guest == "" {
		t.Fatalf("openFileRequest: %+v", r.Errors)
	}

	// 访客能做的:开会话、看自己送达的
	r = gqlAuthed(t, h, guest, `mutation { collectInitUpload(name: "a.jpg", size: 5) { id partSize partUrls { partNumber url } } }`, nil)
	if len(r.Errors) > 0 || !strings.Contains(string(r.Data), `"partNumber":1`) {
		t.Fatalf("collectInitUpload: %s %+v", r.Data, r.Errors)
	}
	r = gqlAuthed(t, h, guest, `{ collectMyUploads { name } }`, nil)
	if len(r.Errors) > 0 {
		t.Fatalf("collectMyUploads: %+v", r.Errors)
	}

	// 访客不能做的:读目录、搜索、取节点、分享根、属主接口、普通上传
	denied := map[string]string{
		"node":           `query($id: ID!) { node(id: $id) { id } }`,
		"children":       `query($id: ID!) { children(parentId: $id) { items { id } } }`,
		"childrenRoot":   `{ children { items { id } } }`,
		"searchNodes":    `{ searchNodes(q: "a") { items { id } } }`,
		"shareRoot":      `{ shareRoot { id } }`,
		"me":             `{ me { id } }`,
		"trash":          `{ trash { items { id } } }`,
		"myShares":       `{ myShares { id } }`,
		"myFileRequests": `{ myFileRequests { id } }`,
		"chatMessages":   `{ chatMessages { id } }`,
		"initUpload":     `mutation($id: ID!) { initUpload(parentId: $id, name: "x", sha256: "` + strings.Repeat("a", 64) + `", size: 1) { instant } }`,
		"createFolder":   `mutation($id: ID!) { createFolder(parentId: $id, name: "x") { id } }`,
		"deleteNodes":    `mutation($id: ID!) { deleteNodes(ids: [$id]) }`,
		"createShare":    `mutation($id: ID!) { createShare(nodeId: $id) { token } }`,
		"revokeRequest":  `mutation { revokeFileRequest(id: "` + fr.ID + `") }`,
	}
	for name, query := range denied {
		r := gqlAuthed(t, h, guest, query, map[string]any{"id": folder.ID.String()})
		if len(r.Errors) == 0 {
			t.Errorf("%s:收集令牌不应能调用,got %s", name, r.Data)
			continue
		}
		if code, _ := r.Errors[0].Extensions["code"].(string); code != "FORBIDDEN" && code != "NOT_FOUND" && code != "UNAUTHENTICATED" {
			t.Errorf("%s:应被拒,got %s", name, code)
		}
	}
	// 反过来:登录用户的令牌、没有令牌都开不了收集会话
	for _, tok := range []string{userToken, ""} {
		r := gqlAuthed(t, h, tok, `mutation { collectInitUpload(name: "a.jpg", size: 5) { id } }`, nil)
		if len(r.Errors) == 0 {
			t.Errorf("非收集令牌(%q)不应能开收集会话", tok[:min(len(tok), 8)])
		}
	}

	// /pack 同样拒绝收集令牌
	packer := service.NewPacker(q, obj, shares)
	pack := httpx.PackHandler(auth, packer, shares, nil)
	req := httptest.NewRequest("GET", "/pack?nodes="+folder.ID.String()+"&token="+guest, nil)
	rec := httptest.NewRecorder()
	pack.ServeHTTP(rec, req)
	if rec.Code == http.StatusOK {
		t.Fatalf("/pack 不应接受收集令牌")
	}

	// 落地页:og 标题是链接标题;撤销后显示已结束
	landing := httpx.CollectLanding(spaIndex, collects)
	get := func() string {
		req := httptest.NewRequest("GET", "/c/"+fr.Token, nil)
		req.SetPathValue("token", fr.Token)
		rec := httptest.NewRecorder()
		landing.ServeHTTP(rec, req)
		return rec.Body.String()
	}
	if body := get(); !strings.Contains(body, `og:title" content="交照片"`) || !strings.Contains(body, "gopan-spa") {
		t.Fatalf("落地页 og 不对:%s", body)
	}
	r = gqlAuthed(t, h, userToken, `mutation { revokeFileRequest(id: "`+fr.ID+`") }`, nil)
	if len(r.Errors) > 0 {
		t.Fatalf("revoke: %+v", r.Errors)
	}
	if body := get(); !strings.Contains(body, "收集已结束") {
		t.Fatalf("撤销后落地页应显示已结束:%s", body)
	}
	// 撤销后访客令牌立即失效
	r = gqlAuthed(t, h, guest, `{ collectMyUploads { name } }`, nil)
	if code, _ := errCode(t, r); code != "COLLECT_CLOSED" {
		t.Fatalf("撤销后应 COLLECT_CLOSED,got %s", code)
	}
}
