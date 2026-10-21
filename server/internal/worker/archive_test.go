package worker

import (
	"archive/zip"
	"bytes"
	"context"
	"slices"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/archive"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func zipBytes(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	f, _ := w.Create("a/b.txt")
	f.Write([]byte("hello"))
	w.Close()
	return buf.Bytes()
}

// 名字是压缩包的 blob 校验通过后排上 archive_index;执行后落下目录索引。别的文件不排。
func TestArchiveIndexTask(t *testing.T) {
	w, pool, obj := setup(t)
	w.SetArchives(service.NewArchives(pool, obj, nil))
	ctx := context.Background()
	q := store.New(pool)
	owner := newOwner(t, pool)
	z := seedFile(t, pool, obj, owner, "包.zip", zipBytes(t), "application/zip")
	txt := seedFile(t, pool, obj, owner, "a.txt", []byte("plain"), "text/plain")
	for _, id := range []uuid.UUID{z, txt} {
		b, err := q.GetBlob(ctx, id)
		if err != nil {
			t.Fatal(err)
		}
		w.enqueueDerivatives(ctx, b)
	}
	var kinds []string
	rows, _ := pool.Query(ctx, `SELECT blob_id::text || ':' || kind FROM tasks WHERE kind = 'archive_index'`)
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		kinds = append(kinds, s)
	}
	if len(kinds) != 1 || kinds[0] != z.String()+":archive_index" {
		t.Fatalf("只该给 zip 排: %v", kinds)
	}
	task, err := q.ClaimTask(ctx)
	for err == nil && task.Kind != "archive_index" {
		w.execute(ctx, task)
		task, err = q.ClaimTask(ctx)
	}
	if err != nil {
		t.Fatal(err)
	}
	w.execute(ctx, task)
	ba, err := q.GetBlobArchive(ctx, z)
	if err != nil || ba.Status != "done" || ba.FileCount != 1 || ba.EntryCount != 2 || ba.Indexer != archive.Version {
		t.Fatalf("索引: %+v %v", ba, err)
	}
}

func TestBackfillArchives(t *testing.T) {
	w, pool, obj := setup(t)
	w.SetArchives(service.NewArchives(pool, obj, nil))
	ctx := context.Background()
	owner := newOwner(t, pool)
	fresh := seedFile(t, pool, obj, owner, "fresh.tar.gz", []byte("x"), "application/gzip")
	old := seedFile(t, pool, obj, owner, "old.rar", []byte("y"), "application/x-rar")
	done := seedFile(t, pool, obj, owner, "done.7z", []byte("z"), "application/x-7z-compressed")
	seedFile(t, pool, obj, owner, "zip-content.bin", zipBytes(t), "application/octet-stream")
	mustExec(t, pool, `INSERT INTO blob_archives (blob_id, status, indexer) VALUES ($1, 'done', $2), ($3, 'done', $4)`,
		old, archive.Version-1, done, archive.Version)
	if err := w.backfillArchives(ctx); err != nil {
		t.Fatal(err)
	}
	var queued []uuid.UUID
	rows, _ := pool.Query(ctx, `SELECT blob_id FROM tasks WHERE kind = 'archive_index' AND status = 'pending'`)
	for rows.Next() {
		var id uuid.UUID
		_ = rows.Scan(&id)
		queued = append(queued, id)
	}
	if len(queued) != 2 || !slices.Contains(queued, fresh) || !slices.Contains(queued, old) {
		t.Fatalf("应排上 fresh 与 old,got %v", queued)
	}
}
