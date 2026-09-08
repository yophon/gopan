package mcpserver

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/yophon/gopan/server/internal/service"
)

func TestMCPConvenienceAndIdempotency(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	token, _, err := e.tokens.Create(ctx, e.alice, "convenience", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, token)
	var path PathOutput
	callTool(t, sess, "create_directories", map[string]any{"path": "/reports/2026", "idempotency_key": "mkdir"}, &path)
	if path.Node == nil {
		t.Fatal("missing directory")
	}
	id := path.Node.ID
	callTool(t, sess, "create_directories", map[string]any{"path": "/reports/2026"}, &path)
	if path.Node.ID != id {
		t.Fatal("mkdir must reuse directories")
	}
	callTool(t, sess, "resolve_path", map[string]any{"path": "/reports/2026"}, &path)
	if path.Node.ID != id {
		t.Fatal("path mismatch")
	}
	callTool(t, sess, "resolve_path", map[string]any{"path": "/"}, &path)
	if !path.Root {
		t.Fatal("virtual root expected")
	}
	for _, bad := range []string{"relative", "/reports/../secret", "/reports//empty"} {
		callToolErr(t, sess, "resolve_path", map[string]any{"path": bad})
	}
	if !strings.Contains(callToolErr(t, sess, "create_directories", map[string]any{"path": "/different", "idempotency_key": "mkdir"}), "IDEMPOTENCY_CONFLICT") {
		t.Fatal("key conflict not detected")
	}
	var source NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"name": "source", "parent_path": "/reports/2026", "idempotency_key": "source"}, &source)
	var repeated NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"name": "source", "parent_path": "/reports/2026", "idempotency_key": "source"}, &repeated)
	if source.ID != repeated.ID {
		t.Fatal("replay duplicated folder")
	}
	callTool(t, sess, "get_file_info", map[string]any{"path": "/reports/2026/source"}, &repeated)
	if source.ID != repeated.ID {
		t.Fatal("get by path failed")
	}
	callToolErr(t, sess, "get_file_info", map[string]any{"node_id": source.ID, "path": "/reports"})
	callToolErr(t, sess, "list_files", map[string]any{"parent_id": id, "path": "/reports"})

	// Concurrent ambiguous retries must all return the same committed copy.
	const count = 8
	results := make(chan string, count)
	failures := make(chan error, count)
	var wg sync.WaitGroup
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := sess.CallTool(ctx, &mcp.CallToolParams{Name: "copy_nodes", Arguments: map[string]any{"node_ids": []string{source.ID}, "target_path": "/reports", "idempotency_key": "copy-once"}})
			if err != nil {
				failures <- err
				return
			}
			if r.IsError {
				failures <- &service.Error{Code: "TEST", Message: resultText(r)}
				return
			}
			b, _ := json.Marshal(r.StructuredContent)
			var out NodesOutput
			if err = json.Unmarshal(b, &out); err != nil {
				failures <- err
				return
			}
			results <- out.Items[0].ID
		}()
	}
	wg.Wait()
	close(results)
	close(failures)
	for err := range failures {
		t.Fatal(err)
	}
	var copied string
	for got := range results {
		if copied != "" && copied != got {
			t.Fatal("concurrent retry duplicated copy")
		}
		copied = got
	}
	var list ListFilesOutput
	callTool(t, sess, "list_files", map[string]any{"path": "/reports"}, &list)
	if list.Total != 2 {
		t.Fatalf("expected 2 nodes, got %d", list.Total)
	}

	// Preview performs real validation, persists neither nodes nor retry records.
	var batch BatchOutput
	args := map[string]any{"operation": "copy", "paths": []string{"/reports/2026/source", "/missing"}, "target_path": "/reports", "dry_run": true, "idempotency_key": "batch-copy"}
	callTool(t, sess, "batch_nodes", args, &batch)
	if !batch.DryRun || batch.Succeeded != 1 || batch.Failed != 1 || batch.Items[0].Node.ID != "" {
		t.Fatalf("bad preview: %+v", batch)
	}
	callTool(t, sess, "list_files", map[string]any{"path": "/reports"}, &list)
	if list.Total != 2 {
		t.Fatal("preview leaked mutation")
	}
	args["dry_run"] = false
	callTool(t, sess, "batch_nodes", args, &batch)
	if batch.Succeeded != 1 || batch.Failed != 1 {
		t.Fatal("partial batch failed")
	}
	batchCopy := batch.Items[0].Node.ID
	callTool(t, sess, "batch_nodes", args, &batch)
	if batch.Items[0].Node.ID != batchCopy {
		t.Fatal("batch replay duplicated node")
	}
	callTool(t, sess, "list_files", map[string]any{"path": "/reports"}, &list)
	if list.Total != 3 {
		t.Fatal("batch replay changed count")
	}
	callTool(t, sess, "batch_nodes", map[string]any{"operation": "move", "node_ids": []string{copied, batchCopy}, "target_path": "/reports/2026"}, &batch)
	if batch.Succeeded != 1 || batch.Failed != 1 {
		t.Fatalf("savepoint failure did not isolate items: %+v", batch)
	}
	callTool(t, sess, "batch_nodes", map[string]any{"operation": "trash", "node_ids": []string{copied, batchCopy}}, &batch)
	if batch.Succeeded != 2 {
		t.Fatal("trash failed")
	}
	var trash TrashOutput
	callTool(t, sess, "list_trash", map[string]any{}, &trash)
	if trash.Total != 2 || trash.Items[0].DeletedAt == "" {
		t.Fatalf("trash list: %+v", trash)
	}
	callTool(t, sess, "batch_nodes", map[string]any{"operation": "restore", "node_ids": []string{copied, batchCopy}}, &batch)
	if batch.Succeeded != 2 {
		t.Fatal("restore failed")
	}

	// Owner and scope checks must precede cache replay and all preview writes.
	bobToken, _, _ := e.tokens.Create(ctx, e.bob, "bob", service.MCPScopes)
	bob := e.connect(t, bobToken)
	callToolErr(t, bob, "resolve_path", map[string]any{"path": "/reports"})
	callTool(t, bob, "batch_nodes", map[string]any{"operation": "trash", "node_ids": []string{source.ID}, "dry_run": true}, &batch)
	if batch.Failed != 1 || batch.Items[0].ErrorCode != "NOT_FOUND" {
		t.Fatal("owner boundary failed")
	}
	limited, _, _ := e.tokens.Create(ctx, e.alice, "reader", []string{"files:read"})
	reader := e.connect(t, limited)
	callToolErr(t, reader, "create_folder", map[string]any{"name": "source", "parent_path": "/reports/2026", "idempotency_key": "source"})
	callToolErr(t, reader, "batch_nodes", map[string]any{"operation": "copy", "node_ids": []string{source.ID}, "dry_run": true})
	// Recursive mkdir rollback: a later invalid name must leave no partial parent.
	callToolErr(t, sess, "create_directories", map[string]any{"path": "/should-not-exist/.."})
	callToolErr(t, sess, "resolve_path", map[string]any{"path": "/should-not-exist"})
	// A mutation that fails after a write must commit neither the write nor the key.
	_, err = e.nodes.MCPMutation(ctx, e.alice, "rolled-back", []byte("failed"), false, func(nodes *service.Nodes) (json.RawMessage, error) {
		_, err := nodes.CreateFolder(ctx, e.alice, nil, "transaction-rollback")
		if err != nil {
			return nil, err
		}
		return nil, service.ErrForbidden
	})
	if err == nil {
		t.Fatal("expected mutation failure")
	}
	callToolErr(t, sess, "resolve_path", map[string]any{"path": "/transaction-rollback"})
	callTool(t, sess, "create_folder", map[string]any{"name": "transaction-retry", "idempotency_key": "rolled-back"}, &repeated)
}

func TestWaitUploadTerminalTimeoutAndCancellation(t *testing.T) {
	e := setupEnv(t)
	ctx := ctxFor(e.alice)
	_, up, err := e.s.prepareUpload(ctx, nil, PrepareUploadInput{Name: "wait.txt", Size: 3, ContentType: "text/plain"})
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	_, out, err := e.s.waitUpload(ctx, nil, WaitUploadInput{UploadID: up.UploadID, TimeoutSeconds: 1})
	if err != nil || out.Status != "uploading" || time.Since(start) > 3*time.Second {
		t.Fatalf("timeout result %+v %v", out, err)
	}
	cancelCtx, cancel := context.WithCancel(ctx)
	cancel()
	if _, _, err = e.s.waitUpload(cancelCtx, nil, WaitUploadInput{UploadID: up.UploadID}); err == nil {
		t.Fatal("cancellation ignored")
	}
	if _, _, err = e.s.waitUpload(ctx, nil, WaitUploadInput{UploadID: up.UploadID, TimeoutSeconds: 26}); err == nil {
		t.Fatal("wait bound ignored")
	}
	httpPutBytes(t, up.PutURL, []byte("abc"))
	if _, _, err = e.s.completeUpload(ctx, nil, UploadPartsInput{UploadID: up.UploadID}); err != nil {
		t.Fatal(err)
	}
	workerDone := make(chan error, 1)
	go func() {
		time.Sleep(100 * time.Millisecond)
		_, err := e.uploads.ProcessNextAgentTransfer(context.Background())
		if err == nil {
			sha := sha256Hex([]byte("abc"))
			blob, e1 := e.q.GetBlobBySha256(context.Background(), sha)
			err = e1
			if err == nil {
				err = e.q.MarkBlobVerified(context.Background(), blob.ID)
			}
			if err == nil {
				err = e.q.MarkTransferReadyBySha(context.Background(), &sha)
			}
		}
		workerDone <- err
	}()
	_, out, err = e.s.waitUpload(ctx, nil, WaitUploadInput{UploadID: up.UploadID, TimeoutSeconds: 5})
	if err != nil || out.Status != "ready" {
		t.Fatalf("ready result %+v %v", out, err)
	}
	if err = <-workerDone; err != nil {
		t.Fatal(err)
	}
	_, other, err := e.s.prepareUpload(ctx, nil, PrepareUploadInput{Name: "abort.txt", Size: 1})
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = e.s.abortUpload(ctx, nil, UploadPartsInput{UploadID: other.UploadID})
	if err != nil {
		t.Fatal(err)
	}
	_, out, err = e.s.waitUpload(ctx, nil, WaitUploadInput{UploadID: other.UploadID})
	if err != nil || out.Status != "aborted" {
		t.Fatal("aborted wait failed")
	}
	_, _, err = e.s.waitUpload(ctxFor(e.bob), nil, WaitUploadInput{UploadID: up.UploadID})
	if err == nil {
		t.Fatal("foreign upload exposed")
	}
	_, _, err = e.s.waitUpload(ctx, nil, WaitUploadInput{UploadID: uuid.NewString(), TimeoutSeconds: 1})
	if err == nil {
		t.Fatal("missing upload accepted")
	}
}
