package service

import (
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// keyedLimiter 按 key(IP、用户、分享)分桶的进程内令牌桶。
// 单机假设下的统一限速件:登录/验密/打包共用此实现。
type keyedLimiter struct {
	mu       sync.Mutex
	limiters map[string]*rate.Limiter
	every    time.Duration
	burst    int
}

func newKeyedLimiter(every time.Duration, burst int) *keyedLimiter {
	return &keyedLimiter{limiters: make(map[string]*rate.Limiter), every: every, burst: burst}
}

func (l *keyedLimiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	lim, ok := l.limiters[key]
	if !ok {
		lim = rate.NewLimiter(rate.Every(l.every), l.burst)
		l.limiters[key] = lim
	}
	return lim.Allow()
}

// SetRate 调整参数并清空已有桶,测试注入用。
func (l *keyedLimiter) SetRate(every time.Duration, burst int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.every, l.burst = every, burst
	clear(l.limiters)
}
