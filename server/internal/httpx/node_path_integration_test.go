package httpx_test

// Node.path:属主看得到从根开始的路径(搜索结果拿来显示「位置」),分享访客拿到的是 null。

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/graph"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestNodePathField(t *testing.T) {
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := t.Context()
	q := store.New(pool)
	auth := newTestAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	shares := service.NewShares(q, auth)
	cfg := &config.Config{TrashTTL: 30 * 24 * time.Hour}
	es := graph.NewExecutableSchema(graph.Config{Resolvers: &graph.Resolver{
		Cfg: cfg, Auth: auth, Nodes: nodes, Uploads: uploads, Previews: service.NewPreviews(pool, obj),
		Shares: shares, Admin: service.NewAdmin(q, 1<<30), Chats: service.NewChats(pool, nodes),
	}})
	h := httpx.WithAuth(httpx.NewGraphQLHandler(es, false), auth, nil)

	res, err := auth.Register(ctx, "path_owner", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	token, err := auth.IssueAccess(owner, service.ScopeUser, uuid.Nil)
	if err != nil {
		t.Fatal(err)
	}
	work, err := nodes.CreateFolder(ctx, owner, nil, "工作资料")
	if err != nil {
		t.Fatal(err)
	}
	contract, err := nodes.CreateFolder(ctx, owner, &work.ID, "合同 2026")
	if err != nil {
		t.Fatal(err)
	}

	// 单个节点
	r := gqlAuthed(t, h, token, `query($id: ID!) { node(id: $id) { path } }`, map[string]any{"id": contract.ID.String()})
	if len(r.Errors) > 0 {
		t.Fatalf("node.path: %+v", r.Errors)
	}
	var one struct{ Node struct{ Path *string } }
	_ = json.Unmarshal(r.Data, &one)
	if one.Node.Path == nil || *one.Node.Path != "/工作资料/合同 2026" {
		t.Fatalf("node.path = %v", one.Node.Path)
	}

	// 搜索结果里每一项都带上
	r = gqlAuthed(t, h, token, `{ searchNodes(q: "合同") { items { name path } } }`, nil)
	if len(r.Errors) > 0 {
		t.Fatalf("searchNodes.path: %+v", r.Errors)
	}
	var found struct {
		SearchNodes struct {
			Items []struct {
				Name string
				Path *string
			}
		}
	}
	_ = json.Unmarshal(r.Data, &found)
	if len(found.SearchNodes.Items) != 1 || found.SearchNodes.Items[0].Path == nil || *found.SearchNodes.Items[0].Path != "/工作资料/合同 2026" {
		t.Fatalf("searchNodes = %+v", found.SearchNodes.Items)
	}

	// 分享访客:看不到属主的目录结构,path 为 null(查询本身不报错)
	sh, err := shares.Create(ctx, owner, work.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	guest, err := auth.IssueAccessFor(owner, "share:"+sh.ID.String(), uuid.Nil, 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	r = gqlAuthed(t, h, guest, `{ shareRoot { name path } }`, nil)
	if len(r.Errors) > 0 {
		t.Fatalf("shareRoot.path: %+v", r.Errors)
	}
	var root struct{ ShareRoot struct{ Path *string } }
	_ = json.Unmarshal(r.Data, &root)
	if root.ShareRoot.Path != nil {
		t.Fatalf("访客不该拿到路径,got %q", *root.ShareRoot.Path)
	}
}
