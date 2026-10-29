package mcpserver

// 助手(doc/33 三):gopan 自己的客户端里的 tau 用登录凭证 + X-Gopan-Assistant 头调 /mcp。
//
// 每个工具标上它可能用到的 scope(_meta["gopan/scopes"])与标准的 readOnlyHint / destructiveHint:
// 客户端据此把权限组的 allow / ask / deny 翻成 tau 的规则;外面的 MCP 客户端也能用上这些标注。
// 助手的 tools/list 只列它拿得到 scope 的工具,分片上传与下载地址那几个不给它。

import (
	"context"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

const (
	assistantHeader     = "X-Gopan-Assistant"
	assistantTurnHeader = "X-Gopan-Turn"
	metaScopes          = "gopan/scopes"
)

// toolScopes 每个文件端工具可能用到的 scope;batch_nodes 看参数,两个都列。
var toolScopes = map[string][]string{
	"list_files": {"files:read"}, "search_files": {"files:read"}, "get_file_info": {"files:read"},
	"resolve_path": {"files:read"}, "list_trash": {"files:read"}, "storage_status": {"files:read"},
	"list_archive": {"files:read"}, "search_file_contents": {"files:read"}, "get_job": {"files:read"}, "list_jobs": {"files:read"},
	"read_text_file": {"files:download"}, "read_document_text": {"files:download"}, "prepare_download": {"files:download"},
	"create_folder": {"files:write"}, "create_directories": {"files:write"}, "rename_node": {"files:write"},
	"move_nodes": {"files:write"}, "copy_nodes": {"files:write"},
	"trash_nodes": {"files:delete"}, "restore_nodes": {"files:delete"},
	"batch_nodes":     {"files:write", "files:delete"},
	"extract_archive": {"files:upload"}, "fetch_url": {"files:upload"}, "clip_url": {"files:upload"},
	"prepare_upload": {"files:upload"}, "get_upload_parts": {"files:upload"}, "complete_upload": {"files:upload"},
	"get_upload_status": {"files:upload"}, "abort_upload": {"files:upload"}, "wait_upload": {"files:upload"},
	"list_shares": {"shares:read"}, "list_file_requests": {"shares:read"}, "list_links": {"shares:read"},
	"create_share": {"shares:write"}, "revoke_share": {"shares:write"}, "create_file_request": {"shares:write"},
	"revoke_file_request": {"shares:write"}, "create_link": {"shares:write"}, "revoke_link": {"shares:write"},
	"chat_list": {"chat:read"}, "chat_send": {"chat:write"},
	"list_activity": {"audit:read"}, "list_audit": {"audit:read"},
}

// 会删东西、吊销东西的(放进回收站可以恢复,但仍按破坏性标)
var destructiveTools = map[string]bool{
	"trash_nodes": true, "batch_nodes": true, "revoke_share": true, "revoke_file_request": true, "revoke_link": true,
}

// 助手用不上的:模型手里没有本地文件,下载地址给它只可能被拿去外发
var assistantHidden = map[string]bool{
	"prepare_upload": true, "get_upload_parts": true, "complete_upload": true, "get_upload_status": true,
	"abort_upload": true, "wait_upload": true, "prepare_download": true,
}

var readScopes = map[string]bool{"files:read": true, "files:download": true, "shares:read": true, "chat:read": true, "audit:read": true}

// annotateTool 注册时按 toolScopes 补上 _meta 与提示;管理端的工具不在表里,不动。
func annotateTool(tool *mcp.Tool) {
	scopes, ok := toolScopes[tool.Name]
	if !ok {
		return
	}
	readOnly := true
	for _, sc := range scopes {
		if !readScopes[sc] {
			readOnly = false
		}
	}
	if tool.Meta == nil {
		tool.Meta = mcp.Meta{}
	}
	tool.Meta[metaScopes] = scopes
	if tool.Annotations == nil {
		tool.Annotations = &mcp.ToolAnnotations{}
	}
	tool.Annotations.ReadOnlyHint = readOnly
	if !readOnly {
		destructive := destructiveTools[tool.Name]
		tool.Annotations.DestructiveHint = &destructive
	}
}

// assistantVisible 助手能看到这个工具:不在隐藏表里,且至少有一个 scope 拿得到。
func assistantVisible(p *service.MCPPrincipal, name string) bool {
	if assistantHidden[name] {
		return false
	}
	scopes, ok := toolScopes[name]
	if !ok {
		return false
	}
	for _, sc := range scopes {
		if p.HasScope(sc) {
			return true
		}
	}
	return false
}

// assistantToolFilter 助手的 tools/list 只列它能用的。
func assistantToolFilter(next mcp.MethodHandler) mcp.MethodHandler {
	return func(ctx context.Context, method string, req mcp.Request) (mcp.Result, error) {
		res, err := next(ctx, method, req)
		if err != nil || method != "tools/list" {
			return res, err
		}
		p, _ := ctx.Value(principalKey{}).(*service.MCPPrincipal)
		list, ok := res.(*mcp.ListToolsResult)
		if p == nil || p.CredentialType != service.CredentialAssistant || !ok {
			return res, err
		}
		out := *list
		out.Tools = make([]*mcp.Tool, 0, len(list.Tools))
		for _, t := range list.Tools {
			if assistantVisible(p, t.Name) {
				out.Tools = append(out.Tools, t)
			}
		}
		return &out, nil
	}
}

// authenticateAssistant 登录凭证 + 对话 id;Turn 头可选(没有就每次调用各自一批)。
func (s *Server) authenticateAssistant(r *http.Request, bearer string) (*service.MCPPrincipal, error) {
	if s.auth == nil || s.assistant == nil {
		return nil, service.ErrUnauthenticated
	}
	conversation, err := uuid.Parse(strings.TrimSpace(r.Header.Get(assistantHeader)))
	if err != nil {
		return nil, service.ErrUnauthenticated
	}
	ident, err := s.auth.ParseAccessContext(r.Context(), bearer)
	if err != nil || ident.Scope != service.ScopeUser {
		return nil, service.ErrUnauthenticated
	}
	u, err := s.auth.GetUser(r.Context(), ident.UserID)
	if err != nil || u.DisabledAt.Valid {
		return nil, service.ErrUnauthenticated
	}
	turn, _ := uuid.Parse(strings.TrimSpace(r.Header.Get(assistantTurnHeader)))
	return s.assistant.Principal(r.Context(), ident.UserID, u.Username, conversation, turn)
}
