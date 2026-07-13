package worker

// worker 的集成测试(包内,直接调未导出方法):verify_hash 两分支、blob GC、
// 会话清理、任务认领语义。需要 TEST_DB_URL + TEST_S3_ENDPOINT,缺一跳过。

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"io"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	gdb "github.com/yophon/gopan/server/db"
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func setup(t *testing.T) (*Pool, *pgxpool.Pool, *objstore.Store) {
	t.Helper()
	dbURL, ep := os.Getenv("TEST_DB_URL"), os.Getenv("TEST_S3_ENDPOINT")
	if dbURL == "" || ep == "" {
		t.Skip("TEST_DB_URL / TEST_S3_ENDPOINT 未设置,跳过 worker 集成测试")
	}
	sqlDB, err := sql.Open("pgx", dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()
	if _, err := sqlDB.Exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		t.Fatal(err)
	}
	goose.SetBaseFS(gdb.Migrations)
	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("postgres"); err != nil {
		t.Fatal(err)
	}
	if err := goose.Up(sqlDB, "migrations"); err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(context.Background(), dbURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)

	obj, err := objstore.New(&config.Config{
		S3Endpoint: ep, S3PublicEndpoint: ep,
		S3Key: envOr("TEST_S3_KEY", "gopan"), S3Secret: envOr("TEST_S3_SECRET", "gopan-minio-dev"),
		S3Bucket: "gopan-test", S3Region: "us-east-1",
		PresignPutTTL: time.Hour, PresignGetTTL: 15 * time.Minute,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := obj.EnsureBucket(context.Background()); err != nil {
		t.Fatal(err)
	}
	w := New(pool, obj, service.NewNodes(pool), 30*24*time.Hour, "ffmpeg", "ffprobe", "")
	return w, pool, obj
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// seed 造一个用户 + 指向 blob 的文件节点,返回 (userID, blobID, nodeID)。
func seed(t *testing.T, pool *pgxpool.Pool, content []byte, declaredSha, mime string) (uuid.UUID, uuid.UUID, uuid.UUID) {
	t.Helper()
	ctx := context.Background()
	user, blob, node := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO users (id, username, password_hash, used_bytes) VALUES ($1, $2, 'x', $3)`,
		user, "u_"+user.String()[:8], len(content)); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO blobs (id, sha256, size, mime, ref_count) VALUES ($1, $2, $3, $4, 1)`,
		blob, declaredSha, len(content), mime); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO nodes (id, owner_id, name, kind, blob_id) VALUES ($1, $2, 'f.bin', 'file', $3)`,
		node, user, blob); err != nil {
		t.Fatal(err)
	}
	return user, blob, node
}

func TestVerifyHashOK(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()

	content := []byte("legit png bytes")
	sum := sha256.Sum256(content)
	sha := hex.EncodeToString(sum[:])
	if err := obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader(content), int64(len(content)), "image/png"); err != nil {
		t.Fatal(err)
	}
	user, blob, _ := seed(t, pool, content, sha, "image/png")
	// 挂一个 verifying 会话,校验后应标 done
	if _, err := pool.Exec(ctx,
		`INSERT INTO upload_sessions (id, owner_id, sha256, size, target_name, minio_upload_id, status, expires_at)
		 VALUES ($1, $2, $3, $4, 'f.bin', 'x', 'verifying', now() + interval '1 hour')`,
		uuid.Must(uuid.NewV7()), user, sha, len(content)); err != nil {
		t.Fatal(err)
	}

	if err := w.verifyHash(ctx, blob); err != nil {
		t.Fatal(err)
	}
	var verified bool
	if err := pool.QueryRow(ctx, `SELECT verified FROM blobs WHERE id = $1`, blob).Scan(&verified); err != nil {
		t.Fatal(err)
	}
	if !verified {
		t.Fatal("校验一致应标 verified")
	}
	var sessStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM upload_sessions WHERE sha256 = $1`, sha).Scan(&sessStatus); err != nil {
		t.Fatal(err)
	}
	if sessStatus != "done" {
		t.Fatalf("会话应 done,got %s", sessStatus)
	}
	// image/png 应入队 thumb 派生
	var thumbCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM tasks WHERE kind = 'thumb' AND blob_id = $1`, blob).Scan(&thumbCount); err != nil {
		t.Fatal(err)
	}
	if thumbCount != 1 {
		t.Fatal("verified 后应入队缩略图任务")
	}
	// 幂等:已 verified 再跑直接返回
	if err := w.verifyHash(ctx, blob); err != nil {
		t.Fatal(err)
	}
}

func TestVerifyHashMismatchPurges(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()

	content := []byte("attacker garbage")
	fakeSha := hex.EncodeToString(bytes.Repeat([]byte{0xab}, 32)) // 谎报的 hash
	if err := obj.Put(ctx, objstore.BlobKey(fakeSha), bytes.NewReader(content), int64(len(content)), "application/octet-stream"); err != nil {
		t.Fatal(err)
	}
	user, blob, node := seed(t, pool, content, fakeSha, "application/octet-stream")
	if _, err := pool.Exec(ctx,
		`INSERT INTO upload_sessions (id, owner_id, sha256, size, target_name, minio_upload_id, status, expires_at)
		 VALUES ($1, $2, $3, $4, 'f.bin', 'x', 'verifying', now() + interval '1 hour')`,
		uuid.Must(uuid.NewV7()), user, fakeSha, len(content)); err != nil {
		t.Fatal(err)
	}

	if err := w.verifyHash(ctx, blob); err != nil {
		t.Fatal(err)
	}
	// 全链回收:节点删、blob 行删、配额退、会话 failed、对象删
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM nodes WHERE id = $1`, node).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("谎报节点应被删除")
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM blobs WHERE id = $1`, blob).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("谎报 blob 行应被删除")
	}
	var used int64
	if err := pool.QueryRow(ctx, `SELECT used_bytes FROM users WHERE id = $1`, user).Scan(&used); err != nil {
		t.Fatal(err)
	}
	if used != 0 {
		t.Fatalf("配额应退回 0,got %d", used)
	}
	var sessStatus, reason string
	if err := pool.QueryRow(ctx, `SELECT status, fail_reason FROM upload_sessions WHERE sha256 = $1`, fakeSha).
		Scan(&sessStatus, &reason); err != nil {
		t.Fatal(err)
	}
	if sessStatus != "failed" || reason == "" {
		t.Fatalf("会话应 failed 且带原因,got %s/%q", sessStatus, reason)
	}
	rc, err := obj.Open(ctx, objstore.BlobKey(fakeSha))
	if err == nil {
		if _, err := io.ReadAll(rc); err == nil {
			t.Fatal("对象应已删除(minio Open 惰性,读到才知道)")
		}
		rc.Close()
	}
}

func TestGCBlobs(t *testing.T) {
	w, pool, obj := setup(t)
	ctx := context.Background()

	mk := func(sha string, derefAge string) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		if err := obj.Put(ctx, objstore.BlobKey(sha), bytes.NewReader([]byte("x")), 1, "text/plain"); err != nil {
			t.Fatal(err)
		}
		if _, err := pool.Exec(ctx,
			`INSERT INTO blobs (id, sha256, size, ref_count, deref_at) VALUES ($1, $2, 1, 0, now() - $3::interval)`,
			id, sha, derefAge); err != nil {
			t.Fatal(err)
		}
		return id
	}
	oldSha := hex.EncodeToString(bytes.Repeat([]byte{0x01}, 32))
	freshSha := hex.EncodeToString(bytes.Repeat([]byte{0x02}, 32))
	oldBlob := mk(oldSha, "25 hours")
	freshBlob := mk(freshSha, "1 hour")
	// 老 blob 挂一个派生物,GC 要连对象一起清
	if err := obj.Put(ctx, objstore.DerivedKey(oldSha, "thumb256"), bytes.NewReader([]byte("t")), 1, "image/webp"); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO derivatives (blob_id, kind, minio_key, size) VALUES ($1, 'thumb256', $2, 1)`,
		oldBlob, objstore.DerivedKey(oldSha, "thumb256")); err != nil {
		t.Fatal(err)
	}

	if err := w.gcBlobs(ctx); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM blobs WHERE id = $1`, oldBlob).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("过宽限期的 blob 应被 GC")
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM blobs WHERE id = $1`, freshBlob).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatal("宽限期内的 blob 不应被 GC")
	}
	for _, key := range []string{objstore.BlobKey(oldSha), objstore.DerivedKey(oldSha, "thumb256")} {
		if rc, err := obj.Open(ctx, key); err == nil {
			if _, err := io.ReadAll(rc); err == nil {
				t.Fatalf("%s 应已删除", key)
			}
			rc.Close()
		}
	}
}

func TestCleanupSessions(t *testing.T) {
	w, pool, _ := setup(t)
	ctx := context.Background()
	user := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO users (id, username, password_hash) VALUES ($1, 'sess', 'x')`, user); err != nil {
		t.Fatal(err)
	}
	sha := hex.EncodeToString(bytes.Repeat([]byte{0x03}, 32))
	mkSess := func(status string, expOffset string) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		if _, err := pool.Exec(ctx,
			`INSERT INTO upload_sessions (id, owner_id, sha256, size, target_name, minio_upload_id, status, expires_at)
			 VALUES ($1, $2, $3, 1, 'f', 'no-such-upload', $4, now() + $5::interval)`,
			id, user, sha, status, expOffset); err != nil {
			t.Fatal(err)
		}
		return id
	}
	expired := mkSess("uploading", "-1 hour")
	alive := mkSess("uploading", "1 hour")

	if err := w.cleanupSessions(ctx); err != nil {
		t.Fatal(err)
	}
	var st string
	if err := pool.QueryRow(ctx, `SELECT status FROM upload_sessions WHERE id = $1`, expired).Scan(&st); err != nil {
		t.Fatal(err)
	}
	if st != "aborted" {
		t.Fatalf("过期会话应 aborted,got %s", st)
	}
	if err := pool.QueryRow(ctx, `SELECT status FROM upload_sessions WHERE id = $1`, alive).Scan(&st); err != nil {
		t.Fatal(err)
	}
	if st != "uploading" {
		t.Fatalf("未过期会话不应被动,got %s", st)
	}
}

func TestClaimTaskSemantics(t *testing.T) {
	_, pool, _ := setup(t)
	ctx := context.Background()
	q := store.New(pool)

	blob := uuid.Must(uuid.NewV7())
	if _, err := pool.Exec(ctx,
		`INSERT INTO blobs (id, sha256, size) VALUES ($1, $2, 1)`,
		blob, hex.EncodeToString(bytes.Repeat([]byte{0x04}, 32))); err != nil {
		t.Fatal(err)
	}
	insert := func(kind, status string, attempts int, staleMin int) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		if _, err := pool.Exec(ctx,
			`INSERT INTO tasks (id, kind, blob_id, status, attempts, updated_at) VALUES ($1, $2, $3, $4, $5, now() - make_interval(mins => $6))`,
			id, kind, blob, status, attempts, staleMin); err != nil {
			t.Fatal(err)
		}
		return id
	}
	// 三个任务:pending 可领;running 且滞留 10 分钟(宿主死亡)可再领;attempts 打满的不可领
	pending := insert("thumb", "pending", 0, 0)
	stale := insert("media_probe", "running", 1, 10)
	insert("video_cover", "failed", 3, 0)

	got := map[uuid.UUID]bool{}
	for {
		task, err := q.ClaimTask(ctx)
		if err != nil {
			if err == pgx.ErrNoRows {
				break
			}
			t.Fatal(err)
		}
		got[task.ID] = true
	}
	if !got[pending] || !got[stale] || len(got) != 2 {
		t.Fatalf("应领到 pending 与滞留 running 各一,got %v", got)
	}
}
