package mcpserver

// 后台任务的工具(doc/21):离线下载提交、查进度。解压见 archive.go。

import (
	"context"
	"encoding/json"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type JobOutput struct {
	ID           string  `json:"id"`
	Kind         string  `json:"kind" jsonschema:"fetch_url or extract_archive"`
	Status       string  `json:"status" jsonschema:"queued, running, done, failed or canceled"`
	Title        string  `json:"title" jsonschema:"file name once known, otherwise the link's host; archive name for extraction"`
	URL          *string `json:"url,omitempty"`
	ParentID     *string `json:"parent_id,omitempty" jsonschema:"target folder; omitted means the root"`
	ResultNodeID *string `json:"result_node_id,omitempty" jsonschema:"the downloaded file, or the folder files were extracted into"`
	BytesDone    int64   `json:"bytes_done"`
	BytesTotal   *int64  `json:"bytes_total,omitempty"`
	FilesDone    int32   `json:"files_done,omitempty"`
	FilesTotal   *int32  `json:"files_total,omitempty"`
	FilesSkipped int32   `json:"files_skipped,omitempty" jsonschema:"archive entries skipped: unsafe paths, links or invalid names"`
	ErrorCode    *string `json:"error_code,omitempty" jsonschema:"why it failed; on success GOT_HTML means the server returned a web page instead of a file"`
	ErrorMessage *string `json:"error_message,omitempty"`
	CreatedAt    string  `json:"created_at"`
	FinishedAt   *string `json:"finished_at,omitempty"`
}

func jobOutput(j store.UserJob) JobOutput {
	out := JobOutput{
		ID: j.ID.String(), Kind: j.Kind, Status: j.Status, Title: j.Title, ParentID: idString(j.ParentID),
		ResultNodeID: idString(j.ResultNodeID), BytesDone: j.BytesDone, BytesTotal: j.BytesTotal,
		FilesDone: j.FilesDone, FilesTotal: j.FilesTotal, FilesSkipped: j.FilesSkipped,
		ErrorCode: j.ErrorCode, ErrorMessage: j.ErrorMessage, CreatedAt: j.CreatedAt.Time.Format(time.RFC3339),
	}
	if j.Kind == service.JobFetch {
		var p struct {
			URL string `json:"url"`
		}
		if json.Unmarshal(j.Params, &p) == nil && p.URL != "" {
			out.URL = &p.URL
		}
	}
	if j.FinishedAt.Valid {
		t := j.FinishedAt.Time.Format(time.RFC3339)
		out.FinishedAt = &t
	}
	return out
}

type FetchURLInput struct {
	URL        string  `json:"url" jsonschema:"http or https link to a file"`
	ParentID   *string `json:"parent_id,omitempty" jsonschema:"folder to save into; default is the root (or the credential's directory)"`
	ParentPath *string `json:"parent_path,omitempty" jsonschema:"folder path, alternative to parent_id"`
}

func (s *Server) fetchURL(ctx context.Context, _ *mcp.CallToolRequest, in FetchURLInput) (*mcp.CallToolResult, JobOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, JobOutput{}, err
	}
	if s.jobs == nil {
		return nil, JobOutput{}, &service.Error{Code: "FETCH_DISABLED", Message: "本站没有开启离线下载"}
	}
	parent, err := s.parentByPath(ctx, p.UserID, in.ParentID, in.ParentPath)
	if err != nil {
		return nil, JobOutput{}, err
	}
	if parent != nil {
		if err := s.nodes.CheckMCPNode(ctx, p, *parent, true); err != nil {
			return nil, JobOutput{}, err
		}
	} else if p.RootID != nil {
		return nil, JobOutput{}, service.ErrForbidden
	}
	rows, err := s.jobs.FetchURLs(ctx, p.UserID, parent, []string{in.URL}, service.ChatSourceMCP)
	if err != nil {
		return nil, JobOutput{}, err
	}
	return nil, jobOutput(rows[0]), nil
}

type GetJobInput struct {
	JobID string `json:"job_id" jsonschema:"job UUID returned by fetch_url or extract_archive"`
	Wait  bool   `json:"wait,omitempty" jsonschema:"wait up to 25 seconds for the job to finish"`
}

func (s *Server) getJob(ctx context.Context, _ *mcp.CallToolRequest, in GetJobInput) (*mcp.CallToolResult, JobOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, JobOutput{}, err
	}
	if s.jobs == nil {
		return nil, JobOutput{}, service.ErrNotFound
	}
	id, err := parseID(in.JobID)
	if err != nil {
		return nil, JobOutput{}, err
	}
	deadline := time.Now().Add(25 * time.Second)
	for {
		j, err := s.jobs.Get(ctx, p.UserID, id)
		if err != nil {
			return nil, JobOutput{}, err
		}
		if !s.jobVisible(ctx, p, j) {
			return nil, JobOutput{}, service.ErrNotFound
		}
		if !in.Wait || (j.Status != "queued" && j.Status != "running") || time.Now().After(deadline) {
			return nil, jobOutput(j), nil
		}
		select {
		case <-ctx.Done():
			return nil, JobOutput{}, ctx.Err()
		case <-time.After(time.Second):
		}
	}
}

type ListJobsInput struct {
	ActiveOnly bool    `json:"active_only,omitempty" jsonschema:"only queued and running jobs"`
	Cursor     *string `json:"cursor,omitempty"`
}

type ListJobsOutput struct {
	Items      []JobOutput `json:"items"`
	NextCursor *string     `json:"next_cursor,omitempty"`
}

func (s *Server) listJobs(ctx context.Context, _ *mcp.CallToolRequest, in ListJobsInput) (*mcp.CallToolResult, ListJobsOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, ListJobsOutput{}, err
	}
	if s.jobs == nil {
		return nil, ListJobsOutput{Items: []JobOutput{}}, nil
	}
	rows, next, err := s.jobs.List(ctx, p.UserID, in.ActiveOnly, in.Cursor, 0)
	if err != nil {
		return nil, ListJobsOutput{}, err
	}
	out := ListJobsOutput{Items: make([]JobOutput, 0, len(rows)), NextCursor: next}
	for _, r := range rows {
		if s.jobVisible(ctx, p, r) {
			out.Items = append(out.Items, jobOutput(r))
		}
	}
	return nil, out, nil
}

// jobVisible 限定了访问目录的凭据只看得到目标在目录里的任务。
func (s *Server) jobVisible(ctx context.Context, p *service.MCPPrincipal, j store.UserJob) bool {
	if p.RootID == nil {
		return true
	}
	if j.ParentID == nil {
		return false
	}
	return s.nodes.CheckMCPNode(ctx, p, *j.ParentID, true) == nil
}

func (s *Server) registerJobTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "fetch_url", Description: "Offline download: the Gopan server fetches an http(s) link into a folder in the background, so the bytes never pass through the agent. Returns a job; poll get_job (wait=true) until status is done, then result_node_id is the file. Private/internal addresses are refused. Not idempotent: check list_jobs before retrying. Requires files:upload."}, s.fetchURL)
	addTool(s, server, &mcp.Tool{Name: "get_job", Description: "Get the status and progress of a background job (offline download or archive extraction). wait=true blocks up to 25 seconds for it to finish."}, s.getJob)
	addTool(s, server, &mcp.Tool{Name: "list_jobs", Description: "List this account's background jobs (offline downloads, archive extractions), newest first."}, s.listJobs)
}
