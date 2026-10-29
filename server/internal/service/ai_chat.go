package service

// 助手的对话记录(doc/33 三):存在 gopan 上,换设备接着聊,设备上不留。
//
// tau 的会话是一条条追加的事件,客户端按序号原样追加进 ai_entries;fromSeq 对不上就报冲突,两台设备同时写
// 一个对话时后到的那台重新读一遍再写,不会写乱。条目内容服务端不解析,只限大小。

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	maxAIEntries       = 20000
	maxAIEntryBytes    = 1 << 20
	maxAIAppendEntries = 500
	maxAITitle         = 100
)

var errAIConflict = &Error{Code: "CONFLICT", Message: "这个对话在别处有了新内容,请重新打开"}

type AIConversation struct {
	ID         uuid.UUID
	Title      string
	ProviderID *uuid.UUID
	Model      string
	Entries    int32
	// Runner 在哪跑:device(客户端)/ server(服务器,doc/34)
	Runner    string
	CreatedAt time.Time
	UpdatedAt time.Time
}

type AIChats struct {
	pool *pgxpool.Pool
}

func NewAIChats(pool *pgxpool.Pool) *AIChats { return &AIChats{pool: pool} }

const aiConvCols = `id, title, provider_id, model, entries, runner, created_at, updated_at`

func scanAIConv(row pgx.Row) (AIConversation, error) {
	var c AIConversation
	err := row.Scan(&c.ID, &c.Title, &c.ProviderID, &c.Model, &c.Entries, &c.Runner, &c.CreatedAt, &c.UpdatedAt)
	return c, err
}

func cleanAITitle(t string) string {
	t = strings.Join(strings.Fields(t), " ")
	if r := []rune(t); len(r) > maxAITitle {
		t = string(r[:maxAITitle])
	}
	return t
}

// List 最近的在前;before 是上一页最后一个的 updated_at。
func (c *AIChats) List(ctx context.Context, user uuid.UUID, before *time.Time, limit int) ([]AIConversation, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := c.pool.Query(ctx, `SELECT `+aiConvCols+` FROM ai_conversations
		WHERE user_id = $1 AND ($2::timestamptz IS NULL OR updated_at < $2) ORDER BY updated_at DESC LIMIT $3`, user, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AIConversation
	for rows.Next() {
		v, err := scanAIConv(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (c *AIChats) Get(ctx context.Context, user, id uuid.UUID) (AIConversation, error) {
	v, err := scanAIConv(c.pool.QueryRow(ctx, `SELECT `+aiConvCols+` FROM ai_conversations WHERE id = $1 AND user_id = $2`, id, user))
	if errors.Is(err, pgx.ErrNoRows) {
		return v, ErrNotFound
	}
	return v, err
}

// Entries 从 fromSeq 起的条目(原样的 JSON 文本)。
func (c *AIChats) Entries(ctx context.Context, user, id uuid.UUID, fromSeq int) ([]string, error) {
	if _, err := c.Get(ctx, user, id); err != nil {
		return nil, err
	}
	rows, err := c.pool.Query(ctx, `SELECT entry::text FROM ai_entries WHERE conversation_id = $1 AND seq >= $2 ORDER BY seq`, id, fromSeq)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (c *AIChats) Create(ctx context.Context, user uuid.UUID, title string, provider *uuid.UUID, model string) (AIConversation, error) {
	provider, err := c.visibleProvider(ctx, user, provider)
	if err != nil {
		return AIConversation{}, err
	}
	return scanAIConv(c.pool.QueryRow(ctx, `INSERT INTO ai_conversations (id, user_id, title, provider_id, model)
		VALUES ($1, $2, $3, $4, $5) RETURNING `+aiConvCols, uuid.Must(uuid.NewV7()), user, cleanAITitle(title), provider, strings.TrimSpace(model)))
}

// visibleProvider 只记这个用户看得到的服务(自己的或站点的),别的当没给。
func (c *AIChats) visibleProvider(ctx context.Context, user uuid.UUID, provider *uuid.UUID) (*uuid.UUID, error) {
	if provider == nil {
		return nil, nil
	}
	var ok bool
	if err := c.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM ai_providers WHERE id = $1 AND (owner_id = $2 OR owner_id IS NULL))`, *provider, user).Scan(&ok); err != nil {
		return nil, err
	}
	if !ok {
		return nil, nil
	}
	return provider, nil
}

// Append 追加条目:fromSeq 必须等于现有条数。
func (c *AIChats) Append(ctx context.Context, user, id uuid.UUID, fromSeq int, entries []string) (AIConversation, error) {
	if len(entries) == 0 {
		return c.Get(ctx, user, id)
	}
	if len(entries) > maxAIAppendEntries {
		return AIConversation{}, errf("INVALID_INPUT", "一次最多追加 500 条")
	}
	for _, e := range entries {
		if len(e) > maxAIEntryBytes {
			return AIConversation{}, errf("INVALID_INPUT", "单条记录太大")
		}
		if !json.Valid([]byte(e)) {
			return AIConversation{}, errf("INVALID_INPUT", "记录不是合法的 JSON")
		}
	}
	tx, err := c.pool.Begin(ctx)
	if err != nil {
		return AIConversation{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var n int
	err = tx.QueryRow(ctx, `SELECT entries FROM ai_conversations WHERE id = $1 AND user_id = $2 FOR UPDATE`, id, user).Scan(&n)
	if errors.Is(err, pgx.ErrNoRows) {
		return AIConversation{}, ErrNotFound
	}
	if err != nil {
		return AIConversation{}, err
	}
	if fromSeq != n {
		return AIConversation{}, errAIConflict
	}
	if n+len(entries) > maxAIEntries {
		return AIConversation{}, errf("INVALID_INPUT", "这个对话太长了,开个新的吧")
	}
	batch := &pgx.Batch{}
	for i, e := range entries {
		batch.Queue(`INSERT INTO ai_entries (conversation_id, seq, entry) VALUES ($1, $2, $3::jsonb)`, id, n+i, e)
	}
	if err := tx.SendBatch(ctx, batch).Close(); err != nil {
		return AIConversation{}, err
	}
	v, err := scanAIConv(tx.QueryRow(ctx, `UPDATE ai_conversations SET entries = $2, updated_at = now() WHERE id = $1 RETURNING `+aiConvCols, id, n+len(entries)))
	if err != nil {
		return v, err
	}
	return v, tx.Commit(ctx)
}

// Update 改标题 / 换服务和模型;nil 的不变。
func (c *AIChats) Update(ctx context.Context, user, id uuid.UUID, title *string, provider *uuid.UUID, model *string) (AIConversation, error) {
	cur, err := c.Get(ctx, user, id)
	if err != nil {
		return cur, err
	}
	if title != nil {
		cur.Title = cleanAITitle(*title)
	}
	if provider, err = c.visibleProvider(ctx, user, provider); err != nil {
		return cur, err
	}
	if provider != nil {
		cur.ProviderID = provider
	}
	if model != nil {
		cur.Model = strings.TrimSpace(*model)
	}
	return scanAIConv(c.pool.QueryRow(ctx, `UPDATE ai_conversations SET title = $3, provider_id = $4, model = $5, updated_at = now()
		WHERE id = $1 AND user_id = $2 RETURNING `+aiConvCols, id, user, cur.Title, cur.ProviderID, cur.Model))
}

func (c *AIChats) Delete(ctx context.Context, user, id uuid.UUID) error {
	tag, err := c.pool.Exec(ctx, `DELETE FROM ai_conversations WHERE id = $1 AND user_id = $2`, id, user)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
