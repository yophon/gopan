package httpx_test

// 压缩包单个文件(/arc)在 HTTP 层的边界:属主与分享访客能读,收集令牌、别人、未登录不能;
// 包里的 html 按纯文本返回并带 CSP sandbox。

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
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

func TestArchiveEntryHTTP(t *testing.T) {
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := t.Context()
	q := store.New(pool)
	auth := newTestAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	shares := service.NewShares(q, auth)
	collects := service.NewCollects(pool, auth, uploads)
	archives := service.NewArchives(pool, obj, uploads)

	res, err := auth.Register(ctx, "arc_owner", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	userToken, _ := auth.IssueAccess(owner, service.ScopeUser, uuid.Nil)
	shared, _ := nodes.CreateFolder(ctx, owner, nil, "分享的")
	private, _ := nodes.CreateFolder(ctx, owner, nil, "私有")

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range map[string]string{"a.txt": "hello", "p.html": "<script>alert(1)</script>", "x.bin": "\x00\x01"} {
		f, _ := zw.Create(name)
		f.Write([]byte(body))
	}
	zw.Close()
	put := func(parent uuid.UUID, name string) store.Node {
		key := "tmp/test/" + uuid.NewString()
		if err := obj.Put(ctx, key, bytes.NewReader(buf.Bytes()), int64(buf.Len()), "application/zip"); err != nil {
			t.Fatal(err)
		}
		sum := sha256.Sum256(buf.Bytes())
		n, err := uploads.CommitStaged(ctx, service.StagedFile{Owner: owner, Parent: &parent, Name: name, Sha: hex.EncodeToString(sum[:]), Size: int64(buf.Len()), TmpKey: key})
		if err != nil {
			t.Fatal(err)
		}
		return *n
	}
	in := put(shared.ID, "包.zip")
	out := put(private.ID, "外面.zip") // 同一份内容,不在分享里
	if err := archives.BuildIndex(ctx, *in.BlobID); err != nil {
		t.Fatal(err)
	}
	idxOf := map[string]int32{}
	rows, _, _, err := archives.Entries(ctx, owner, in.ID, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range rows {
		idxOf[r.Name] = r.Idx
	}

	mux := http.NewServeMux()
	mux.Handle("GET /arc/{node}/{idx}", httpx.ArchiveEntryHandler(auth, archives, shares))
	get := func(node uuid.UUID, name, token, extra string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		u := "/arc/" + node.String() + "/" + strconv.Itoa(int(idxOf[name])) + "?token=" + token + extra
		mux.ServeHTTP(rec, httptest.NewRequest("GET", u, nil))
		return rec
	}

	rec := get(in.ID, "a.txt", userToken, "")
	if rec.Code != 200 || rec.Body.String() != "hello" || !strings.HasPrefix(rec.Header().Get("Content-Type"), "text/plain") ||
		!strings.Contains(rec.Header().Get("Content-Security-Policy"), "sandbox") || rec.Header().Get("Content-Length") != "5" {
		t.Fatalf("属主: %d %q %v", rec.Code, rec.Body.String(), rec.Header())
	}
	if rec := get(in.ID, "p.html", userToken, ""); rec.Code != 200 || rec.Header().Get("Content-Type") != "text/plain; charset=utf-8" {
		t.Fatalf("html 应按纯文本: %d %s", rec.Code, rec.Header().Get("Content-Type"))
	}
	if rec := get(in.ID, "x.bin", userToken, ""); rec.Code != http.StatusUnsupportedMediaType {
		t.Fatalf(".bin 预览: %d", rec.Code)
	}
	rec = get(in.ID, "x.bin", userToken, "&download=1")
	if rec.Code != 200 || !strings.HasPrefix(rec.Header().Get("Content-Disposition"), "attachment") {
		t.Fatalf(".bin 下载: %d %v", rec.Code, rec.Header())
	}
	var body map[string]string
	rec = get(out.ID, "a.txt", userToken, "") // 外面那个还没建索引?同一份内容共享索引
	if rec.Code != 200 {
		_ = json.Unmarshal(rec.Body.Bytes(), &body)
		t.Fatalf("同内容的另一个文件: %d %v", rec.Code, body)
	}

	// 分享访客:分享里的能读,外面的不行
	sh, err := shares.Create(ctx, owner, shared.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	guest, err := shares.Access(ctx, sh.Token, "", "2.2.2.2")
	if err != nil {
		t.Fatal(err)
	}
	if rec := get(in.ID, "a.txt", guest, ""); rec.Code != 200 || rec.Body.String() != "hello" {
		t.Fatalf("分享访客: %d %s", rec.Code, rec.Body.String())
	}
	if rec := get(out.ID, "a.txt", guest, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("分享外的: %d", rec.Code)
	}

	// 收集令牌、别人、未登录
	fr, err := collects.Create(ctx, owner, shared.ID, service.FileRequestInput{Title: "收"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	collectTok, err := collects.Open(ctx, fr.Token, "", "", "3.3.3.3")
	if err != nil {
		t.Fatal(err)
	}
	if rec := get(in.ID, "a.txt", collectTok, ""); rec.Code == 200 {
		t.Fatal("收集令牌不该能读")
	}
	other, _ := auth.Register(ctx, "arc_other", "password123", "1.1.1.1")
	otherTok, _ := auth.IssueAccess(other.User.ID, service.ScopeUser, uuid.Nil)
	if rec := get(in.ID, "a.txt", otherTok, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("别人: %d", rec.Code)
	}
	if rec := get(in.ID, "a.txt", "", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("未登录: %d", rec.Code)
	}

	// GraphQL:分享访客能看分享里压缩包的目录,收集令牌不能
	cfg := &config.Config{TrashTTL: 30 * 24 * time.Hour}
	es := graph.NewExecutableSchema(graph.Config{Resolvers: &graph.Resolver{
		Cfg: cfg, Auth: auth, Nodes: nodes, Uploads: uploads, Previews: service.NewPreviews(pool, obj),
		Shares: shares, Admin: service.NewAdmin(q, 1<<30), Chats: service.NewChats(pool, nodes), Collects: collects, Archives: archives,
	}})
	h := httpx.WithAuth(httpx.NewGraphQLHandler(es, false), auth, nil)
	query := `query($id: ID!) { archiveInfo(nodeId: $id) { status fileCount } archiveEntries(nodeId: $id) { total items { name previewable downloadable } } }`
	r := gqlAuthed(t, h, guest, query, map[string]any{"id": in.ID.String()})
	if len(r.Errors) > 0 || !strings.Contains(string(r.Data), `"status":"DONE"`) || !strings.Contains(string(r.Data), `"total":3`) {
		t.Fatalf("访客 GraphQL: %s %+v", r.Data, r.Errors)
	}
	if r := gqlAuthed(t, h, collectTok, query, map[string]any{"id": in.ID.String()}); len(r.Errors) == 0 {
		t.Fatal("收集令牌不该能列压缩包")
	}
	// 预览类型:压缩包
	r = gqlAuthed(t, h, userToken, `query($id: ID!) { node(id: $id) { preview { kind } } }`, map[string]any{"id": in.ID.String()})
	if !strings.Contains(string(r.Data), `"kind":"ARCHIVE"`) {
		t.Fatalf("preview kind: %s %+v", r.Data, r.Errors)
	}
}
