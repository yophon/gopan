package mcpserver

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// seedDoc 建一个已提取文字的文件(按 worker 的做法写 blob_texts)。
func seedDoc(t *testing.T, e *mcpEnv, parent *uuid.UUID, name string, pages ...string) uuid.UUID {
	t.Helper()
	ctx := context.Background()
	raw := strings.Join(pages, "\f") + name
	sum := sha256.Sum256([]byte(raw))
	blob, err := e.q.UpsertBlob(ctx, store.UpsertBlobParams{ID: uuid.Must(uuid.NewV7()), Sha256: hex.EncodeToString(sum[:]), Size: int64(len(raw)), Mime: "application/pdf"})
	if err != nil {
		t.Fatal(err)
	}
	content, starts, _ := fulltext.Build(pages, fulltext.MaxContentBytes)
	terms, _ := fulltext.Terms(content, fulltext.MaxTerms)
	p := store.UpsertBlobTextParams{BlobID: blob.ID, Status: "done", Extractor: fulltext.Version, Content: content, Terms: terms}
	if len(starts) > 1 {
		p.PageStarts = starts
	}
	if err := e.q.UpsertBlobText(ctx, p); err != nil {
		t.Fatal(err)
	}
	n, err := e.q.CreateNode(ctx, store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: e.alice, ParentID: parent, Name: name, Kind: "file", BlobID: &blob.ID})
	if err != nil {
		t.Fatal(err)
	}
	return n.ID
}

// 内容搜索与读文字:访问目录受限的凭据只搜得到、读得到目录里的。
func TestMCPContentTools(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	root, err := e.nodes.CreateFolder(ctx, e.alice, nil, "合同")
	if err != nil {
		t.Fatal(err)
	}
	inside := seedDoc(t, e, &root.ID, "租房合同.pdf", "甲方 张三", "第二页:押一付三,租房合同自签字之日起生效")
	outside := seedDoc(t, e, nil, "别处的租房合同.pdf", "租房合同自签字之日起生效")
	token, key, err := e.tokens.Create(ctx, e.alice, "restricted", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	path := "/合同"
	if err = e.nodes.SetMCPAccessRoot(ctx, e.alice, key.ID, "api_key", &path); err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, token)

	var found SearchContentsOutput
	callTool(t, sess, "search_file_contents", map[string]any{"query": "签字之日"}, &found)
	if len(found.Items) != 1 || found.Items[0].ID != inside.String() || found.Items[0].Page == nil || *found.Items[0].Page != 2 ||
		!strings.Contains(found.Items[0].Snippet, "签字之日") {
		t.Fatalf("search: %+v", found)
	}
	var doc ReadDocumentOutput
	callTool(t, sess, "read_document_text", map[string]any{"node_id": inside.String(), "max_chars": 4}, &doc)
	if doc.Text != "甲方张三" || doc.NextOffset != 4 || doc.EOF || doc.Total == 0 || doc.Name != "租房合同.pdf" {
		t.Fatalf("read: %+v", doc)
	}
	// 第 4 个字之后是分页的换行(仍属第 1 页),从它后面读就是第 2 页
	callTool(t, sess, "read_document_text", map[string]any{"path": "/租房合同.pdf", "offset": doc.NextOffset + 1}, &doc)
	if !doc.EOF || !strings.HasPrefix(doc.Text, "第二页") || doc.Page == nil || *doc.Page != 2 {
		t.Fatalf("read by path: %+v", doc)
	}
	callToolErr(t, sess, "read_document_text", map[string]any{"node_id": outside.String()})
	if msg := callToolErr(t, sess, "search_file_contents", map[string]any{"query": "合"}); !strings.Contains(msg, "QUERY_TOO_SHORT") && !strings.Contains(msg, "太短") {
		t.Fatalf("短关键词: %s", msg)
	}
}
