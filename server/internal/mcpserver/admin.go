package mcpserver

import (
	"context"
	"net/http"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func NewAdminHandler(nodes *service.Nodes, tokens *service.MCPTokens, oauth *service.OAuth, defaultQuota int64) http.Handler {
	s := &Server{nodes: nodes, tokens: tokens, oauth: oauth, admin: nodes.MCPAdmin(defaultQuota), adminDefaultQuota: defaultQuota}
	protocol := mcp.NewServer(&mcp.Implementation{Name: "gopan-admin", Version: "1.0.0"}, nil)
	addTool(s, protocol, &mcp.Tool{Name: "admin_overview", Description: "Administrator-only instance statistics and task counts."}, s.adminOverview)
	addTool(s, protocol, &mcp.Tool{Name: "admin_list_users", Description: "List account status and quotas; never returns password hashes."}, s.adminUsers)
	addTool(s, protocol, &mcp.Tool{Name: "admin_create_user", Description: "Create an account with password and optional quota; supports idempotency."}, mutation(s, "admin_create_user", "admin:users", (*Server).adminCreateUser))
	addTool(s, protocol, &mcp.Tool{Name: "admin_set_quota", Description: "Set an account quota. Supports idempotency."}, mutation(s, "admin_set_quota", "admin:users", (*Server).adminSetQuota))
	addTool(s, protocol, &mcp.Tool{Name: "admin_set_disabled", Description: "Enable or disable an account; self-disable is rejected. Supports idempotency."}, mutation(s, "admin_set_disabled", "admin:users", (*Server).adminSetDisabled))
	addTool(s, protocol, &mcp.Tool{Name: "admin_reset_password", Description: "Generate a new random password and revoke account login sessions. Response is sensitive; do not automatically retry."}, mutation(s, "admin_reset_password", "admin:users", (*Server).adminResetPassword))
	addTool(s, protocol, &mcp.Tool{Name: "admin_list_tasks", Description: "Inspect task status and attempt counts, with cursor pagination."}, s.adminListTasks)
	addTool(s, protocol, &mcp.Tool{Name: "admin_retry_failed_tasks", Description: "Requeue failed background tasks; supports idempotency."}, mutation(s, "admin_retry_failed_tasks", "admin:tasks", (*Server).adminRetry))
	addTool(s, protocol, &mcp.Tool{Name: "admin_purge_nodes", Description: "Permanently delete selected trashed nodes for a user. Use dry_run first; actual deletion requires confirm=true. Supports idempotency."}, mutation(s, "admin_purge_nodes", "admin:purge", (*Server).adminPurge))
	addTool(s, protocol, &mcp.Tool{Name: "admin_list_audit", Description: "Read instance-wide MCP operation history with account and credential IDs; no passwords, tokens or presigned URLs."}, s.listAudit)
	handler := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return protocol }, &mcp.StreamableHTTPOptions{Stateless: true, JSONResponse: true})
	return s.withAuth(http.NewCrossOriginProtection().Handler(handler))
}

type AdminUserOutput struct {
	ID         string    `json:"id"`
	Username   string    `json:"username"`
	QuotaBytes int64     `json:"quota_bytes"`
	UsedBytes  int64     `json:"used_bytes"`
	IsAdmin    bool      `json:"is_admin"`
	Disabled   bool      `json:"disabled"`
	CreatedAt  time.Time `json:"created_at"`
}

func adminUser(u store.User) AdminUserOutput {
	return AdminUserOutput{ID: u.ID.String(), Username: u.Username, QuotaBytes: u.QuotaBytes, UsedBytes: u.UsedBytes, IsAdmin: u.IsAdmin, Disabled: u.DisabledAt.Valid, CreatedAt: u.CreatedAt.Time}
}
func (s *Server) adminOverview(ctx context.Context, _ *mcp.CallToolRequest, _ EmptyInput) (*mcp.CallToolResult, *service.OverviewView, error) {
	p, err := principal(ctx, "admin:read")
	if err != nil {
		return nil, nil, err
	}
	out, err := s.admin.Overview(ctx, p.UserID)
	return nil, out, err
}

type AdminUsersOutput struct {
	Items []AdminUserOutput `json:"items"`
}

func (s *Server) adminUsers(ctx context.Context, _ *mcp.CallToolRequest, _ EmptyInput) (*mcp.CallToolResult, AdminUsersOutput, error) {
	p, err := principal(ctx, "admin:read")
	if err != nil {
		return nil, AdminUsersOutput{}, err
	}
	rows, err := s.admin.ListUsers(ctx, p.UserID)
	out := AdminUsersOutput{Items: make([]AdminUserOutput, 0, len(rows))}
	for _, u := range rows {
		out.Items = append(out.Items, adminUser(u))
	}
	return nil, out, err
}

type AdminCreateInput struct {
	Username       string `json:"username"`
	Password       string `json:"password"`
	QuotaBytes     *int64 `json:"quota_bytes,omitempty"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

func (s *Server) adminCreateUser(ctx context.Context, _ *mcp.CallToolRequest, in AdminCreateInput) (*mcp.CallToolResult, AdminUserOutput, error) {
	p, err := principal(ctx, "admin:users")
	if err != nil {
		return nil, AdminUserOutput{}, err
	}
	u, err := s.nodes.MCPAdmin(s.adminDefaultQuota).CreateUser(ctx, p.UserID, in.Username, in.Password, in.QuotaBytes)
	return nil, adminUser(u), err
}

type AdminQuotaInput struct {
	UserID         string `json:"user_id"`
	QuotaBytes     int64  `json:"quota_bytes"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

func (s *Server) adminSetQuota(ctx context.Context, _ *mcp.CallToolRequest, in AdminQuotaInput) (*mcp.CallToolResult, AdminUserOutput, error) {
	p, err := principal(ctx, "admin:users")
	if err != nil {
		return nil, AdminUserOutput{}, err
	}
	id, err := parseID(in.UserID)
	if err != nil {
		return nil, AdminUserOutput{}, err
	}
	u, err := s.nodes.MCPAdmin(0).SetQuota(ctx, p.UserID, id, in.QuotaBytes)
	return nil, adminUser(u), err
}

type AdminDisabledInput struct {
	UserID         string `json:"user_id"`
	Disabled       bool   `json:"disabled"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

func (s *Server) adminSetDisabled(ctx context.Context, _ *mcp.CallToolRequest, in AdminDisabledInput) (*mcp.CallToolResult, AdminUserOutput, error) {
	p, err := principal(ctx, "admin:users")
	if err != nil {
		return nil, AdminUserOutput{}, err
	}
	id, err := parseID(in.UserID)
	if err != nil {
		return nil, AdminUserOutput{}, err
	}
	u, err := s.nodes.MCPAdmin(0).SetDisabled(ctx, p.UserID, id, in.Disabled)
	return nil, adminUser(u), err
}

type AdminResetInput struct {
	UserID string `json:"user_id"`
}
type AdminPasswordOutput struct {
	Password string `json:"password"`
}

func (s *Server) adminResetPassword(ctx context.Context, _ *mcp.CallToolRequest, in AdminResetInput) (*mcp.CallToolResult, AdminPasswordOutput, error) {
	p, err := principal(ctx, "admin:users")
	if err != nil {
		return nil, AdminPasswordOutput{}, err
	}
	id, err := parseID(in.UserID)
	if err != nil {
		return nil, AdminPasswordOutput{}, err
	}
	password, err := s.nodes.MCPAdmin(0).ResetPassword(ctx, p.UserID, id)
	return nil, AdminPasswordOutput{Password: password}, err
}

type AdminRetryInput struct {
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}
type CountOutput struct {
	Count int64 `json:"count"`
}

func (s *Server) adminRetry(ctx context.Context, _ *mcp.CallToolRequest, _ AdminRetryInput) (*mcp.CallToolResult, CountOutput, error) {
	p, err := principal(ctx, "admin:tasks")
	if err != nil {
		return nil, CountOutput{}, err
	}
	count, err := s.nodes.MCPAdmin(0).RetryFailedTasks(ctx, p.UserID)
	return nil, CountOutput{Count: count}, err
}

type AdminPurgeInput struct {
	UserID         string   `json:"user_id"`
	NodeIDs        []string `json:"node_ids"`
	DryRun         bool     `json:"dry_run,omitempty"`
	Confirm        bool     `json:"confirm,omitempty"`
	IdempotencyKey string   `json:"idempotency_key,omitempty"`
}
type AdminPurgeOutput struct {
	DryRun bool         `json:"dry_run"`
	Items  []NodeOutput `json:"items"`
}

func (s *Server) adminPurge(ctx context.Context, _ *mcp.CallToolRequest, in AdminPurgeInput) (*mcp.CallToolResult, AdminPurgeOutput, error) {
	p, err := principal(ctx, "admin:purge")
	if err != nil {
		return nil, AdminPurgeOutput{}, err
	}
	if _, err = s.nodes.MCPAdmin(0).Overview(ctx, p.UserID); err != nil {
		return nil, AdminPurgeOutput{}, err
	}
	if !in.DryRun && !in.Confirm {
		return nil, AdminPurgeOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "永久删除需要 confirm=true；可先 dry_run 预览"}
	}
	owner, err := parseID(in.UserID)
	if err != nil {
		return nil, AdminPurgeOutput{}, err
	}
	ids, err := parseIDs(in.NodeIDs)
	if err != nil {
		return nil, AdminPurgeOutput{}, err
	}
	out := AdminPurgeOutput{DryRun: in.DryRun, Items: make([]NodeOutput, 0, len(ids))}
	for _, id := range ids {
		n, err := s.nodes.Get(ctx, owner, id)
		if err != nil {
			return nil, out, err
		}
		if !n.DeletedAt.Valid {
			return nil, out, &service.Error{Code: "INVALID_INPUT", Message: "只能永久删除回收站节点"}
		}
		out.Items = append(out.Items, nodeFromStore(n))
	}
	err = s.nodes.Purge(ctx, owner, ids)
	return nil, out, err
}

type AdminTasksInput struct {
	Status   string  `json:"status,omitempty"`
	BeforeID *string `json:"before_id,omitempty"`
	Limit    int     `json:"limit,omitempty"`
}
type AdminTasksOutput struct {
	Items        []service.MCPTask `json:"items"`
	NextBeforeID string            `json:"next_before_id,omitempty"`
}

func (s *Server) adminListTasks(ctx context.Context, _ *mcp.CallToolRequest, in AdminTasksInput) (*mcp.CallToolResult, AdminTasksOutput, error) {
	p, err := principal(ctx, "admin:read")
	if err != nil {
		return nil, AdminTasksOutput{}, err
	}
	rows, err := s.nodes.MCPTasks(ctx, p.UserID, in.Status, in.BeforeID, in.Limit)
	out := AdminTasksOutput{Items: rows}
	if len(rows) > 0 {
		out.NextBeforeID = rows[len(rows)-1].ID
	}
	return nil, out, err
}
