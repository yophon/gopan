package mcpserver

// 看图与写文本(doc/35):助手的 read / write / edit 底下调的就是这两个;外部 MCP 客户端也能直接用。

import (
	"context"
	"errors"
	"fmt"
	"path"
	"strings"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

// 给模型的图长边上限(Claude 建议的尺寸;再大模型那边也是缩了看)
const viewImageMaxPx = 1568

type ViewImageInput struct {
	Path   *string `json:"path,omitempty" jsonschema:"absolute node path instead of node_id"`
	NodeID string  `json:"node_id,omitempty" jsonschema:"file node UUID"`
}

type ViewImageOutput struct {
	Name   string `json:"name"`
	Source string `json:"source" jsonschema:"image, video_cover or pdf_page"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

func (s *Server) viewImage(ctx context.Context, _ *mcp.CallToolRequest, in ViewImageInput) (*mcp.CallToolResult, ViewImageOutput, error) {
	p, err := principal(ctx, "files:download")
	if err != nil {
		return nil, ViewImageOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, ViewImageOutput{}, err
	}
	img, err := s.uploads.ViewImage(ctx, p.UserID, id, viewImageMaxPx)
	if err != nil {
		return nil, ViewImageOutput{}, err
	}
	what := map[string]string{"image": "image", "video_cover": "video cover", "pdf_page": "first page"}[img.Source]
	return &mcp.CallToolResult{Content: []mcp.Content{
		&mcp.TextContent{Text: fmt.Sprintf("%s (%s, %dx%d JPEG)", img.Name, what, img.Width, img.Height)},
		&mcp.ImageContent{Data: img.JPEG, MIMEType: "image/jpeg"},
	}}, ViewImageOutput{Name: img.Name, Source: img.Source, Width: img.Width, Height: img.Height}, nil
}

type WriteTextInput struct {
	Path       *string `json:"path,omitempty" jsonschema:"absolute file path; created when missing (its folder must exist)"`
	NodeID     string  `json:"node_id,omitempty" jsonschema:"existing text file UUID instead of path"`
	ParentID   *string `json:"parent_id,omitempty" jsonschema:"folder UUID for a new file, with name"`
	ParentPath *string `json:"parent_path,omitempty" jsonschema:"folder path for a new file, with name"`
	Name       string  `json:"name,omitempty" jsonschema:"new file name, with parent_id or parent_path"`
	Content    string  `json:"content" jsonschema:"full UTF-8 content, at most 2 MiB"`
	BaseSHA256 string  `json:"base_sha256,omitempty" jsonschema:"sha256 from read_text_file or get_file_info; required to overwrite a non-empty file, fails with EDIT_CONFLICT if it changed since"`
}

type WriteTextOutput struct {
	NodeOutput
	Created bool `json:"created"`
}

func (s *Server) writeTextFile(ctx context.Context, _ *mcp.CallToolRequest, in WriteTextInput) (*mcp.CallToolResult, WriteTextOutput, error) {
	p, err := principal(ctx, "files:write")
	if err != nil {
		return nil, WriteTextOutput{}, err
	}
	existing, parent, name, err := s.writeTarget(ctx, p, in)
	if err != nil {
		return nil, WriteTextOutput{}, err
	}
	if existing != uuid.Nil {
		row, err := s.uploads.NodeInfo(ctx, p.UserID, existing)
		if err != nil {
			return nil, WriteTextOutput{}, err
		}
		if row.Kind != "file" {
			return nil, WriteTextOutput{}, &service.Error{Code: "NOT_A_FILE", Message: "这是文件夹,不能写内容"}
		}
		// 覆盖非空文件必须带读时的版本:没读过就写,和 Claude Code 的「先读再改」一样拒绝
		if in.BaseSHA256 == "" && row.BlobSize != nil && *row.BlobSize > 0 {
			return nil, WriteTextOutput{}, &service.Error{Code: "READ_FIRST", Message: "这个文件已有内容,先读一遍(拿到 sha256)再覆盖"}
		}
		node, err := s.uploads.SaveTextFile(ctx, p.UserID, existing, in.Content, in.BaseSHA256)
		if err != nil {
			return nil, WriteTextOutput{}, err
		}
		return nil, WriteTextOutput{NodeOutput: s.nodeOutput(ctx, p.UserID, node.ID)}, nil
	}
	node, err := s.uploads.CreateTextFile(ctx, p.UserID, parent, name, in.Content)
	if err != nil {
		return nil, WriteTextOutput{}, err
	}
	return nil, WriteTextOutput{NodeOutput: s.nodeOutput(ctx, p.UserID, node.ID), Created: true}, nil
}

// writeTarget 写到哪:已有的文件(existing),或者新建在 parent 下叫 name。
// path 指向不存在的文件时取它的上一级当 parent;什么都没给父目录时落在凭证的根(目录范围)。
func (s *Server) writeTarget(ctx context.Context, p *service.MCPPrincipal, in WriteTextInput) (existing uuid.UUID, parent *uuid.UUID, name string, err error) {
	switch {
	case in.NodeID != "":
		if in.Path != nil {
			return uuid.Nil, nil, "", &service.Error{Code: "INVALID_INPUT", Message: "node_id 和 path 只能提供一个"}
		}
		id, err := parseID(in.NodeID)
		return id, nil, "", err
	case in.Path != nil:
		n, err := s.nodes.ResolvePathAt(ctx, p.UserID, *in.Path, rootFrom(ctx))
		if err == nil && n != nil {
			return n.ID, nil, "", nil
		}
		if err != nil && !errors.Is(err, service.ErrNotFound) {
			return uuid.Nil, nil, "", err
		}
		clean := strings.TrimRight(*in.Path, "/")
		dir, base := path.Dir(clean), path.Base(clean)
		if base == "" || base == "." || base == "/" {
			return uuid.Nil, nil, "", &service.Error{Code: "INVALID_INPUT", Message: "path 要指向一个文件"}
		}
		parent, err := s.parentByPath(ctx, p.UserID, nil, &dir)
		if err != nil {
			return uuid.Nil, nil, "", err
		}
		if parent == nil {
			parent = rootFrom(ctx)
		}
		return uuid.Nil, parent, base, nil
	default:
		if strings.TrimSpace(in.Name) == "" {
			return uuid.Nil, nil, "", &service.Error{Code: "INVALID_INPUT", Message: "给 path,或 node_id,或 parent + name"}
		}
		parent, err := s.parentByPath(ctx, p.UserID, in.ParentID, in.ParentPath)
		if err != nil {
			return uuid.Nil, nil, "", err
		}
		if parent == nil {
			parent = rootFrom(ctx)
		}
		return uuid.Nil, parent, in.Name, nil
	}
}

func (s *Server) nodeOutput(ctx context.Context, owner, id uuid.UUID) NodeOutput {
	row, err := s.uploads.NodeInfo(ctx, owner, id)
	if err != nil {
		return NodeOutput{ID: id.String()}
	}
	return nodeFromInfo(row)
}

func (s *Server) registerFileTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "view_image", Description: "Look at a file as an image: any image format (JPEG, PNG, WebP, HEIC, RAW…), a video's cover frame, or the first page of a PDF or converted Office document. Returns a JPEG of at most 1568 px on the long edge."}, s.viewImage)
	addTool(s, server, &mcp.Tool{Name: "write_text_file", Description: "Create a UTF-8 text file, or replace the whole content of one (at most 2 MiB). Overwriting a non-empty file needs base_sha256 from read_text_file; the old content stays in version history."}, s.writeTextFile)
}
