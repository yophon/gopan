package service_test

// 传输助手 v2.5 整改的服务层测试:SendNode 幂等、删除、搜索、
// 文件落进传输助手文件夹自动成消息(秒传 / 浏览器分片两条路径)、refresh 宽限期。

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

func TestChatSendNodeIdempotent(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_idem")
	chats, nodes := newChats(pool)

	f, err := nodes.CreateFolder(ctx, owner, nil, "重复发")
	if err != nil {
		t.Fatal(err)
	}
	m1, err := chats.SendNode(ctx, owner, f.ID)
	if err != nil {
		t.Fatal(err)
	}
	m2, err := chats.SendNode(ctx, owner, f.ID)
	if err != nil {
		t.Fatal(err)
	}
	if m1.ID != m2.ID {
		t.Fatalf("同一节点再次发送应返回已有消息:%s vs %s", m1.ID, m2.ID)
	}
	if m1.Source != service.ChatSourceWeb {
		t.Fatalf("默认来源应为 web,got %q", m1.Source)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 {
		t.Fatalf("会话里应只有 1 条,got %d", len(msgs))
	}
}

func TestChatDelete(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_del_owner")
	other := registerChatUser(t, pool, "chat_del_other")
	chats, nodes := newChats(pool)

	f, err := nodes.CreateFolder(ctx, owner, nil, "附件")
	if err != nil {
		t.Fatal(err)
	}
	text, err := chats.SendText(ctx, owner, "待删文本")
	if err != nil {
		t.Fatal(err)
	}
	file, err := chats.SendNode(ctx, owner, f.ID)
	if err != nil {
		t.Fatal(err)
	}

	// 他人删 → NOT_FOUND,消息仍在
	if err := chats.Delete(ctx, other, text.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("他人删除应 ErrNotFound,got %v", err)
	}
	// 本人删文本 → 成功;再删 → NOT_FOUND
	if err := chats.Delete(ctx, owner, text.ID); err != nil {
		t.Fatal(err)
	}
	if err := chats.Delete(ctx, owner, text.ID); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("重复删除应 ErrNotFound,got %v", err)
	}
	// 删文件消息:只删消息,节点还活着
	if err := chats.Delete(ctx, owner, file.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := nodes.Get(ctx, owner, f.ID); err != nil {
		t.Fatalf("删消息不应影响附件节点:%v", err)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 0 {
		t.Fatalf("应全部删除,got %d", len(msgs))
	}
}

func TestChatSearch(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_search")
	other := registerChatUser(t, pool, "chat_search_other")
	chats, nodes := newChats(pool)

	for _, body := range []string{"今晚把合同发我", "100% 完成", "无关内容"} {
		if _, err := chats.SendText(ctx, owner, body); err != nil {
			t.Fatal(err)
		}
	}
	f, err := nodes.CreateFolder(ctx, owner, nil, "合同扫描件")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := chats.SendNode(ctx, owner, f.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := chats.SendText(ctx, other, "别人的合同"); err != nil {
		t.Fatal(err)
	}

	// 正文 + 附件名都命中,升序,不串用户
	hits, err := chats.Search(ctx, owner, "合同", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 2 {
		t.Fatalf("「合同」应命中 2 条(正文 + 附件名),got %d:%+v", len(hits), hits)
	}
	if !(hits[0].Body == "今晚把合同发我" && hits[1].NodeID != nil) {
		t.Fatalf("应升序且第二条是附件消息:%+v", hits)
	}
	// 通配符按字面匹配:"%" 只命中含百分号的那条
	hits, err = chats.Search(ctx, owner, "%", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 || hits[0].Body != "100% 完成" {
		t.Fatalf("「%%」应按字面只命中 1 条,got %+v", hits)
	}
	// 大小写不敏感 + 空关键词返回空
	if _, err := chats.SendText(ctx, owner, "Hello World"); err != nil {
		t.Fatal(err)
	}
	hits, err = chats.Search(ctx, owner, "hello", 10)
	if err != nil || len(hits) != 1 {
		t.Fatalf("应大小写不敏感命中 1 条:%+v err=%v", hits, err)
	}
	hits, err = chats.Search(ctx, owner, "   ", 10)
	if err != nil || len(hits) != 0 {
		t.Fatalf("空关键词应返回空:%+v err=%v", hits, err)
	}
}

// after 游标:取比锚点更新的消息,从锚点往后数 limit 条,升序。
func TestChatListAfter(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_after")
	chats, _ := newChats(pool)

	var ids []uuid.UUID
	for _, body := range []string{"a", "b", "c", "d", "e"} {
		m, err := chats.SendText(ctx, owner, body)
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, m.ID)
	}
	// 第 2 条之后 → c d e,升序
	got, err := chats.ListAfter(ctx, owner, ids[1], 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 || got[0].ID != ids[2] || got[2].ID != ids[4] {
		t.Fatalf("after 第 2 条应得 c d e,got %+v", got)
	}
	// limit 从锚点往后数:第 2 条之后取 2 → c d
	got, err = chats.ListAfter(ctx, owner, ids[1], 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].Body != "c" || got[1].Body != "d" {
		t.Fatalf("limit=2 应得 c d,got %+v", got)
	}
	// 最后一条之后 → 空
	got, err = chats.ListAfter(ctx, owner, ids[4], 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 {
		t.Fatalf("最后一条之后应为空,got %+v", got)
	}
	// 与 before 对称:第 4 条之前取 2 → b c
	got, err = chats.ListRecent(ctx, owner, &ids[3], 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].Body != "b" || got[1].Body != "c" {
		t.Fatalf("before 第 4 条 limit=2 应得 b c,got %+v", got)
	}
}

// 来源标记:context 里带 WithChatSource 的写入按该来源落库,文本与文件消息都生效。
func TestChatSourceFromContext(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_source")
	chats, nodes := newChats(pool)

	mcpCtx := service.WithChatSource(ctx, service.ChatSourceMCP)
	text, err := chats.SendText(mcpCtx, owner, "来自 agent")
	if err != nil {
		t.Fatal(err)
	}
	if text.Source != service.ChatSourceMCP {
		t.Fatalf("文本来源应为 mcp,got %q", text.Source)
	}
	f, err := nodes.CreateFolder(ctx, owner, nil, "附件")
	if err != nil {
		t.Fatal(err)
	}
	file, err := chats.SendNode(service.WithChatSource(ctx, service.ChatSourceWebDAV), owner, f.ID)
	if err != nil {
		t.Fatal(err)
	}
	if file.Source != service.ChatSourceWebDAV {
		t.Fatalf("文件来源应为 webdav,got %q", file.Source)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 2 || msgs[0].Source != service.ChatSourceMCP || msgs[1].Source != service.ChatSourceWebDAV {
		t.Fatalf("回读应带来源:%+v", msgs)
	}
}

// 秒传路径(linkBlob):同内容第二次上传到传输助手文件夹 → 自动成消息。
// 上传到别的文件夹 → 不成消息。
func TestChatAutoMessageOnInstantUpload(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_auto_instant")
	chats, nodes := newChats(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, 48*time.Hour)

	chatFolder, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	other, err := nodes.CreateFolder(ctx, owner, nil, "普通文件夹")
	if err != nil {
		t.Fatal(err)
	}

	data := []byte("auto chat message via instant upload")
	sum := sha256.Sum256(data)
	sha := hex.EncodeToString(sum[:])

	// 第一次:分片上传到普通文件夹(不应成消息)
	init, err := uploads.Init(ctx, owner, &other.ID, "a.txt", sha, int64(len(data)))
	if err != nil || init.Session == nil {
		t.Fatalf("init: %+v %v", init, err)
	}
	for _, url := range init.Session.PartURLs {
		httpPut(t, url, data)
	}
	if _, err := completeBrowser(t, uploads, owner, init.Session.Session.ID); err != nil {
		t.Fatal(err)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 0 {
		t.Fatalf("传到普通文件夹不应成消息,got %+v", msgs)
	}

	// 第二次:同内容秒传到传输助手文件夹 → 自动落一条文件消息
	init2, err := uploads.Init(ctx, owner, &chatFolder.ID, "b.txt", sha, int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	if !init2.Instant || init2.Node == nil {
		t.Fatalf("同内容应秒传:%+v", init2)
	}
	msgs, err = chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].NodeID == nil || *msgs[0].NodeID != init2.Node.ID {
		t.Fatalf("秒传到传输助手文件夹应自动成消息:%+v", msgs)
	}
	// 客户端再补发一次同节点 → 幂等,仍 1 条
	if _, err := chats.SendNode(ctx, owner, init2.Node.ID); err != nil {
		t.Fatal(err)
	}
	msgs, _ = chats.ListRecent(ctx, owner, nil, 10)
	if len(msgs) != 1 {
		t.Fatalf("补发同节点应幂等,got %d 条", len(msgs))
	}
}

// 浏览器分片路径(finalizeBrowserUpload):直接传到传输助手文件夹 → 自动成消息。
func TestChatAutoMessageOnBrowserUpload(t *testing.T) {
	pool := setup(t)
	obj := setupS3(t)
	ctx := context.Background()
	owner := registerChatUser(t, pool, "chat_auto_browser")
	chats, nodes := newChats(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, 48*time.Hour)

	chatFolder, err := chats.EnsureFolder(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("auto chat message via browser multipart upload")
	sum := sha256.Sum256(data)
	sha := hex.EncodeToString(sum[:])

	init, err := uploads.Init(ctx, owner, &chatFolder.ID, "c.txt", sha, int64(len(data)))
	if err != nil || init.Session == nil {
		t.Fatalf("init: %+v %v", init, err)
	}
	for _, url := range init.Session.PartURLs {
		httpPut(t, url, data)
	}
	n, err := completeBrowser(t, uploads, owner, init.Session.Session.ID)
	if err != nil {
		t.Fatal(err)
	}
	msgs, err := chats.ListRecent(ctx, owner, nil, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].NodeID == nil || *msgs[0].NodeID != n.ID {
		t.Fatalf("分片上传到传输助手文件夹应自动成消息:%+v", msgs)
	}
}

// refresh 重用检测的宽限期:旋转后 10 秒内旧 token 再来 = 并发标签页,再发一枚;
// 超过宽限期再来 = 泄露,整族吊销。
func TestRefreshReuseGrace(t *testing.T) {
	pool := setup(t)
	ctx := context.Background()
	auth := newAuth(pool)
	res, err := auth.Register(ctx, "grace_user", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	t0 := res.RefreshToken

	// 标签 A 用 t0 刷新 → t1
	r1, err := auth.Refresh(ctx, t0)
	if err != nil {
		t.Fatal(err)
	}
	// 标签 B 紧接着也用 t0 刷新(Set-Cookie 还没落地)→ 宽限,拿到 t2,family 不吊销
	r2, err := auth.Refresh(ctx, t0)
	if err != nil {
		t.Fatalf("宽限期内重用应放行,got %v", err)
	}
	if r2.RefreshToken == r1.RefreshToken || r2.RefreshToken == t0 {
		t.Fatalf("宽限重用应签发新 token")
	}
	// t1、t2 都还能正常旋转
	if _, err := auth.Refresh(ctx, r1.RefreshToken); err != nil {
		t.Fatalf("t1 应仍可用:%v", err)
	}
	r3, err := auth.Refresh(ctx, r2.RefreshToken)
	if err != nil {
		t.Fatalf("t2 应仍可用:%v", err)
	}

	// 把 t0 的 used_at 拨到 20 秒前 → 再次出现就是真重放,整族吊销
	if _, err := pool.Exec(ctx, `UPDATE refresh_tokens SET used_at = now() - interval '20 seconds' WHERE user_id = $1 AND used_at IS NOT NULL`, res.User.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := auth.Refresh(ctx, t0); !errors.Is(err, service.ErrUnauthenticated) {
		t.Fatalf("超出宽限期的重用应 UNAUTHENTICATED,got %v", err)
	}
	if _, err := auth.Refresh(ctx, r3.RefreshToken); !errors.Is(err, service.ErrUnauthenticated) {
		t.Fatalf("整族应已吊销,最新 token 也应失效,got %v", err)
	}
}
