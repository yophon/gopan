package service_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/minio/minio-go/v7"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func requireCode(t *testing.T, err error, code string) {
	t.Helper()
	var se *service.Error
	if !errors.As(err, &se) || se.Code != code {
		t.Fatalf("want %s, got %v", code, err)
	}
}

func completeBrowser(t *testing.T, u *service.Uploads, owner, id uuid.UUID) (*store.Node, error) {
	t.Helper()
	n, err := u.Complete(t.Context(), owner, id, nil)
	var se *service.Error
	if !errors.As(err, &se) || se.Code != "UPLOAD_PROCESSING" {
		return n, err
	}
	if _, err := u.ProcessNextBrowserUpload(t.Context()); err != nil {
		return nil, err
	}
	return u.Complete(t.Context(), owner, id, nil)
}

func stageBrowser(t *testing.T, u *service.Uploads, owner uuid.UUID, name, sha string, data []byte) store.UploadSession {
	t.Helper()
	init, err := u.Init(t.Context(), owner, nil, name, sha, int64(len(data)))
	if err != nil || init.Session == nil {
		t.Fatalf("init: %+v %v", init, err)
	}
	for part, url := range init.Session.PartURLs {
		ps := int(init.Session.Session.PartSize)
		httpPut(t, url, data[(part-1)*ps:min(part*ps, len(data))])
	}
	return init.Session.Session
}

func execBrowserSQL(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := pool.Exec(t.Context(), sql, args...); err != nil {
		t.Fatal(err)
	}
}

func requireObject(t *testing.T, obj *objstore.Store, key string, want []byte) {
	t.Helper()
	r, err := obj.Open(t.Context(), key)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	got, err := io.ReadAll(r)
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("object mismatch: %q %v", got, err)
	}
}

func requireStageGone(t *testing.T, obj *objstore.Store, key string) {
	t.Helper()
	_, err := obj.Stat(t.Context(), key)
	if minio.ToErrorResponse(err).Code != "NoSuchKey" {
		t.Fatalf("staging still exists: %v", err)
	}
}

func TestBrowserSameHashIsolation(t *testing.T) {
	pool, obj := setup(t), setupS3(t)
	u, _, a := newUploads(t, pool, obj)
	_, _, b := newUploads(t, pool, obj)
	data := []byte("original file")
	sha := shaHex(data)
	good := stageBrowser(t, u, a, "good.txt", sha, data)
	bad := stageBrowser(t, u, b, "bad.txt", sha, bytes.Repeat([]byte("!"), len(data)))
	late := stageBrowser(t, u, b, "late.txt", sha, data)
	if *good.ObjectKey == *bad.ObjectKey || *good.ObjectKey == objstore.BlobKey(sha) {
		t.Fatal("sessions must have private staging keys")
	}
	n, err := completeBrowser(t, u, a, good.ID)
	if err != nil {
		t.Fatal(err)
	}
	_, err = completeBrowser(t, u, b, bad.ID)
	requireCode(t, err, "HASH_MISMATCH")
	requireObject(t, obj, objstore.BlobKey(sha), data)
	requireStageGone(t, obj, *bad.ObjectKey)
	lateNode, err := completeBrowser(t, u, b, late.ID)
	if err != nil {
		t.Fatal(err)
	}
	if lateNode.BlobID == nil || *lateNode.BlobID != *n.BlobID {
		t.Fatal("valid late upload should deduplicate")
	}
	q := store.New(pool)
	blob, err := q.GetBlob(t.Context(), *n.BlobID)
	if err != nil || !blob.Verified || blob.RefCount != 2 {
		t.Fatalf("blob: %+v %v", blob, err)
	}
	user, err := q.GetUserByID(t.Context(), b)
	if err != nil || user.UsedBytes != int64(len(data)) {
		t.Fatalf("bad upload must not charge quota: %+v %v", user, err)
	}
	// Retrying success returns the same node; deleting it must not resurrect it.
	again, err := u.Complete(t.Context(), b, late.ID, nil)
	if err != nil || again.ID != lateNode.ID {
		t.Fatalf("idempotency: %v", err)
	}
	if err := service.NewNodes(pool).Purge(t.Context(), b, []uuid.UUID{lateNode.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := u.Complete(t.Context(), b, late.ID, nil); err == nil {
		t.Fatal("deleted node must not be recreated")
	}
}

func TestBrowserConcurrentQuotaAndLoweredQuota(t *testing.T) {
	for _, lowered := range []bool{false, true} {
		t.Run(map[bool]string{false: "concurrent", true: "lowered"}[lowered], func(t *testing.T) {
			pool, obj := setup(t), setupS3(t)
			u, _, owner := newUploads(t, pool, obj)
			a := stageBrowser(t, u, owner, "a.bin", shaHex([]byte("aaaaaaaa")), []byte("aaaaaaaa"))
			b := stageBrowser(t, u, owner, "b.bin", shaHex([]byte("bbbbbbbb")), []byte("bbbbbbbb"))
			quota := int64(10)
			if lowered {
				quota = 7
			}
			execBrowserSQL(t, pool, "UPDATE users SET quota_bytes=$2 WHERE id=$1", owner, quota)
			for _, sess := range []store.UploadSession{a, b} {
				_, err := u.Complete(t.Context(), owner, sess.ID, nil)
				requireCode(t, err, "UPLOAD_PROCESSING")
			}
			var wg sync.WaitGroup
			errs := make(chan error, 2)
			for i := 0; i < 2; i++ {
				wg.Add(1)
				go func() { defer wg.Done(); _, err := u.ProcessNextBrowserUpload(t.Context()); errs <- err }()
			}
			wg.Wait()
			close(errs)
			for err := range errs {
				if err != nil {
					t.Fatal(err)
				}
			}
			successes := 0
			for _, sess := range []store.UploadSession{a, b} {
				_, err := u.Complete(t.Context(), owner, sess.ID, nil)
				if err == nil {
					successes++
				} else {
					requireCode(t, err, "QUOTA_EXCEEDED")
				}
			}
			want := 1
			if lowered {
				want = 0
			}
			user, err := store.New(pool).GetUserByID(t.Context(), owner)
			if err != nil || successes != want || user.UsedBytes != int64(want*8) || user.UsedBytes > user.QuotaBytes {
				t.Fatalf("successes=%d user=%+v err=%v", successes, user, err)
			}
		})
	}
}

func TestBrowserMergeAndDatabaseFailureRecovery(t *testing.T) {
	pool, obj := setup(t), setupS3(t)
	u, _, owner := newUploads(t, pool, obj)
	data := []byte("recoverable upload")
	sess := stageBrowser(t, u, owner, "recover.txt", shaHex(data), data)
	// Model a crash after S3 merge but before the uploading -> completing commit.
	parts, err := obj.ListParts(t.Context(), *sess.ObjectKey, sess.MinioUploadID)
	if err != nil {
		t.Fatal(err)
	}
	if err := obj.CompleteMultipart(t.Context(), *sess.ObjectKey, sess.MinioUploadID, []objstore.Part{{Number: 1, ETag: parts[1]}}); err != nil {
		t.Fatal(err)
	}
	resumed, err := u.Session(t.Context(), owner, sess.ID)
	if err != nil || len(resumed.PartURLs) != 0 {
		t.Fatalf("sealed staging must remain resumable: %+v %v", resumed, err)
	}
	_, err = u.Complete(t.Context(), owner, sess.ID, nil)
	requireCode(t, err, "UPLOAD_PROCESSING")
	// Fail after object publication, forcing the node/blob/quota transaction to
	// roll back. Staging and the completing session must survive for a retry.
	execBrowserSQL(t, pool, `CREATE FUNCTION browser_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected accounting failure'; END $$;
CREATE TRIGGER browser_test_fault BEFORE UPDATE OF used_bytes ON users FOR EACH ROW EXECUTE FUNCTION browser_test_fault()`)
	if _, err := u.ProcessNextBrowserUpload(t.Context()); err == nil {
		t.Fatal("fault must fail finalization")
	}
	view, err := u.Session(t.Context(), owner, sess.ID)
	if err != nil || view.Session.Status != "completing" {
		t.Fatalf("must remain retryable: %+v %v", view, err)
	}
	requireObject(t, obj, *sess.ObjectKey, data)
	execBrowserSQL(t, pool, "DROP TRIGGER browser_test_fault ON users; DROP FUNCTION browser_test_fault()")
	// Use a new service instance, as after restarting the server.
	restarted := service.NewUploads(pool, obj, service.NewNodes(pool), 5<<20, time.Hour)
	if processed, err := restarted.ProcessNextBrowserUpload(t.Context()); err != nil || !processed {
		t.Fatalf("restart recovery: %v %v", processed, err)
	}
	n, err := restarted.Complete(t.Context(), owner, sess.ID, nil)
	if err != nil {
		t.Fatal(err)
	}
	again, err := restarted.Complete(t.Context(), owner, sess.ID, nil)
	if err != nil || again.ID != n.ID {
		t.Fatal("completion must be idempotent")
	}
	requireObject(t, obj, objstore.BlobKey(sess.Sha256), data)
	requireStageGone(t, obj, *sess.ObjectKey)
	user, err := store.New(pool).GetUserByID(t.Context(), owner)
	if err != nil || user.UsedBytes != int64(len(data)) {
		t.Fatalf("must charge once: %+v %v", user, err)
	}
}

func TestBrowserNameConflictAndSizeMismatch(t *testing.T) {
	pool, obj := setup(t), setupS3(t)
	u, nodes, owner := newUploads(t, pool, obj)
	data := []byte("valid bytes")
	sess := stageBrowser(t, u, owner, "taken", shaHex(data), data)
	if _, err := nodes.CreateFolder(t.Context(), owner, nil, "taken"); err != nil {
		t.Fatal(err)
	}
	_, err := completeBrowser(t, u, owner, sess.ID)
	requireCode(t, err, "NAME_CONFLICT")
	requireStageGone(t, obj, *sess.ObjectKey)
	init, err := u.Init(t.Context(), owner, nil, "size.bin", shaHex(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	httpPut(t, init.Session.PartURLs[1], append(data, '!'))
	_, err = completeBrowser(t, u, owner, init.Session.Session.ID)
	requireCode(t, err, "SIZE_MISMATCH")
	user, _ := store.New(pool).GetUserByID(t.Context(), owner)
	if user.UsedBytes != 0 {
		t.Fatal("failed uploads must not consume quota")
	}
}

func TestBrowserAbortExpiryAndLegacy(t *testing.T) {
	pool, obj := setup(t), setupS3(t)
	u, _, owner := newUploads(t, pool, obj)
	data := []byte("keep canonical bytes")
	for _, expire := range []bool{false, true} {
		sess := stageBrowser(t, u, owner, uuid.NewString(), shaHex(data), data)
		_, err := u.Complete(t.Context(), owner, sess.ID, nil)
		requireCode(t, err, "UPLOAD_PROCESSING")
		if expire {
			execBrowserSQL(t, pool, "UPDATE upload_sessions SET expires_at=now()-interval '1 hour' WHERE id=$1", sess.ID)
			if _, err := u.CleanupBrowserUploads(t.Context()); err != nil {
				t.Fatal(err)
			}
		} else if err := u.Abort(t.Context(), owner, sess.ID); err != nil {
			t.Fatal(err)
		}
		requireStageGone(t, obj, *sess.ObjectKey)
		view, err := u.Session(t.Context(), owner, sess.ID)
		if err != nil || view.Session.Status != "aborted" {
			t.Fatalf("abort: %+v %v", view, err)
		}
	}
	// Old sessions address canonical storage. Reject resume/completion, but allow
	// aborting their multipart without ever removing the existing shared object.
	sha := shaHex(data)
	key := objstore.BlobKey(sha)
	if err := obj.Put(t.Context(), key, bytes.NewReader(data), int64(len(data)), "text/plain"); err != nil {
		t.Fatal(err)
	}
	legacyID := uuid.Must(uuid.NewV7())
	multipart, err := obj.NewMultipart(t.Context(), key, "text/plain")
	if err != nil {
		t.Fatal(err)
	}
	execBrowserSQL(t, pool, `INSERT INTO upload_sessions(id,owner_id,sha256,size,target_name,minio_upload_id,expires_at)
VALUES($1,$2,$3,$4,'legacy',$5,now()+interval '1 hour')`, legacyID, owner, sha, len(data), multipart)
	_, err = u.Complete(t.Context(), owner, legacyID, nil)
	requireCode(t, err, "UPLOAD_EXPIRED")
	if err := u.Abort(t.Context(), owner, legacyID); err != nil {
		t.Fatal(err)
	}
	requireObject(t, obj, key, data)
}

func TestBrowserPollDoesNotWaitForWorker(t *testing.T) {
	pool, obj := setup(t), setupS3(t)
	u, _, owner := newUploads(t, pool, obj)
	sess := stageBrowser(t, u, owner, "poll", shaHex([]byte("poll")), []byte("poll"))
	_, err := u.Complete(t.Context(), owner, sess.ID, nil)
	requireCode(t, err, "UPLOAD_PROCESSING")
	tx, err := pool.Begin(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(t.Context())
	if _, err := store.New(tx).ClaimCompletingUpload(t.Context()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	_, err = u.Complete(ctx, owner, sess.ID, nil)
	requireCode(t, err, "UPLOAD_PROCESSING")
}
