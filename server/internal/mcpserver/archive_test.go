package mcpserver

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

// Agent 列压缩包目录、解压到压缩包所在目录;限定访问目录的凭据碰不到目录外的包。
func TestMCPArchive(t *testing.T) {
	e := setupEnv(t)
	ctx := t.Context()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range map[string]string{"docs/a.txt": "aaa", "docs/sub/b.txt": "bb", "top.txt": "t"} {
		f, _ := zw.Create(name)
		f.Write([]byte(body))
	}
	zw.Close()
	work, _ := e.nodes.CreateFolder(ctx, e.alice, nil, "work")
	key := "tmp/test/" + uuid.NewString()
	if err := e.obj.Put(ctx, key, bytes.NewReader(buf.Bytes()), int64(buf.Len()), "application/zip"); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(buf.Bytes())
	n, err := e.uploads.CommitStaged(ctx, service.StagedFile{Owner: e.alice, Parent: &work.ID, Name: "pack.zip", Sha: hex.EncodeToString(sum[:]), Size: int64(buf.Len()), TmpKey: key})
	if err != nil {
		t.Fatal(err)
	}

	plain, _, _ := e.tokens.Create(ctx, e.alice, "arc", service.MCPScopes)
	sess := e.connect(t, plain)
	var pending ListArchiveOutput
	callTool(t, sess, "list_archive", map[string]any{"path": "/work/pack.zip"}, &pending)
	if pending.Status != "PENDING" || len(pending.Entries) != 0 {
		t.Fatalf("pending: %+v", pending)
	}
	if err := e.archives.BuildIndex(ctx, *n.BlobID); err != nil {
		t.Fatal(err)
	}
	var root ListArchiveOutput
	callTool(t, sess, "list_archive", map[string]any{"node_id": n.ID.String()}, &root)
	if root.Status != "DONE" || root.FileCount != 3 || root.Total != 2 || !root.Entries[0].IsDir || root.Entries[0].Path != "docs" {
		t.Fatalf("root: %+v", root)
	}
	var docs ListArchiveOutput
	callTool(t, sess, "list_archive", map[string]any{"node_id": n.ID.String(), "dir": "docs"}, &docs)
	if docs.Total != 2 {
		t.Fatalf("docs: %+v", docs)
	}
	var job JobOutput
	callTool(t, sess, "extract_archive", map[string]any{"node_id": n.ID.String(), "base": "docs", "paths": []string{"docs/sub"}}, &job)
	if job.Kind != "extract_archive" || job.ParentID == nil || *job.ParentID != work.ID.String() {
		t.Fatalf("extract: %+v", job)
	}
	if ok, err := e.jobs.RunOnce(ctx); !ok || err != nil {
		t.Fatal(ok, err)
	}
	var done JobOutput
	callTool(t, sess, "get_job", map[string]any{"job_id": job.ID}, &done)
	if done.Status != "done" || done.FilesDone != 1 || done.ResultNodeID == nil {
		t.Fatalf("done: %+v", done)
	}
	var listing ListFilesOutput
	callTool(t, sess, "list_files", map[string]any{"parent_id": *done.ResultNodeID}, &listing)
	if len(listing.Items) != 1 || listing.Items[0].Name != "sub" {
		t.Fatalf("解出来的: %+v", listing)
	}

	// 限定在 /agent 的凭据:目录外的压缩包列不了、解不了
	e.nodes.CreateFolder(ctx, e.alice, nil, "agent")
	tok, key2, _ := e.tokens.Create(ctx, e.alice, "scoped", service.MCPScopes)
	path := "/agent"
	if err := e.nodes.SetMCPAccessRoot(ctx, e.alice, key2.ID, "api_key", &path); err != nil {
		t.Fatal(err)
	}
	scoped := e.connect(t, tok)
	if msg := callToolErr(t, scoped, "list_archive", map[string]any{"node_id": n.ID.String()}); !strings.Contains(msg, "NOT_FOUND") && !strings.Contains(msg, "不存在") {
		t.Fatalf("目录外的包: %s", msg)
	}
	if msg := callToolErr(t, scoped, "extract_archive", map[string]any{"node_id": n.ID.String()}); msg == "" {
		t.Fatal("目录外的包不该能解")
	}
}
