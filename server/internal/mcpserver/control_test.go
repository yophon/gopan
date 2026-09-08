package mcpserver

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/yophon/gopan/server/internal/service"
)

func TestMCPDirectoryBoundarySharesAndAudit(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	container, err := e.nodes.CreateFolder(ctx, e.alice, nil, "container")
	if err != nil {
		t.Fatal(err)
	}
	root, err := e.nodes.CreateFolder(ctx, e.alice, &container.ID, "workspace")
	if err != nil {
		t.Fatal(err)
	}
	outside, _ := e.nodes.CreateFolder(ctx, e.alice, nil, "outside")
	token, key, err := e.tokens.Create(ctx, e.alice, "restricted", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	path := "/container/workspace"
	if err = e.nodes.SetMCPAccessRoot(ctx, e.alice, key.ID, "api_key", &path); err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, token)
	var rootInfo NodeOutput
	callTool(t, sess, "get_file_info", map[string]any{"node_id": root.ID.String()}, &rootInfo)
	if rootInfo.ParentID != nil {
		t.Fatal("root metadata exposed outside parent")
	}
	var info service.MCPStatus
	callTool(t, sess, "storage_status", map[string]any{}, &info)
	if info.RootID == nil || *info.RootID != root.ID.String() || info.AvailableBytes <= 0 {
		t.Fatalf("bad status %+v", info)
	}
	var node NodeOutput
	callTool(t, sess, "create_folder", map[string]any{"name": "scoped"}, &node)
	if node.ParentID == nil || *node.ParentID != root.ID.String() {
		t.Fatal("default destination escaped root")
	}
	var listing ListFilesOutput
	callTool(t, sess, "list_files", map[string]any{}, &listing)
	if listing.Total != 1 {
		t.Fatalf("root listing leaked: %+v", listing)
	}
	for _, entry := range []struct {
		name string
		args map[string]any
	}{
		{"list_files", map[string]any{"parent_id": outside.ID.String()}},
		{"get_file_info", map[string]any{"node_id": outside.ID.String()}},
		{"prepare_download", map[string]any{"node_ids": []string{outside.ID.String()}}},
		{"prepare_upload", map[string]any{"name": "escape.txt", "size": 1, "parent_id": outside.ID.String()}},
		{"create_folder", map[string]any{"name": "escape", "parent_id": outside.ID.String()}},
		{"move_nodes", map[string]any{"node_ids": []string{node.ID}, "target_folder_id": outside.ID.String()}},
		{"copy_nodes", map[string]any{"node_ids": []string{outside.ID.String()}}},
		{"rename_node", map[string]any{"path": "/", "name": "escape"}},
		{"trash_nodes", map[string]any{"node_ids": []string{root.ID.String()}}},
		{"create_share", map[string]any{"node_id": outside.ID.String()}},
	} {
		callToolErr(t, sess, entry.name, entry.args)
	}
	var search SearchFilesOutput
	callTool(t, sess, "search_files", map[string]any{"query": "outside"}, &search)
	if search.Total != 0 {
		t.Fatal("search escaped root")
	}
	var batch BatchOutput
	callTool(t, sess, "batch_nodes", map[string]any{"operation": "copy", "node_ids": []string{node.ID, outside.ID.String()}, "dry_run": true}, &batch)
	if batch.Succeeded != 1 || batch.Failed != 1 {
		t.Fatalf("batch scope %+v", batch)
	}
	var share ShareOutput
	shareArgs := map[string]any{"path": "/scoped", "password": "audit-secret-password", "expires_at": time.Now().Add(time.Hour).Format(time.RFC3339), "idempotency_key": "share-one"}
	callTool(t, sess, "create_share", shareArgs, &share)
	original := share.ID
	callTool(t, sess, "create_share", shareArgs, &share)
	if share.ID != original || !share.HasPassword {
		t.Fatal("share idempotency failed")
	}
	var shares SharesOutput
	callTool(t, sess, "list_shares", map[string]any{}, &shares)
	if len(shares.Items) != 1 {
		t.Fatal("share list mismatch")
	}
	var result SuccessOutput
	callTool(t, sess, "revoke_share", map[string]any{"share_id": share.ID, "idempotency_key": "revoke-one"}, &result)
	callTool(t, sess, "revoke_share", map[string]any{"share_id": share.ID, "idempotency_key": "revoke-one"}, &result)
	callTool(t, sess, "list_shares", map[string]any{}, &shares)
	if len(shares.Items) != 0 {
		t.Fatal("revoked share listed")
	}
	var audit AuditOutput
	callTool(t, sess, "list_audit", map[string]any{"limit": 100}, &audit)
	data, _ := json.Marshal(audit)
	if strings.Contains(string(data), "audit-secret-password") || strings.Contains(string(data), token) || strings.Contains(string(data), "/s/") {
		t.Fatal("audit leaked credential")
	}
	failed := 0
	for _, row := range audit.Items {
		if row.CredentialID != key.ID.String() {
			t.Fatal("foreign audit exposed")
		}
		if row.Status == "failed" {
			failed++
		}
	}
	if failed < 5 {
		t.Fatal("failure audit missing")
	}
	_, outsideUpload, err := e.s.prepareUpload(ctxFor(e.alice), nil, PrepareUploadInput{Name: "outside.txt", Size: 1})
	if err != nil {
		t.Fatal(err)
	}
	callToolErr(t, sess, "get_upload_status", map[string]any{"upload_id": outsideUpload.UploadID})
	if err = e.nodes.Delete(ctx, e.alice, []uuid.UUID{root.ID}); err != nil {
		t.Fatal(err)
	}
	if got := e.rawPost(t, "Bearer "+token); got != http.StatusForbidden {
		t.Fatalf("deleted root must deny, got %d", got)
	}
	if err = e.nodes.Purge(ctx, e.alice, []uuid.UUID{root.ID}); err != nil {
		t.Fatal(err)
	}
	if got := e.rawPost(t, "Bearer "+token); got != http.StatusForbidden {
		t.Fatalf("purged root became unrestricted: %d", got)
	}
}

func TestSeparateAdminMCP(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	if _, _, err := e.tokens.Create(ctx, e.alice, "not-admin", service.MCPAdminScopes); err == nil {
		t.Fatal("non-admin minted admin token")
	}
	if _, err := e.pool.Exec(ctx, `UPDATE users SET is_admin=true WHERE id=$1`, e.alice); err != nil {
		t.Fatal(err)
	}
	if _, _, err := e.tokens.Create(ctx, e.alice, "mixed", []string{"files:read", "admin:read"}); err == nil {
		t.Fatal("mixed endpoint credential accepted")
	}
	adminToken, _, err := e.tokens.Create(ctx, e.alice, "administrator", service.MCPAdminScopes)
	if err != nil {
		t.Fatal(err)
	}
	if got := e.rawPost(t, "Bearer "+adminToken); got != 403 {
		t.Fatalf("admin token accepted by file endpoint: %d", got)
	}
	server := httptest.NewServer(NewAdminHandler(e.nodes, e.tokens, e.oauth, 1024))
	defer server.Close()
	previous := e.srv
	e.srv = server
	defer func() { e.srv = previous }()
	fileToken, _, _ := e.tokens.Create(ctx, e.alice, "file", service.MCPScopes)
	if got := e.rawPost(t, "Bearer "+fileToken); got != 403 {
		t.Fatalf("file token accepted by admin endpoint: %d", got)
	}
	sess := e.connect(t, adminToken)
	catalog, err := sess.ListTools(ctx, nil)
	if err != nil || len(catalog.Tools) != 10 {
		t.Fatalf("admin catalog %v %v", catalog, err)
	}
	var users AdminUsersOutput
	callTool(t, sess, "admin_list_users", map[string]any{}, &users)
	b, _ := json.Marshal(users)
	if strings.Contains(string(b), "password") {
		t.Fatal("user hashes exposed")
	}
	var created AdminUserOutput
	callTool(t, sess, "admin_create_user", map[string]any{"username": "mcp-test-user", "password": "Secret-admin-create", "idempotency_key": "create-user"}, &created)
	id := created.ID
	callTool(t, sess, "admin_create_user", map[string]any{"username": "mcp-test-user", "password": "Secret-admin-create", "idempotency_key": "create-user"}, &created)
	if created.ID != id {
		t.Fatal("admin create replay failed")
	}
	callTool(t, sess, "admin_set_quota", map[string]any{"user_id": id, "quota_bytes": 2048}, &created)
	if created.QuotaBytes != 2048 {
		t.Fatal("quota failed")
	}
	callTool(t, sess, "admin_set_disabled", map[string]any{"user_id": id, "disabled": true}, &created)
	if !created.Disabled {
		t.Fatal("disable failed")
	}
	callToolErr(t, sess, "admin_set_disabled", map[string]any{"user_id": e.alice.String(), "disabled": true})
	var password AdminPasswordOutput
	callTool(t, sess, "admin_reset_password", map[string]any{"user_id": id}, &password)
	if len(password.Password) < 12 {
		t.Fatal("password reset failed")
	}
	var tasks AdminTasksOutput
	callTool(t, sess, "admin_list_tasks", map[string]any{}, &tasks)
	var count CountOutput
	callTool(t, sess, "admin_retry_failed_tasks", map[string]any{}, &count)
	folder, _ := e.nodes.CreateFolder(ctx, e.bob, nil, "purge-me")
	_ = e.nodes.Delete(ctx, e.bob, []uuid.UUID{folder.ID})
	args := map[string]any{"user_id": e.bob.String(), "node_ids": []string{folder.ID.String()}, "dry_run": true}
	var preview AdminPurgeOutput
	callTool(t, sess, "admin_purge_nodes", args, &preview)
	if _, err = e.nodes.Get(ctx, e.bob, folder.ID); err != nil {
		t.Fatal("purge preview deleted data")
	}
	args["dry_run"] = false
	callToolErr(t, sess, "admin_purge_nodes", args)
	args["confirm"] = true
	callTool(t, sess, "admin_purge_nodes", args, &preview)
	if _, err = e.nodes.Get(ctx, e.bob, folder.ID); err == nil {
		t.Fatal("purge did not delete")
	}
	var audit AuditOutput
	callTool(t, sess, "admin_list_audit", map[string]any{"limit": 100}, &audit)
	b, _ = json.Marshal(audit)
	if strings.Contains(string(b), password.Password) || strings.Contains(string(b), "Secret-admin-create") {
		t.Fatal("admin audit leaked password")
	}
	_, _ = e.pool.Exec(ctx, `UPDATE users SET is_admin=false WHERE id=$1`, e.alice)
	if got := e.rawPost(t, "Bearer "+adminToken); got != 403 {
		t.Fatalf("demoted admin retained access: %d", got)
	}
	_ = mcp.Implementation{}
}
