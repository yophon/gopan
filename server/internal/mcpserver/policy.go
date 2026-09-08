package mcpserver

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/yophon/gopan/server/internal/service"
)

func addTool[In, Out any](s *Server, server *mcp.Server, tool *mcp.Tool, handler func(context.Context, *mcp.CallToolRequest, In) (*mcp.CallToolResult, Out, error)) {
	mcp.AddTool(server, tool, func(ctx context.Context, req *mcp.CallToolRequest, in In) (res *mcp.CallToolResult, out Out, err error) {
		p, _ := ctx.Value(principalKey{}).(*service.MCPPrincipal)
		if p == nil {
			return nil, out, service.ErrUnauthenticated
		}
		raw, e := json.Marshal(in)
		if e != nil {
			return nil, out, e
		}
		var args map[string]any
		if e = json.Unmarshal(raw, &args); e != nil {
			return nil, out, e
		}
		safe := map[string]any{}
		for _, key := range []string{"node_id", "node_ids", "path", "paths", "parent_id", "parent_path", "target_folder_id", "target_path", "upload_id", "share_id", "user_id", "operation", "dry_run"} {
			if v, ok := args[key]; ok {
				safe[key] = v
			}
		}
		targets, _ := json.Marshal(safe)
		endpoint := "files"
		if s.admin != nil {
			endpoint = "admin"
		}
		audit, e := s.nodes.StartMCPAudit(ctx, p, endpoint, tool.Name, targets)
		if e != nil {
			return nil, out, e
		}
		defer func() {
			status, code := "success", ""
			if batch, ok := any(out).(BatchOutput); ok && batch.Failed > 0 {
				status = "partial"
				if batch.Succeeded == 0 {
					status = "failed"
				}
			}
			if err != nil || res != nil && res.IsError {
				status = "failed"
				code = "INTERNAL"
				var business *service.Error
				if errors.As(err, &business) {
					code = business.Code
				}
			}
			s.nodes.FinishMCPAudit(audit, status, code)
		}()
		if err = s.enforceRoot(ctx, p, tool.Name, args); err != nil {
			return nil, out, err
		}
		raw, _ = json.Marshal(args)
		if err = json.Unmarshal(raw, &in); err != nil {
			return nil, out, err
		}
		res, out, err = handler(ctx, req, in)
		if err == nil && p.RootID != nil {
			raw, e := json.Marshal(out)
			if e != nil {
				return nil, out, e
			}
			var value any
			if e = json.Unmarshal(raw, &value); e != nil {
				return nil, out, e
			}
			hideRootParent(value, p.RootID.String())
			raw, e = json.Marshal(value)
			if e != nil {
				return nil, out, e
			}
			var clean Out
			if e = json.Unmarshal(raw, &clean); e != nil {
				return nil, out, e
			}
			out = clean
		}
		return res, out, err
	})
}

func hideRootParent(value any, root string) {
	switch v := value.(type) {
	case map[string]any:
		if v["id"] == root {
			delete(v, "parent_id")
		}
		if v["path"] == "/" {
			if _, exists := v["root"]; exists {
				v["root"] = true
			}
		}
		for _, child := range v {
			hideRootParent(child, root)
		}
	case []any:
		for _, child := range v {
			hideRootParent(child, root)
		}
	}
}

func (s *Server) enforceRoot(ctx context.Context, p *service.MCPPrincipal, tool string, args map[string]any) error {
	if p.RootID == nil {
		return nil
	}
	if strings.HasPrefix(tool, "admin_") {
		return service.ErrForbidden
	}
	// Share-list results and trash/search are filtered by their handlers. Batch
	// validates each source in its savepoint to preserve per-item error results.
	if tool != "batch_nodes" {
		allowRoot := tool != "rename_node" && tool != "move_nodes" && tool != "trash_nodes" && tool != "restore_nodes"
		for _, field := range []string{"node_id", "node_ids"} {
			var ids []string
			switch v := args[field].(type) {
			case string:
				if v != "" {
					ids = []string{v}
				}
			case []any:
				for _, id := range v {
					ids = append(ids, id.(string))
				}
			}
			for _, raw := range ids {
				id, err := parseID(raw)
				if err != nil {
					return err
				}
				if err = s.nodes.CheckMCPNode(ctx, p, id, allowRoot); err != nil {
					return err
				}
			}
		}
		if path, ok := args["path"].(string); ok && tool != "create_directories" {
			n, err := s.nodes.ResolvePathAt(ctx, p.UserID, path, p.RootID)
			if err != nil {
				return err
			}
			if n != nil {
				if err = s.nodes.CheckMCPNode(ctx, p, n.ID, allowRoot); err != nil {
					return err
				}
			}
		}
	}
	for _, field := range []string{"parent_id", "target_folder_id"} {
		if raw, ok := args[field].(string); ok && raw != "" {
			id, err := parseID(raw)
			if err != nil {
				return err
			}
			if err = s.nodes.CheckMCPNode(ctx, p, id, true); err != nil {
				return err
			}
		}
	}
	if raw, ok := args["upload_id"].(string); ok && raw != "" {
		id, err := parseID(raw)
		if err != nil {
			return err
		}
		if err = s.nodes.CheckMCPUpload(ctx, p, id); err != nil {
			return err
		}
	}
	var idField, pathField string
	switch tool {
	case "list_files":
		idField, pathField = "parent_id", "path"
	case "create_folder", "prepare_upload":
		idField, pathField = "parent_id", "parent_path"
	case "move_nodes", "copy_nodes":
		idField, pathField = "target_folder_id", "target_path"
	case "batch_nodes":
		if args["operation"] == "move" || args["operation"] == "copy" {
			idField, pathField = "target_folder_id", "target_path"
		}
	}
	if idField != "" && (args[idField] == nil || args[idField] == "") && args[pathField] == nil {
		args[idField] = p.RootID.String()
	}
	return nil
}

func rootFrom(ctx context.Context) *uuid.UUID {
	p, _ := ctx.Value(principalKey{}).(*service.MCPPrincipal)
	if p == nil {
		return nil
	}
	return p.RootID
}

func (s *Server) checkResultRoot(ctx context.Context, p *service.MCPPrincipal, value any) error {
	switch v := value.(type) {
	case map[string]any:
		var raw string
		if id, ok := v["node_id"].(string); ok {
			raw = id
		}
		if _, ok := v["kind"]; ok {
			raw, _ = v["id"].(string)
		}
		if raw != "" {
			id, err := parseID(raw)
			if err != nil {
				return err
			}
			if err = s.nodes.CheckMCPNode(ctx, p, id, true); err != nil {
				return err
			}
		}
		for _, child := range v {
			if err := s.checkResultRoot(ctx, p, child); err != nil {
				return err
			}
		}
	case []any:
		for _, child := range v {
			if err := s.checkResultRoot(ctx, p, child); err != nil {
				return err
			}
		}
	}
	return nil
}
