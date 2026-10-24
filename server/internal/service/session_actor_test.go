package service

import (
	"context"
	"testing"
)

// 上传会话里存的来源:worker 定稿时要取回发起者和传输助手消息的来源(桌面端发的文件记成「电脑」)
func TestSessionActorKeepsChatSource(t *testing.T) {
	ctx := WithChatSource(WithActor(context.Background(), Actor{Kind: "session", Ref: "r1", Label: "Windows 桌面端"}), ChatSourceDesktop)
	got := actorFromJSON(context.Background(), actorJSON(ctx))
	if a := ActorFrom(got); a.Kind != "session" || a.Ref != "r1" || a.Label != "Windows 桌面端" {
		t.Fatalf("actor = %+v", a)
	}
	if s := chatSourceFrom(got); s != ChatSourceDesktop {
		t.Fatalf("chat source = %q", s)
	}

	// 旧会话只存了发起者:来源回到默认
	old := actorFromJSON(context.Background(), []byte(`{"k":"session","r":"x"}`))
	if a := ActorFrom(old); a.Kind != "session" || a.Ref != "x" {
		t.Fatalf("old actor = %+v", a)
	}
	if s := chatSourceFrom(old); s != ChatSourceWeb {
		t.Fatalf("old chat source = %q", s)
	}
	if s := chatSourceFrom(actorFromJSON(context.Background(), nil)); s != ChatSourceWeb {
		t.Fatalf("empty chat source = %q", s)
	}
}
