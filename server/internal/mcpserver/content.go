package mcpserver

// 内容搜索的两个工具(doc/20 第三节):按内容找文件,以及不下载就读 PDF / Office 里的文字。

import (
	"context"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type SearchContentsInput struct {
	Query  string  `json:"query" jsonschema:"words or a phrase that appears in the file content; at least two CJK characters or three letters"`
	Cursor *string `json:"cursor,omitempty" jsonschema:"next_cursor from the previous page"`
}

type ContentHitOutput struct {
	NodeOutput
	Snippet string `json:"snippet" jsonschema:"text around the first match"`
	Page    *int   `json:"page,omitempty" jsonschema:"page of the first match, for PDFs"`
}

type SearchContentsOutput struct {
	Items      []ContentHitOutput `json:"items"`
	NextCursor *string            `json:"next_cursor,omitempty"`
}

func (s *Server) searchFileContents(ctx context.Context, _ *mcp.CallToolRequest, in SearchContentsInput) (*mcp.CallToolResult, SearchContentsOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, SearchContentsOutput{}, err
	}
	page, err := s.content.Search(ctx, p.UserID, p.RootID, in.Query, in.Cursor, nil)
	if err != nil {
		return nil, SearchContentsOutput{}, err
	}
	out := SearchContentsOutput{Items: make([]ContentHitOutput, 0, len(page.Items)), NextCursor: page.NextCursor}
	for _, h := range page.Items {
		r := h.Row
		item := ContentHitOutput{
			NodeOutput: NodeOutput{
				ID: r.ID.String(), ParentID: idString(r.ParentID), Name: r.Name, Kind: "file",
				Size: &r.BlobSize, Mime: &r.BlobMime, SHA256: &r.BlobSha256,
				UpdatedAt: r.UpdatedAt.Time.Format(time.RFC3339),
			},
			Snippet: h.Snippet,
		}
		if h.Page > 0 {
			pg := h.Page
			item.Page = &pg
		}
		out.Items = append(out.Items, item)
	}
	return nil, out, nil
}

type ReadDocumentInput struct {
	Path     *string `json:"path,omitempty" jsonschema:"absolute node path instead of node_id"`
	NodeID   string  `json:"node_id,omitempty" jsonschema:"file node UUID"`
	Offset   int     `json:"offset,omitempty" jsonschema:"character offset, default 0"`
	MaxChars int     `json:"max_chars,omitempty" jsonschema:"maximum characters to return, default 20000, maximum 50000"`
}

type ReadDocumentOutput struct {
	Name       string `json:"name"`
	Text       string `json:"text"`
	Offset     int    `json:"offset"`
	NextOffset int    `json:"next_offset"`
	Total      int    `json:"total" jsonschema:"total characters of extracted text"`
	EOF        bool   `json:"eof"`
	Page       *int   `json:"page,omitempty" jsonschema:"page where this chunk starts, for PDFs"`
	Truncated  bool   `json:"truncated,omitempty" jsonschema:"the document was longer than the indexed text; only the beginning is available"`
}

func (s *Server) readDocumentText(ctx context.Context, _ *mcp.CallToolRequest, in ReadDocumentInput) (*mcp.CallToolResult, ReadDocumentOutput, error) {
	p, err := principal(ctx, "files:download")
	if err != nil {
		return nil, ReadDocumentOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, ReadDocumentOutput{}, err
	}
	if in.MaxChars == 0 {
		in.MaxChars = 20000
	}
	d, err := s.content.ReadText(ctx, p.UserID, id, in.Offset, in.MaxChars)
	if err != nil {
		return nil, ReadDocumentOutput{}, err
	}
	out := ReadDocumentOutput{
		Name: d.Name, Text: d.Text, Offset: d.Offset, NextOffset: d.NextOffset, Total: d.Total, EOF: d.EOF,
		Truncated: d.Status == "truncated",
	}
	if d.Page > 0 {
		pg := d.Page
		out.Page = &pg
	}
	return nil, out, nil
}

func (s *Server) registerContentTools(server *mcp.Server) {
	addTool(s, server, &mcp.Tool{Name: "search_file_contents", Description: "Search files by the text inside them (PDF, Word/Excel/PowerPoint, OpenDocument, plain text and code). Matches a literal substring, case-insensitive; returns a snippet around the first match and its PDF page. Scanned images are not searchable."}, s.searchFileContents)
	addTool(s, server, &mcp.Tool{Name: "read_document_text", Description: "Read the extracted text of a PDF, Office or text file in chunks of up to 50000 characters, without downloading the file. Use next_offset to continue. For plain-text and code files where the exact bytes matter, prefer read_text_file."}, s.readDocumentText)
}
