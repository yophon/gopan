package mcpserver

import (
	"context"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

type principalKey struct{}
type baseURLKey struct{}

type Server struct {
	nodes   *service.Nodes
	uploads *service.Uploads
	tokens  *service.MCPTokens
	oauth   *service.OAuth
	packer  *service.Packer
	tickets *service.PackTickets
}

func NewHandler(nodes *service.Nodes, uploads *service.Uploads, tokens *service.MCPTokens, oauth *service.OAuth, packer *service.Packer, tickets *service.PackTickets) http.Handler {
	s := &Server{nodes: nodes, uploads: uploads, tokens: tokens, oauth: oauth, packer: packer, tickets: tickets}
	protocol := mcp.NewServer(&mcp.Implementation{Name: "gopan", Version: "2.2.0"}, nil)
	s.registerTools(protocol)
	h := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return protocol }, &mcp.StreamableHTTPOptions{
		Stateless: true, JSONResponse: true,
	})
	return s.withAuth(http.NewCrossOriginProtection().Handler(h))
}

func (s *Server) withAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			s.writeAuthenticateHeader(w, r)
			http.Error(w, "missing MCP bearer token", http.StatusUnauthorized)
			return
		}
		principal, err := s.authenticate(r.Context(), strings.TrimSpace(strings.TrimPrefix(h, "Bearer ")))
		if err != nil {
			s.writeAuthenticateHeader(w, r)
			http.Error(w, "invalid MCP bearer token", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), principalKey{}, principal)
		ctx = context.WithValue(ctx, baseURLKey{}, requestBaseURL(r))
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) authenticate(ctx context.Context, bearer string) (*service.MCPPrincipal, error) {
	switch {
	case strings.HasPrefix(bearer, "gopan_key_"), strings.HasPrefix(bearer, "gopan_mcp_"):
		return s.tokens.Authenticate(ctx, bearer)
	case strings.HasPrefix(bearer, "gopan_oauth_"):
		return s.oauth.Authenticate(ctx, bearer)
	default:
		return nil, service.ErrUnauthenticated
	}
}

func (s *Server) writeAuthenticateHeader(w http.ResponseWriter, r *http.Request) {
	metadata := requestBaseURL(r) + "/.well-known/oauth-protected-resource"
	w.Header().Set("WWW-Authenticate", `Bearer realm="gopan-mcp", resource_metadata="`+metadata+`"`)
}

func requestBaseURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if forwarded := firstHeaderValue(r.Header.Get("X-Forwarded-Proto")); forwarded == "http" || forwarded == "https" {
		scheme = forwarded
	}
	host := r.Host
	if forwarded := firstHeaderValue(r.Header.Get("X-Forwarded-Host")); forwarded != "" {
		host = forwarded
	}
	return scheme + "://" + host
}

func firstHeaderValue(value string) string {
	value, _, _ = strings.Cut(value, ",")
	return strings.TrimSpace(value)
}

func principal(ctx context.Context, scope string) (*service.MCPPrincipal, error) {
	p, _ := ctx.Value(principalKey{}).(*service.MCPPrincipal)
	if p == nil {
		return nil, service.ErrUnauthenticated
	}
	if !p.HasScope(scope) {
		return nil, service.ErrForbidden
	}
	return p, nil
}

func parseID(raw string) (uuid.UUID, error) {
	id, err := uuid.Parse(raw)
	if err != nil {
		return uuid.Nil, &service.Error{Code: "INVALID_INPUT", Message: "非法 ID"}
	}
	return id, nil
}

func parseOptionalID(raw *string) (*uuid.UUID, error) {
	if raw == nil || *raw == "" {
		return nil, nil
	}
	id, err := parseID(*raw)
	if err != nil {
		return nil, err
	}
	return &id, nil
}

func parseIDs(raw []string) ([]uuid.UUID, error) {
	if len(raw) > 100 {
		return nil, &service.Error{Code: "INVALID_INPUT", Message: "每批最多 100 个节点"}
	}
	ids := make([]uuid.UUID, 0, len(raw))
	for _, value := range raw {
		id, err := parseID(value)
		if err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	if len(ids) == 0 {
		return nil, &service.Error{Code: "INVALID_INPUT", Message: "ids 不能为空"}
	}
	return ids, nil
}

type NodeOutput struct {
	ID           string  `json:"id"`
	ParentID     *string `json:"parent_id,omitempty"`
	Name         string  `json:"name"`
	Kind         string  `json:"kind"`
	Size         *int64  `json:"size,omitempty"`
	Mime         *string `json:"mime,omitempty"`
	SHA256       *string `json:"sha256,omitempty"`
	SubtreeBytes *int64  `json:"subtree_bytes,omitempty"`
	SubtreeCount *int64  `json:"subtree_count,omitempty"`
	StatsStale   *bool   `json:"stats_stale,omitempty"`
	UpdatedAt    string  `json:"updated_at"`
}

func idString(id *uuid.UUID) *string {
	if id == nil {
		return nil
	}
	s := id.String()
	return &s
}

func nodeFromStore(n store.Node) NodeOutput {
	out := NodeOutput{
		ID: n.ID.String(), ParentID: idString(n.ParentID), Name: n.Name,
		Kind: n.Kind, UpdatedAt: n.UpdatedAt.Time.Format(time.RFC3339),
	}
	if n.Kind == "folder" {
		out.SubtreeBytes, out.SubtreeCount, out.StatsStale = &n.SubtreeBytes, &n.SubtreeCount, &n.StatsStale
	}
	return out
}

func nodeFromList(n store.ListChildrenRow) NodeOutput {
	out := NodeOutput{
		ID: n.ID.String(), ParentID: idString(n.ParentID), Name: n.Name, Kind: n.Kind,
		Size: n.BlobSize, Mime: n.BlobMime, SHA256: n.BlobSha256,
		UpdatedAt: n.UpdatedAt.Time.Format(time.RFC3339),
	}
	if n.Kind == "folder" {
		out.SubtreeBytes, out.SubtreeCount, out.StatsStale = &n.SubtreeBytes, &n.SubtreeCount, &n.StatsStale
	}
	return out
}

func nodeFromSearch(n store.SearchNodesRow) NodeOutput {
	out := NodeOutput{
		ID: n.ID.String(), ParentID: idString(n.ParentID), Name: n.Name, Kind: n.Kind,
		Size: n.BlobSize, Mime: n.BlobMime, SHA256: n.BlobSha256,
		UpdatedAt: n.UpdatedAt.Time.Format(time.RFC3339),
	}
	if n.Kind == "folder" {
		out.SubtreeBytes, out.SubtreeCount, out.StatsStale = &n.SubtreeBytes, &n.SubtreeCount, &n.StatsStale
	}
	return out
}

type ListFilesInput struct {
	Path     *string `json:"path,omitempty" jsonschema:"absolute folder path instead of parent_id"`
	ParentID *string `json:"parent_id,omitempty" jsonschema:"folder node ID; omit for root"`
	Cursor   *string `json:"cursor,omitempty" jsonschema:"cursor returned by the previous call"`
	Order    string  `json:"order,omitempty" jsonschema:"sort order: name, size, or updated_at"`
	Desc     bool    `json:"desc,omitempty" jsonschema:"sort descending"`
}

type ListFilesOutput struct {
	Items      []NodeOutput `json:"items"`
	NextCursor *string      `json:"next_cursor,omitempty"`
	Total      int64        `json:"total"`
}

func (s *Server) listFiles(ctx context.Context, _ *mcp.CallToolRequest, in ListFilesInput) (*mcp.CallToolResult, ListFilesOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, ListFilesOutput{}, err
	}
	parentID, err := s.parentByPath(ctx, p.UserID, in.ParentID, in.Path)
	if err != nil {
		return nil, ListFilesOutput{}, err
	}
	order := strings.ToUpper(in.Order)
	if order == "" {
		order = "NAME"
	}
	if order != "NAME" && order != "SIZE" && order != "UPDATED_AT" {
		return nil, ListFilesOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "order 必须是 name、size 或 updated_at"}
	}
	page, err := s.nodes.Children(ctx, p.UserID, parentID, in.Cursor, order, in.Desc)
	if err != nil {
		return nil, ListFilesOutput{}, err
	}
	out := ListFilesOutput{Items: make([]NodeOutput, 0, len(page.Items)), NextCursor: page.NextCursor, Total: page.Total}
	for _, item := range page.Items {
		out.Items = append(out.Items, nodeFromList(item))
	}
	return nil, out, nil
}

type SearchFilesInput struct {
	Query  string  `json:"query" jsonschema:"substring to search for in file and folder names"`
	Cursor *string `json:"cursor,omitempty" jsonschema:"cursor returned by the previous call"`
}

type SearchFilesOutput = ListFilesOutput

func (s *Server) searchFiles(ctx context.Context, _ *mcp.CallToolRequest, in SearchFilesInput) (*mcp.CallToolResult, SearchFilesOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, SearchFilesOutput{}, err
	}
	page, err := s.nodes.Search(ctx, p.UserID, in.Query, in.Cursor)
	if err != nil {
		return nil, SearchFilesOutput{}, err
	}
	out := SearchFilesOutput{Items: make([]NodeOutput, 0, len(page.Items)), NextCursor: page.NextCursor, Total: page.Total}
	for _, item := range page.Items {
		out.Items = append(out.Items, nodeFromSearch(item))
	}
	return nil, out, nil
}

type NodeIDInput struct {
	Path   *string `json:"path,omitempty" jsonschema:"absolute node path instead of node_id"`
	NodeID string  `json:"node_id,omitempty" jsonschema:"Gopan node UUID"`
}

func (s *Server) getFileInfo(ctx context.Context, _ *mcp.CallToolRequest, in NodeIDInput) (*mcp.CallToolResult, NodeOutput, error) {
	p, err := principal(ctx, "files:read")
	if err != nil {
		return nil, NodeOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, NodeOutput{}, err
	}
	row, err := s.uploads.NodeInfo(ctx, p.UserID, id)
	if err != nil {
		return nil, NodeOutput{}, err
	}
	out := NodeOutput{
		ID: row.ID.String(), ParentID: idString(row.ParentID), Name: row.Name, Kind: row.Kind,
		Size: row.BlobSize, Mime: row.BlobMime, SHA256: row.BlobSha256,
		UpdatedAt: row.UpdatedAt.Time.Format(time.RFC3339),
	}
	if row.Kind == "folder" {
		out.SubtreeBytes, out.SubtreeCount, out.StatsStale = &row.SubtreeBytes, &row.SubtreeCount, &row.StatsStale
	}
	return nil, out, nil
}

type ReadTextInput struct {
	Path     *string `json:"path,omitempty" jsonschema:"absolute node path instead of node_id"`
	NodeID   string  `json:"node_id,omitempty" jsonschema:"text file node UUID"`
	Offset   int64   `json:"offset,omitempty" jsonschema:"byte offset, default 0"`
	MaxBytes int64   `json:"max_bytes,omitempty" jsonschema:"maximum bytes to return, default and maximum 65536"`
}

type ReadTextOutput struct {
	Text       string `json:"text"`
	Offset     int64  `json:"offset"`
	NextOffset int64  `json:"next_offset"`
	EOF        bool   `json:"eof"`
}

func (s *Server) readTextFile(ctx context.Context, _ *mcp.CallToolRequest, in ReadTextInput) (*mcp.CallToolResult, ReadTextOutput, error) {
	p, err := principal(ctx, "files:download")
	if err != nil {
		return nil, ReadTextOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, ReadTextOutput{}, err
	}
	if in.MaxBytes == 0 {
		in.MaxBytes = 64 << 10
	}
	text, eof, err := s.uploads.ReadText(ctx, p.UserID, id, in.Offset, in.MaxBytes)
	if err != nil {
		return nil, ReadTextOutput{}, err
	}
	return nil, ReadTextOutput{Text: text, Offset: in.Offset, NextOffset: in.Offset + int64(len([]byte(text))), EOF: eof}, nil
}

type CreateFolderInput struct {
	ParentPath     *string `json:"parent_path,omitempty" jsonschema:"absolute parent folder path instead of parent_id"`
	IdempotencyKey string  `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 bytes"`
	ParentID       *string `json:"parent_id,omitempty" jsonschema:"parent folder UUID; omit for root"`
	Name           string  `json:"name" jsonschema:"new folder name"`
}

func (s *Server) createFolder(ctx context.Context, _ *mcp.CallToolRequest, in CreateFolderInput) (*mcp.CallToolResult, NodeOutput, error) {
	p, err := principal(ctx, "files:write")
	if err != nil {
		return nil, NodeOutput{}, err
	}
	parentID, err := s.parentByPath(ctx, p.UserID, in.ParentID, in.ParentPath)
	if err != nil {
		return nil, NodeOutput{}, err
	}
	node, err := s.nodes.CreateFolder(ctx, p.UserID, parentID, in.Name)
	return nil, nodeFromStore(node), err
}

type RenameInput struct {
	Path           *string `json:"path,omitempty" jsonschema:"absolute node path instead of node_id"`
	IdempotencyKey string  `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 bytes"`
	NodeID         string  `json:"node_id,omitempty" jsonschema:"node UUID"`
	Name           string  `json:"name" jsonschema:"new file or folder name"`
}

func (s *Server) renameNode(ctx context.Context, _ *mcp.CallToolRequest, in RenameInput) (*mcp.CallToolResult, NodeOutput, error) {
	p, err := principal(ctx, "files:write")
	if err != nil {
		return nil, NodeOutput{}, err
	}
	id, err := s.nodeByPath(ctx, p.UserID, in.NodeID, in.Path)
	if err != nil {
		return nil, NodeOutput{}, err
	}
	node, err := s.nodes.Rename(ctx, p.UserID, id, in.Name)
	return nil, nodeFromStore(node), err
}

type MoveCopyInput struct {
	TargetPath     *string  `json:"target_path,omitempty" jsonschema:"absolute destination folder path instead of target_folder_id"`
	IdempotencyKey string   `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 bytes"`
	NodeIDs        []string `json:"node_ids" jsonschema:"node UUIDs to process"`
	TargetFolderID *string  `json:"target_folder_id,omitempty" jsonschema:"destination folder UUID; omit for root"`
}

type NodesOutput struct {
	Items []NodeOutput `json:"items"`
}

func (s *Server) moveNodes(ctx context.Context, _ *mcp.CallToolRequest, in MoveCopyInput) (*mcp.CallToolResult, NodesOutput, error) {
	return s.moveOrCopy(ctx, in, false)
}

func (s *Server) copyNodes(ctx context.Context, _ *mcp.CallToolRequest, in MoveCopyInput) (*mcp.CallToolResult, NodesOutput, error) {
	return s.moveOrCopy(ctx, in, true)
}

func (s *Server) moveOrCopy(ctx context.Context, in MoveCopyInput, copyMode bool) (*mcp.CallToolResult, NodesOutput, error) {
	p, err := principal(ctx, "files:write")
	if err != nil {
		return nil, NodesOutput{}, err
	}
	ids, err := parseIDs(in.NodeIDs)
	if err != nil {
		return nil, NodesOutput{}, err
	}
	target, err := s.parentByPath(ctx, p.UserID, in.TargetFolderID, in.TargetPath)
	if err != nil {
		return nil, NodesOutput{}, err
	}
	var nodes []store.Node
	if copyMode {
		nodes, err = s.nodes.Copy(ctx, p.UserID, ids, target)
	} else {
		nodes, err = s.nodes.Move(ctx, p.UserID, ids, target)
	}
	if err != nil {
		return nil, NodesOutput{}, err
	}
	out := NodesOutput{Items: make([]NodeOutput, 0, len(nodes))}
	for _, node := range nodes {
		out.Items = append(out.Items, nodeFromStore(node))
	}
	return nil, out, nil
}

type NodeIDsInput struct {
	IdempotencyKey string   `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 bytes"`
	NodeIDs        []string `json:"node_ids" jsonschema:"node UUIDs to process"`
}

type SuccessOutput struct {
	Success bool `json:"success"`
}

func (s *Server) trashNodes(ctx context.Context, _ *mcp.CallToolRequest, in NodeIDsInput) (*mcp.CallToolResult, SuccessOutput, error) {
	p, err := principal(ctx, "files:delete")
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	ids, err := parseIDs(in.NodeIDs)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	err = s.nodes.Delete(ctx, p.UserID, ids)
	return nil, SuccessOutput{Success: err == nil}, err
}

func (s *Server) restoreNodes(ctx context.Context, _ *mcp.CallToolRequest, in NodeIDsInput) (*mcp.CallToolResult, NodesOutput, error) {
	p, err := principal(ctx, "files:delete")
	if err != nil {
		return nil, NodesOutput{}, err
	}
	ids, err := parseIDs(in.NodeIDs)
	if err != nil {
		return nil, NodesOutput{}, err
	}
	nodes, err := s.nodes.Restore(ctx, p.UserID, ids)
	if err != nil {
		return nil, NodesOutput{}, err
	}
	out := NodesOutput{Items: make([]NodeOutput, 0, len(nodes))}
	for _, node := range nodes {
		out.Items = append(out.Items, nodeFromStore(node))
	}
	return nil, out, nil
}

type PrepareUploadInput struct {
	ParentPath     *string `json:"parent_path,omitempty" jsonschema:"absolute parent folder path instead of parent_id"`
	ParentID       *string `json:"parent_id,omitempty" jsonschema:"destination folder UUID; omit for root"`
	Name           string  `json:"name" jsonschema:"target file name"`
	Size           int64   `json:"size" jsonschema:"file size in bytes"`
	ContentType    string  `json:"content_type,omitempty" jsonschema:"MIME type"`
	SHA256         *string `json:"sha256,omitempty" jsonschema:"optional lowercase SHA-256; enables instant upload"`
	Transport      string  `json:"transport,omitempty" jsonschema:"auto, single_put, or multipart"`
	IdempotencyKey *string `json:"idempotency_key,omitempty" jsonschema:"stable retry key, maximum 128 characters"`
}

type PartURL struct {
	PartNumber int    `json:"part_number"`
	URL        string `json:"url"`
}

type UploadOutput struct {
	Mode          string      `json:"mode"`
	UploadID      string      `json:"upload_id,omitempty"`
	Status        string      `json:"status"`
	Node          *NodeOutput `json:"node,omitempty"`
	PutURL        string      `json:"put_url,omitempty"`
	PartSize      int32       `json:"part_size,omitempty"`
	TotalParts    int         `json:"total_parts,omitempty"`
	PartURLs      []PartURL   `json:"part_urls,omitempty"`
	UploadedParts []int       `json:"uploaded_parts,omitempty"`
	ExpiresAt     string      `json:"expires_at,omitempty"`
	SHA256        *string     `json:"sha256,omitempty"`
	Failure       *string     `json:"failure,omitempty"`
}

func uploadOutput(init *service.AgentUploadInit) UploadOutput {
	if init.Node != nil {
		node := nodeFromStore(*init.Node)
		return UploadOutput{Mode: "instant", Status: "ready", Node: &node}
	}
	return transferOutput(init.Mode, init.Transfer)
}

func transferOutput(mode string, view *service.AgentTransferView) UploadOutput {
	sess := view.Session
	out := UploadOutput{
		Mode: mode, UploadID: sess.ID.String(), Status: sess.Status, PutURL: view.PutURL,
		PartSize: sess.PartSize, TotalParts: view.TotalParts, UploadedParts: view.UploadedParts,
		ExpiresAt: sess.ExpiresAt.Time.Format(time.RFC3339), SHA256: sess.ComputedSha256, Failure: sess.FailReason,
	}
	numbers := make([]int, 0, len(view.PartURLs))
	for number := range view.PartURLs {
		numbers = append(numbers, number)
	}
	sort.Ints(numbers)
	for _, number := range numbers {
		out.PartURLs = append(out.PartURLs, PartURL{PartNumber: number, URL: view.PartURLs[number]})
	}
	if sess.NodeID != nil {
		node := NodeOutput{ID: sess.NodeID.String(), Name: sess.TargetName, Kind: "file"}
		out.Node = &node
	}
	return out
}

func (s *Server) prepareUpload(ctx context.Context, _ *mcp.CallToolRequest, in PrepareUploadInput) (*mcp.CallToolResult, UploadOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, UploadOutput{}, err
	}
	parent, err := s.parentByPath(ctx, p.UserID, in.ParentID, in.ParentPath)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	init, err := s.uploads.PrepareAgentUpload(ctx, p.UserID, parent, in.Name, in.Size, in.ContentType, in.SHA256, in.Transport, in.IdempotencyKey)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	return nil, uploadOutput(init), nil
}

type UploadPartsInput struct {
	UploadID  string `json:"upload_id" jsonschema:"upload UUID returned by prepare_upload"`
	FirstPart int    `json:"first_part,omitempty" jsonschema:"first part number, default 1"`
	Limit     int    `json:"limit,omitempty" jsonschema:"number of part URLs, maximum 100"`
}

func (s *Server) getUploadParts(ctx context.Context, _ *mcp.CallToolRequest, in UploadPartsInput) (*mcp.CallToolResult, UploadOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, UploadOutput{}, err
	}
	id, err := parseID(in.UploadID)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	view, err := s.uploads.AgentTransfer(ctx, p.UserID, id, in.FirstPart, in.Limit)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	return nil, transferOutput(view.Session.Transport, view), nil
}

func (s *Server) completeUpload(ctx context.Context, _ *mcp.CallToolRequest, in UploadPartsInput) (*mcp.CallToolResult, UploadOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, UploadOutput{}, err
	}
	id, err := parseID(in.UploadID)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	if _, err := s.uploads.CompleteAgentTransfer(ctx, p.UserID, id); err != nil {
		return nil, UploadOutput{}, err
	}
	view, err := s.uploads.AgentTransfer(ctx, p.UserID, id, 1, 1)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	return nil, transferOutput(view.Session.Transport, view), nil
}

func (s *Server) getUploadStatus(ctx context.Context, _ *mcp.CallToolRequest, in UploadPartsInput) (*mcp.CallToolResult, UploadOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, UploadOutput{}, err
	}
	id, err := parseID(in.UploadID)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	view, err := s.uploads.AgentTransfer(ctx, p.UserID, id, 1, 1)
	if err != nil {
		return nil, UploadOutput{}, err
	}
	return nil, transferOutput(view.Session.Transport, view), nil
}

func (s *Server) abortUpload(ctx context.Context, _ *mcp.CallToolRequest, in UploadPartsInput) (*mcp.CallToolResult, SuccessOutput, error) {
	p, err := principal(ctx, "files:upload")
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	id, err := parseID(in.UploadID)
	if err != nil {
		return nil, SuccessOutput{}, err
	}
	err = s.uploads.AbortAgentTransfer(ctx, p.UserID, id)
	return nil, SuccessOutput{Success: err == nil}, err
}

type DownloadOutput struct {
	Method      string `json:"method"`
	URL         string `json:"url"`
	Filename    string `json:"filename"`
	Size        int64  `json:"size"`
	ContentType string `json:"content_type"`
	Archive     bool   `json:"archive"`
	ExpiresIn   int64  `json:"expires_in_seconds"`
}

type PrepareDownloadInput struct {
	Paths   []string `json:"paths,omitempty" jsonschema:"absolute file or folder paths instead of node_id/node_ids"`
	NodeID  *string  `json:"node_id,omitempty" jsonschema:"single node UUID; use node_ids for a batch"`
	NodeIDs []string `json:"node_ids,omitempty" jsonschema:"one or more file or folder UUIDs"`
}

func (s *Server) prepareDownload(ctx context.Context, _ *mcp.CallToolRequest, in PrepareDownloadInput) (*mcp.CallToolResult, DownloadOutput, error) {
	p, err := principal(ctx, "files:download")
	if err != nil {
		return nil, DownloadOutput{}, err
	}
	rawIDs := append([]string(nil), in.NodeIDs...)
	if len(in.Paths) > 0 {
		if in.NodeID != nil || len(in.NodeIDs) > 0 {
			return nil, DownloadOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "paths 与节点 ID 不能同时提供"}
		}
		if len(in.Paths) > 100 {
			return nil, DownloadOutput{}, &service.Error{Code: "INVALID_INPUT", Message: "最多 100 个路径"}
		}
		for _, path := range in.Paths {
			id, err := s.nodeByPath(ctx, p.UserID, "", &path)
			if err != nil {
				return nil, DownloadOutput{}, err
			}
			rawIDs = append(rawIDs, id.String())
		}
	}
	if in.NodeID != nil && *in.NodeID != "" {
		rawIDs = append(rawIDs, *in.NodeID)
	}
	ids, err := parseIDs(rawIDs)
	if err != nil {
		return nil, DownloadOutput{}, err
	}
	if len(ids) == 1 {
		info, err := s.uploads.NodeInfo(ctx, p.UserID, ids[0])
		if err != nil {
			return nil, DownloadOutput{}, err
		}
		if info.Kind == "file" && info.BlobSize != nil {
			u, err := s.uploads.DownloadURL(ctx, p.UserID, ids[0])
			if err != nil {
				return nil, DownloadOutput{}, err
			}
			contentType := "application/octet-stream"
			if info.BlobMime != nil {
				contentType = *info.BlobMime
			}
			return nil, DownloadOutput{
				Method: "GET", URL: u, Filename: info.Name, Size: *info.BlobSize,
				ContentType: contentType, ExpiresIn: 15 * 60,
			}, nil
		}
	}
	ident := &service.Identity{UserID: p.UserID, Scope: service.ScopeUser}
	name, entries, err := s.packer.Collect(ctx, ident, ids)
	if err != nil {
		return nil, DownloadOutput{}, err
	}
	ticket, err := s.tickets.Issue(p.UserID, ids)
	if err != nil {
		return nil, DownloadOutput{}, err
	}
	base, _ := ctx.Value(baseURLKey{}).(string)
	return nil, DownloadOutput{
		Method: "GET", URL: base + "/mcp-download/" + url.PathEscape(ticket), Filename: name,
		Size: service.PackEntriesSize(entries), ContentType: "application/zip", Archive: true, ExpiresIn: 10 * 60,
	}, nil
}

func (s *Server) registerTools(server *mcp.Server) {
	mcp.AddTool(server, &mcp.Tool{Name: "wait_upload", Description: "Wait up to 25 seconds for an upload to become ready, failed, or aborted. On timeout returns latest status; repeat if nonterminal."}, s.waitUpload)
	mcp.AddTool(server, &mcp.Tool{Name: "resolve_path", Description: "Resolve an absolute, case-sensitive Gopan path to a node ID. / is the virtual root."}, s.resolvePath)
	mcp.AddTool(server, &mcp.Tool{Name: "create_directories", Description: "Atomically create missing parent directories; reuse existing directories. Supports persistent idempotency."}, mutation(s, "create_directories", "files:write", (*Server).createDirectories))
	mcp.AddTool(server, &mcp.Tool{Name: "list_trash", Description: "List recoverable trash with deletion times and cursor pagination. Use returned IDs with restore_nodes or batch_nodes."}, s.listTrash)
	mcp.AddTool(server, &mcp.Tool{Name: "batch_nodes", Description: "Move, copy, trash, or restore up to 100 nodes with individual success/error results. dry_run simulates then rolls back; preview IDs are not usable. idempotency_key replays the original entire result; use a new key to retry failed items."}, s.batchMutation)
	mcp.AddTool(server, &mcp.Tool{Name: "list_files", Description: "List files and folders in a Gopan folder with cursor pagination."}, s.listFiles)
	mcp.AddTool(server, &mcp.Tool{Name: "search_files", Description: "Search the user's Gopan files and folders by name."}, s.searchFiles)
	mcp.AddTool(server, &mcp.Tool{Name: "get_file_info", Description: "Get metadata for one Gopan file or folder."}, s.getFileInfo)
	mcp.AddTool(server, &mcp.Tool{Name: "read_text_file", Description: "Read up to 64 KiB from a UTF-8 text file without downloading binary content into context."}, s.readTextFile)
	mcp.AddTool(server, &mcp.Tool{Name: "create_folder", Description: "Create a folder in Gopan."}, mutation(s, "create_folder", "files:write", (*Server).createFolder))
	mcp.AddTool(server, &mcp.Tool{Name: "rename_node", Description: "Rename one Gopan file or folder."}, mutation(s, "rename_node", "files:write", (*Server).renameNode))
	mcp.AddTool(server, &mcp.Tool{Name: "move_nodes", Description: "Move multiple files or folders in one operation."}, mutation(s, "move_nodes", "files:write", (*Server).moveNodes))
	mcp.AddTool(server, &mcp.Tool{Name: "copy_nodes", Description: "Copy multiple files or folders in one operation."}, mutation(s, "copy_nodes", "files:write", (*Server).copyNodes))
	mcp.AddTool(server, &mcp.Tool{Name: "trash_nodes", Description: "Move files or folders to the recoverable trash."}, mutation(s, "trash_nodes", "files:delete", (*Server).trashNodes))
	mcp.AddTool(server, &mcp.Tool{Name: "restore_nodes", Description: "Restore files or folders from trash."}, mutation(s, "restore_nodes", "files:delete", (*Server).restoreNodes))
	mcp.AddTool(server, &mcp.Tool{Name: "prepare_upload", Description: "Create a direct upload target. Optional SHA-256 enables instant upload; bytes must be sent by the agent using another HTTP-capable tool."}, s.prepareUpload)
	mcp.AddTool(server, &mcp.Tool{Name: "get_upload_parts", Description: "Refresh direct upload URLs and inspect uploaded multipart parts."}, s.getUploadParts)
	mcp.AddTool(server, &mcp.Tool{Name: "complete_upload", Description: "Declare a direct upload complete and start server-side hashing, verification, deduplication, and commit."}, s.completeUpload)
	mcp.AddTool(server, &mcp.Tool{Name: "get_upload_status", Description: "Poll an upload until ready or failed."}, s.getUploadStatus)
	mcp.AddTool(server, &mcp.Tool{Name: "abort_upload", Description: "Abort an active upload and remove staged data."}, s.abortUpload)
	mcp.AddTool(server, &mcp.Tool{Name: "prepare_download", Description: "Create a short-lived direct GET URL for one file, or a restricted ZIP ticket for folders and multiple nodes."}, s.prepareDownload)
}
