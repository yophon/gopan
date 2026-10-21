package mcpserver

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

// Agent 用 fetch_url 让服务器去下载,用 get_job 等结果;限定了访问目录的凭据只能存进目录、只看得到目录里的任务。
func TestMCPFetchURL(t *testing.T) {
	e := setupEnv(t)
	ctx := t.Context()
	src := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", `attachment; filename="report.csv"`)
		w.Write([]byte("a,b\n1,2\n"))
	}))
	defer src.Close()

	plain, _, err := e.tokens.Create(ctx, e.alice, "fetcher", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, plain)
	var job JobOutput
	callTool(t, sess, "fetch_url", map[string]any{"url": src.URL + "/x?token=secret"}, &job)
	if job.Status != "queued" || job.Kind != "fetch_url" || job.URL == nil {
		t.Fatalf("fetch_url: %+v", job)
	}
	if ok, err := e.jobs.RunOnce(ctx); !ok || err != nil {
		t.Fatalf("RunOnce %v %v", ok, err)
	}
	var done JobOutput
	callTool(t, sess, "get_job", map[string]any{"job_id": job.ID, "wait": true}, &done)
	if done.Status != "done" || done.Title != "report.csv" || done.ResultNodeID == nil || done.BytesDone != 8 {
		t.Fatalf("get_job: %+v", done)
	}
	var info NodeOutput
	callTool(t, sess, "get_file_info", map[string]any{"node_id": *done.ResultNodeID}, &info)
	if info.Name != "report.csv" || info.ParentID != nil {
		t.Fatalf("file: %+v", info)
	}
	var list ListJobsOutput
	callTool(t, sess, "list_jobs", map[string]any{}, &list)
	if len(list.Items) != 1 || list.Items[0].ID != job.ID {
		t.Fatalf("list_jobs: %+v", list)
	}
	// 审计里不记链接(可能带签名)
	var args string
	if err := e.pool.QueryRow(ctx, `SELECT targets::text FROM mcp_audit WHERE tool = 'fetch_url' LIMIT 1`).Scan(&args); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(args, "secret") || strings.Contains(args, src.URL) {
		t.Fatalf("审计带了链接:%s", args)
	}
	// 内网地址被拒(真实 Guard 下)、没有 files:upload 被拒
	readOnly, _, _ := e.tokens.Create(ctx, e.alice, "ro", []string{"files:read"})
	if msg := callToolErr(t, e.connect(t, readOnly), "fetch_url", map[string]any{"url": "https://example.com/a"}); !strings.Contains(msg, "FORBIDDEN") && !strings.Contains(msg, "权限") {
		t.Fatalf("只读凭据应拒:%s", msg)
	}

	// 限定访问目录:不给目标就存进目录;根目录下的任务看不到
	root, _ := e.nodes.CreateFolder(ctx, e.alice, nil, "agent")
	token, key, err := e.tokens.Create(ctx, e.alice, "scoped", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	path := "/agent"
	if err := e.nodes.SetMCPAccessRoot(ctx, e.alice, key.ID, "api_key", &path); err != nil {
		t.Fatal(err)
	}
	scoped := e.connect(t, token)
	var sj JobOutput
	callTool(t, scoped, "fetch_url", map[string]any{"url": src.URL + "/y"}, &sj)
	got, err := e.jobs.Get(ctx, e.alice, mustUUID(t, sj.ID))
	if err != nil || got.ParentID == nil || *got.ParentID != root.ID {
		t.Fatalf("默认目标应是授权目录:%+v %v", got, err)
	}
	var scopedList ListJobsOutput
	callTool(t, scoped, "list_jobs", map[string]any{}, &scopedList)
	if len(scopedList.Items) != 1 || scopedList.Items[0].ID != sj.ID {
		t.Fatalf("受限凭据看到了目录外的任务:%+v", scopedList)
	}
	if msg := callToolErr(t, scoped, "get_job", map[string]any{"job_id": job.ID}); !strings.Contains(msg, "NOT_FOUND") && !strings.Contains(msg, "不存在") {
		t.Fatalf("目录外的任务应 NOT_FOUND:%s", msg)
	}
	outside, _ := e.nodes.CreateFolder(ctx, e.alice, nil, "outside")
	if msg := callToolErr(t, scoped, "fetch_url", map[string]any{"url": src.URL, "parent_id": outside.ID.String()}); msg == "" {
		t.Fatal("存到目录外应拒")
	}
}

func mustUUID(t *testing.T, s string) uuid.UUID {
	t.Helper()
	id, err := uuid.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
