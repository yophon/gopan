package mcpserver

import (
	"context"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/yophon/gopan/server/internal/service"
)

type EmptyInput struct{}

func (s *Server) storageStatus(ctx context.Context, _ *mcp.CallToolRequest, _ EmptyInput) (*mcp.CallToolResult, service.MCPStatus, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, service.MCPStatus{}, err
	}
	out, err := s.nodes.MCPStatus(ctx, p, s.uploads.MCPPartSize())
	return nil, out, err
}

type AuditInput struct {
	BeforeID int64 `json:"before_id,omitempty" jsonschema:"last event ID from previous page"`
	Limit    int   `json:"limit,omitempty" jsonschema:"default 50, maximum 200"`
}
type AuditOutput struct {
	Items        []service.MCPAuditItem `json:"items"`
	NextBeforeID int64                  `json:"next_before_id,omitempty"`
}

func (s *Server) listAudit(ctx context.Context, _ *mcp.CallToolRequest, in AuditInput) (*mcp.CallToolResult, AuditOutput, error) {
	scope := "audit:read"
	if s.admin != nil {
		scope = "admin:read"
	}
	p, err := principal(ctx, scope)
	if err != nil {
		return nil, AuditOutput{}, err
	}
	items, err := s.nodes.MCPAudit(ctx, p, in.BeforeID, in.Limit)
	out := AuditOutput{Items: items}
	if len(items) > 0 {
		out.NextBeforeID = items[len(items)-1].ID
	}
	return nil, out, err
}

type ShareOutput struct {
	ID          string     `json:"id"`
	NodeID      string     `json:"node_id"`
	URL         string     `json:"url"`
	Name        string     `json:"name,omitempty"`
	HasPassword bool       `json:"has_password"`
	ExpiresAt   *time.Time `json:"expires_at,omitempty"`
}
type SharesOutput struct {
	Items []ShareOutput `json:"items"`
}
type CreateShareInput struct {
	NodeID         string     `json:"node_id,omitempty"`
	Path           *string    `json:"path,omitempty"`
	Password       *string    `json:"password,omitempty"`
	ExpiresAt      *time.Time `json:"expires_at,omitempty"`
	IdempotencyKey string     `json:"idempotency_key,omitempty"`
}

func (s *Server) createShare(ctx context.Context, _ *mcp.CallToolRequest, in CreateShareInput) (*mcp.CallToolResult, ShareOutput, error) {
	p, err := principal(ctx, "shares:write")
	if err != nil {
		return nil, ShareOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, ShareOutput{}, err
	}
	sh, err := s.nodes.MCPShares().Create(ctx, p.UserID, id, in.Password, in.ExpiresAt)
	if err != nil {
		return nil, ShareOutput{}, err
	}
	base, _ := ctx.Value(baseURLKey{}).(string)
	out := ShareOutput{ID: sh.ID.String(), NodeID: sh.NodeID.String(), URL: base + "/s/" + sh.Token, HasPassword: sh.PasswordHash != nil}
	if sh.ExpiresAt.Valid {
		out.ExpiresAt = &sh.ExpiresAt.Time
	}
	return nil, out, nil
}
func (s *Server) listShares(ctx context.Context, _ *mcp.CallToolRequest, _ EmptyInput) (*mcp.CallToolResult, SharesOutput, error) {
	p, err := principal(ctx, "shares:read")
	if err != nil {
		return nil, SharesOutput{}, err
	}
	rows, err := s.nodes.MCPShares().List(ctx, p.UserID)
	if err != nil {
		return nil, SharesOutput{}, err
	}
	out := SharesOutput{Items: make([]ShareOutput, 0, len(rows))}
	base, _ := ctx.Value(baseURLKey{}).(string)
	for _, sh := range rows {
		if err := s.nodes.CheckMCPNode(ctx, p, sh.NodeID, true); err != nil {
			continue
		}
		item := ShareOutput{ID: sh.ID.String(), NodeID: sh.NodeID.String(), URL: base + "/s/" + sh.Token, Name: sh.NodeName, HasPassword: sh.PasswordHash != nil}
		if sh.ExpiresAt.Valid {
			item.ExpiresAt = &sh.ExpiresAt.Time
		}
		out.Items = append(out.Items, item)
	}
	return nil, out, nil
}

type RevokeShareInput struct {
	ShareID        string `json:"share_id"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

func (s *Server) revokeShare(ctx context.Context, _ *mcp.CallToolRequest, in RevokeShareInput) (*mcp.CallToolResult, SuccessOutput, error) {
	p, err := principal(ctx, "shares:write")
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	id, err := parseID(in.ShareID)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	rows, err := s.nodes.MCPShares().List(ctx, p.UserID)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	found := false
	for _, sh := range rows {
		if sh.ID == id {
			if err = s.nodes.CheckMCPNode(ctx, p, sh.NodeID, true); err != nil {
				return nil, SuccessOutput{}, err
			}
			found = true
			break
		}
	}
	if !found {
		return nil, SuccessOutput{}, service.ErrNotFound
	}
	err = s.nodes.MCPShares().Revoke(ctx, p.UserID, id)
	return nil, SuccessOutput{Success: err == nil}, err
}

// ---- 收集文件链接(doc/20):访客不登录往文件夹里传,只写不读 ----

type FileRequestOutput struct {
	ID              string     `json:"id"`
	FolderID        string     `json:"folder_id"`
	URL             string     `json:"url"`
	Title           string     `json:"title"`
	Note            string     `json:"note,omitempty"`
	FolderName      string     `json:"folder_name,omitempty"`
	HasPassword     bool       `json:"has_password"`
	RequireName     bool       `json:"require_name"`
	GroupByUploader bool       `json:"group_by_uploader"`
	MaxFileSize     *int64     `json:"max_file_size,omitempty"`
	MaxTotalBytes   *int64     `json:"max_total_bytes,omitempty"`
	MaxFiles        *int32     `json:"max_files,omitempty"`
	ExpiresAt       *time.Time `json:"expires_at,omitempty"`
	FileCount       int64      `json:"file_count"`
	TotalBytes      int64      `json:"total_bytes"`
	NewCount        int64      `json:"new_count"`
}
type FileRequestsOutput struct {
	Items []FileRequestOutput `json:"items"`
}
type CreateFileRequestInput struct {
	FolderID        string     `json:"folder_id,omitempty"`
	Path            *string    `json:"path,omitempty" jsonschema:"folder path, alternative to folder_id"`
	Title           string     `json:"title" jsonschema:"shown to uploaders, up to 100 characters"`
	Note            string     `json:"note,omitempty" jsonschema:"instructions shown to uploaders"`
	Password        *string    `json:"password,omitempty"`
	RequireName     bool       `json:"require_name,omitempty" jsonschema:"uploaders must enter a name"`
	GroupByUploader bool       `json:"group_by_uploader,omitempty" jsonschema:"put each uploader's files in a subfolder named after them (implies require_name)"`
	MaxFileSize     *int64     `json:"max_file_size,omitempty" jsonschema:"bytes per file"`
	MaxTotalBytes   *int64     `json:"max_total_bytes,omitempty" jsonschema:"bytes in total across all uploads"`
	MaxFiles        *int32     `json:"max_files,omitempty"`
	ExpiresAt       *time.Time `json:"expires_at,omitempty" jsonschema:"deadline; uploads started before it may finish"`
	IdempotencyKey  string     `json:"idempotency_key,omitempty"`
}

func fileRequestOutput(base string, r service.FileRequestRow) FileRequestOutput {
	out := FileRequestOutput{
		ID: r.ID.String(), FolderID: r.FolderID.String(), URL: base + "/c/" + r.Token,
		Title: r.Title, Note: r.Note, FolderName: r.FolderName, HasPassword: r.PasswordHash != nil,
		RequireName: r.RequireName || r.GroupByUploader, GroupByUploader: r.GroupByUploader,
		MaxFileSize: r.MaxFileSize, MaxTotalBytes: r.MaxTotalBytes, MaxFiles: r.MaxFiles,
		FileCount: r.FileCount, TotalBytes: r.TotalBytes, NewCount: r.NewCount,
	}
	if r.ExpiresAt.Valid {
		out.ExpiresAt = &r.ExpiresAt.Time
	}
	return out
}

func (s *Server) createFileRequest(ctx context.Context, _ *mcp.CallToolRequest, in CreateFileRequestInput) (*mcp.CallToolResult, FileRequestOutput, error) {
	p, err := principal(ctx, "shares:write")
	if err != nil {
		return nil, FileRequestOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.FolderID, in.Path)
	if err != nil {
		return nil, FileRequestOutput{}, err
	}
	// enforceRoot 只看 node_id / path 字段,folder_id 要在这里自己查访问目录
	if err := s.nodes.CheckMCPNode(ctx, p, id, true); err != nil {
		return nil, FileRequestOutput{}, err
	}
	collects := s.nodes.MCPCollects()
	fr, err := collects.Create(ctx, p.UserID, id, service.FileRequestInput{
		Title: in.Title, Note: in.Note, RequireName: in.RequireName, GroupByUploader: in.GroupByUploader,
		MaxFileSize: in.MaxFileSize, MaxTotalBytes: in.MaxTotalBytes, MaxFiles: in.MaxFiles, ExpiresAt: in.ExpiresAt,
	}, in.Password)
	if err != nil {
		return nil, FileRequestOutput{}, err
	}
	row, err := collects.Get(ctx, p.UserID, fr.ID)
	if err != nil {
		return nil, FileRequestOutput{}, err
	}
	base, _ := ctx.Value(baseURLKey{}).(string)
	return nil, fileRequestOutput(base, row), nil
}

func (s *Server) listFileRequests(ctx context.Context, _ *mcp.CallToolRequest, _ EmptyInput) (*mcp.CallToolResult, FileRequestsOutput, error) {
	p, err := principal(ctx, "shares:read")
	if err != nil {
		return nil, FileRequestsOutput{}, err
	}
	rows, err := s.nodes.MCPCollects().List(ctx, p.UserID)
	if err != nil {
		return nil, FileRequestsOutput{}, err
	}
	base, _ := ctx.Value(baseURLKey{}).(string)
	out := FileRequestsOutput{Items: make([]FileRequestOutput, 0, len(rows))}
	for _, r := range rows {
		if err := s.nodes.CheckMCPNode(ctx, p, r.FolderID, true); err != nil {
			continue
		}
		out.Items = append(out.Items, fileRequestOutput(base, r))
	}
	return nil, out, nil
}

type RevokeFileRequestInput struct {
	RequestID      string `json:"request_id"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

func (s *Server) revokeFileRequest(ctx context.Context, _ *mcp.CallToolRequest, in RevokeFileRequestInput) (*mcp.CallToolResult, SuccessOutput, error) {
	p, err := principal(ctx, "shares:write")
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	id, err := parseID(in.RequestID)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	collects := s.nodes.MCPCollects()
	row, err := collects.Get(ctx, p.UserID, id)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	if err := s.nodes.CheckMCPNode(ctx, p, row.FolderID, true); err != nil {
		return nil, SuccessOutput{}, err
	}
	err = collects.Revoke(ctx, p.UserID, id)
	return nil, SuccessOutput{Success: err == nil}, err
}

func (s *Server) registerControlTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "storage_status", Description: "Get account quota, used/reserved/available bytes, active uploads and transfer limits. Available space is advisory; final commit enforces quota."}, s.storageStatus)
	addTool(s, server, &mcp.Tool{Name: "list_audit", Description: "Read MCP operation history without passwords or tokens. Directory-restricted credentials see only their own events."}, s.listAudit)
	addTool(s, server, &mcp.Tool{Name: "list_shares", Description: "List active shares owned by this account and within the credential directory."}, s.listShares)
	addTool(s, server, &mcp.Tool{Name: "create_share", Description: "Create a public share URL with optional password and future expiry. Requires shares:write; supports idempotency."}, mutation(s, "create_share", "shares:write", (*Server).createShare))
	addTool(s, server, &mcp.Tool{Name: "revoke_share", Description: "Revoke a share URL immediately. Requires shares:write; supports idempotency."}, mutation(s, "revoke_share", "shares:write", (*Server).revokeShare))
	addTool(s, server, &mcp.Tool{Name: "list_file_requests", Description: "List active file request links (anonymous upload links into a folder) with received file counts. Requires shares:read."}, s.listFileRequests)
	addTool(s, server, &mcp.Tool{Name: "create_file_request", Description: "Create a file request link for a folder: anyone with the link can upload files into it without signing in, but cannot see its contents. Optional password, deadline, uploader name, per-uploader subfolders and size/count limits. Uploads count against this account's quota. Requires shares:write; supports idempotency."}, mutation(s, "create_file_request", "shares:write", (*Server).createFileRequest))
	addTool(s, server, &mcp.Tool{Name: "revoke_file_request", Description: "Close a file request link immediately; uploads in progress are rejected. Requires shares:write; supports idempotency."}, mutation(s, "revoke_file_request", "shares:write", (*Server).revokeFileRequest))
}
