package mcpserver

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/yophon/gopan/server/internal/service"
)

func mutation[In, Out any](s *Server, name, scope string, handler func(*Server, context.Context, *mcp.CallToolRequest, In) (*mcp.CallToolResult, Out, error)) func(context.Context, *mcp.CallToolRequest, In) (*mcp.CallToolResult, Out, error) {
	return func(ctx context.Context, req *mcp.CallToolRequest, in In) (*mcp.CallToolResult, Out, error) {
		var zero Out
		p, err := principal(ctx, scope)
		if err != nil {
			return nil, zero, err
		}
		args, err := json.Marshal(in)
		if err != nil {
			return nil, zero, err
		}
		var opts struct {
			Key    string `json:"idempotency_key"`
			DryRun bool   `json:"dry_run"`
		}
		if err = json.Unmarshal(args, &opts); err != nil {
			return nil, zero, err
		}
		raw, err := s.nodes.MCPMutation(ctx, p.UserID, opts.Key, append([]byte(name+":"), args...), opts.DryRun, func(nodes *service.Nodes) (json.RawMessage, error) {
			local := *s
			local.nodes = nodes
			_, out, err := handler(&local, ctx, req, in)
			if err != nil {
				return nil, err
			}
			return json.Marshal(out)
		})
		if err != nil {
			return nil, zero, err
		}
		err = json.Unmarshal(raw, &zero)
		return nil, zero, err
	}
}

type WaitUploadInput struct {
	UploadID       string `json:"upload_id" jsonschema:"upload UUID"`
	TimeoutSeconds int    `json:"timeout_seconds,omitempty" jsonschema:"maximum wait in seconds, default 20, range 1 to 25; timeout returns the latest nonterminal status"`
}

func (s *Server) waitUpload(ctx context.Context, req *mcp.CallToolRequest, in WaitUploadInput) (*mcp.CallToolResult, UploadOutput, error) {
	if in.TimeoutSeconds == 0 {
		in.TimeoutSeconds = 20
	}
	if in.TimeoutSeconds < 1 || in.TimeoutSeconds > 25 {
		return nil, UploadOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "timeout_seconds 必须为 1–25"}
	}
	deadline := time.NewTimer(time.Duration(in.TimeoutSeconds) * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		_, out, err := s.getUploadStatus(ctx, req, UploadPartsInput{UploadID: in.UploadID})
		if err != nil {
			return nil, out, err
		}
		if out.Status == "ready" || out.Status == "failed" || out.Status == "aborted" {
			return nil, out, nil
		}
		select {
		case <-ctx.Done():
			return nil, out, ctx.Err()
		case <-deadline.C:
			return nil, out, nil
		case <-ticker.C:
		}
	}
}

type PathInput struct {
	Path string `json:"path" jsonschema:"absolute case-sensitive Gopan path, for example /reports/2026; / is the root"`
}
type PathOutput struct {
	Path string      `json:"path"`
	Root bool        `json:"root"`
	Node *NodeOutput `json:"node,omitempty"`
}

func (s *Server) resolvePath(ctx context.Context, _ *mcp.CallToolRequest, in PathInput) (*mcp.CallToolResult, PathOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, PathOutput{}, err
	}
	n, err := s.nodes.ResolvePath(ctx, p.UserID, in.Path)
	out := PathOutput{Path: in.Path, Root: n == nil}
	if n != nil {
		node := nodeFromStore(*n)
		out.Node = &node
	}
	return nil, out, err
}

type DirectoriesInput struct {
	Path           string `json:"path" jsonschema:"absolute directory path; existing directories are reused, missing parents are created atomically"`
	IdempotencyKey string `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 bytes"`
}

func (s *Server) createDirectories(ctx context.Context, _ *mcp.CallToolRequest, in DirectoriesInput) (*mcp.CallToolResult, PathOutput, error) {
	p, err := principal(ctx, "files:write")
	if err != nil {
		return nil, PathOutput{}, err
	}
	n, err := s.nodes.EnsurePath(ctx, p.UserID, in.Path)
	out := PathOutput{Path: in.Path, Root: n == nil}
	if n != nil {
		node := nodeFromStore(*n)
		out.Node = &node
	}
	return nil, out, err
}

type TrashInput struct {
	Cursor *string `json:"cursor,omitempty" jsonschema:"cursor from the preceding page"`
}
type TrashItem struct {
	NodeOutput
	DeletedAt string `json:"deleted_at"`
}
type TrashOutput struct {
	Items      []TrashItem `json:"items"`
	NextCursor *string     `json:"next_cursor,omitempty"`
	Total      int64       `json:"total"`
}

func (s *Server) listTrash(ctx context.Context, _ *mcp.CallToolRequest, in TrashInput) (*mcp.CallToolResult, TrashOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, TrashOutput{}, err
	}
	page, err := s.nodes.Trash(ctx, p.UserID, in.Cursor)
	if err != nil {
		return nil, TrashOutput{}, err
	}
	out := TrashOutput{Items: make([]TrashItem, 0, len(page.Items)), Total: page.Total, NextCursor: page.NextCursor}
	for _, n := range page.Items {
		out.Items = append(out.Items, TrashItem{NodeOutput: NodeOutput{ID: n.ID.String(), ParentID: idString(n.ParentID), Name: n.Name, Kind: n.Kind, Size: n.BlobSize, Mime: n.BlobMime, UpdatedAt: n.UpdatedAt.Time.Format(time.RFC3339)}, DeletedAt: n.DeletedAt.Time.Format(time.RFC3339)})
	}
	return nil, out, nil
}

// parentByPath resolves a destination without granting access to other owners.
func (s *Server) parentByPath(ctx context.Context, owner uuid.UUID, id, path *string) (*uuid.UUID, error) {
	if path == nil {
		return parseOptionalID(id)
	}
	if id != nil {
		return nil, &service.Error{Code: "INVALID_INPUT", Message: "ID 和 path 只能提供一个"}
	}
	n, err := s.nodes.ResolvePath(ctx, owner, *path)
	if err != nil {
		return nil, err
	}
	if n == nil {
		return nil, nil
	}
	if n.Kind != "folder" {
		return nil, &service.Error{Code: "INVALID_INPUT", Message: "目标路径不是文件夹"}
	}
	return &n.ID, nil
}
func (s *Server) nodeByPath(ctx context.Context, owner uuid.UUID, id string, path *string) (uuid.UUID, error) {
	if path == nil {
		return parseID(id)
	}
	if id != "" {
		return uuid.Nil, &service.Error{Code: "INVALID_INPUT", Message: "node_id 和 path 只能提供一个"}
	}
	n, err := s.nodes.ResolvePath(ctx, owner, *path)
	if err != nil {
		return uuid.Nil, err
	}
	if n == nil {
		return uuid.Nil, &service.Error{Code: "INVALID_INPUT", Message: "此操作不能用于根目录"}
	}
	return n.ID, nil
}

type BatchInput struct {
	Operation      string   `json:"operation" jsonschema:"move, copy, trash, or restore"`
	NodeIDs        []string `json:"node_ids,omitempty" jsonschema:"source node UUIDs; do not combine with paths; maximum 100"`
	Paths          []string `json:"paths,omitempty" jsonschema:"absolute source paths; use IDs from list_trash for restore"`
	TargetFolderID *string  `json:"target_folder_id,omitempty" jsonschema:"destination for move/copy; omit for root"`
	TargetPath     *string  `json:"target_path,omitempty" jsonschema:"destination path instead of target_folder_id"`
	DryRun         bool     `json:"dry_run,omitempty" jsonschema:"validate and simulate the entire batch then roll back; preview IDs are not usable"`
	IdempotencyKey string   `json:"idempotency_key,omitempty" jsonschema:"stable retry key; retries replay all per-item results, including failures"`
}
type BatchItem struct {
	Source    string      `json:"source"`
	Success   bool        `json:"success"`
	Node      *NodeOutput `json:"node,omitempty"`
	ErrorCode string      `json:"error_code,omitempty"`
	Error     string      `json:"error,omitempty"`
}
type BatchOutput struct {
	DryRun    bool        `json:"dry_run"`
	Items     []BatchItem `json:"items"`
	Succeeded int         `json:"succeeded"`
	Failed    int         `json:"failed"`
}

func (s *Server) batchMutation(ctx context.Context, req *mcp.CallToolRequest, in BatchInput) (*mcp.CallToolResult, BatchOutput, error) {
	scope := "files:write"
	if in.Operation == "trash" || in.Operation == "restore" {
		scope = "files:delete"
	}
	return mutation(s, "batch_nodes", scope, (*Server).batchNodes)(ctx, req, in)
}
func (s *Server) batchNodes(ctx context.Context, _ *mcp.CallToolRequest, in BatchInput) (*mcp.CallToolResult, BatchOutput, error) {
	scope := "files:write"
	switch in.Operation {
	case "move", "copy":
	case "trash", "restore":
		scope = "files:delete"
	default:
		return nil, BatchOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "不支持的批量操作"}
	}
	p, err := principal(ctx, scope)
	if err != nil {
		return nil, BatchOutput{}, err
	}
	if len(in.NodeIDs) > 0 && len(in.Paths) > 0 {
		return nil, BatchOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "node_ids 和 paths 只能提供一个"}
	}
	sources := in.NodeIDs
	if len(in.Paths) > 0 {
		sources = in.Paths
	}
	if len(sources) < 1 || len(sources) > 100 {
		return nil, BatchOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "每批需提供 1–100 个节点"}
	}
	var target *uuid.UUID
	if in.Operation == "move" || in.Operation == "copy" {
		target, err = s.parentByPath(ctx, p.UserID, in.TargetFolderID, in.TargetPath)
		if err != nil {
			return nil, BatchOutput{}, err
		}
	} else if in.TargetFolderID != nil || in.TargetPath != nil {
		return nil, BatchOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "删除和恢复不接受目标目录"}
	}
	out := BatchOutput{DryRun: in.DryRun, Items: make([]BatchItem, 0, len(sources))}
	seen := map[uuid.UUID]bool{}
	for _, source := range sources {
		item := BatchItem{Source: source}
		err = s.nodes.MCPItem(ctx, func(nodes *service.Nodes) error {
			local := *s
			local.nodes = nodes
			var id uuid.UUID
			var err error
			if len(in.Paths) > 0 {
				id, err = local.nodeByPath(ctx, p.UserID, "", &source)
			} else {
				id, err = parseID(source)
			}
			if err != nil {
				return err
			}
			if seen[id] {
				return &service.Error{Code: "INVALID_INPUT", Message: "同一批次包含重复节点"}
			}
			seen[id] = true
			n, err := nodes.Get(ctx, p.UserID, id)
			if err != nil {
				return err
			}
			if in.Operation != "restore" && n.DeletedAt.Valid {
				return service.ErrNotFound
			}
			if in.Operation == "restore" && !n.DeletedAt.Valid {
				return &service.Error{Code: "INVALID_INPUT", Message: "节点不在回收站"}
			}
			var output NodeOutput
			switch in.Operation {
			case "move":
				ns, e := nodes.Move(ctx, p.UserID, []uuid.UUID{id}, target)
				if e != nil {
					return e
				}
				output = nodeFromStore(ns[0])
			case "copy":
				ns, e := nodes.Copy(ctx, p.UserID, []uuid.UUID{id}, target)
				if e != nil {
					return e
				}
				output = nodeFromStore(ns[0])
			case "trash":
				if e := nodes.Delete(ctx, p.UserID, []uuid.UUID{id}); e != nil {
					return e
				}
				output = nodeFromStore(n)
			case "restore":
				ns, e := nodes.Restore(ctx, p.UserID, []uuid.UUID{id})
				if e != nil {
					return e
				}
				output = nodeFromStore(ns[0])
			}
			item.Node = &output
			return nil
		})
		if err != nil {
			item.ErrorCode = "INTERNAL"
			item.Error = "操作失败"
			var e *service.Error
			if errors.As(err, &e) {
				item.ErrorCode = e.Code
				item.Error = e.Message
			}
			out.Failed++
		} else {
			item.Success = true
			out.Succeeded++
			if in.DryRun && item.Node != nil && in.Operation == "copy" {
				item.Node.ID = ""
			}
		}
		out.Items = append(out.Items, item)
	}
	return nil, out, nil
}
