package service

// 收集文件(doc/20 第二节):属主对一个文件夹开收集链接,访客不登录往里传。
//
// 访客令牌的 scope 是 collect:{链接 id}:{访客 id},sub 仍是属主。现有读接口的访客分支都汇到
// Shares.Authorize / ValidateScope,它们只认 share: 前缀,所以这种令牌在那里一律被拒(fail closed):
// 收集链接只写不读。上传沿用浏览器分片会话(doc/15),只在 init 与落库两处分叉。

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"github.com/yophon/gopan/server/internal/store"
)

// CollectGuestTTL 访客令牌有效期。每次请求都回表复查链接状态,撤销即时生效;
// 有效期只决定"多久要重新输一次密码",比分享访客长,大文件传得完。
const CollectGuestTTL = 12 * time.Hour

const (
	maxCollectSessionsPerLink    = 20
	maxCollectSessionsPerVisitor = 4
	maxCollectTitle              = 100 // 字符
	maxCollectNote               = 1000
	maxUploaderName              = 50
	unnamedUploader              = "未署名"
)

var (
	errCollectClosed = &Error{Code: "COLLECT_CLOSED", Message: "收集已结束"}
	errCollectFull   = &Error{Code: "COLLECT_FULL", Message: "收件已满,请联系对方"}
)

// CollectScope 拼出访客令牌的 scope。
func CollectScope(requestID, visitorID uuid.UUID) string {
	return "collect:" + requestID.String() + ":" + visitorID.String()
}

// CollectScopeIDs 从 scope 里解出链接与访客 id。
func CollectScopeIDs(scope string) (requestID, visitorID uuid.UUID, ok bool) {
	raw, found := strings.CutPrefix(scope, "collect:")
	if !found {
		return uuid.Nil, uuid.Nil, false
	}
	a, b, found := strings.Cut(raw, ":")
	if !found {
		return uuid.Nil, uuid.Nil, false
	}
	r, err1 := uuid.Parse(a)
	v, err2 := uuid.Parse(b)
	if err1 != nil || err2 != nil {
		return uuid.Nil, uuid.Nil, false
	}
	return r, v, true
}

type Collects struct {
	pool interface {
		Begin(context.Context) (pgx.Tx, error)
	}
	q       *store.Queries
	auth    *Auth
	uploads *Uploads

	openLimiter *keyedLimiter // 打开链接:按 IP
	pwFails     *keyedLimiter // 错密码:按链接,换 IP 也躲不过
	initLimiter *keyedLimiter // 开上传会话:按访客
}

func NewCollects(pool *pgxpool.Pool, auth *Auth, uploads *Uploads) *Collects {
	return newCollects(pool, store.New(pool), auth, uploads)
}

func newCollects(pool interface {
	Begin(context.Context) (pgx.Tx, error)
}, q *store.Queries, auth *Auth, uploads *Uploads) *Collects {
	return &Collects{
		pool: pool, q: q, auth: auth, uploads: uploads,
		openLimiter: newKeyedLimiter(6*time.Second, 10),
		pwFails:     newKeyedLimiter(3*time.Minute, 20), // 一小时最多错 20 次
		initLimiter: newKeyedLimiter(time.Second, 60),
	}
}

// SetRateLimits 调整限速,测试注入用。
func (s *Collects) SetRateLimits(open, pwFail, init time.Duration, burst int) {
	s.openLimiter.SetRate(open, burst)
	s.pwFails.SetRate(pwFail, burst)
	s.initLimiter.SetRate(init, burst)
}

// ---- 属主操作 ----

type FileRequestInput struct {
	Title           string
	Note            string
	RequireName     bool
	GroupByUploader bool
	MaxFileSize     *int64
	MaxTotalBytes   *int64
	MaxFiles        *int32
	ExpiresAt       *time.Time
}

func (in *FileRequestInput) normalize() error {
	in.Title = strings.TrimSpace(in.Title)
	in.Note = strings.TrimSpace(in.Note)
	if in.Title == "" || utf8.RuneCountInString(in.Title) > maxCollectTitle {
		return errf("INVALID_INPUT", "标题不能为空且不超过 %d 个字", maxCollectTitle)
	}
	if utf8.RuneCountInString(in.Note) > maxCollectNote {
		return errf("INVALID_INPUT", "说明不超过 %d 个字", maxCollectNote)
	}
	for _, v := range []*int64{in.MaxFileSize, in.MaxTotalBytes} {
		if v != nil && *v <= 0 {
			return errf("INVALID_INPUT", "上限必须是正数")
		}
	}
	if in.MaxFiles != nil && *in.MaxFiles <= 0 {
		return errf("INVALID_INPUT", "上限必须是正数")
	}
	if in.ExpiresAt != nil && !in.ExpiresAt.After(time.Now()) {
		return errf("INVALID_INPUT", "截止时间必须是将来的时间")
	}
	if in.GroupByUploader {
		in.RequireName = true // 按上传者分文件夹,得知道是谁
	}
	return nil
}

func (s *Collects) Create(ctx context.Context, owner, folderID uuid.UUID, in FileRequestInput, password *string) (store.FileRequest, error) {
	if err := in.normalize(); err != nil {
		return store.FileRequest{}, err
	}
	n, err := s.q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: folderID, OwnerID: owner})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return store.FileRequest{}, ErrNotFound
		}
		return store.FileRequest{}, err
	}
	if n.Kind != "folder" {
		return store.FileRequest{}, errf("NOT_A_FOLDER", "只能对文件夹收集文件")
	}
	var pwHash *string
	if password != nil && *password != "" {
		if len(*password) > 72 {
			return store.FileRequest{}, errf("INVALID_INPUT", "密码过长")
		}
		h, err := bcrypt.GenerateFromPassword([]byte(*password), 12)
		if err != nil {
			return store.FileRequest{}, err
		}
		hs := string(h)
		pwHash = &hs
	}
	for range 3 { // token 唯一冲突概率极小,兜底重试几次
		token, err := shareToken()
		if err != nil {
			return store.FileRequest{}, err
		}
		r, err := s.q.CreateFileRequest(ctx, store.CreateFileRequestParams{
			ID: uuid.Must(uuid.NewV7()), Token: token, OwnerID: owner, FolderID: folderID,
			Title: in.Title, Note: in.Note, PasswordHash: pwHash,
			RequireName: in.RequireName, GroupByUploader: in.GroupByUploader,
			MaxFileSize: in.MaxFileSize, MaxTotalBytes: in.MaxTotalBytes, MaxFiles: in.MaxFiles,
			ExpiresAt: optTstz(in.ExpiresAt),
		})
		if isUniqueViolation(err) {
			continue
		}
		return r, err
	}
	return store.FileRequest{}, errf("INTERNAL", "生成链接失败")
}

func (s *Collects) Update(ctx context.Context, owner, id uuid.UUID, in FileRequestInput) (store.FileRequest, error) {
	if err := in.normalize(); err != nil {
		return store.FileRequest{}, err
	}
	r, err := s.q.UpdateFileRequest(ctx, store.UpdateFileRequestParams{
		ID: id, OwnerID: owner, Title: in.Title, Note: in.Note,
		RequireName: in.RequireName, GroupByUploader: in.GroupByUploader,
		MaxFileSize: in.MaxFileSize, MaxTotalBytes: in.MaxTotalBytes, MaxFiles: in.MaxFiles,
		ExpiresAt: optTstz(in.ExpiresAt),
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.FileRequest{}, ErrNotFound
	}
	return r, err
}

func (s *Collects) Revoke(ctx context.Context, owner, id uuid.UUID) error {
	n, err := s.q.RevokeFileRequest(ctx, store.RevokeFileRequestParams{ID: id, OwnerID: owner})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// FileRequestRow 列表里的一条:链接本身 + 目标文件夹 + 收件统计。
type FileRequestRow = store.ListMyFileRequestsRow

func (s *Collects) List(ctx context.Context, owner uuid.UUID) ([]store.ListMyFileRequestsRow, error) {
	return s.q.ListMyFileRequests(ctx, owner)
}

// Get 列表里的一条(创建、修改后回显统计用;属主的链接不多,直接复用列表查询)。
func (s *Collects) Get(ctx context.Context, owner, id uuid.UUID) (store.ListMyFileRequestsRow, error) {
	rows, err := s.List(ctx, owner)
	if err != nil {
		return store.ListMyFileRequestsRow{}, err
	}
	for _, r := range rows {
		if r.ID == id {
			return r, nil
		}
	}
	return store.ListMyFileRequestsRow{}, ErrNotFound
}

// Uploads 收件记录,新的在前;before 是上一页最后一条的 id。
func (s *Collects) Uploads(ctx context.Context, owner, id uuid.UUID, before *int64, limit int32) ([]store.ListFileRequestUploadsRow, error) {
	r, err := s.q.GetFileRequestByID(ctx, id)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if r.OwnerID != owner {
		return nil, ErrNotFound // 不泄露"这条链接存不存在"
	}
	return s.q.ListFileRequestUploads(ctx, store.ListFileRequestUploadsParams{RequestID: id, Before: before, Limit: limit})
}

func (s *Collects) MarkSeen(ctx context.Context, owner, id uuid.UUID) error {
	n, err := s.q.MarkFileRequestSeen(ctx, store.MarkFileRequestSeenParams{ID: id, OwnerID: owner})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// ---- 公开 / 访客 ----

type FileRequestInfoView struct {
	Title        string
	Note         string
	NeedPassword bool
	NeedName     bool
	MaxFileSize  *int64
	ExpiresAt    *time.Time
	Closed       bool
}

// Info 访客打开链接时看到的信息。不含属主信息,也不含属主剩余空间。
func (s *Collects) Info(ctx context.Context, token string) (*FileRequestInfoView, error) {
	r, err := s.q.GetFileRequestByToken(ctx, token)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	v := &FileRequestInfoView{
		Title: r.Title, Note: r.Note,
		NeedPassword: r.PasswordHash != nil,
		NeedName:     r.RequireName || r.GroupByUploader,
		MaxFileSize:  r.MaxFileSize,
		Closed:       collectDead(r.RevokedAt.Valid, r.FolderDeletedAt.Valid, r.OwnerDisabled) || collectExpired(r.ExpiresAt, time.Now()),
	}
	if r.ExpiresAt.Valid {
		t := r.ExpiresAt.Time
		v.ExpiresAt = &t
	}
	return v, nil
}

// Open 验密码、记名字,签发访客令牌。
func (s *Collects) Open(ctx context.Context, token, password, name, ip string) (string, error) {
	if !s.openLimiter.Allow("open:" + ip) {
		return "", ErrRateLimited
	}
	r, err := s.q.GetFileRequestByToken(ctx, token)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", ErrNotFound
		}
		return "", err
	}
	if collectDead(r.RevokedAt.Valid, r.FolderDeletedAt.Valid, r.OwnerDisabled) || collectExpired(r.ExpiresAt, time.Now()) {
		return "", errCollectClosed
	}
	if r.PasswordHash != nil {
		if password == "" {
			return "", errf("COLLECT_PASSWORD_REQUIRED", "需要密码")
		}
		key := "pw:" + r.ID.String()
		if s.pwFails.Exhausted(key) {
			return "", errf("RATE_LIMITED", "密码错误次数过多,稍后再试")
		}
		if bcrypt.CompareHashAndPassword([]byte(*r.PasswordHash), []byte(password)) != nil {
			s.pwFails.Allow(key)
			return "", errf("BAD_COLLECT_PASSWORD", "密码错误")
		}
	}
	name, err = cleanUploaderName(name)
	if err != nil {
		return "", err
	}
	if name == "" && (r.RequireName || r.GroupByUploader) {
		return "", errf("COLLECT_NAME_REQUIRED", "请填写你的名字")
	}
	ua := ClientMetaFrom(ctx).UA
	if len(ua) > sessionMaxUA {
		ua = ua[:sessionMaxUA]
	}
	v, err := s.q.CreateFileRequestVisitor(ctx, store.CreateFileRequestVisitorParams{
		ID: uuid.Must(uuid.NewV7()), RequestID: r.ID, Name: name,
		Ip: ptrIfNotEmpty(ip), UserAgent: ptrIfNotEmpty(ua),
	})
	if err != nil {
		return "", err
	}
	return s.auth.IssueAccessFor(r.OwnerID, CollectScope(r.ID, v.ID), uuid.Nil, CollectGuestTTL)
}

// CollectGuest 一个已通过校验的访客。
type CollectGuest struct {
	Request store.GetFileRequestByIDRow
	Visitor store.FileRequestVisitor
}

// Guest 从身份里解出链接与访客并校验。截止时间不在这里判:截止前已经开始的上传允许传完,
// 新开会话(InitUpload)才要求未截止。撤销、文件夹进回收站、属主被禁用则立即拒绝。
func (s *Collects) Guest(ctx context.Context, ident *Identity) (*CollectGuest, error) {
	reqID, visitorID, ok := CollectScopeIDs(ident.Scope)
	if !ok {
		return nil, ErrForbidden
	}
	r, err := s.q.GetFileRequestByID(ctx, reqID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errCollectClosed
		}
		return nil, err
	}
	if r.OwnerID != ident.UserID {
		return nil, ErrForbidden
	}
	if collectDead(r.RevokedAt.Valid, r.FolderDeletedAt.Valid, r.OwnerDisabled) {
		return nil, errCollectClosed
	}
	v, err := s.q.GetFileRequestVisitor(ctx, visitorID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrUnauthenticated // 访客行被清理了:重新打开链接即可
		}
		return nil, err
	}
	if v.RequestID != r.ID {
		return nil, ErrForbidden
	}
	return &CollectGuest{Request: r, Visitor: v}, nil
}

// InitUpload 访客开一个上传会话。没有秒传:不收 sha256,也就无从探测"某份内容是否有人存过"。
func (s *Collects) InitUpload(ctx context.Context, g *CollectGuest, name string, size int64) (*SessionView, error) {
	if !s.initLimiter.Allow("visitor:" + g.Visitor.ID.String()) {
		return nil, ErrRateLimited
	}
	r := g.Request
	if collectExpired(r.ExpiresAt, time.Now()) {
		return nil, errCollectClosed
	}
	name = strings.TrimSpace(name)
	if err := validateName(name); err != nil {
		return nil, err
	}
	if size <= 0 || size > 1<<40 {
		return nil, errf("INVALID_INPUT", "文件大小非法")
	}
	if r.MaxFileSize != nil && size > *r.MaxFileSize {
		return nil, errf("FILE_TOO_LARGE", "文件超过单个上限")
	}

	// 预检不加锁,挡掉明显超限的;下面的事务里锁住链接行与属主行再复核一次
	if err := s.checkInit(ctx, s.q, r, g.Visitor.ID, size); err != nil {
		return nil, err
	}

	id := uuid.Must(uuid.NewV7())
	// key 里不放属主的用户 id:预签名地址会到访客手里
	key := "staging/collect/" + r.ID.String() + "/" + id.String()
	uploadID, err := s.uploads.obj.NewMultipart(ctx, key, mimeByName(name))
	if err != nil {
		return nil, err
	}
	sess, err := s.createSession(ctx, g, id, key, uploadID, name, size)
	if err != nil {
		_ = s.uploads.obj.AbortMultipart(context.WithoutCancel(ctx), key, uploadID)
		return nil, err
	}
	view, err := s.uploads.sessionView(ctx, sess)
	if err != nil {
		_ = s.uploads.Abort(context.WithoutCancel(ctx), sess.OwnerID, id)
		return nil, err
	}
	return view, nil
}

func (s *Collects) createSession(ctx context.Context, g *CollectGuest, id uuid.UUID, key, uploadID, name string, size int64) (store.UploadSession, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.UploadSession{}, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	// 锁序:链接行 → 属主行,与落库(resolveCollectTarget)一致
	locked, err := q.LockFileRequest(ctx, g.Request.ID)
	if err != nil {
		return store.UploadSession{}, err
	}
	r := store.GetFileRequestByIDRow(locked)
	if collectDead(r.RevokedAt.Valid, r.FolderDeletedAt.Valid, r.OwnerDisabled) || collectExpired(r.ExpiresAt, time.Now()) {
		return store.UploadSession{}, errCollectClosed
	}
	if _, err := q.GetUserByIDForUpdate(ctx, r.OwnerID); err != nil {
		return store.UploadSession{}, err
	}
	if err := s.checkInit(ctx, q, r, g.Visitor.ID, size); err != nil {
		return store.UploadSession{}, err
	}
	sess, err := q.CreateCollectUploadSession(ctx, store.CreateCollectUploadSessionParams{
		ID: id, OwnerID: r.OwnerID, Size: size,
		TargetParent: &r.FolderID, TargetName: name,
		MinioUploadID: uploadID, PartSize: int32(s.uploads.partSize),
		ExpiresAt: tstz(time.Now().Add(s.uploads.ttl)), ObjectKey: &key,
		RequestID: &r.ID, VisitorID: &g.Visitor.ID,
	})
	if err != nil {
		return store.UploadSession{}, err
	}
	return sess, tx.Commit(ctx)
}

// checkInit 开会话前的额度检查:链接的个数 / 总量上限与属主配额都把进行中的会话算进去(预留),
// 否则一群访客同时开传,每个都"还没超",落库时才一起失败。
func (s *Collects) checkInit(ctx context.Context, q *store.Queries, r store.GetFileRequestByIDRow, visitor uuid.UUID, size int64) error {
	usage, err := q.FileRequestUsage(ctx, r.ID)
	if err != nil {
		return err
	}
	pending, err := q.FileRequestPending(ctx, &r.ID)
	if err != nil {
		return err
	}
	if r.MaxFiles != nil && usage.Files+pending.Sessions+1 > int64(*r.MaxFiles) {
		return errCollectFull
	}
	if r.MaxTotalBytes != nil && usage.Bytes+pending.Bytes+size > *r.MaxTotalBytes {
		return errCollectFull
	}
	if pending.Sessions >= maxCollectSessionsPerLink {
		return errf("TOO_MANY_SESSIONS", "同时上传的人太多,稍后再试")
	}
	mine, err := q.CountVisitorSessions(ctx, &visitor)
	if err != nil {
		return err
	}
	if mine >= maxCollectSessionsPerVisitor {
		return errf("TOO_MANY_SESSIONS", "同时上传的文件过多,等前面的传完")
	}
	u, err := q.GetUserByID(ctx, r.OwnerID)
	if err != nil {
		return err
	}
	if u.DisabledAt.Valid {
		return errCollectClosed
	}
	reserved, err := q.SumOwnerCollectPendingBytes(ctx, r.OwnerID)
	if err != nil {
		return err
	}
	if u.UsedBytes+reserved+size > u.QuotaBytes {
		return errCollectFull // 不向访客透露属主的空间状态
	}
	return nil
}

func (s *Collects) session(ctx context.Context, g *CollectGuest, id uuid.UUID) (store.UploadSession, error) {
	sess, err := s.q.GetCollectUploadSession(ctx, store.GetCollectUploadSessionParams{
		ID: id, RequestID: &g.Request.ID, VisitorID: &g.Visitor.ID,
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.UploadSession{}, ErrNotFound
	}
	return sess, err
}

// UploadSession 断点续传:已传分片与缺失分片的新预签名。
func (s *Collects) UploadSession(ctx context.Context, g *CollectGuest, id uuid.UUID) (*SessionView, error) {
	sess, err := s.session(ctx, g, id)
	if err != nil {
		return nil, err
	}
	if sess.Status != "uploading" {
		return &SessionView{Session: sess}, nil
	}
	return s.uploads.sessionView(ctx, sess)
}

type CollectUploadView struct {
	Name      string
	Size      int64
	CreatedAt time.Time
}

// CompleteUpload 与登录用户的 completeUpload 同语义(处理中返回 UPLOAD_PROCESSING,客户端轮询),
// 只是不返回节点:访客只该知道"送达了,叫什么名字"。
func (s *Collects) CompleteUpload(ctx context.Context, g *CollectGuest, id uuid.UUID, etags map[int]string) (*CollectUploadView, error) {
	sess, err := s.session(ctx, g, id)
	if err != nil {
		return nil, err
	}
	n, err := s.uploads.Complete(ctx, sess.OwnerID, id, etags)
	if err != nil {
		return nil, err
	}
	return &CollectUploadView{Name: n.Name, Size: sess.Size, CreatedAt: n.CreatedAt.Time}, nil
}

func (s *Collects) AbortUpload(ctx context.Context, g *CollectGuest, id uuid.UUID) error {
	sess, err := s.session(ctx, g, id)
	if err != nil {
		return err
	}
	return s.uploads.Abort(ctx, sess.OwnerID, id)
}

// MyUploads 这位访客已送达的文件。
func (s *Collects) MyUploads(ctx context.Context, g *CollectGuest) ([]store.ListVisitorUploadsRow, error) {
	return s.q.ListVisitorUploads(ctx, &g.Visitor.ID)
}

// CleanupVisitors 清掉 30 天前的访客行(worker 每天调)。
func (s *Collects) CleanupVisitors(ctx context.Context) error {
	n, err := s.q.DeleteStaleFileRequestVisitors(ctx)
	if n > 0 {
		slog.Info("file request visitors cleanup", "deleted", n)
	}
	return err
}

// ---- 落库(浏览器上传的 worker 里调,见 finalizeBrowserUpload)----

type collectTarget struct {
	request store.LockFileRequestRow
	visitor *store.FileRequestVisitor
	parent  uuid.UUID
	name    string
}

// resolveCollectTarget 锁住链接行,复核链接状态与上限,定下落到哪个目录、叫什么名字。
// 名字在这里才最终确定:匿名访客撞名很常见(都叫 IMG_0001.jpg),而链接行锁住后,
// 同一链接的落库是串行的,挑出来的名字不会被另一个访客抢走。
func resolveCollectTarget(ctx context.Context, q *store.Queries, sess store.UploadSession, size int64) (*collectTarget, error) {
	r, err := q.LockFileRequest(ctx, *sess.RequestID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errCollectClosed
	}
	if err != nil {
		return nil, err
	}
	if r.OwnerID != sess.OwnerID || collectDead(r.RevokedAt.Valid, r.FolderDeletedAt.Valid, r.OwnerDisabled) {
		return nil, errCollectClosed
	}
	// 截止前开始的上传允许传完
	if collectExpired(r.ExpiresAt, sess.CreatedAt.Time) {
		return nil, errCollectClosed
	}
	if r.MaxFileSize != nil && size > *r.MaxFileSize {
		return nil, errf("FILE_TOO_LARGE", "文件超过单个上限")
	}
	usage, err := q.FileRequestUsage(ctx, r.ID)
	if err != nil {
		return nil, err
	}
	if r.MaxFiles != nil && usage.Files+1 > int64(*r.MaxFiles) {
		return nil, errCollectFull
	}
	if r.MaxTotalBytes != nil && usage.Bytes+size > *r.MaxTotalBytes {
		return nil, errCollectFull
	}
	t := &collectTarget{request: r, parent: r.FolderID}
	if sess.VisitorID != nil {
		v, err := q.GetFileRequestVisitor(ctx, *sess.VisitorID)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return nil, err
		}
		if err == nil {
			t.visitor = &v
		}
	}
	if r.GroupByUploader {
		uploader := unnamedUploader
		if t.visitor != nil && t.visitor.Name != "" {
			uploader = folderNameFor(t.visitor.Name)
		}
		if t.parent, err = uploaderFolder(ctx, q, r.OwnerID, r.FolderID, uploader); err != nil {
			return nil, err
		}
	}
	if t.name, err = freeName(ctx, q, r.OwnerID, &t.parent, sess.TargetName); err != nil {
		return nil, err
	}
	return t, nil
}

// record 写收件记录,并把服务端算出的 sha 回填到会话。
func (t *collectTarget) record(ctx context.Context, q *store.Queries, sess store.UploadSession, n store.Node, size int64, sum string) error {
	p := store.RecordFileRequestUploadParams{
		RequestID: t.request.ID, VisitorID: sess.VisitorID, NodeID: &n.ID,
		FileName: n.Name, Size: size,
	}
	if t.visitor != nil {
		p.UploaderName, p.Ip, p.UserAgent = t.visitor.Name, t.visitor.Ip, t.visitor.UserAgent
	}
	if err := q.RecordFileRequestUpload(ctx, p); err != nil {
		return err
	}
	return q.SetUploadSessionSha(ctx, store.SetUploadSessionShaParams{ID: sess.ID, Sha256: &sum})
}

// uploaderFolder 找到或建出 <链接文件夹>/<上传者>/。同名的若是文件,文件夹另取一个名字。
func uploaderFolder(ctx context.Context, q *store.Queries, owner, parent uuid.UUID, name string) (uuid.UUID, error) {
	existing, err := q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: &parent, Name: name})
	if err == nil && existing.Kind == "folder" {
		return existing.ID, nil
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return uuid.Nil, err
	}
	if err == nil { // 同名的是文件
		if name, err = freeName(ctx, q, owner, &parent, name); err != nil {
			return uuid.Nil, err
		}
	}
	n, err := q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, ParentID: &parent, Name: name, Kind: "folder",
	})
	if isUniqueViolation(err) {
		// 属主恰好同时建了同名目录:整笔回滚重来(由调用方当作可重试错误)
		return uuid.Nil, fmt.Errorf("uploader folder %q taken concurrently", name)
	}
	if err != nil {
		return uuid.Nil, err
	}
	return n.ID, nil
}

// freeName 同 Nodes.cleanName,但走事务里的查询。
func freeName(ctx context.Context, q *store.Queries, owner uuid.UUID, parent *uuid.UUID, name string) (string, error) {
	name = strings.TrimSpace(name)
	if err := validateName(name); err != nil {
		return "", err
	}
	ext := filepath.Ext(name)
	candidate := name
	for i := 1; i <= 100; i++ {
		exists, err := q.SiblingNameExists(ctx, store.SiblingNameExistsParams{OwnerID: owner, ParentID: parent, Name: candidate})
		if err != nil {
			return "", err
		}
		if !exists {
			return candidate, nil
		}
		candidate = fmt.Sprintf("%s (%d)%s", strings.TrimSuffix(name, ext), i, ext)
	}
	return "", errf("NAME_CONFLICT", "同名文件过多")
}

// cleanUploaderName 访客自填的名字:去掉首尾空白与控制字符,限长。
func cleanUploaderName(name string) (string, error) {
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, strings.TrimSpace(name))
	if utf8.RuneCountInString(name) > maxUploaderName {
		return "", errf("INVALID_INPUT", "名字不超过 %d 个字", maxUploaderName)
	}
	return name, nil
}

// folderNameFor 把上传者名字变成合法的文件夹名。
func folderNameFor(name string) string {
	name = strings.Map(func(r rune) rune {
		if r == '/' {
			return '_'
		}
		return r
	}, name)
	if name == "." || name == ".." {
		return unnamedUploader
	}
	return name
}

func collectDead(revoked, folderDeleted, ownerDisabled bool) bool {
	return revoked || folderDeleted || ownerDisabled
}

// collectExpired:at 时刻链接是否已过截止时间。
func collectExpired(expiresAt pgtype.Timestamptz, at time.Time) bool {
	return expiresAt.Valid && !at.Before(expiresAt.Time)
}

func optTstz(t *time.Time) pgtype.Timestamptz {
	if t == nil {
		return pgtype.Timestamptz{}
	}
	return tstz(*t)
}
