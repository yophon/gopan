package mcpserver

import (
	"context"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

type ChatSendInput struct {
	Text           *string `json:"text,omitempty" jsonschema:"message text; required if node_id omitted"`
	NodeID         *string `json:"node_id,omitempty" jsonschema:"file or folder node UUID to attach; required if text omitted"`
	IdempotencyKey string  `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 bytes"`
}

type ChatListInput struct {
	Limit    *int    `json:"limit,omitempty" jsonschema:"maximum messages to return, default 50, cap 200, ordered newest last"`
	BeforeID *string `json:"before_id,omitempty" jsonschema:"exclusive cursor; returns messages strictly older than this message ID"`
}

type ChatMessageOutput struct {
	ID        string  `json:"id"`
	Body      string  `json:"body,omitempty"`
	NodeID    *string `json:"node_id,omitempty"`
	CreatedAt string  `json:"created_at"`
}

type ChatListOutput struct {
	Items []ChatMessageOutput `json:"items"`
}

func toChatMessageOutput(m service.ChatMessage) ChatMessageOutput {
	return ChatMessageOutput{
		ID:        m.ID.String(),
		Body:      m.Body,
		NodeID:    idString(m.NodeID),
		CreatedAt: m.CreatedAt.Format(time.RFC3339),
	}
}

func (s *Server) chatSend(ctx context.Context, _ *mcp.CallToolRequest, in ChatSendInput) (*mcp.CallToolResult, ChatMessageOutput, error) {
	var zero ChatMessageOutput
	p, err := principal(ctx, "chat:write")
	if err != nil {
		return nil, zero, err
	}
	if in.NodeID != nil {
		id, err := parseID(*in.NodeID)
		if err != nil {
			return nil, zero, err
		}
		if in.Text != nil {
			return nil, zero, &service.Error{Code: "INVALID_INPUT", Message: "text 和 node_id 只能提供一个"}
		}
		msg, err := s.chats.SendNode(ctx, p.UserID, id)
		if err != nil {
			return nil, zero, err
		}
		return nil, toChatMessageOutput(msg), nil
	}
	if in.Text == nil {
		return nil, zero, &service.Error{Code: "INVALID_INPUT", Message: "text 与 node_id 至少提供一个"}
	}
	msg, err := s.chats.SendText(ctx, p.UserID, *in.Text)
	if err != nil {
		return nil, zero, err
	}
	return nil, toChatMessageOutput(msg), nil
}

func (s *Server) chatList(ctx context.Context, _ *mcp.CallToolRequest, in ChatListInput) (*mcp.CallToolResult, ChatListOutput, error) {
	p, err := principal(ctx, "chat:read")
	if err != nil {
		return nil, ChatListOutput{}, err
	}
	limit := 50
	if in.Limit != nil && *in.Limit > 0 {
		limit = *in.Limit
	}
	if limit > 200 {
		limit = 200
	}
	before, err := parseOptionalID(in.BeforeID)
	if err != nil {
		return nil, ChatListOutput{}, err
	}
	msgs, err := s.chats.ListRecent(ctx, p.UserID, before, limit)
	if err != nil {
		return nil, ChatListOutput{}, err
	}
	out := make([]ChatMessageOutput, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, toChatMessageOutput(m))
	}
	return nil, ChatListOutput{Items: out}, nil
}