package service_test

// 助手的长期记忆(doc/38):每人一份,没有就是空串,整份替换,限长,只认 USER.md。

import (
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

func TestAIMemory(t *testing.T) {
	pool := setup(t)
	ctx := t.Context()
	auth := newAuth(pool)
	alice, err := auth.Register(ctx, "mem_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	bob, err := auth.Register(ctx, "mem_"+uuid.NewString()[:6], "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	a := service.NewAssistant(pool)

	if got, err := a.Memory(ctx, alice.User.ID, service.AIMemoryUser); err != nil || got != "" {
		t.Fatalf("empty memory: %q %v", got, err)
	}
	if err := a.SetMemory(ctx, alice.User.ID, service.AIMemoryUser, "- 报销单放 /报销\n"); err != nil {
		t.Fatal(err)
	}
	if err := a.SetMemory(ctx, alice.User.ID, service.AIMemoryUser, "- 报销单放 /报销,按月分\n"); err != nil {
		t.Fatal(err)
	}
	if got, _ := a.Memory(ctx, alice.User.ID, service.AIMemoryUser); got != "- 报销单放 /报销,按月分\n" {
		t.Fatalf("replace: %q", got)
	}
	// 各人各一份
	if got, _ := a.Memory(ctx, bob.User.ID, service.AIMemoryUser); got != "" {
		t.Fatalf("bob sees alice's memory: %q", got)
	}
	if err := a.SetMemory(ctx, alice.User.ID, "../etc", "x"); err == nil {
		t.Fatal("other names accepted")
	}
	if err := a.SetMemory(ctx, alice.User.ID, service.AIMemoryUser, strings.Repeat("x", 64<<10+1)); err == nil {
		t.Fatal("over 64 KB accepted")
	}
	if err := a.SetMemory(ctx, alice.User.ID, service.AIMemoryUser, ""); err != nil {
		t.Fatal(err)
	}
	if got, _ := a.Memory(ctx, alice.User.ID, service.AIMemoryUser); got != "" {
		t.Fatalf("clear: %q", got)
	}
}
