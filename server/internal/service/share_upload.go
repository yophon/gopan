package service

// 分享允许对方上传(doc/32 一):文件夹分享打开「允许对方上传」后,访客(share:{id} 令牌)能往分享范围内的
// 文件夹传文件、建文件夹,不能删、改名、覆盖。同名的文件自动改名;建文件夹时同名文件夹已经在就直接用它
// (整个文件夹传进来时合并进去)。
//
// 上传沿用浏览器分片会话,和收集链接一样只在开会话与落库两处分叉:分享跟着会话行走(upload_sessions.share_id),
// 落库时锁住分享行,复核分享还开着上传、目标文件夹还在分享范围里,再挑名字。访客留的名字记在会话的来源里,
// 动态、访问记录、通知里都看得到。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/store"
)

// maxShareSessions 同一分享上同时进行中的访客会话。
const maxShareSessions = 20

var (
	errShareNoUpload = &Error{Code: "SHARE_NO_UPLOAD", Message: "这个分享不能上传"}
	errShareFull     = &Error{Code: "SHARE_FULL", Message: "对方的网盘空间不够了"}
)

type ShareUploads struct {
	pool interface {
		Begin(context.Context) (pgx.Tx, error)
	}
	q       *store.Queries
	shares  *Shares
	uploads *Uploads

	initLimiter  *keyedLimiter // 开上传会话:按 IP
	mkdirLimiter *keyedLimiter // 建文件夹:按 IP
}

func NewShareUploads(pool *pgxpool.Pool, shares *Shares, uploads *Uploads) *ShareUploads {
	return &ShareUploads{
		pool: pool, q: store.New(pool), shares: shares, uploads: uploads,
		initLimiter:  newKeyedLimiter(time.Second, 60),
		mkdirLimiter: newKeyedLimiter(time.Second, 60),
	}
}

// SetRateLimits 调整限速,测试注入用。
func (s *ShareUploads) SetRateLimits(interval time.Duration, burst int) {
	s.initLimiter.SetRate(interval, burst)
	s.mkdirLimiter.SetRate(interval, burst)
}

// guest 访客令牌指向的分享仍有效,并且开着上传。
func (s *ShareUploads) guest(ctx context.Context, ident *Identity) (store.GetShareByIDRow, error) {
	sh, err := s.shares.ValidateScope(ctx, ident)
	if err != nil {
		return sh, err
	}
	if !sh.AllowUpload || sh.NodeKind != "folder" {
		return sh, errShareNoUpload
	}
	return sh, nil
}

// ShareGuestActor 访客改动的来源:动态里显示「分享「旅行照片」· 小王」。
func ShareGuestActor(shareID uuid.UUID, shareName, uploader string) Actor {
	label := "分享「" + shareName + "」"
	if uploader != "" {
		label += " · " + uploader
	}
	return Actor{Kind: ActorShare, Ref: shareID.String(), Label: label}
}

// shareFolder 目标必须是分享范围内(含分享的文件夹本身)、没删除的文件夹。
func shareFolder(ctx context.Context, q *store.Queries, owner, root, folder uuid.UUID) error {
	n, err := q.GetActiveNodeOwned(ctx, store.GetActiveNodeOwnedParams{ID: folder, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if n.Kind != "folder" {
		return errf("NOT_A_FOLDER", "只能传到文件夹里")
	}
	in, err := q.IsDescendant(ctx, store.IsDescendantParams{ID: root, ID_2: folder})
	if err != nil {
		return err
	}
	if !in {
		return ErrNotFound
	}
	return nil
}

// InitUpload 访客往 parent 里开一个上传会话。和收集链接一样没有秒传。
func (s *ShareUploads) InitUpload(ctx context.Context, ident *Identity, parent uuid.UUID, name string, size int64, uploader string) (*SessionView, error) {
	sh, err := s.guest(ctx, ident)
	if err != nil {
		return nil, err
	}
	if !s.initLimiter.Allow("ip:" + ClientMetaFrom(ctx).IP) {
		return nil, ErrRateLimited
	}
	name = strings.TrimSpace(name)
	if err := validateName(name); err != nil {
		return nil, err
	}
	if size <= 0 || size > 1<<40 {
		return nil, errf("INVALID_INPUT", "文件大小非法")
	}
	if uploader, err = cleanUploaderName(uploader); err != nil {
		return nil, err
	}
	if err := shareFolder(ctx, s.q, sh.CreatedBy, sh.NodeID, parent); err != nil {
		return nil, err
	}
	// 预检不加锁,挡掉明显超限的;下面的事务里锁住分享行与属主行再复核一次
	if err := s.checkInit(ctx, s.q, sh.ID, sh.CreatedBy, size); err != nil {
		return nil, err
	}

	id := uuid.Must(uuid.NewV7())
	// key 里不放属主的用户 id:预签名地址会到访客手里
	key := "staging/share/" + sh.ID.String() + "/" + id.String()
	uploadID, err := s.uploads.obj.NewMultipart(ctx, key, mimeByName(name))
	if err != nil {
		return nil, err
	}
	meta := ClientMetaFrom(ctx)
	if len(meta.UA) > sessionMaxUA {
		meta.UA = meta.UA[:sessionMaxUA]
	}
	actor, _ := json.Marshal(sessionActor{Actor: ShareGuestActor(sh.ID, sh.NodeName, uploader), Uploader: uploader, IP: meta.IP, UA: meta.UA})
	sess, err := s.createSession(ctx, sh.ID, id, key, uploadID, parent, name, size, actor)
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

func (s *ShareUploads) createSession(ctx context.Context, shareID, id uuid.UUID, key, uploadID string, parent uuid.UUID, name string, size int64, actor []byte) (store.UploadSession, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.UploadSession{}, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	// 锁序:分享行 → 属主行,与落库(resolveShareTarget)一致
	sh, err := q.LockShare(ctx, shareID)
	if err != nil {
		return store.UploadSession{}, err
	}
	if shareDead(sh.RevokedAt.Valid, sh.ExpiresAt, sh.NodeDeletedAt.Valid, sh.OwnerDisabled) {
		return store.UploadSession{}, errShareExpired
	}
	if !sh.AllowUpload {
		return store.UploadSession{}, errShareNoUpload
	}
	if _, err := q.GetUserByIDForUpdate(ctx, sh.CreatedBy); err != nil {
		return store.UploadSession{}, err
	}
	if err := s.checkInit(ctx, q, sh.ID, sh.CreatedBy, size); err != nil {
		return store.UploadSession{}, err
	}
	sess, err := q.CreateShareUploadSession(ctx, store.CreateShareUploadSessionParams{
		ID: id, OwnerID: sh.CreatedBy, Size: size,
		TargetParent: &parent, TargetName: name,
		MinioUploadID: uploadID, PartSize: int32(s.uploads.partSize),
		ExpiresAt: tstz(time.Now().Add(s.uploads.ttl)), ObjectKey: &key,
		ShareID: &sh.ID, Actor: actor,
	})
	if err != nil {
		return store.UploadSession{}, err
	}
	return sess, tx.Commit(ctx)
}

// checkInit 同时进行中的会话数,与属主配额(把所有访客进行中的会话预留进去)。
func (s *ShareUploads) checkInit(ctx context.Context, q *store.Queries, shareID, owner uuid.UUID, size int64) error {
	pending, err := q.ShareUploadPending(ctx, &shareID)
	if err != nil {
		return err
	}
	if pending.Sessions >= maxShareSessions {
		return errf("TOO_MANY_SESSIONS", "同时上传的文件太多,等前面的传完")
	}
	u, err := q.GetUserByID(ctx, owner)
	if err != nil {
		return err
	}
	if u.DisabledAt.Valid {
		return errShareExpired
	}
	reserved, err := q.SumOwnerCollectPendingBytes(ctx, owner)
	if err != nil {
		return err
	}
	if u.UsedBytes+reserved+size > u.QuotaBytes {
		return errShareFull
	}
	return nil
}

func (s *ShareUploads) session(ctx context.Context, sh store.GetShareByIDRow, id uuid.UUID) (store.UploadSession, error) {
	sess, err := s.q.GetShareUploadSession(ctx, store.GetShareUploadSessionParams{ID: id, ShareID: &sh.ID})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.UploadSession{}, ErrNotFound
	}
	return sess, err
}

// UploadSession 断点续传:已传分片与缺失分片的新预签名。
func (s *ShareUploads) UploadSession(ctx context.Context, ident *Identity, id uuid.UUID) (*SessionView, error) {
	sh, err := s.guest(ctx, ident)
	if err != nil {
		return nil, err
	}
	sess, err := s.session(ctx, sh, id)
	if err != nil {
		return nil, err
	}
	if sess.Status != "uploading" {
		return &SessionView{Session: sess}, nil
	}
	return s.uploads.sessionView(ctx, sess)
}

// CompleteUpload 与登录用户的 completeUpload 同语义(处理中返回 UPLOAD_PROCESSING,客户端轮询)。
// 返回落好的节点:它在分享范围里,访客本来就看得到。
func (s *ShareUploads) CompleteUpload(ctx context.Context, ident *Identity, id uuid.UUID, etags map[int]string) (*store.Node, error) {
	sh, err := s.guest(ctx, ident)
	if err != nil {
		return nil, err
	}
	sess, err := s.session(ctx, sh, id)
	if err != nil {
		return nil, err
	}
	return s.uploads.Complete(ctx, sess.OwnerID, id, etags)
}

func (s *ShareUploads) AbortUpload(ctx context.Context, ident *Identity, id uuid.UUID) error {
	sh, err := s.shares.ValidateScope(ctx, ident) // 关掉上传后也让访客取消自己的
	if err != nil {
		return err
	}
	sess, err := s.session(ctx, sh, id)
	if err != nil {
		return err
	}
	return s.uploads.Abort(ctx, sess.OwnerID, id)
}

// EnsureFolder 在 parent 里找到或建出名叫 name 的文件夹。同名的是文件时另取一个名字。
func (s *ShareUploads) EnsureFolder(ctx context.Context, ident *Identity, parent uuid.UUID, name, uploader string) (store.Node, error) {
	sh, err := s.guest(ctx, ident)
	if err != nil {
		return store.Node{}, err
	}
	if !s.mkdirLimiter.Allow("ip:" + ClientMetaFrom(ctx).IP) {
		return store.Node{}, ErrRateLimited
	}
	name = strings.TrimSpace(name)
	if err := validateName(name); err != nil {
		return store.Node{}, err
	}
	if uploader, err = cleanUploaderName(uploader); err != nil {
		return store.Node{}, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	q := s.q.WithTx(tx)
	// 锁住分享行:同一分享的建文件夹与落库串行,两个访客同时建同名文件夹不会撞
	locked, err := q.LockShare(ctx, sh.ID)
	if err != nil {
		return store.Node{}, err
	}
	if shareDead(locked.RevokedAt.Valid, locked.ExpiresAt, locked.NodeDeletedAt.Valid, locked.OwnerDisabled) {
		return store.Node{}, errShareExpired
	}
	if !locked.AllowUpload {
		return store.Node{}, errShareNoUpload
	}
	if err := shareFolder(ctx, q, sh.CreatedBy, sh.NodeID, parent); err != nil {
		return store.Node{}, err
	}
	existing, err := q.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: sh.CreatedBy, ParentID: &parent, Name: name})
	if err == nil && existing.Kind == "folder" {
		return existing, nil
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return store.Node{}, err
	}
	if err == nil { // 同名的是文件
		if name, err = freeName(ctx, q, sh.CreatedBy, &parent, name); err != nil {
			return store.Node{}, err
		}
	}
	n, err := q.CreateNode(ctx, store.CreateNodeParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: sh.CreatedBy, ParentID: &parent, Name: name, Kind: "folder",
	})
	if isUniqueViolation(err) {
		return store.Node{}, errf("NAME_CONFLICT", "同名文件或文件夹已存在,请重试")
	}
	if err != nil {
		return store.Node{}, err
	}
	actx := WithActor(ctx, ShareGuestActor(sh.ID, sh.NodeName, uploader))
	if err := recordActs(actx, q, sh.CreatedBy, nodeAct(OpMkdir, n, actDetail{})); err != nil {
		return store.Node{}, err
	}
	return n, tx.Commit(ctx)
}

// ---- 落库(浏览器上传的 worker 里调,见 finalizeBrowserUpload)----

type shareTarget struct {
	share  store.LockShareRow
	guest  sessionActor
	parent uuid.UUID
	name   string
}

// resolveShareTarget 锁住分享行,复核分享还开着上传、目标文件夹还在分享范围里,再挑名字。
// 名字在这里才最终确定:分享行锁住后,同一分享的落库是串行的,挑出来的名字不会被另一个访客抢走。
func resolveShareTarget(ctx context.Context, q *store.Queries, sess store.UploadSession) (*shareTarget, error) {
	sh, err := q.LockShare(ctx, *sess.ShareID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errShareExpired
	}
	if err != nil {
		return nil, err
	}
	if sh.CreatedBy != sess.OwnerID || shareDead(sh.RevokedAt.Valid, sh.ExpiresAt, sh.NodeDeletedAt.Valid, sh.OwnerDisabled) {
		return nil, errShareExpired
	}
	if !sh.AllowUpload {
		return nil, errShareNoUpload
	}
	if sess.TargetParent == nil {
		return nil, ErrNotFound
	}
	if err := shareFolder(ctx, q, sh.CreatedBy, sh.NodeID, *sess.TargetParent); err != nil {
		if errors.Is(err, ErrNotFound) {
			return nil, errf("NOT_A_FOLDER", "目标文件夹不在了")
		}
		return nil, err
	}
	t := &shareTarget{share: sh, parent: *sess.TargetParent}
	_ = json.Unmarshal(sess.Actor, &t.guest)
	if t.name, err = freeName(ctx, q, sh.CreatedBy, &t.parent, sess.TargetName); err != nil {
		return nil, err
	}
	return t, nil
}

// record 访问记录里记一笔 upload,并把服务端算出的 sha 回填到会话。随落库事务提交。
func (t *shareTarget) record(ctx context.Context, q *store.Queries, sess store.UploadSession, n store.Node, sum string) error {
	detail := n.Name
	if t.guest.Uploader != "" {
		detail = t.guest.Uploader + " · " + n.Name
	}
	if err := q.RecordShareVisit(ctx, store.RecordShareVisitParams{
		ShareID: t.share.ID, Kind: "upload", Ip: ptrIfNotEmpty(t.guest.IP), UserAgent: ptrIfNotEmpty(t.guest.UA), Detail: &detail,
	}); err != nil {
		return fmt.Errorf("record share upload: %w", err)
	}
	return q.SetUploadSessionSha(ctx, store.SetUploadSessionShaParams{ID: sess.ID, Sha256: &sum})
}
