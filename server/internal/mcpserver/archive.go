package mcpserver

// 压缩包的两个工具(doc/21 四):列目录、解压。解压是后台任务,用 get_job 查进度。

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
)

type ListArchiveInput struct {
	NodeID string  `json:"node_id,omitempty" jsonschema:"archive file node UUID (.zip, .7z, .rar, .tar, .tar.gz, .tar.bz2, .tar.xz)"`
	Path   *string `json:"path,omitempty" jsonschema:"absolute Gopan path of the archive, alternative to node_id"`
	Dir    string  `json:"dir,omitempty" jsonschema:"directory inside the archive, e.g. docs/2024; empty for the top level"`
	Cursor *string `json:"cursor,omitempty"`
}

type ArchiveEntryOutput struct {
	Path       string  `json:"path"`
	IsDir      bool    `json:"is_dir"`
	Size       int64   `json:"size"`
	ModifiedAt *string `json:"modified_at,omitempty"`
	Flag       string  `json:"flag,omitempty" jsonschema:"encrypted, unsafe (path escapes with ..), link or unsupported: such entries cannot be extracted"`
}

type ListArchiveOutput struct {
	Status     string               `json:"status" jsonschema:"PENDING (still reading, retry shortly), DONE, TRUNCATED (only part listed), UNSUPPORTED or FAILED"`
	Format     string               `json:"format"`
	FileCount  int                  `json:"file_count"`
	TotalSize  int64                `json:"total_size" jsonschema:"uncompressed bytes of all files"`
	Error      *string              `json:"error,omitempty"`
	Entries    []ArchiveEntryOutput `json:"entries"`
	Total      int                  `json:"total" jsonschema:"entries in this directory"`
	NextCursor *string              `json:"next_cursor,omitempty"`
}

func (s *Server) listArchive(ctx context.Context, _ *mcp.CallToolRequest, in ListArchiveInput) (*mcp.CallToolResult, ListArchiveOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, ListArchiveOutput{}, err
	}
	if s.archives == nil {
		return nil, ListArchiveOutput{}, &service.Error{Code: "UNSUPPORTED", Message: "本次部署没有压缩包功能"}
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, ListArchiveOutput{}, err
	}
	info, err := s.archives.Info(ctx, p.UserID, id)
	if err != nil {
		return nil, ListArchiveOutput{}, err
	}
	out := ListArchiveOutput{Status: info.Status, Format: info.Format, FileCount: info.Files, TotalSize: info.TotalSize, Error: info.Error, Entries: []ArchiveEntryOutput{}}
	if info.Status != "DONE" && info.Status != "TRUNCATED" {
		return nil, out, nil
	}
	rows, next, total, err := s.archives.Entries(ctx, p.UserID, id, in.Dir, in.Cursor)
	if err != nil {
		return nil, ListArchiveOutput{}, err
	}
	out.Total, out.NextCursor = int(total), next
	for _, e := range rows {
		item := ArchiveEntryOutput{Path: e.Path, IsDir: e.IsDir, Size: e.Size, Flag: e.Flag}
		if e.Mtime.Valid {
			t := e.Mtime.Time.Format(time.RFC3339)
			item.ModifiedAt = &t
		}
		out.Entries = append(out.Entries, item)
	}
	return nil, out, nil
}

type ExtractArchiveInput struct {
	NodeID     string   `json:"node_id,omitempty" jsonschema:"archive file node UUID"`
	Path       *string  `json:"path,omitempty" jsonschema:"absolute Gopan path of the archive, alternative to node_id"`
	Base       string   `json:"base,omitempty" jsonschema:"directory inside the archive the extraction is relative to; empty for the top level"`
	Paths      []string `json:"paths,omitempty" jsonschema:"full paths inside the archive to extract (files or directories under base); empty extracts everything under base"`
	ParentID   *string  `json:"parent_id,omitempty" jsonschema:"folder to create the output folder in; default is the archive's own folder"`
	ParentPath *string  `json:"parent_path,omitempty" jsonschema:"folder path, alternative to parent_id"`
}

func (s *Server) extractArchive(ctx context.Context, _ *mcp.CallToolRequest, in ExtractArchiveInput) (*mcp.CallToolResult, JobOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, JobOutput{}, err
	}
	if s.archives == nil || s.jobs == nil {
		return nil, JobOutput{}, &service.Error{Code: "UNSUPPORTED", Message: "本次部署没有压缩包功能"}
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, JobOutput{}, err
	}
	var parent *uuid.UUID
	if in.ParentID != nil || in.ParentPath != nil {
		parent, err = s.parentByPath(ctx, p.UserID, in.ParentID, in.ParentPath)
		if err != nil {
			return nil, JobOutput{}, err
		}
	} else {
		n, err := s.nodes.Get(ctx, p.UserID, id)
		if err != nil {
			return nil, JobOutput{}, err
		}
		parent = n.ParentID
	}
	if parent != nil {
		if err := s.nodes.CheckMCPNode(ctx, p, *parent, true); err != nil {
			return nil, JobOutput{}, err
		}
	} else if p.RootID != nil {
		return nil, JobOutput{}, service.ErrForbidden
	}
	row, err := s.archives.Extract(ctx, s.jobs, p.UserID, service.ExtractRequest{NodeID: id, Base: in.Base, Paths: in.Paths, Parent: parent}, service.ChatSourceMCP)
	if err != nil {
		return nil, JobOutput{}, err
	}
	return nil, jobOutput(row), nil
}

func (s *Server) registerArchiveTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "list_archive", Description: "List what is inside an archive file (.zip, .7z, .rar, .tar, .tar.gz, .tar.bz2, .tar.xz) without downloading it, one directory at a time. status PENDING means the server is still reading it; retry shortly."}, s.listArchive)
	addTool(s, server, &mcp.Tool{Name: "extract_archive", Description: "Extract an archive (all of it, or selected paths) into a new folder named after it, in the background. Existing files are never overwritten; name clashes get a numeric suffix. Returns a job; poll get_job (wait=true) until done, then result_node_id is the new folder. Encrypted, link and unsafe-path entries are skipped. Requires files:upload."}, s.extractArchive)
}
