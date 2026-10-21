package mcpserver

// 直链的工具(doc/22 四.4):给文件生成永久公开地址、列出、吊销。scope 与分享工具相同。

import (
	"context"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

type LinkOutput struct {
	ID         string     `json:"id"`
	NodeID     string     `json:"node_id"`
	Name       string     `json:"name"`
	URL        string     `json:"url" jsonschema:"permanent public URL; images can be embedded directly, e.g. in Markdown"`
	Markdown   string     `json:"markdown"`
	Status     string     `json:"status" jsonschema:"ACTIVE, PAUSED, EXPIRED or UNAVAILABLE"`
	HasGPS     bool       `json:"has_gps,omitempty" jsonschema:"the JPEG carries the shooting location; warn the user before publishing it"`
	HitsTotal  int64      `json:"hits_total"`
	BytesTotal int64      `json:"bytes_total"`
	CreatedAt  time.Time  `json:"created_at"`
	ExpiresAt  *time.Time `json:"expires_at,omitempty"`
}

type LinksOutput struct {
	Items      []LinkOutput `json:"items"`
	NextCursor *string      `json:"next_cursor,omitempty"`
}

func (s *Server) linkOutput(ctx context.Context, v service.LinkView) LinkOutput {
	u := s.links.URL(v.Token, v.NodeName)
	if strings.HasPrefix(u, "/") {
		base, _ := ctx.Value(baseURLKey{}).(string)
		u = base + u
	}
	md := "[" + v.NodeName + "](" + u + ")"
	if v.Mime != nil && strings.HasPrefix(*v.Mime, "image/") {
		md = "!" + md
	}
	out := LinkOutput{
		ID: v.ID.String(), NodeID: v.NodeID.String(), Name: v.NodeName, URL: u, Markdown: md,
		Status: s.links.Status(v), HasGPS: v.HasGps, HitsTotal: v.HitsTotal, BytesTotal: v.BytesTotal,
		CreatedAt: v.CreatedAt.Time,
	}
	if v.ExpiresAt.Valid {
		out.ExpiresAt = &v.ExpiresAt.Time
	}
	return out
}

type CreateLinkInput struct {
	NodeID         string  `json:"node_id,omitempty"`
	Path           *string `json:"path,omitempty"`
	IdempotencyKey string  `json:"idempotency_key,omitempty"`
}

func (s *Server) createLink(ctx context.Context, _ *mcp.CallToolRequest, in CreateLinkInput) (*mcp.CallToolResult, LinkOutput, error) {
	p, err := principal(ctx, "shares:write")
	if err != nil {
		return nil, LinkOutput{}, err
	}
	if s.links == nil || !s.links.Enabled() {
		return nil, LinkOutput{}, &service.Error{Code: "LINKS_DISABLED", Message: "本站没有开直链"}
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, LinkOutput{}, err
	}
	if err := s.nodes.CheckMCPNode(ctx, p, id, true); err != nil {
		return nil, LinkOutput{}, err
	}
	vs, err := s.links.CreateLinks(ctx, p.UserID, []uuid.UUID{id}, service.LinkMeta{Source: "mcp"})
	if err != nil {
		return nil, LinkOutput{}, err
	}
	return nil, s.linkOutput(ctx, vs[0]), nil
}

type ListLinksInput struct {
	Cursor *string `json:"cursor,omitempty"`
}

func (s *Server) listLinks(ctx context.Context, _ *mcp.CallToolRequest, in ListLinksInput) (*mcp.CallToolResult, LinksOutput, error) {
	p, err := principal(ctx, "shares:read")
	if err != nil {
		return nil, LinksOutput{}, err
	}
	if s.links == nil {
		return nil, LinksOutput{Items: []LinkOutput{}}, nil
	}
	vs, next, _, err := s.links.List(ctx, p.UserID, in.Cursor)
	if err != nil {
		return nil, LinksOutput{}, err
	}
	out := LinksOutput{Items: make([]LinkOutput, 0, len(vs)), NextCursor: next}
	for _, v := range vs {
		if err := s.nodes.CheckMCPNode(ctx, p, v.NodeID, true); err != nil {
			continue
		}
		out.Items = append(out.Items, s.linkOutput(ctx, v))
	}
	return nil, out, nil
}

type RevokeLinkInput struct {
	LinkID         string `json:"link_id"`
	IdempotencyKey string `json:"idempotency_key,omitempty"`
}

func (s *Server) revokeLink(ctx context.Context, _ *mcp.CallToolRequest, in RevokeLinkInput) (*mcp.CallToolResult, SuccessOutput, error) {
	p, err := principal(ctx, "shares:write")
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	if s.links == nil {
		return nil, SuccessOutput{}, service.ErrNotFound
	}
	id, err := parseID(in.LinkID)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	v, err := s.links.Get(ctx, p.UserID, id)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	if err := s.nodes.CheckMCPNode(ctx, p, v.NodeID, true); err != nil {
		return nil, SuccessOutput{}, err
	}
	if err := s.links.Revoke(ctx, p.UserID, id); err != nil {
		return nil, SuccessOutput{}, err
	}
	return nil, SuccessOutput{Success: true}, nil
}

func (s *Server) registerLinkTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "create_link", Description: "Create (or return the existing) permanent public direct URL for one file, usable in web pages and Markdown; images can be embedded directly. Access is logged and capped per day. Requires shares:write; supports idempotency."}, mutation(s, "create_link", "shares:write", (*Server).createLink))
	addTool(s, server, &mcp.Tool{Name: "list_links", Description: "List active direct URLs with access counts, newest first, with cursor pagination. Requires shares:read."}, s.listLinks)
	addTool(s, server, &mcp.Tool{Name: "revoke_link", Description: "Revoke a direct URL immediately; it starts returning 404. Requires shares:write; supports idempotency."}, mutation(s, "revoke_link", "shares:write", (*Server).revokeLink))
}
