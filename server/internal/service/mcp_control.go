package service

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/yophon/gopan/server/internal/store"
)

func (s *Nodes) db() store.DBTX              { return s.pool.(store.DBTX) }
func (s *Nodes) MCPAdmin(quota int64) *Admin { return NewAdmin(s.q, quota) }
func (s *Nodes) MCPShares() *Shares          { return NewShares(s.q, nil) }

type MCPAccessRoot struct {
	CredentialID   string    `json:"credential_id"`
	CredentialType string    `json:"credential_type"`
	RootID         uuid.UUID `json:"root_id"`
}

func (s *Nodes) MCPAccessRoots(ctx context.Context, owner uuid.UUID) ([]MCPAccessRoot, error) {
	rows, err := s.db().Query(ctx, `SELECT credential_id,credential_type,root_id FROM mcp_access_roots WHERE owner_id=$1 ORDER BY credential_type,credential_id`, owner)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[MCPAccessRoot])
}
func (s *Nodes) SetMCPAccessRoot(ctx context.Context, owner, id uuid.UUID, kind string, path *string) error {
	var scopes []string
	var err error
	switch kind {
	case "api_key":
		err = s.db().QueryRow(ctx, `SELECT scopes FROM mcp_tokens WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL`, id, owner).Scan(&scopes)
	case "oauth":
		err = s.db().QueryRow(ctx, `SELECT scopes FROM oauth_grants WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL`, id, owner).Scan(&scopes)
	default:
		return errf("INVALID_INPUT", "credential_type 必须为 api_key 或 oauth")
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	for _, scope := range scopes {
		if len(scope) >= 6 && scope[:6] == "admin:" {
			return errf("INVALID_INPUT", "管理员凭据不能设置文件目录范围")
		}
	}
	if path == nil {
		_, err = s.db().Exec(ctx, `DELETE FROM mcp_access_roots WHERE owner_id=$1 AND credential_id=$2 AND credential_type=$3`, owner, id, kind)
		return err
	}
	node, err := s.ResolvePath(ctx, owner, *path)
	if err != nil {
		return err
	}
	if node == nil || node.Kind != "folder" {
		return errf("INVALID_INPUT", "请选择实际文件夹；全盘访问请清除范围")
	}
	_, err = s.db().Exec(ctx, `INSERT INTO mcp_access_roots(credential_id,credential_type,owner_id,root_id) VALUES($1,$2,$3,$4) ON CONFLICT(credential_id,credential_type) DO UPDATE SET root_id=excluded.root_id,updated_at=now()`, id, kind, owner, node.ID)
	return err
}
func (s *Nodes) ApplyMCPPolicy(ctx context.Context, p *MCPPrincipal) error {
	var root uuid.UUID
	err := s.db().QueryRow(ctx, `SELECT root_id FROM mcp_access_roots WHERE owner_id=$1 AND credential_id=$2 AND credential_type=$3`, p.UserID, p.CredentialID, p.CredentialType).Scan(&root)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	n, err := s.Get(ctx, p.UserID, root)
	if err != nil || n.DeletedAt.Valid || n.Kind != "folder" {
		return ErrForbidden
	}
	p.RootID = &root
	return nil
}
func (s *Nodes) CheckMCPNode(ctx context.Context, p *MCPPrincipal, id uuid.UUID, allowRoot bool) error {
	if p.RootID == nil {
		return nil
	}
	if !allowRoot && id == *p.RootID {
		return errf("FORBIDDEN", "不能移动、删除或重命名授权根目录")
	}
	if _, err := s.Get(ctx, p.UserID, id); err != nil {
		return err
	}
	inside, err := s.q.IsDescendant(ctx, store.IsDescendantParams{ID: *p.RootID, ID_2: id})
	if err != nil {
		return err
	}
	if !inside {
		return ErrNotFound
	}
	return nil
}
func (s *Nodes) CheckMCPUpload(ctx context.Context, p *MCPPrincipal, id uuid.UUID) error {
	if p.RootID == nil {
		return nil
	}
	sess, err := s.q.GetTransferSession(ctx, store.GetTransferSessionParams{ID: id, OwnerID: p.UserID})
	if err != nil {
		return ErrNotFound
	}
	if sess.TargetParent == nil {
		return ErrNotFound
	}
	if err = s.CheckMCPNode(ctx, p, *sess.TargetParent, true); err != nil {
		return err
	}
	if sess.NodeID != nil {
		return s.CheckMCPNode(ctx, p, *sess.NodeID, true)
	}
	return nil
}

type MCPStatus struct {
	Username         string   `json:"username"`
	UsedBytes        int64    `json:"used_bytes"`
	QuotaBytes       int64    `json:"quota_bytes"`
	ReservedBytes    int64    `json:"reserved_bytes"`
	AvailableBytes   int64    `json:"available_bytes"`
	ActiveUploads    int64    `json:"active_uploads"`
	MaxActiveUploads int      `json:"max_active_uploads"`
	MaxUploadBytes   int64    `json:"max_upload_bytes"`
	SinglePutLimit   int64    `json:"single_put_limit"`
	PartSize         int64    `json:"part_size"`
	RootID           *string  `json:"root_id,omitempty"`
	Scopes           []string `json:"scopes"`
}

func (s *Nodes) MCPStatus(ctx context.Context, p *MCPPrincipal, partSize int64) (MCPStatus, error) {
	u, err := s.q.GetUserByID(ctx, p.UserID)
	if err != nil {
		return MCPStatus{}, err
	}
	reserved, err := s.q.SumActiveTransferBytes(ctx, p.UserID)
	if err != nil {
		return MCPStatus{}, err
	}
	count, err := s.q.CountActiveTransferSessions(ctx, p.UserID)
	scopes := make([]string, 0, len(p.Scopes))
	for scope := range p.Scopes {
		scopes = append(scopes, scope)
	}
	var root *string
	if p.RootID != nil {
		v := p.RootID.String()
		root = &v
	}
	return MCPStatus{Username: u.Username, UsedBytes: u.UsedBytes, QuotaBytes: u.QuotaBytes, ReservedBytes: reserved, AvailableBytes: max(0, u.QuotaBytes-u.UsedBytes-reserved), ActiveUploads: count, MaxActiveUploads: 20, MaxUploadBytes: maxAgentUploadSize, SinglePutLimit: autoSinglePutLimit, PartSize: partSize, RootID: root, Scopes: scopes}, err
}
func (s *Uploads) MCPPartSize() int64 { return int64(s.partSize) }

type MCPTask struct {
	ID        string    `json:"id"`
	Kind      string    `json:"kind"`
	Status    string    `json:"status"`
	Attempts  int32     `json:"attempts"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (s *Nodes) MCPTasks(ctx context.Context, caller uuid.UUID, status string, before *string, limit int) ([]MCPTask, error) {
	if err := s.MCPAdmin(0).require(ctx, caller); err != nil {
		return nil, err
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 200 {
		return nil, errf("INVALID_INPUT", "limit 必须为 1–200")
	}
	if status != "" && status != "pending" && status != "running" && status != "failed" && status != "done" {
		return nil, errf("INVALID_INPUT", "非法任务状态")
	}
	var id *uuid.UUID
	if before != nil {
		v, err := uuid.Parse(*before)
		if err != nil {
			return nil, errf("INVALID_INPUT", "非法游标")
		}
		id = &v
	}
	rows, err := s.db().Query(ctx, `SELECT id,kind,status,attempts,updated_at FROM tasks WHERE ($1='' OR status=$1) AND ($2::uuid IS NULL OR id<$2) ORDER BY id DESC LIMIT $3`, status, id, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[MCPTask])
}

type MCPAuditItem struct {
	ID             int64          `json:"id"`
	OwnerID        string         `json:"owner_id"`
	CredentialID   string         `json:"credential_id"`
	CredentialType string         `json:"credential_type"`
	Endpoint       string         `json:"endpoint"`
	Tool           string         `json:"tool"`
	Targets        map[string]any `json:"targets"`
	Status         string         `json:"status"`
	ErrorCode      *string        `json:"error_code,omitempty"`
	CreatedAt      time.Time      `json:"created_at"`
	FinishedAt     *time.Time     `json:"finished_at,omitempty"`
}

func (s *Nodes) StartMCPAudit(ctx context.Context, p *MCPPrincipal, endpoint, tool string, targets json.RawMessage) (int64, error) {
	var id int64
	err := s.db().QueryRow(ctx, `INSERT INTO mcp_audit(owner_id,credential_id,credential_type,endpoint,tool,targets) VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, p.UserID, p.CredentialID, p.CredentialType, endpoint, tool, []byte(targets)).Scan(&id)
	return id, err
}
func (s *Nodes) FinishMCPAudit(id int64, status, code string) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, _ = s.db().Exec(ctx, `UPDATE mcp_audit SET status=$2,error_code=NULLIF($3,''),finished_at=now() WHERE id=$1`, id, status, code)
}
func (s *Nodes) MCPAudit(ctx context.Context, p *MCPPrincipal, before int64, limit int) ([]MCPAuditItem, error) {
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 200 {
		return nil, errf("INVALID_INPUT", "limit 必须为 1–200")
	}
	var credential *uuid.UUID
	if p.RootID != nil {
		credential = &p.CredentialID
	}
	admin := p.HasScope("admin:read")
	if admin {
		if err := s.MCPAdmin(0).require(ctx, p.UserID); err != nil {
			return nil, err
		}
	}
	rows, err := s.db().Query(ctx, `SELECT id,owner_id,credential_id,credential_type,endpoint,tool,targets,status,error_code,created_at,finished_at FROM mcp_audit WHERE ($5 OR owner_id=$1) AND ($2::bigint=0 OR id<$2) AND ($3::uuid IS NULL OR credential_id=$3) ORDER BY id DESC LIMIT $4`, p.UserID, before, credential, limit, admin)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[MCPAuditItem])
}
func (s *Nodes) MCPTrash(ctx context.Context, p *MCPPrincipal, cursor *string) (*Page[store.ListTrashRow], error) {
	if p.RootID == nil {
		return s.Trash(ctx, p.UserID, cursor)
	}
	var offset int
	if cursor != nil {
		n, err := strconv.Atoi(*cursor)
		if err != nil || n < 0 {
			return nil, errf("INVALID_INPUT", "非法回收站游标")
		}
		offset = n
	}
	const subtree = `WITH RECURSIVE sub AS (SELECT id FROM nodes WHERE id=$2 AND owner_id=$1 UNION ALL SELECT n.id FROM nodes n JOIN sub ON n.parent_id=sub.id WHERE n.owner_id=$1) `
	var total int64
	err := s.db().QueryRow(ctx, subtree+`SELECT count(*) FROM nodes WHERE id IN(SELECT id FROM sub) AND deleted_at IS NOT NULL`, p.UserID, *p.RootID).Scan(&total)
	if err != nil {
		return nil, err
	}
	rows, err := s.db().Query(ctx, subtree+`SELECT n.*,b.size AS blob_size,b.mime AS blob_mime,b.sha256 AS blob_sha256 FROM nodes n LEFT JOIN blobs b ON b.id=n.blob_id WHERE n.id IN(SELECT id FROM sub) AND n.deleted_at IS NOT NULL ORDER BY n.deleted_at DESC,n.id LIMIT 200 OFFSET $3`, p.UserID, *p.RootID, offset)
	if err != nil {
		return nil, err
	}
	items, err := pgx.CollectRows(rows, pgx.RowToStructByName[store.ListTrashRow])
	if err != nil {
		return nil, err
	}
	var next *string
	if int64(offset+len(items)) < total {
		v := strconv.Itoa(offset + len(items))
		next = &v
	}
	return &Page[store.ListTrashRow]{Items: items, Total: total, NextCursor: next}, nil
}
