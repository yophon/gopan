package service_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/service"
)

// registerChatUser 创建一个已注册用户并返回 owner id。
func registerChatUser(t *testing.T, pool *pgxpool.Pool, username string) uuid.UUID {
	t.Helper()
	auth := newAuth(pool)
	res, err := auth.Register(context.Background(), username, "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	return res.User.ID
}

func newChats(pool *pgxpool.Pool) (*service.Chats, *service.Nodes) {
	nodes := service.NewNodes(pool)
	return service.NewChats(pool, nodes), nodes
}

func TestChatSendText(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_text")
	chats, _ := newChats(pool)

	msg, err := chats.SendText(ctx, owner, "  你好,世界  ")
	if err != nil {
		t.Fatal(err)
	}
	if msg.Body != "你好,世界" {
		t.Fatalf("body 应 trim 后存入,got %q", msg.Body)
	}
	if msg.NodeID != nil {
		t.Fatalf("纯文本消息不应带 node")
	}

	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].ID != msg.ID {
		t.Fatalf("ListRecent 未回读新消息: %+v", msgs)
	}
}

func TestChatSendTextEmpty(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_empty")
	chats, _ := newChats(pool)

	for _, body := range []string{"", "   ", "\n\t"} {
		if _, err := chats.SendText(ctx, owner, body); err == nil {
			t.Fatalf("空 body %q 应报错", body)
		}
	}
}

func TestChatSendNodeOwnership(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_node_owner")
	other := registerChatUser(t, pool, "chat_node_other")
	chats, nodes := newChats(pool)

	mine, err := nodes.CreateFolder(ctx, owner, nil, "我的文件夹")
	if err != nil {
		t.Fatal(err)
	}
	othersF, err := nodes.CreateFolder(ctx, other, nil, "别人文件夹")
	if err != nil {
		t.Fatal(err)
	}

	// 发别人的节点 → ErrNotFound(防探测)
	if _, err := chats.SendNode(ctx, owner, othersF.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("发他人 node 应 ErrNotFound, got %v", err)
	}
	// 发自己活跃节点 → 成功
	if _, err := chats.SendNode(ctx, owner, mine.ID); err != nil {
		t.Fatalf("发自己的 node 应成功: %v", err)
	}
}

func TestChatSendNodeDeleted(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_node_del")
	chats, nodes := newChats(pool)

	f, err := nodes.CreateFolder(ctx, owner, nil, "待删")
	if err != nil {
		t.Fatal(err)
	}
	if err := nodes.Delete(ctx, owner, []uuid.UUID{f.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := chats.SendNode(ctx, owner, f.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("已删 node 应 ErrNotFound, got %v", err)
	}
}

func TestChatEnsureFolderIdempotent(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_folder")
	chats, nodes := newChats(pool)

	f1, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	if f1.Name != "我的设备" {
		t.Fatalf("默认文件夹名应为 '我的设备', got %q", f1.Name)
	}
	f2, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	if f1.ID != f2.ID {
		t.Fatalf("EnsureFolder 应幂等返回同一文件夹: %s vs %s", f1.ID, f2.ID)
	}

	// 改名后指针仍在,再次 Ensure 仍返回同一节点
	if _, err := nodes.Rename(ctx, owner, f1.ID, "改过的名字"); err != nil {
		t.Fatal(err)
	}
	f3, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	if f3.ID != f1.ID {
		t.Fatalf("改名后 EnsureFolder 应仍返回原节点")
	}

	// 指针落库校验
	var ptr uuid.UUID
	if err := pool.QueryRow(ctx, `SELECT chat_folder_id FROM users WHERE id=$1`, owner).Scan(&ptr); err != nil {
		t.Fatal(err)
	}
	if ptr != f1.ID {
		t.Fatalf("users.chat_folder_id 应指向 %s, got %s", f1.ID, ptr)
	}
}

func TestChatEnsureFolderAfterPurge(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_folder_purge")
	chats, nodes := newChats(pool)

	f1, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	// purge 会删掉 node 行,users.chat_folder_id 因外键 ON DELETE SET NULL 自动置空
	if err := nodes.Purge(ctx, owner, []uuid.UUID{f1.ID}); err != nil {
		t.Fatal(err)
	}
	f2, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	if f2.ID == f1.ID {
		t.Fatalf("purge 后应重建新文件夹, 不能复用旧 id")
	}
	if f2.Name != "我的设备" {
		t.Fatalf("重建后名字应为 '我的设备', got %q", f2.Name)
	}
}

func TestChatListRecentOrder(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_order")
	chats, _ := newChats(pool)

	var ids []uuid.UUID
	for _, body := range []string{"a", "b", "c", "d", "e"} {
		m, err := chats.SendText(ctx, owner, body)
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, m.ID)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 5 {
		t.Fatalf("应有 5 条, got %d", len(msgs))
	}
	for i, m := range msgs {
		if m.ID != ids[i] {
			t.Fatalf("应升序(id 时间序), 第 %d 条不符", i)
		}
	}
}