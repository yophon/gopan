package mcpserver

// 纯函数与认证边界的单元测试:不依赖 Postgres / MinIO,始终运行。
// 集成路径(真 DB + 真 MinIO)见 integration_test.go。

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

// wantCode 断言 err 是携带指定 code 的 *service.Error。
func wantCode(t *testing.T, err error, code string) {
	t.Helper()
	se, ok := err.(*service.Error)
	if !ok || se.Code != code {
		t.Fatalf("期望 %s,got %v", code, err)
	}
}

func TestParseIDHelpers(t *testing.T) {
	valid := uuid.Must(uuid.NewV7())

	// parseID:合法 / 非法
	if got, err := parseID(valid.String()); err != nil || got != valid {
		t.Fatalf("parseID 合法输入失败:%v %v", got, err)
	}
	_, err := parseID("not-a-uuid")
	wantCode(t, err, "INVALID_INPUT")

	// parseOptionalID:nil、空串都视为"未提供"
	if got, err := parseOptionalID(nil); err != nil || got != nil {
		t.Fatalf("parseOptionalID(nil) 应返回 nil:%v %v", got, err)
	}
	empty := ""
	if got, err := parseOptionalID(&empty); err != nil || got != nil {
		t.Fatalf("parseOptionalID(空串) 应返回 nil:%v %v", got, err)
	}
	s := valid.String()
	if got, err := parseOptionalID(&s); err != nil || got == nil || *got != valid {
		t.Fatalf("parseOptionalID 合法输入失败:%v %v", got, err)
	}
	bad := "xyz"
	_, err = parseOptionalID(&bad)
	wantCode(t, err, "INVALID_INPUT")

	// parseIDs:合法列表 / 混入非法项 / 空列表
	a, b := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	ids, err := parseIDs([]string{a.String(), b.String()})
	if err != nil || len(ids) != 2 || ids[0] != a || ids[1] != b {
		t.Fatalf("parseIDs 合法输入失败:%v %v", ids, err)
	}
	_, err = parseIDs([]string{a.String(), "oops"})
	wantCode(t, err, "INVALID_INPUT")
	_, err = parseIDs(nil)
	wantCode(t, err, "INVALID_INPUT")
	_, err = parseIDs([]string{})
	wantCode(t, err, "INVALID_INPUT")
}

func TestIDStringAndNodeConversions(t *testing.T) {
	if idString(nil) != nil {
		t.Fatal("idString(nil) 应为 nil")
	}
	id := uuid.Must(uuid.NewV7())
	if got := idString(&id); got == nil || *got != id.String() {
		t.Fatalf("idString 不符:%v", got)
	}

	parent := uuid.Must(uuid.NewV7())
	ts := pgtype.Timestamptz{Time: time.Date(2026, 7, 13, 8, 0, 0, 0, time.UTC), Valid: true}
	wantTime := "2026-07-13T08:00:00Z"

	// nodeFromStore:folder 带 subtree 统计,file 不带
	folder := store.Node{ID: id, ParentID: &parent, Name: "docs", Kind: "folder",
		UpdatedAt: ts, SubtreeBytes: 42, SubtreeCount: 3, StatsStale: true}
	out := nodeFromStore(folder)
	if out.ID != id.String() || *out.ParentID != parent.String() || out.Name != "docs" ||
		out.UpdatedAt != wantTime || out.SubtreeBytes == nil || *out.SubtreeBytes != 42 ||
		*out.SubtreeCount != 3 || !*out.StatsStale {
		t.Fatalf("nodeFromStore(folder) 不符:%+v", out)
	}
	file := store.Node{ID: id, Name: "a.txt", Kind: "file", UpdatedAt: ts}
	out = nodeFromStore(file)
	if out.ParentID != nil || out.SubtreeBytes != nil || out.SubtreeCount != nil || out.StatsStale != nil {
		t.Fatalf("nodeFromStore(file) 不应带 subtree 字段:%+v", out)
	}

	// nodeFromList / nodeFromSearch:file 带 blob 元数据,folder 带 subtree 统计
	size, mime, sha := int64(7), "text/plain", strings.Repeat("a", 64)
	lf := store.ListChildrenRow{ID: id, Name: "a.txt", Kind: "file", UpdatedAt: ts,
		BlobSize: &size, BlobMime: &mime, BlobSha256: &sha}
	lo := nodeFromList(lf)
	if *lo.Size != 7 || *lo.Mime != "text/plain" || *lo.SHA256 != sha || lo.SubtreeBytes != nil {
		t.Fatalf("nodeFromList(file) 不符:%+v", lo)
	}
	lo = nodeFromList(store.ListChildrenRow{ID: id, Name: "d", Kind: "folder", UpdatedAt: ts, SubtreeBytes: 9, SubtreeCount: 1})
	if lo.SubtreeBytes == nil || *lo.SubtreeBytes != 9 || *lo.SubtreeCount != 1 {
		t.Fatalf("nodeFromList(folder) 不符:%+v", lo)
	}

	sf := store.SearchNodesRow{ID: id, Name: "a.txt", Kind: "file", UpdatedAt: ts,
		BlobSize: &size, BlobMime: &mime, BlobSha256: &sha}
	so := nodeFromSearch(sf)
	if *so.Size != 7 || *so.SHA256 != sha || so.SubtreeBytes != nil {
		t.Fatalf("nodeFromSearch(file) 不符:%+v", so)
	}
	so = nodeFromSearch(store.SearchNodesRow{ID: id, Name: "d", Kind: "folder", UpdatedAt: ts, SubtreeBytes: 9, SubtreeCount: 1})
	if so.SubtreeBytes == nil || *so.SubtreeBytes != 9 {
		t.Fatalf("nodeFromSearch(folder) 不符:%+v", so)
	}
}

func TestUploadAndTransferOutput(t *testing.T) {
	// 秒传分支:有 Node 即 instant/ready
	node := store.Node{ID: uuid.Must(uuid.NewV7()), Name: "a.txt", Kind: "file",
		UpdatedAt: pgtype.Timestamptz{Time: time.Now(), Valid: true}}
	out := uploadOutput(&service.AgentUploadInit{Mode: "single_put", Node: &node})
	if out.Mode != "instant" || out.Status != "ready" || out.Node == nil || out.Node.ID != node.ID.String() {
		t.Fatalf("uploadOutput(instant) 不符:%+v", out)
	}

	// 直传分支:part URL 按 part number 升序,node_id 回填节点
	sessID, nodeID := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	sha, reason := strings.Repeat("b", 64), "boom"
	sess := store.TransferSession{
		ID: sessID, TargetName: "big.bin", Status: "uploading", PartSize: 5 << 20,
		NodeID: &nodeID, ComputedSha256: &sha, FailReason: &reason,
		ExpiresAt: pgtype.Timestamptz{Time: time.Date(2026, 7, 13, 9, 0, 0, 0, time.UTC), Valid: true},
	}
	view := &service.AgentTransferView{
		Session: sess, TotalParts: 3, UploadedParts: []int{2},
		PartURLs: map[int]string{3: "u3", 1: "u1", 2: "u2"},
	}
	out = uploadOutput(&service.AgentUploadInit{Mode: "multipart", Transfer: view})
	if out.Mode != "multipart" || out.UploadID != sessID.String() || out.Status != "uploading" ||
		out.PartSize != 5<<20 || out.TotalParts != 3 || out.ExpiresAt != "2026-07-13T09:00:00Z" ||
		*out.SHA256 != sha || *out.Failure != "boom" {
		t.Fatalf("transferOutput 元数据不符:%+v", out)
	}
	if len(out.PartURLs) != 3 || out.PartURLs[0].PartNumber != 1 || out.PartURLs[1].PartNumber != 2 ||
		out.PartURLs[2].PartNumber != 3 || out.PartURLs[2].URL != "u3" {
		t.Fatalf("part URL 应升序:%+v", out.PartURLs)
	}
	if out.Node == nil || out.Node.ID != nodeID.String() || out.Node.Name != "big.bin" || out.Node.Kind != "file" {
		t.Fatalf("transferOutput 应回填节点:%+v", out.Node)
	}

	// 无 node_id 时不带节点
	sess.NodeID = nil
	out = transferOutput("single_put", &service.AgentTransferView{Session: sess, PutURL: "put-url", TotalParts: 1})
	if out.Node != nil || out.PutURL != "put-url" || len(out.PartURLs) != 0 {
		t.Fatalf("单 PUT 视图不符:%+v", out)
	}
}

func TestAuthenticateRejectsUnknownScheme(t *testing.T) {
	// 未知前缀在查库前就应拒绝(tokens/oauth 传 nil 也不该被触碰)
	s := &Server{}
	for _, bearer := range []string{"", "random-token", "gopan_share_xxx", "Bearer gopan_key_x"} {
		if _, err := s.authenticate(context.Background(), bearer); err != service.ErrUnauthenticated {
			t.Fatalf("bearer=%q 应 ErrUnauthenticated,got %v", bearer, err)
		}
	}
}

func TestWithAuthRejectsBadAuthorizationHeader(t *testing.T) {
	h := NewHandler(nil, nil, service.NewMCPTokens(nil), service.NewOAuth(nil), nil, nil)
	cases := []struct{ name, header string }{
		{"非 Bearer 方案", "Basic dXNlcjpwYXNz"},
		{"未知前缀 token", "Bearer definitely-not-a-gopan-token"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, "http://pan.example.com/mcp", nil)
			req.Header.Set("Authorization", tc.header)
			h.ServeHTTP(rec, req)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("应 401,got %d", rec.Code)
			}
			// RFC 9728:401 必须回 WWW-Authenticate 指向资源元数据,agent 靠它发现 OAuth 入口
			www := rec.Header().Get("WWW-Authenticate")
			if !strings.Contains(www, "resource_metadata=") ||
				!strings.Contains(www, "http://pan.example.com/.well-known/oauth-protected-resource") {
				t.Fatalf("WWW-Authenticate 不符:%q", www)
			}
		})
	}
}

// TestToolScopeEnforcement 逐一验证 16 个工具的 scope 门禁:
// 缺对应 scope → FORBIDDEN;完全没有 principal → UNAUTHENTICATED。
// scope 检查在触库前完成,所以用零值 Server 即可。
func TestToolScopeEnforcement(t *testing.T) {
	s := &Server{}
	cases := []struct {
		tool  string
		scope string
		call  func(ctx context.Context) error
	}{
		{"list_files", "files:read", func(ctx context.Context) error {
			_, _, err := s.listFiles(ctx, nil, ListFilesInput{})
			return err
		}},
		{"search_files", "files:read", func(ctx context.Context) error {
			_, _, err := s.searchFiles(ctx, nil, SearchFilesInput{Query: "x"})
			return err
		}},
		{"get_file_info", "files:read", func(ctx context.Context) error {
			_, _, err := s.getFileInfo(ctx, nil, NodeIDInput{NodeID: uuid.NewString()})
			return err
		}},
		{"read_text_file", "files:download", func(ctx context.Context) error {
			_, _, err := s.readTextFile(ctx, nil, ReadTextInput{NodeID: uuid.NewString()})
			return err
		}},
		{"create_folder", "files:write", func(ctx context.Context) error {
			_, _, err := s.createFolder(ctx, nil, CreateFolderInput{Name: "x"})
			return err
		}},
		{"rename_node", "files:write", func(ctx context.Context) error {
			_, _, err := s.renameNode(ctx, nil, RenameInput{NodeID: uuid.NewString(), Name: "y"})
			return err
		}},
		{"move_nodes", "files:write", func(ctx context.Context) error {
			_, _, err := s.moveNodes(ctx, nil, MoveCopyInput{NodeIDs: []string{uuid.NewString()}})
			return err
		}},
		{"copy_nodes", "files:write", func(ctx context.Context) error {
			_, _, err := s.copyNodes(ctx, nil, MoveCopyInput{NodeIDs: []string{uuid.NewString()}})
			return err
		}},
		{"trash_nodes", "files:delete", func(ctx context.Context) error {
			_, _, err := s.trashNodes(ctx, nil, NodeIDsInput{NodeIDs: []string{uuid.NewString()}})
			return err
		}},
		{"restore_nodes", "files:delete", func(ctx context.Context) error {
			_, _, err := s.restoreNodes(ctx, nil, NodeIDsInput{NodeIDs: []string{uuid.NewString()}})
			return err
		}},
		{"prepare_upload", "files:upload", func(ctx context.Context) error {
			_, _, err := s.prepareUpload(ctx, nil, PrepareUploadInput{Name: "a", Size: 1})
			return err
		}},
		{"get_upload_parts", "files:upload", func(ctx context.Context) error {
			_, _, err := s.getUploadParts(ctx, nil, UploadPartsInput{UploadID: uuid.NewString()})
			return err
		}},
		{"complete_upload", "files:upload", func(ctx context.Context) error {
			_, _, err := s.completeUpload(ctx, nil, UploadPartsInput{UploadID: uuid.NewString()})
			return err
		}},
		{"get_upload_status", "files:upload", func(ctx context.Context) error {
			_, _, err := s.getUploadStatus(ctx, nil, UploadPartsInput{UploadID: uuid.NewString()})
			return err
		}},
		{"abort_upload", "files:upload", func(ctx context.Context) error {
			_, _, err := s.abortUpload(ctx, nil, UploadPartsInput{UploadID: uuid.NewString()})
			return err
		}},
		{"prepare_download", "files:download", func(ctx context.Context) error {
			_, _, err := s.prepareDownload(ctx, nil, PrepareDownloadInput{NodeIDs: []string{uuid.NewString()}})
			return err
		}},
	}
	for _, tc := range cases {
		t.Run(tc.tool, func(t *testing.T) {
			// 没有 principal → UNAUTHENTICATED
			if err := tc.call(context.Background()); err != service.ErrUnauthenticated {
				t.Fatalf("无 principal 应 UNAUTHENTICATED,got %v", err)
			}
			// 授予除所需 scope 之外的全部 scope → FORBIDDEN(证明缺的正是这个)
			scopes := map[string]struct{}{}
			for _, sc := range service.MCPScopes {
				if sc != tc.scope {
					scopes[sc] = struct{}{}
				}
			}
			ctx := context.WithValue(context.Background(), principalKey{},
				&service.MCPPrincipal{UserID: uuid.Must(uuid.NewV7()), Scopes: scopes})
			if err := tc.call(ctx); err != service.ErrForbidden {
				t.Fatalf("缺 %s 应 FORBIDDEN,got %v", tc.scope, err)
			}
		})
	}
}
