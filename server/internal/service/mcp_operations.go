package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/yophon/gopan/server/internal/store"
)

// MCPMutation commits the response and all node changes together. A retry after
// an ambiguous connection failure either replays this response or runs once.
func (s *Nodes) MCPMutation(ctx context.Context, owner uuid.UUID, key string, request []byte, preview bool, fn func(*Nodes) (json.RawMessage, error)) (json.RawMessage, error) {
	if len(key) > 128 || (key != "" && strings.TrimSpace(key) == "") {
		return nil, errf("INVALID_INPUT", "idempotency_key 必须为 1–128 字节")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(context.Background())
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, "mcp-write:"+owner.String()); err != nil {
		return nil, err
	}
	sum := sha256.Sum256(request)
	hash := hex.EncodeToString(sum[:])
	if key != "" && !preview {
		var savedHash string
		var saved json.RawMessage
		err = tx.QueryRow(ctx, `SELECT request_hash,response FROM mcp_operations WHERE owner_id=$1 AND key=$2`, owner, key).Scan(&savedHash, &saved)
		if err == nil {
			if savedHash != hash {
				return nil, errf("IDEMPOTENCY_CONFLICT", "此幂等键已用于不同请求")
			}
			return saved, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return nil, err
		}
	}
	result, err := fn(&Nodes{pool: tx, q: store.New(tx)})
	if err != nil {
		return nil, err
	}
	if preview {
		return result, nil
	}
	if key != "" {
		if _, err = tx.Exec(ctx, `INSERT INTO mcp_operations(owner_id,key,request_hash,response) VALUES($1,$2,$3,$4)`, owner, key, hash, []byte(result)); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return result, nil
}

// MCPItem isolates a batch item's failure using a savepoint.
func (s *Nodes) MCPItem(ctx context.Context, fn func(*Nodes) error) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	if err = fn(&Nodes{pool: tx, q: store.New(tx)}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func pathParts(path string) ([]string, error) {
	if !strings.HasPrefix(path, "/") || len(path) > 4096 {
		return nil, errf("INVALID_INPUT", "路径必须以 / 开头且不超过 4096 字节")
	}
	if path == "/" {
		return nil, nil
	}
	parts := strings.Split(strings.TrimSuffix(path, "/"), "/")[1:]
	if len(parts) > 64 {
		return nil, errf("INVALID_INPUT", "路径最多 64 层")
	}
	for _, part := range parts {
		if err := validateName(part); err != nil {
			return nil, err
		}
	}
	return parts, nil
}

// ResolvePath uses exact, case-sensitive names. nil denotes the virtual root.
func (s *Nodes) ResolvePath(ctx context.Context, owner uuid.UUID, path string) (*store.Node, error) {
	return s.ResolvePathAt(ctx, owner, path, nil)
}
func (s *Nodes) ResolvePathAt(ctx context.Context, owner uuid.UUID, path string, root *uuid.UUID) (*store.Node, error) {
	parts, err := pathParts(path)
	if err != nil {
		return nil, err
	}
	parent := root
	var result *store.Node
	if root != nil {
		n, err := s.Get(ctx, owner, *root)
		if err != nil || n.DeletedAt.Valid {
			return nil, ErrNotFound
		}
		result = &n
	}
	for i, name := range parts {
		n, err := s.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: parent, Name: name})
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		if i < len(parts)-1 && n.Kind != "folder" {
			return nil, ErrNotFound
		}
		result = &n
		parent = &n.ID
	}
	return result, nil
}

// EnsurePath must run inside MCPMutation so recursive creation is atomic.
func (s *Nodes) EnsurePath(ctx context.Context, owner uuid.UUID, path string) (*store.Node, error) {
	return s.EnsurePathAt(ctx, owner, path, nil)
}
func (s *Nodes) EnsurePathAt(ctx context.Context, owner uuid.UUID, path string, root *uuid.UUID) (*store.Node, error) {
	parts, err := pathParts(path)
	if err != nil {
		return nil, err
	}
	parent := root
	var result *store.Node
	if root != nil {
		n, err := s.Get(ctx, owner, *root)
		if err != nil || n.DeletedAt.Valid {
			return nil, ErrNotFound
		}
		result = &n
	}
	for _, name := range parts {
		n, err := s.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: parent, Name: name})
		if errors.Is(err, pgx.ErrNoRows) {
			// ON CONFLICT avoids auto-renaming and keeps concurrent mkdir retries safe.
			_, err = s.qDBInsertFolder(ctx, owner, parent, name)
			if err != nil {
				return nil, err
			}
			n, err = s.q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: parent, Name: name})
		}
		if err != nil {
			return nil, err
		}
		if n.Kind != "folder" {
			return nil, errf("NAME_CONFLICT", "路径中存在同名文件")
		}
		result = &n
		parent = &n.ID
	}
	return result, nil
}

func (s *Nodes) qDBInsertFolder(ctx context.Context, owner uuid.UUID, parent *uuid.UUID, name string) (bool, error) {
	// Nodes inside MCPMutation always uses a transaction.
	tx, ok := s.pool.(pgx.Tx)
	if !ok {
		return false, errf("INTERNAL", "递归创建需要事务")
	}
	tag, err := tx.Exec(ctx, `INSERT INTO nodes(id,owner_id,parent_id,name,kind) VALUES($1,$2,$3,$4,'folder') ON CONFLICT DO NOTHING`, uuid.Must(uuid.NewV7()), owner, parent, name)
	return tag.RowsAffected() > 0, err
}
