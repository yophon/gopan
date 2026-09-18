package store

import (
	"context"
	"github.com/google/uuid"
)

// Used even when the provider is disabled, so ID sessions cannot become legacy sessions.
func (q *Queries) HasIdentitySession(ctx context.Context, family uuid.UUID) (bool, error) {
	var linked bool
	err := q.db.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM id_sessions WHERE family_id=$1)", family).Scan(&linked)
	return linked, err
}
