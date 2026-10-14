package dav_test

// WebDAV 往传输助手文件夹 PUT 文件 → 服务端自动落一条会话消息;
// 覆盖 PUT 同名文件不重复落消息;PUT 到别的目录不落消息。

import (
	"context"
	"testing"

	"github.com/yophon/gopan/server/internal/service"
)

func TestWebDAVPutIntoChatFolder(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	nodes := service.NewNodes(e.pool)
	chats := service.NewChats(e.pool, nodes)

	folder, err := chats.EnsureFolder(ctx, e.owner)
	if err != nil {
		t.Fatal(err)
	}
	if code, _ := e.do(t, "MKCOL", "/docs", "", nil); code != 201 {
		t.Fatalf("MKCOL 应 201,got %d", code)
	}

	// 别的目录 → 不落消息
	if code, _ := e.do(t, "PUT", "/docs/plain.txt", "plain", nil); code != 201 {
		t.Fatalf("PUT 应 201,got %d", code)
	}
	msgs, err := chats.ListRecent(ctx, e.owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 0 {
		t.Fatalf("PUT 到普通目录不应成消息,got %+v", msgs)
	}

	// 传输助手文件夹 → 自动落一条文件消息
	if code, _ := e.do(t, "PUT", "/"+folder.Name+"/from-rclone.txt", "hello from rclone", nil); code != 201 {
		t.Fatalf("PUT 应 201,got %d", code)
	}
	msgs, err = chats.ListRecent(ctx, e.owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].NodeID == nil {
		t.Fatalf("PUT 到传输助手文件夹应自动成一条文件消息,got %+v", msgs)
	}
	if msgs[0].Source != service.ChatSourceWebDAV {
		t.Fatalf("WebDAV 写入的消息来源应为 webdav,got %q", msgs[0].Source)
	}
	n, err := nodes.Get(ctx, e.owner, *msgs[0].NodeID)
	if err != nil || n.Name != "from-rclone.txt" || n.ParentID == nil || *n.ParentID != folder.ID {
		t.Fatalf("消息应指向刚 PUT 的文件:%+v err=%v", n, err)
	}

	// 覆盖 PUT 同名文件:节点不变,不重复落消息
	if code, _ := e.do(t, "PUT", "/"+folder.Name+"/from-rclone.txt", "hello again", nil); code >= 300 {
		t.Fatalf("覆盖 PUT 失败:%d", code)
	}
	msgs, _ = chats.ListRecent(ctx, e.owner, nil, 10)
	if len(msgs) != 1 {
		t.Fatalf("覆盖同名文件不应重复落消息,got %d 条", len(msgs))
	}
}
