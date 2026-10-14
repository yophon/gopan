package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
)

// 传输助手里的文件被彻删(回收站彻删 / 清空回收站 / 过期清理都走 Purge):
// 迁移 00020 之前 node_id 置空会撞 chat_messages 的 CHECK 约束,整个彻删失败。
// 现在彻删成功,消息保留(node_id 为空、body 为空),客户端显示「文件已不存在」。
func TestChatPurgeKeepsMessage(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_purge")
	chats, nodes := newChats(pool)

	folder, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	f, err := nodes.CreateFolder(ctx, owner, &folder.ID, "将被彻删")
	if err != nil {
		t.Fatal(err)
	}
	msg, err := chats.SendNode(ctx, owner, f.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := nodes.Delete(ctx, owner, []uuid.UUID{f.ID}); err != nil {
		t.Fatal(err)
	}
	if err := nodes.Purge(ctx, owner, []uuid.UUID{f.ID}); err != nil {
		t.Fatalf("彻删带消息的节点失败:%v", err)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].ID != msg.ID {
		t.Fatalf("消息应保留,got %+v", msgs)
	}
	if msgs[0].NodeID != nil || msgs[0].Body != "" {
		t.Fatalf("彻删后消息应只剩空壳(node_id 为空),got node=%v body=%q", msgs[0].NodeID, msgs[0].Body)
	}

	// 过期清理走同一条路径:再放一个进回收站,按 0 保留期清理
	g, err := nodes.CreateFolder(ctx, owner, &folder.ID, "过期清理")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := chats.SendNode(ctx, owner, g.ID); err != nil {
		t.Fatal(err)
	}
	if err := nodes.Delete(ctx, owner, []uuid.UUID{g.ID}); err != nil {
		t.Fatal(err)
	}
	if n, err := nodes.PurgeExpiredTrash(ctx, -time.Second); err != nil || n != 1 {
		t.Fatalf("过期清理应删掉 1 个,got n=%d err=%v", n, err)
	}
}
