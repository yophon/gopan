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
func (s *Server) registerControlTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "storage_status", Description: "Get account quota, used/reserved/available bytes, active uploads and transfer limits. Available space is advisory; final commit enforces quota."}, s.storageStatus)
	addTool(s, server, &mcp.Tool{Name: "list_audit", Description: "Read MCP operation history without passwords or tokens. Directory-restricted credentials see only their own events."}, s.listAudit)
	addTool(s, server, &mcp.Tool{Name: "list_shares", Description: "List active shares owned by this account and within the credential directory."}, s.listShares)
	addTool(s, server, &mcp.Tool{Name: "create_share", Description: "Create a public share URL with optional password and future expiry. Requires shares:write; supports idempotency."}, mutation(s, "create_share", "shares:write", (*Server).createShare))
	addTool(s, server, &mcp.Tool{Name: "revoke_share", Description: "Revoke a share URL immediately. Requires shares:write; supports idempotency."}, mutation(s, "revoke_share", "shares:write", (*Server).revokeShare))
}
