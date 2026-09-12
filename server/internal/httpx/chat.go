package httpx

import (
  "encoding/json"
  "net/http"
  "strings"
  "github.com/google/uuid"
  "github.com/jackc/pgx/v5/pgxpool"
)

type chatMessage struct { ID uuid.UUID `json:"id"`; Body string `json:"body"`; CreatedAt string `json:"createdAt"` }
type chatInput struct { Body string `json:"body"` }

func ChatHandler(pool *pgxpool.Pool) http.Handler {
  return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    ident, err := UserFrom(r.Context()); if err != nil { http.Error(w, "unauthorized", http.StatusUnauthorized); return }
    w.Header().Set("Content-Type", "application/json")
    switch r.Method {
    case http.MethodGet:
      rows, err := pool.Query(r.Context(), `SELECT id, body, created_at::text FROM chat_messages WHERE owner_id=$1 ORDER BY created_at DESC, id DESC LIMIT 200`, ident.UserID)
      if err != nil { http.Error(w, "database error", 500); return }; defer rows.Close()
      out := make([]chatMessage, 0); for rows.Next() { var m chatMessage; if err := rows.Scan(&m.ID, &m.Body, &m.CreatedAt); err != nil { http.Error(w, "database error", 500); return }; out = append(out, m) }
      json.NewEncoder(w).Encode(out)
    case http.MethodPost:
      var in chatInput; if json.NewDecoder(r.Body).Decode(&in) != nil || strings.TrimSpace(in.Body)=="" { http.Error(w, "body required", 400); return }
      var m chatMessage; err := pool.QueryRow(r.Context(), `INSERT INTO chat_messages(id,owner_id,body) VALUES($1,$2,$3) RETURNING id,body,created_at::text`, uuid.New(), ident.UserID, strings.TrimSpace(in.Body)).Scan(&m.ID,&m.Body,&m.CreatedAt)
      if err != nil { http.Error(w, "database error", 500); return }; w.WriteHeader(http.StatusCreated); json.NewEncoder(w).Encode(m)
    default: w.Header().Set("Allow", "GET, POST"); http.Error(w, "method not allowed", 405)
    }
  })
}
