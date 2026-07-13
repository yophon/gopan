package graph

import "github.com/yophon/gopan/server/internal/store"

func gqlAppPassword(r store.AppPassword) *AppPassword {
	out := &AppPassword{ID: r.ID.String(), Name: r.Name, CreatedAt: r.CreatedAt.Time}
	if r.LastUsedAt.Valid {
		t := r.LastUsedAt.Time
		out.LastUsedAt = &t
	}
	return out
}
