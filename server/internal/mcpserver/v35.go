package mcpserver

// 动态与剪藏的工具(doc/23 二.4、五.3):Agent 能查网盘里最近发生了什么、把网页剪藏成 Markdown。
// 不提供撤回工具:撤回由人在网页 / App 上点。

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

type ActivityOutput struct {
	ID         int64   `json:"id"`
	At         string  `json:"at"`
	Actor      string  `json:"actor" jsonschema:"web, android, webdav, agent, img, collect, job, feed, clip or system"`
	ActorLabel string  `json:"actor_label" jsonschema:"device name, app password name, API key / OAuth client name, etc."`
	BatchID    string  `json:"batch_id" jsonschema:"changes made by one request, one tool call or one background job share it"`
	Action     string  `json:"action" jsonschema:"mkdir, upload, overwrite, rename, move, copy, delete, restore, purge or extract"`
	NodeID     *string `json:"node_id,omitempty"`
	Name       string  `json:"name"`
	ParentPath string  `json:"parent_path" jsonschema:"folder the node was in (after a move: the destination)"`
	FromPath   *string `json:"from_path,omitempty"`
	OldName    string  `json:"old_name,omitempty"`
	NewName    string  `json:"new_name,omitempty"`
	Files      int64   `json:"files,omitempty" jsonschema:"files involved (whole subtree for delete / copy / extract)"`
	Bytes      int64   `json:"bytes,omitempty"`
	Undone     bool    `json:"undone,omitempty"`
}

type ListActivityInput struct {
	Actor    string  `json:"actor,omitempty" jsonschema:"only this source kind: web, android, webdav, agent, img, collect, job, feed, clip, system"`
	Action   string  `json:"action,omitempty" jsonschema:"only this action"`
	Since    string  `json:"since,omitempty" jsonschema:"RFC 3339 time; only changes at or after it"`
	BeforeID int64   `json:"before_id,omitempty" jsonschema:"cursor: next_before_id from the previous page"`
	Limit    int     `json:"limit,omitempty" jsonschema:"1-200, default 50"`
	NodeID   *string `json:"node_id,omitempty" jsonschema:"history of one file or folder"`
}

type ActivityListOutput struct {
	Items        []ActivityOutput `json:"items"`
	NextBeforeID int64            `json:"next_before_id,omitempty"`
}

func (s *Server) listActivity(ctx context.Context, _ *mcp.CallToolRequest, in ListActivityInput) (*mcp.CallToolResult, ActivityListOutput, error) {
	p, err := principal(ctx, "audit:read")
	if err != nil {
		return nil, ActivityListOutput{}, err
	}
	if s.activities == nil {
		return nil, ActivityListOutput{Items: []ActivityOutput{}}, nil
	}
	f := service.ActivityFilter{ActorKind: in.Actor}
	if in.Action != "" {
		f.Actions = []string{in.Action}
	}
	if in.Since != "" {
		t, err := time.Parse(time.RFC3339, in.Since)
		if err != nil {
			return nil, ActivityListOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "since 要是 RFC 3339 时间"}
		}
		f.Since = &t
	}
	if in.NodeID != nil && *in.NodeID != "" {
		id, err := uuid.Parse(*in.NodeID)
		if err != nil {
			return nil, ActivityListOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "node_id 不是合法的 UUID"}
		}
		f.NodeID = &id
	}
	var cursor *string
	if in.BeforeID > 0 {
		cursor = service.ActivityCursor(in.BeforeID)
	}
	limit := in.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	rows, _, err := s.activities.List(ctx, p.UserID, f, cursor, limit)
	if err != nil {
		return nil, ActivityListOutput{}, err
	}
	out := ActivityListOutput{Items: make([]ActivityOutput, 0, len(rows))}
	for _, a := range rows {
		out.NextBeforeID = a.ID
		// 限定了授权根目录的凭据:只看根目录里(还在的)节点的动态
		if p.RootID != nil && (a.NodeID == nil || s.nodes.CheckMCPNode(ctx, p, *a.NodeID, true) != nil) {
			continue
		}
		from, _, oldName, newName, files, bytes, _, _ := service.ActivityDetail(a.Detail)
		o := ActivityOutput{
			ID: a.ID, At: a.At.Time.Format(time.RFC3339), Actor: a.ActorKind, ActorLabel: a.ActorLabel, BatchID: a.BatchID.String(),
			Action: a.Action, NodeID: idString(a.NodeID), Name: a.Name, ParentPath: a.ParentPath, FromPath: from,
			OldName: oldName, NewName: newName, Files: files, Bytes: bytes, Undone: a.UndoneAt.Valid,
		}
		out.Items = append(out.Items, o)
	}
	if len(rows) < limit {
		out.NextBeforeID = 0 // 到底了
	}
	return nil, out, nil
}

type ClipURLInput struct {
	URL            string `json:"url" jsonschema:"http(s) address of a web page"`
	Force          bool   `json:"force,omitempty" jsonschema:"clip again even if this address was clipped before"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

type ClipURLOutput struct {
	Job      *JobOutput `json:"job,omitempty" jsonschema:"the clipping job; poll get_job until done, then result_node_id is the .md file"`
	Existing *ClipItem  `json:"existing,omitempty" jsonschema:"already clipped before (and force was not set)"`
}

type ClipItem struct {
	ID     string `json:"id"`
	NodeID string `json:"node_id" jsonschema:"the Markdown file; read it with read_text_file"`
	Title  string `json:"title"`
	URL    string `json:"url"`
	Site   string `json:"site"`
}

func (s *Server) clipURL(ctx context.Context, _ *mcp.CallToolRequest, in ClipURLInput) (*mcp.CallToolResult, ClipURLOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, ClipURLOutput{}, err
	}
	if s.clips == nil {
		return nil, ClipURLOutput{}, &service.Error{Code: "FETCH_DISABLED", Message: "本站没有开启剪藏"}
	}
	if p.RootID != nil {
		return nil, ClipURLOutput{}, service.ErrForbidden // 剪藏存在剪藏文件夹,不一定在授权根目录里
	}
	job, existing, err := s.clips.Submit(ctx, p.UserID, in.URL, nil, in.Force, service.ChatSourceMCP)
	if err != nil {
		return nil, ClipURLOutput{}, err
	}
	var out ClipURLOutput
	if job != nil {
		j := jobOutput(*job)
		out.Job = &j
	}
	if existing != nil {
		out.Existing = &ClipItem{ID: existing.ID.String(), NodeID: existing.NodeID.String(), Title: existing.Title, URL: existing.Url, Site: existing.Site}
	}
	return nil, out, nil
}

func (s *Server) registerV35Tools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "list_activity", Description: "List recent changes to the drive from every source (web, Android app, WebDAV, agents, file requests, background jobs), newest first: who did what to which file and where. Filter by source kind, action, time or one node's history. Paginate with before_id. Undo is only available to the user in the app. For the log of MCP tool calls themselves, use list_audit. Requires audit:read."}, s.listActivity)
	addTool(s, server, &mcp.Tool{Name: "clip_url", Description: "Save a web page's main article as a Markdown file (images downloaded next to it) into the user's clippings folder, in the background. Returns a job (poll get_job until done; result_node_id is the .md, readable with read_text_file), or the existing clipping if this address was clipped before. Not available to credentials limited to a folder. Requires files:upload; supports idempotency."}, mutation(s, "clip_url", "files:upload", (*Server).clipURL))
}
