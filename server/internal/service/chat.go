package service

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/store"
)

// Chats 文件传输助手(我的设备):单用户单会话的 append-only 消息流。
//
// 与全仓库其他 service 一样,这里只做业务编排与事务,纯 SQL 在 sqlc 生成层。
// 会话没有"收发方"概念——浏览器与 MCP Agent 写入的是同一个 owner 的会话,
// 前端也不区分身份(都是"我")。
type Chats struct {
	pool  *pgxpool.Pool
	q     *store.Queries
	nodes *Nodes
}

func NewChats(pool *pgxpool.Pool, nodes *Nodes) *Chats {
	return &Chats{pool: pool, q: store.New(pool), nodes: nodes}
}

// ChatMessage 是对外消息视图(不带 pgtype 细节)。
type ChatMessage struct {
	ID        uuid.UUID
	OwnerID   uuid.UUID
	Body      string
	NodeID    *uuid.UUID
	CreatedAt time.Time
}

const (
	chatFolderName   = "我的设备"
	maxChatBodyBytes = 4000
	maxChatListLimit = 200
)

func toChatMessage(row store.ChatMessage) ChatMessage {
	return ChatMessage{
		ID:        row.ID,
		OwnerID:   row.OwnerID,
		Body:      row.Body,
		NodeID:    row.NodeID,
		CreatedAt: row.CreatedAt.Time,
	}
}

// ListRecent 返回最近的 limit 条消息,升序(旧→新,UI 直接渲染)。
// lastID 为"当前已见的最大消息 id"(uuid v7 时间有序)时只取比它更旧的存量消息;
// 传 nil 则取全量最近消息。limit 超出上限截断。
func (s *Chats) ListRecent(ctx context.Context, owner uuid.UUID, last *uuid.UUID, limit int) ([]ChatMessage, error) {
	if limit <= 0 || limit > maxChatListLimit {
		limit = maxChatListLimit
	}
	var (
		rows []store.ChatMessage
		err  error
	)
	if last == nil {
		rows, err = s.q.ListChatMessages(ctx, store.ListChatMessagesParams{OwnerID: owner, Limit: int32(limit)})
	} else {
		rows, err = s.q.ListChatMessagesBefore(ctx, store.ListChatMessagesBeforeParams{OwnerID: owner, ID: *last, Limit: int32(limit)})
	}
	if err != nil {
		return nil, err
	}
	// 表内是 id DESC(新→旧),翻转为旧→新后给前端。
	out := make([]ChatMessage, 0, len(rows))
	for i := len(rows) - 1; i >= 0; i-- {
		out = append(out, toChatMessage(rows[i]))
	}
	return out, nil
}

// SendText 发纯文本消息。空 body(trim 后)或超长返回 INVALID_INPUT。
func (s *Chats) SendText(ctx context.Context, ownerID uuid.UUID, body string) (ChatMessage, error) {
	body = strings.TrimSpace(body)
	if body == "" {
		return ChatMessage{}, errf("INVALID_INPUT", "消息内容不能为空")
	}
	if len(body) > maxChatBodyBytes {
		return ChatMessage{}, errf("INVALID_INPUT", "消息最长 %d 字", maxChatBodyBytes)
	}
	row, err := s.q.InsertChatMessage(ctx, store.InsertChatMessageParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: ownerID, Body: body,
	})
	if err != nil {
		return ChatMessage{}, err
	}
	return toChatMessage(row), nil
}

// SendNode 发文件消息。单事务:先校验 node 属于当前用户且未被删除(active),
// 再插消息。node 缺失/非属主/已删除一律 ErrNotFound——与 Nodes.Get 的防探测语义一致。
func (s *Chats) SendNode(ctx context.Context, ownerID, nodeID uuid.UUID) (ChatMessage, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return ChatMessage{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	qtx := s.q.WithTx(tx)

	if _, err := qtx.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: nodeID, OwnerID: ownerID}); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ChatMessage{}, ErrNotFound
		}
		return ChatMessage{}, err
	}

	row, err := qtx.InsertChatMessage(ctx, store.InsertChatMessageParams{
		ID:      uuid.Must(uuid.NewV7()),
		OwnerID: ownerID,
		NodeID:  &nodeID,
	})
	if err != nil {
		return ChatMessage{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return ChatMessage{}, err
	}
	return toChatMessage(row), nil
}

// ChatFolder 返回读取到的"我的设备"文件夹;未创建(指针 NULL)返回 (nil,nil),不触发创建。
func (s *Chats) ChatFolder(ctx context.Context, ownerID uuid.UUID) (*store.Node, error) {
	id, err := s.q.ReadUserChatFolderID(ctx, ownerID)
	if err != nil {
		return nil, err
	}
	if id == nil {
		return nil, nil
	}
	n, err := s.nodes.Get(ctx, ownerID, *id)
	if err != nil {
		return nil, err
	}
	return &n, nil
}

// EnsureFolder 幂等返回"我的设备"文件夹。指针失效(删除/回收/purge)则重建新文件夹。
// advisory lock 串行,多端同时首传不会对撞建两个。
func (s *Chats) EnsureFolder(ctx context.Context, ownerID uuid.UUID) (store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	// 锁到事务结束;多端同时首传不会对撞建两个
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "chat-folder:"+ownerID.String()); err != nil {
		return store.Node{}, err
	}
	qtx := s.q.WithTx(tx)

	id, err := qtx.ReadUserChatFolderID(ctx, ownerID)
	if err != nil {
		return store.Node{}, err
	}
	if id != nil {
		// 指针还指向一个活跃文件夹 → 直接复用
		if n, err := s.nodes.Get(ctx, ownerID, *id); err == nil && n.Kind == "folder" && !n.DeletedAt.Valid {
			return n, tx.Commit(ctx)
		}
		// 指针失效(被删/purge)→ 掉下去重建
	}

	n, err := s.nodes.CreateFolder(ctx, ownerID, nil, chatFolderName)
	if err != nil {
		return store.Node{}, err
	}
	if err := qtx.SetUserChatFolderID(ctx, store.SetUserChatFolderIDParams{ID: ownerID, ChatFolderID: &n.ID}); err != nil {
		return store.Node{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return store.Node{}, err
	}
	return n, nil
}