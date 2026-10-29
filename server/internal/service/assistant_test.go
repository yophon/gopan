package service_test

// 助手(doc/33)的集成测试:模型服务与 key、站点额度、助手的权限、对话记录。真 Postgres。

import (
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func aiCode(err error) string {
	var se *service.Error
	if errors.As(err, &se) {
		return se.Code
	}
	return ""
}

func TestAIProviders(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	auth := newAuth(pool)
	admin := service.NewAdmin(store.New(pool), 1<<30)
	ai := service.NewAI(pool, admin, []byte("test-secret-0123456789abcdef0123"))
	boss, err := auth.Register(ctx, "boss_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE users SET is_admin = true WHERE id = $1`, boss.User.ID); err != nil {
		t.Fatal(err)
	}
	kid, err := auth.Register(ctx, "kid_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	key := "sk-site-secret"
	in := service.AIProviderInput{Name: "DeepSeek", Kind: "openai", BaseURL: "http://127.0.0.1:11434/v1/", Models: []string{"deepseek-chat", " deepseek-chat ", ""}, Key: &key, DailyTokens: 100}

	// 站点提供的要管理员;管理员可以接本机 http
	if _, err := ai.CreateProvider(ctx, kid.User.ID, true, in); aiCode(err) != "FORBIDDEN" {
		t.Fatalf("non-admin site provider: %v", err)
	}
	site, err := ai.CreateProvider(ctx, boss.User.ID, true, in)
	if err != nil {
		t.Fatal(err)
	}
	if site.BaseURL != "http://127.0.0.1:11434/v1" || len(site.Models) != 1 || !site.HasKey || !site.Site() {
		t.Fatalf("site provider: %+v", site)
	}

	// 自己的只能 https,不带每日额度
	own := service.AIProviderInput{Name: "我的", Kind: "anthropic", BaseURL: "http://api.example.com", Models: []string{"m1"}, DailyTokens: 5}
	if _, err := ai.CreateProvider(ctx, kid.User.ID, false, own); aiCode(err) != "INVALID_INPUT" {
		t.Fatalf("http own provider: %v", err)
	}
	ownKey := "sk-kid"
	own.BaseURL, own.Key = "https://api.example.com", &ownKey
	mine, err := ai.CreateProvider(ctx, kid.User.ID, false, own)
	if err != nil {
		t.Fatal(err)
	}
	if mine.DailyTokens != 0 {
		t.Fatalf("own provider daily tokens: %d", mine.DailyTokens)
	}

	// 列表:站点的在前,看不到别人的
	list, err := ai.Providers(ctx, kid.User.ID)
	if err != nil || len(list) != 2 || list[0].ID != site.ID || list[1].ID != mine.ID {
		t.Fatalf("kid providers: %+v %v", list, err)
	}
	if bossList, _ := ai.Providers(ctx, boss.User.ID); len(bossList) != 1 {
		t.Fatalf("boss sees others' providers: %+v", bossList)
	}

	// 直连只给自己的 key;站点的拒绝
	if got, err := ai.ProviderKey(ctx, kid.User.ID, mine.ID); err != nil || got != ownKey {
		t.Fatalf("own key: %q %v", got, err)
	}
	if _, err := ai.ProviderKey(ctx, kid.User.ID, site.ID); aiCode(err) != "FORBIDDEN" {
		t.Fatalf("site key leaked: %v", err)
	}
	if _, err := ai.ProviderKey(ctx, boss.User.ID, mine.ID); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("other's key: %v", err)
	}

	// 转发:拿得到真 key;额度用完 AI_QUOTA
	tg, err := ai.ProxyTarget(ctx, kid.User.ID, site.ID)
	if err != nil || tg.Key != key {
		t.Fatalf("proxy target: %+v %v", tg, err)
	}
	if err := ai.RecordUsage(ctx, kid.User.ID, site.ID, 60, 50); err != nil {
		t.Fatal(err)
	}
	if _, err := ai.ProxyTarget(ctx, kid.User.ID, site.ID); aiCode(err) != "AI_QUOTA" {
		t.Fatalf("quota: %v", err)
	}
	if _, err := ai.ProxyTarget(ctx, boss.User.ID, mine.ID); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("proxy other's provider: %v", err)
	}
	usage, err := ai.UsageByUser(ctx, boss.User.ID)
	if err != nil || usage[kid.User.ID] != 110 {
		t.Fatalf("usage by user: %v %v", usage, err)
	}

	// 改:key 留空不变;别的密钥解不开时报 AI_KEY_BROKEN
	own.Key = nil
	own.Name = "改名"
	if p, err := ai.UpdateProvider(ctx, kid.User.ID, mine.ID, own); err != nil || p.Name != "改名" || !p.HasKey {
		t.Fatalf("update: %+v %v", p, err)
	}
	if got, _ := ai.ProviderKey(ctx, kid.User.ID, mine.ID); got != ownKey {
		t.Fatalf("key changed on update: %q", got)
	}
	other := service.NewAI(pool, admin, []byte("another-secret-0123456789abcdef"))
	if _, err := other.ProviderKey(ctx, kid.User.ID, mine.ID); aiCode(err) != "AI_KEY_BROKEN" {
		t.Fatalf("wrong secret: %v", err)
	}
	if err := ai.DeleteProvider(ctx, boss.User.ID, mine.ID); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("delete other's: %v", err)
	}
	if err := ai.DeleteProvider(ctx, kid.User.ID, mine.ID); err != nil {
		t.Fatal(err)
	}
}

func TestAssistantAccess(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	auth := newAuth(pool)
	res, err := auth.Register(ctx, "as_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	user := res.User.ID
	a := service.NewAssistant(pool)

	acc, err := a.Access(ctx, user)
	if err != nil || acc.Disabled || acc.Policy["files:read"] != "allow" || acc.Policy["files:delete"] != "ask" {
		t.Fatalf("defaults: %+v %v", acc, err)
	}
	if _, err := a.SetAccess(ctx, user, false, map[string]string{"admin:read": "allow"}); aiCode(err) != "INVALID_INPUT" {
		t.Fatalf("unknown group: %v", err)
	}
	if _, err := a.SetAccess(ctx, user, false, map[string]string{"files:delete": "deny", "shares:write": "allow"}); err != nil {
		t.Fatal(err)
	}
	conv, turn := uuid.New(), uuid.New()
	p, err := a.Principal(ctx, user, "u", conv, turn)
	if err != nil {
		t.Fatal(err)
	}
	if p.HasScope("files:delete") || !p.HasScope("files:write") || !p.HasScope("files:download") || !p.HasScope("shares:write") || p.HasScope("admin:read") {
		t.Fatalf("scopes: %v", p.Scopes)
	}
	if act := p.Actor(); act.Kind != service.ActorAssistant || act.Ref != conv.String() || act.Label != "助手" {
		t.Fatalf("actor: %+v", act)
	}
	// 没给的组保持原样
	acc, err = a.SetAccess(ctx, user, true, nil)
	if err != nil || !acc.Disabled || acc.Policy["files:delete"] != "deny" {
		t.Fatalf("disable: %+v %v", acc, err)
	}
	if _, err := a.Principal(ctx, user, "u", conv, turn); aiCode(err) != "FORBIDDEN" {
		t.Fatalf("disabled principal: %v", err)
	}
}

func TestAIChats(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	auth := newAuth(pool)
	res, err := auth.Register(ctx, "ch_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	user := res.User.ID
	stranger, err := auth.Register(ctx, "st_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	c := service.NewAIChats(pool)
	bogus := uuid.New()
	conv, err := c.Create(ctx, user, "  把发票\n挑出来  ", &bogus, "deepseek-chat")
	if err != nil {
		t.Fatal(err)
	}
	if conv.Title != "把发票 挑出来" || conv.ProviderID != nil || conv.Entries != 0 {
		t.Fatalf("create: %+v", conv)
	}
	if _, err := c.Append(ctx, user, conv.ID, 0, []string{`{"type":"user","text":"hi"}`, `{"type":"assistant"}`}); err != nil {
		t.Fatal(err)
	}
	// 别的设备拿着旧的条数来写:冲突
	if _, err := c.Append(ctx, user, conv.ID, 1, []string{`{"x":1}`}); aiCode(err) != "CONFLICT" {
		t.Fatalf("stale append: %v", err)
	}
	if _, err := c.Append(ctx, user, conv.ID, 2, []string{`not json`}); aiCode(err) != "INVALID_INPUT" {
		t.Fatalf("bad json: %v", err)
	}
	got, err := c.Append(ctx, user, conv.ID, 2, []string{`{"type":"tool"}`})
	if err != nil || got.Entries != 3 {
		t.Fatalf("append: %+v %v", got, err)
	}
	entries, err := c.Entries(ctx, user, conv.ID, 1)
	if err != nil || len(entries) != 2 || entries[1] != `{"type": "tool"}` {
		t.Fatalf("entries: %q %v", entries, err)
	}
	if _, err := c.Entries(ctx, stranger.User.ID, conv.ID, 0); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("stranger reads: %v", err)
	}
	title := "报销"
	if v, err := c.Update(ctx, user, conv.ID, &title, nil, nil); err != nil || v.Title != "报销" || v.Model != "deepseek-chat" {
		t.Fatalf("update: %+v %v", v, err)
	}
	list, err := c.List(ctx, user, nil, 10)
	if err != nil || len(list) != 1 {
		t.Fatalf("list: %+v %v", list, err)
	}
	if err := c.Delete(ctx, stranger.User.ID, conv.ID); aiCode(err) != "NOT_FOUND" {
		t.Fatalf("stranger deletes: %v", err)
	}
	if err := c.Delete(ctx, user, conv.ID); err != nil {
		t.Fatal(err)
	}
}
