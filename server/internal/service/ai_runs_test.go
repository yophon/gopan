package service_test

// 助手在服务器上运行(doc/34)的数据库部分:排队、同一个对话只能一轮、停掉排队中的、切换在哪跑。
// 真跑一轮(qjs 进程 + 真 HTTP)见 airun 包的测试与发版前的本地核对。

import (
	"os"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestAIRunsQueue(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	auth := newAuth(pool)
	res, err := auth.Register(ctx, "run_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	user := res.User.ID
	ai := service.NewAI(pool, service.NewAdmin(store.New(pool), 1<<30), []byte("test-secret-0123456789abcdef0123"))
	key := "sk"
	p, err := ai.CreateProvider(ctx, user, false, service.AIProviderInput{Name: "DS", Kind: "openai-responses", BaseURL: "https://api.example.com/v1", Models: []string{"m1"}, Key: &key})
	if err != nil {
		t.Fatal(err)
	}
	chats := service.NewAIChats(pool)

	// 没装 qjs:直接说清楚
	none := service.NewAIRuns(pool, chats, auth, nil, nil, service.AIRunsConfig{QJS: "/nope/qjs"})
	if _, _, err := none.Start(ctx, user, nil, "hi", "", p.ID, ""); aiCode(err) != "AI_RUN_UNAVAILABLE" {
		t.Fatalf("without qjs: %v", err)
	}

	// 有 qjs(任何存在的文件即可,这里不起调度,只看排队)
	exe, _ := os.Executable()
	runs := service.NewAIRuns(pool, chats, auth, nil, nil, service.AIRunsConfig{QJS: exe})
	run, conv, err := runs.Start(ctx, user, nil, "  把发票挑出来\n第二行", "用户正在看:/", p.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if run.Status != service.RunQueued || conv.Title != "把发票挑出来" {
		t.Fatalf("start: %+v %+v", run, conv)
	}
	got, err := chats.Get(ctx, user, conv.ID)
	if err != nil || got.Runner != "server" || got.Model != "m1" {
		t.Fatalf("conversation after start: %+v %v", got, err)
	}
	// 同一个对话同时只能一轮
	if _, _, err := runs.Start(ctx, user, &conv.ID, "再来", "", p.ID, "m1"); aiCode(err) != "AI_RUN_BUSY" {
		t.Fatalf("second run: %v", err)
	}
	st, err := runs.ActiveStatuses(ctx, user)
	if err != nil || st[conv.ID] != service.RunQueued {
		t.Fatalf("active: %v %v", st, err)
	}
	// 别人停不了;自己停掉排队中的
	if err := runs.Stop(ctx, uuid.New(), run.ID); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("stranger stop: %v", err)
	}
	if err := runs.Stop(ctx, user, run.ID); err != nil {
		t.Fatal(err)
	}
	cur, err := runs.Current(ctx, user, conv.ID)
	if err != nil || cur == nil || cur.Status != service.RunStopped || cur.FinishedAt == nil {
		t.Fatalf("current: %+v %v", cur, err)
	}
	// 停了之后可以再发
	if _, _, err := runs.Start(ctx, user, &conv.ID, "再来", "", p.ID, "m1"); err != nil {
		t.Fatalf("after stop: %v", err)
	}
	// 答复没在跑的一轮
	if err := runs.Answer(ctx, user, run.ID, "once"); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("answer finished run: %v", err)
	}
	if err := runs.Answer(ctx, user, run.ID, "maybe"); aiCode(err) != "INVALID_INPUT" {
		t.Fatalf("bad answer: %v", err)
	}
	// 切回本机
	if err := runs.SetRunner(ctx, user, conv.ID, "device"); err != nil {
		t.Fatal(err)
	}
	if err := runs.SetRunner(ctx, user, conv.ID, "cloud"); aiCode(err) != "INVALID_INPUT" {
		t.Fatalf("bad runner: %v", err)
	}
	if got, _ := chats.Get(ctx, user, conv.ID); got.Runner != "device" {
		t.Fatalf("runner: %q", got.Runner)
	}
}

func TestAIRunScope(t *testing.T) {
	id := uuid.New()
	if got, ok := service.AIRunScopeConversation(service.ScopeAIRunPrefix + id.String()); !ok || got != id {
		t.Fatalf("scope: %v %v", got, ok)
	}
	for _, s := range []string{service.ScopeUser, "share:" + id.String(), service.ScopeAIRunPrefix + "x"} {
		if _, ok := service.AIRunScopeConversation(s); ok {
			t.Fatalf("%q parsed as run scope", s)
		}
	}
}
