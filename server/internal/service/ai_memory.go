package service

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// 助手的长期记忆(doc/38):tau 的记忆扩展按「文件名」读写,这里按用户存。
// 这一版只有 USER.md;一份最多 64 KB(tau 只把前 200 行或 25 KB 放进提示词,写多了它自己提醒整理)。

const (
	AIMemoryUser     = "USER.md"
	maxAIMemoryBytes = 64 << 10
)

func checkMemoryName(name string) error {
	if name != AIMemoryUser {
		return errf("INVALID_INPUT", "只有 USER.md 这一份记忆")
	}
	return nil
}

// Memory 读一份记忆;没有就是空串。
func (s *Assistant) Memory(ctx context.Context, user uuid.UUID, name string) (string, error) {
	if err := checkMemoryName(name); err != nil {
		return "", err
	}
	var content string
	err := s.pool.QueryRow(ctx, `SELECT content FROM ai_memory WHERE user_id = $1 AND name = $2`, user, name).Scan(&content)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	return content, err
}

// SetMemory 整份替换一份记忆;空串就是清空。
func (s *Assistant) SetMemory(ctx context.Context, user uuid.UUID, name, content string) error {
	if err := checkMemoryName(name); err != nil {
		return err
	}
	if len(content) > maxAIMemoryBytes {
		return errf("INVALID_INPUT", "记忆太长了(最多 64 KB),删掉些过时的再存")
	}
	_, err := s.pool.Exec(ctx, `INSERT INTO ai_memory (user_id, name, content) VALUES ($1, $2, $3)
		ON CONFLICT (user_id, name) DO UPDATE SET content = EXCLUDED.content, updated_at = now()`, user, name, content)
	return err
}
