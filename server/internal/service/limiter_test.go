package service

import (
	"testing"
	"time"
)

func TestKeyedLimiter(t *testing.T) {
	l := newKeyedLimiter(time.Hour, 2)
	// 突发 2 之内放行,之后拒绝;不同 key 互不影响
	if !l.Allow("a") || !l.Allow("a") {
		t.Fatal("突发额度内应放行")
	}
	if l.Allow("a") {
		t.Fatal("超突发应拒绝")
	}
	if !l.Allow("b") {
		t.Fatal("不同 key 应有独立额度")
	}
	// SetRate 清空重来
	l.SetRate(time.Hour, 1)
	if !l.Allow("a") {
		t.Fatal("SetRate 后应重置额度")
	}
	if l.Allow("a") {
		t.Fatal("新突发 1 用完应拒绝")
	}
}
