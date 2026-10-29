package service

// 自动刷新(doc/32 二):网盘里的改动随所在事务 pg_notify(提交才发出,回滚就不发),
// 一条专用连接 LISTEN,按用户分发给连着的客户端(网页、桌面端、App 在前台时)。
// 客户端收到的只是「哪儿变了」,自己再去拉;漏收的靠重连时的 resync 与各页面原有的刷新兜底。

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/store"
)

const changesChannel = "gopan_changes"

// 一次改动涉及的文件夹超过这么多就不逐个列了,客户端当成「都可能变了」
const maxChangeFolders = 50

// Change 推给客户端的一条。Topic:files(文件夹里的东西变了)/ chat(传输助手)/ recent(「最近」)/
// resync(服务端重连过,可能漏了,全部重拉)/ assistant(服务器上跑的助手,doc/34)。
type Change struct {
	Topic string `json:"t"`
	// files:变了的文件夹,"" = 根目录;All = 太多了没列
	Folders []string `json:"f,omitempty"`
	All     bool     `json:"all,omitempty"`
	// files:回收站也变了(删除、还原、彻底删除)
	Trash bool `json:"trash,omitempty"`
	// assistant:哪个对话、运行时交出来的事件(原样的 JSON)
	Conversation string          `json:"c,omitempty"`
	Event        json.RawMessage `json:"e,omitempty"`
}

type changeMsg struct {
	User uuid.UUID `json:"u"`
	Change
}

// notifyChange 在 q 所在的事务里登记一条改动。
func notifyChange(ctx context.Context, q *store.Queries, owner uuid.UUID, c Change) error {
	b, err := json.Marshal(changeMsg{User: owner, Change: c})
	if err != nil {
		return err
	}
	return q.NotifyChange(ctx, string(b))
}

// filesChange 由动态里的一批改动算出变了哪些文件夹。
func filesChange(items []actItem) (Change, bool) {
	c := Change{Topic: "files"}
	seen := map[string]bool{}
	add := func(p *uuid.UUID) {
		k := ""
		if p != nil {
			k = p.String()
		}
		if !seen[k] {
			seen[k] = true
			c.Folders = append(c.Folders, k)
		}
	}
	for _, it := range items {
		add(it.Parent)
		for _, pl := range []*actPlace{it.Detail.From, it.Detail.To} {
			if pl != nil {
				add(pl.Parent)
			}
		}
		if it.Kind == "folder" && it.NodeID != uuid.Nil {
			id := it.NodeID // 文件夹本身改名、删除:正开着它的人也要知道
			add(&id)
		}
		switch it.Action {
		case OpDelete, OpRestore, OpPurge:
			c.Trash = true
		}
	}
	if len(c.Folders) == 0 {
		return c, false
	}
	if len(c.Folders) > maxChangeFolders {
		c.Folders, c.All = nil, true
	}
	return c, true
}

// ChangeHub 一条 LISTEN 连接,按用户分发。
type ChangeHub struct {
	pool *pgxpool.Pool

	mu     sync.Mutex
	subs   map[uuid.UUID]map[chan Change]struct{}
	closed bool
}

func NewChangeHub(pool *pgxpool.Pool) *ChangeHub {
	return &ChangeHub{pool: pool, subs: map[uuid.UUID]map[chan Change]struct{}{}}
}

// maxSubsPerUser 每人同时连着的客户端上限(网页标签页、桌面端、手机加起来)
const maxSubsPerUser = 16

// Subscribe 订阅一个用户的改动。ok=false:连接数到上限或正在关机。
// 通道被关掉 = 正在关机,调用方结束连接即可。
func (h *ChangeHub) Subscribe(user uuid.UUID) (ch <-chan Change, cancel func(), ok bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed || len(h.subs[user]) >= maxSubsPerUser {
		return nil, nil, false
	}
	c := make(chan Change, 32)
	if h.subs[user] == nil {
		h.subs[user] = map[chan Change]struct{}{}
	}
	h.subs[user][c] = struct{}{}
	return c, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		if _, live := h.subs[user][c]; live {
			delete(h.subs[user], c)
			close(c)
			if len(h.subs[user]) == 0 {
				delete(h.subs, user)
			}
		}
	}, true
}

// Close 关机:关掉所有订阅,推送连接随之结束(否则 http.Server.Shutdown 要等它们)。
func (h *ChangeHub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closed = true
	for user, set := range h.subs {
		for c := range set {
			close(c)
		}
		delete(h.subs, user)
	}
}

// Publish 进程内直接推给这个用户连着的设备(不经 Postgres:服务器上跑的助手就在这个进程里,事件也大)。
func (h *ChangeHub) Publish(user uuid.UUID, c Change) { h.publish(user, c) }

func (h *ChangeHub) publish(user uuid.UUID, c Change) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[user] {
		select {
		case ch <- c:
		default: // 客户端读得慢:丢掉这条,它下次拉的时候照样拿到最新的
		}
	}
}

func (h *ChangeHub) broadcast(c Change) {
	h.mu.Lock()
	users := make([]uuid.UUID, 0, len(h.subs))
	for u := range h.subs {
		users = append(users, u)
	}
	h.mu.Unlock()
	for _, u := range users {
		h.publish(u, c)
	}
}

// Run 一直 LISTEN 到 ctx 结束;连接断了退避重连,重连后让所有客户端重拉一次(断开期间的通知收不到)。
func (h *ChangeHub) Run(ctx context.Context) {
	backoff := time.Second
	first := true
	for ctx.Err() == nil {
		err := h.listen(ctx, func() {
			backoff = time.Second
			if !first {
				h.broadcast(Change{Topic: "resync"})
			}
			first = false
		})
		if ctx.Err() != nil {
			return
		}
		slog.Warn("changes listener stopped, reconnecting", "err", err, "in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 30*time.Second)
	}
}

func (h *ChangeHub) listen(ctx context.Context, ready func()) error {
	conn, err := h.pool.Acquire(ctx)
	if err != nil {
		return err
	}
	// LISTEN 的连接不还回池里:还回去别人拿到会收到通知,用完直接关掉
	pc := conn.Hijack()
	defer pc.Close(context.WithoutCancel(ctx))
	if _, err := pc.Exec(ctx, "LISTEN "+changesChannel); err != nil {
		return err
	}
	ready()
	for {
		n, err := pc.WaitForNotification(ctx)
		if err != nil {
			return err
		}
		var m changeMsg
		if err := json.Unmarshal([]byte(n.Payload), &m); err != nil || m.User == uuid.Nil {
			continue
		}
		h.publish(m.User, m.Change)
	}
}
