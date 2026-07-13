package service_test

// 分页基准:10 万节点单目录,offset(v1 的旧 SQL 原样保留在这里)vs keyset。
//   TEST_DB_URL=... go test ./internal/service/ -bench BenchmarkChildren -benchtime 20x -run XXX
// 结论记录在 doc/milestones/M8-规模.md。

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	"github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

const benchNodes = 100_000

func setupBench(b *testing.B) (*pgxpool.Pool, uuid.UUID, uuid.UUID) {
	b.Helper()
	url := os.Getenv("TEST_DB_URL")
	if url == "" {
		b.Skip("TEST_DB_URL 未设置,跳过基准")
	}
	sqlDB, err := sql.Open("pgx", url)
	if err != nil {
		b.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		b.Fatal(err)
	}
	goose.SetBaseFS(db.Migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("postgres"); err != nil {
		b.Fatal(err)
	}
	if err := goose.Up(sqlDB, "migrations"); err != nil {
		b.Fatal(err)
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		b.Fatal(err)
	}
	b.Cleanup(pool.Close)

	ctx := context.Background()
	owner, folder := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO users (id, username, password_hash) VALUES ($1, 'bench', 'x')`, owner); err != nil {
		b.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO nodes (id, owner_id, name, kind) VALUES ($1, $2, 'big', 'folder')`, folder, owner); err != nil {
		b.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO nodes (id, owner_id, parent_id, name, kind)
		SELECT gen_random_uuid(), $1, $2, 'file_' || lpad(i::text, 7, '0'), 'file'
		FROM generate_series(1, `+fmt.Sprint(benchNodes)+`) i`, owner, folder); err != nil {
		b.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `ANALYZE nodes`); err != nil {
		b.Fatal(err)
	}
	return pool, owner, folder
}

// v1 的 offset 查询,原样保留作对照(NAME 升序档)
const offsetSQL = `
SELECT n.*, b.size AS blob_size, b.mime AS blob_mime, b.sha256 AS blob_sha256
FROM nodes n
LEFT JOIN blobs b ON b.id = n.blob_id
WHERE n.owner_id = $1 AND n.parent_id IS NOT DISTINCT FROM $2 AND n.deleted_at IS NULL
ORDER BY n.kind DESC, n.name ASC, n.id
LIMIT 200 OFFSET $3`

func drainOffset(b *testing.B, pool *pgxpool.Pool, owner, folder uuid.UUID, offset int) {
	b.Helper()
	rows, err := pool.Query(context.Background(), offsetSQL, owner, folder, offset)
	if err != nil {
		b.Fatal(err)
	}
	n := 0
	for rows.Next() {
		n++
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		b.Fatal(err)
	}
	if n == 0 {
		b.Fatal("offset 查询返回 0 行")
	}
}

func drainKeyset(b *testing.B, q *store.Queries, owner, folder uuid.UUID, afterIdx int) {
	b.Helper()
	// keyset 的"深页"直接构造边界键(名字是顺序生成的,第 afterIdx 个即边界)
	rows, err := q.ListChildrenNameAsc(context.Background(), store.ListChildrenNameAscParams{
		OwnerID: owner, ParentID: &folder, PageLimit: 200,
		CKind: "file", CName: fmt.Sprintf("file_%07d", afterIdx), CID: uuid.Nil,
	})
	if err != nil {
		b.Fatal(err)
	}
	if len(rows) == 0 {
		b.Fatal("keyset 查询返回 0 行")
	}
}

func BenchmarkChildren(b *testing.B) {
	pool, owner, folder := setupBench(b)
	q := store.New(pool)
	nodes := service.NewNodes(pool)

	pages := []struct {
		name   string
		offset int
	}{
		{"page1", 0},
		{"page250", 249 * 200},
		{"lastPage", benchNodes - 200},
	}
	for _, p := range pages {
		b.Run("offset/"+p.name, func(b *testing.B) {
			for b.Loop() {
				drainOffset(b, pool, owner, folder, p.offset)
			}
		})
		b.Run("keyset/"+p.name, func(b *testing.B) {
			for b.Loop() {
				if p.offset == 0 {
					// 首页走服务层同款查询
					if _, err := q.ListChildren(context.Background(), store.ListChildrenParams{
						OwnerID: owner, ParentID: &folder, OrderBy: "NAME", Descending: false, PageLimit: 200,
					}); err != nil {
						b.Fatal(err)
					}
				} else {
					drainKeyset(b, q, owner, folder, p.offset)
				}
			}
		})
	}

	// 全目录顺序遍历(真实滚动加载路径,走 service 层含 count)
	b.Run("keyset/fullWalk", func(b *testing.B) {
		ctx := context.Background()
		for b.Loop() {
			var cursor *string
			for {
				page, err := nodes.Children(ctx, owner, &folder, cursor, "NAME", false)
				if err != nil {
					b.Fatal(err)
				}
				if page.NextCursor == nil {
					break
				}
				cursor = page.NextCursor
			}
		}
	})
}
