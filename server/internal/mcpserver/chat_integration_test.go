package mcpserver

// 「我的设备」会话的 MCP 集成:chat_send / chat_list。
// 端到端用官方 go-sdk client 走真协议(与 TestMCPEndToEndOverHTTP 同一条路)。
// 环境变量(见 integration_test.go 顶部)缺一即跳过。

import (
	"context"
	"strings"
	"testing"
)

func TestChatSendAndList(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()

	plain, _, err := e.tokens.Create(ctx, e.alice, "chat", []string{"chat:read", "chat:write"})
	if err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, plain)

	// 1. 发文本
	var sent ChatMessageOutput
	callTool(t, sess, "chat_send", map[string]any{"text": "  你好,世界  "}, &sent)
	if sent.ID == "" || sent.Body != "你好,世界" || sent.NodeID != nil {
		t.Fatalf("chat_send 文本输出不符:%+v", sent)
	}

	// 2. 发文件消息:节点用 create_folder 的文件夹(服务端 SendNode 只校验属主活跃)
	var folder NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"name": "chat-files"}, &folder)
	var file ChatMessageOutput
	callTool(t, sess, "chat_send", map[string]any{"node_id": folder.ID}, &file)
	if file.NodeID == nil || *file.NodeID != folder.ID {
		t.Fatalf("chat_send(node) 未回带 node_id: %+v", file)
	}

	// 3. chat_list:两条都在,升序
	var list ChatListOutput
	callTool(t, sess, "chat_list", map[string]any{"limit": 10}, &list)
	if len(list.Items) != 2 {
		t.Fatalf("chat_list 应返回 2 条,got %d:%+v", len(list.Items), list.Items)
	}
	if list.Items[0].ID != sent.ID || list.Items[1].ID != file.ID {
		t.Fatalf("chat_list 应按时间升序:%+v", list.Items)
	}

	// 4. before_id 游标:取最早一条之前 → 空
	var before ChatListOutput
	callTool(t, sess, "chat_list", map[string]any{"limit": 10, "before_id": sent.ID}, &before)
	if len(before.Items) != 0 {
		t.Fatalf("before_id=sent.id 应返回空,got %+v", before.Items)
	}

	// 5. 他人 node → NOT_FOUND(防探测)
	otherToken, _, _ := e.tokens.Create(ctx, e.bob, "bob-chat", []string{"chat:read", "chat:write"})
	otherSess := e.connect(t, otherToken)
	msg := callToolErr(t, otherSess, "chat_send", map[string]any{"node_id": folder.ID})
	if !strings.Contains(msg, "NOT_FOUND") {
		t.Fatalf("发他人 node 应 NOT_FOUND,got %q", msg)
	}
}

// 无 chat:* scope 的 token 调 chat 工具 → FORBIDDEN。
func TestChatScope0(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()

	plain, _, err := e.tokens.Create(ctx, e.alice, "files-only", []string{"files:read"})
	if err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, plain)
	got := callToolErr(t, sess, "chat_list", map[string]any{})
	if !strings.Contains(got, "FORBIDDEN") {
		t.Fatalf("无 chat:read 应 FORBIDDEN,got %q", got)
	}
	got = callToolErr(t, sess, "chat_send", map[string]any{"text": "hi"})
	if !strings.Contains(got, "FORBIDDEN") {
		t.Fatalf("无 chat:write 应 FORBIDDEN,got %q", got)
	}
}