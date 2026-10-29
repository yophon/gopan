package mcpserver

// 看图与写文本(doc/35):助手的 read / write / edit 底下调的两个工具;不列给模型,但运行时能调。

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/png"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

func TestAssistantFileTools(t *testing.T) {
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
	sess, err := connectAssistant(t, srv.URL, assistantTransport{token: login.AccessToken, conv: uuid.NewString(), turn: uuid.NewString()})
	if err != nil {
		t.Fatal(err)
	}

	// 运行时内部用的不列给模型
	tools := toolNames(t, sess)
	for _, name := range []string{"read_text_file", "read_document_text", "view_image", "write_text_file"} {
		if tools[name] != nil {
			t.Fatalf("%s listed for the assistant", name)
		}
	}

	// 写:上级不存在报 NOT_FOUND;建好再写
	if msg := callToolErr(t, sess, "write_text_file", map[string]any{"path": "/笔记/会议.md", "content": "a"}); !strings.Contains(msg, "NOT_FOUND") {
		t.Fatalf("missing parent: %s", msg)
	}
	callTool(t, sess, "create_folder", map[string]any{"name": "笔记"}, nil)
	var created WriteTextOutput
	callTool(t, sess, "write_text_file", map[string]any{"path": "/笔记/会议.md", "content": "第一行\n"}, &created)
	if !created.Created || created.SHA256 == nil || created.Name != "会议.md" {
		t.Fatalf("create: %+v", created)
	}
	var read ReadTextOutput
	callTool(t, sess, "read_text_file", map[string]any{"path": "/笔记/会议.md"}, &read)
	if read.Text != "第一行\n" || read.SHA256 != *created.SHA256 {
		t.Fatalf("read: %+v", read)
	}

	// 覆盖:没带版本拒绝;版本过期报冲突;对得上才写
	if msg := callToolErr(t, sess, "write_text_file", map[string]any{"path": "/笔记/会议.md", "content": "x"}); !strings.Contains(msg, "READ_FIRST") {
		t.Fatalf("blind overwrite: %s", msg)
	}
	if msg := callToolErr(t, sess, "write_text_file", map[string]any{"path": "/笔记/会议.md", "content": "x", "base_sha256": strings.Repeat("0", 64)}); !strings.Contains(msg, "EDIT_CONFLICT") {
		t.Fatalf("stale overwrite: %s", msg)
	}
	var saved WriteTextOutput
	callTool(t, sess, "write_text_file", map[string]any{"path": "/笔记/会议.md", "content": "第一行\n第二行\n", "base_sha256": read.SHA256}, &saved)
	if saved.Created || saved.ID != created.ID || *saved.SHA256 == read.SHA256 {
		t.Fatalf("overwrite: %+v", saved)
	}
	var kind string
	if err := e.pool.QueryRow(ctx, `SELECT actor_kind FROM activities WHERE owner_id = $1 AND action = 'overwrite' ORDER BY id DESC LIMIT 1`, e.alice).Scan(&kind); err != nil || kind != service.ActorAssistant {
		t.Fatalf("overwrite activity: %q %v", kind, err)
	}

	// 看图:原图(还没有派生图)当场缩成 JPEG;文本文件没有图
	var buf bytes.Buffer
	img := image.NewRGBA(image.Rect(0, 0, 40, 30))
	for x := 0; x < 40; x++ {
		for y := 0; y < 30; y++ {
			img.Set(x, y, color.RGBA{R: 200, A: 255})
		}
	}
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	if _, err := e.uploads.CreateTextFile(ctx, e.alice, nil, "red.png", buf.String()); err != nil {
		t.Fatal(err)
	}
	res, err := sess.CallTool(ctx, &mcp.CallToolParams{Name: "view_image", Arguments: map[string]any{"path": "/red.png"}})
	if err != nil || res.IsError {
		t.Fatalf("view_image: %v %s", err, resultText(res))
	}
	var jpeg *mcp.ImageContent
	for _, c := range res.Content {
		if ic, ok := c.(*mcp.ImageContent); ok {
			jpeg = ic
		}
	}
	if jpeg == nil || jpeg.MIMEType != "image/jpeg" || !bytes.HasPrefix(jpeg.Data, []byte{0xff, 0xd8}) {
		t.Fatalf("view_image content: %+v", res.Content)
	}
	if msg := callToolErr(t, sess, "view_image", map[string]any{"path": "/笔记/会议.md"}); !strings.Contains(msg, "NOT_IMAGE") {
		t.Fatalf("view_image on text: %s", msg)
	}

	// 目录范围:路径在范围的根下解析,只给名字时落在范围的根
	root := "/笔记"
	if err := e.nodes.SetMCPAccessRoot(ctx, e.alice, e.alice, service.CredentialAssistant, &root); err != nil {
		t.Fatal(err)
	}
	var scoped WriteTextOutput
	callTool(t, sess, "write_text_file", map[string]any{"name": "待办.md", "content": "- 买菜"}, &scoped)
	n, err := e.nodes.ResolvePathAt(ctx, e.alice, "/笔记/待办.md", nil)
	if err != nil || n == nil || n.ID.String() != scoped.ID {
		t.Fatalf("scoped write landed elsewhere: %v %v", n, err)
	}
	if msg := callToolErr(t, sess, "view_image", map[string]any{"path": "/red.png"}); msg == "" {
		t.Fatal("view_image outside the scoped root succeeded")
	}
}
