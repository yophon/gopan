package graph

// This file will not be regenerated automatically.
//
// v3.5(doc/23):动态与撤回、空间分析、订阅、剪藏。resolver 里只调这里的方法。

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/feedparse"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func optTime(valid bool, t time.Time) *time.Time {
	if !valid {
		return nil
	}
	return &t
}

func optStr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// ---------- 动态 ----------

func gqlActivity(a store.Activity) *Activity {
	from, to, oldName, newName, files, bytes, undoOf, note := service.ActivityDetail(a.Detail)
	out := &Activity{
		ID: strconv.FormatInt(a.ID, 10), At: a.At.Time, BatchID: a.BatchID.String(),
		Actor:  &ActivityActor{Kind: ActorKind(strings.ToUpper(a.ActorKind)), Ref: a.ActorRef, Label: a.ActorLabel},
		Action: ActivityAction(strings.ToUpper(a.Action)), NodeID: idPtrStr(a.NodeID), Name: a.Name, ParentPath: a.ParentPath,
		FromPath: from, ToPath: to, OldName: optStr(oldName), NewName: optStr(newName), Files: files, Bytes: bytes,
		Note: optStr(note), UndoneAt: optTime(a.UndoneAt.Valid, a.UndoneAt.Time), Undoable: service.Undoable(a),
		OwnerID: a.OwnerID.String(),
	}
	if !out.Actor.Kind.IsValid() {
		out.Actor.Kind = ActorKindSystem
	}
	switch a.NodeKind {
	case "file":
		k := NodeKindFile
		out.NodeKind = &k
	case "folder":
		k := NodeKindFolder
		out.NodeKind = &k
	}
	if undoOf != 0 {
		s := strconv.FormatInt(undoOf, 10)
		out.UndoOf = &s
	}
	return out
}

func activityFilter(f *ActivityFilter) (service.ActivityFilter, error) {
	var out service.ActivityFilter
	if f == nil {
		return out, nil
	}
	if f.ActorKind != nil {
		out.ActorKind = strings.ToLower(string(*f.ActorKind))
	}
	if f.ActorRef != nil {
		out.ActorRef = *f.ActorRef
	}
	for _, a := range f.Actions {
		out.Actions = append(out.Actions, strings.ToLower(string(a)))
	}
	var err error
	if out.NodeID, err = parseIDPtr(f.NodeID); err != nil {
		return out, err
	}
	if out.BatchID, err = parseIDPtr(f.BatchID); err != nil {
		return out, err
	}
	out.Since, out.Until = f.Since, f.Until
	return out, nil
}

func undoSelection(sel UndoSelection) (service.UndoSelection, error) {
	var out service.UndoSelection
	for _, s := range sel.Ids {
		id, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return out, service.ErrNotFound
		}
		out.IDs = append(out.IDs, id)
	}
	var err error
	if out.BatchID, err = parseIDPtr(sel.BatchID); err != nil {
		return out, err
	}
	if sel.Filter != nil {
		f, err := activityFilter(sel.Filter)
		if err != nil {
			return out, err
		}
		out.Filter = &f
	}
	return out, nil
}

func gqlUndo(res *service.UndoResult) *UndoResult {
	out := &UndoResult{Done: res.Done, Failed: res.Failed, Items: make([]*UndoItem, 0, len(res.Items))}
	for _, it := range res.Items {
		out.Items = append(out.Items, &UndoItem{Activity: gqlActivity(it.Activity), Ok: it.OK, Reason: optStr(it.Reason)})
	}
	return out
}

func (r *Resolver) listActivities(ctx context.Context, filter *ActivityFilter, cursor *string, limit *int) (*ActivityPage, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	f, err := activityFilter(filter)
	if err != nil {
		return nil, err
	}
	n := 50
	if limit != nil {
		n = *limit
	}
	rows, next, err := r.Activities.List(ctx, ident.UserID, f, cursor, n)
	if err != nil {
		return nil, err
	}
	out := &ActivityPage{Items: make([]*Activity, 0, len(rows)), NextCursor: next}
	for _, a := range rows {
		out.Items = append(out.Items, gqlActivity(a))
	}
	return out, nil
}

func (r *Resolver) activityActors(ctx context.Context) ([]*ActivityActorSummary, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := r.Activities.Actors(ctx, ident.UserID)
	if err != nil {
		return nil, err
	}
	out := make([]*ActivityActorSummary, 0, len(rows))
	for _, a := range rows {
		k := ActorKind(strings.ToUpper(a.ActorKind))
		if !k.IsValid() {
			k = ActorKindSystem
		}
		out = append(out, &ActivityActorSummary{
			Actor: &ActivityActor{Kind: k, Ref: a.ActorRef, Label: a.ActorLabel}, Total: a.Total, LastAt: a.LastAt.Time,
		})
	}
	return out, nil
}

func (r *Resolver) undo(ctx context.Context, sel UndoSelection, dry bool) (*UndoResult, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	s, err := undoSelection(sel)
	if err != nil {
		return nil, err
	}
	var res *service.UndoResult
	if dry {
		res, err = r.Activities.PreviewUndo(ctx, ident.UserID, s)
	} else {
		res, err = r.Activities.Undo(ctx, ident.UserID, s)
	}
	if err != nil {
		return nil, err
	}
	return gqlUndo(res), nil
}

// activeNode 记录 / 订阅 / 剪藏里的节点:还在网盘里(不在回收站)才给出。
func (r *Resolver) activeNode(ctx context.Context, owner, id string) (*Node, error) {
	if id == "" {
		return nil, nil
	}
	o, err := uuid.Parse(owner)
	if err != nil {
		return nil, nil
	}
	nid, err := uuid.Parse(id)
	if err != nil {
		return nil, nil
	}
	n, err := r.getNodeFull(ctx, o, nid)
	if err != nil {
		return nil, nil
	}
	return n, nil
}

// ---------- 空间 ----------

func gqlCategory(c string) *FileCategory {
	if c == "" {
		return nil
	}
	v := FileCategory(strings.ToUpper(c))
	if !v.IsValid() {
		v = FileCategoryOther
	}
	return &v
}

func gqlSpaceFile(f service.SpaceFile) *SpaceFile {
	n := gqlNode(f.Node)
	if f.Node.Kind == "file" {
		size := f.Size
		n.Size = &size
		if f.Mime != "" {
			m := f.Mime
			n.Mime = &m
		}
	}
	n.InList = true
	return &SpaceFile{Node: n, Path: f.Path, Category: gqlCategory(f.Category)}
}

func gqlSpaceFiles(fs []service.SpaceFile) []*SpaceFile {
	out := make([]*SpaceFile, 0, len(fs))
	for _, f := range fs {
		out = append(out, gqlSpaceFile(f))
	}
	return out
}

func (r *Resolver) spaceOverview(ctx context.Context) (*SpaceOverview, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	ov, err := r.Space.Overview(ctx, ident.UserID)
	if err != nil {
		return nil, err
	}
	out := &SpaceOverview{
		UsedBytes: ov.UsedBytes, QuotaBytes: ov.QuotaBytes, TrashFiles: ov.TrashFiles, TrashBytes: ov.TrashBytes,
		DuplicateGroups: ov.DuplicateGroups, DuplicateBytes: ov.DuplicateReclaimable, ByType: []*SpaceCategory{},
	}
	for _, t := range ov.ByType {
		out.ByType = append(out.ByType, &SpaceCategory{Category: *gqlCategory(t.Category), Files: t.Files, Bytes: t.Bytes})
	}
	return out, nil
}

func (r *Resolver) spaceFolders(ctx context.Context, parentID *string) (*SpaceFolderLevel, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	pid, err := parseIDPtr(parentID)
	if err != nil {
		return nil, err
	}
	lv, err := r.Space.Folders(ctx, ident.UserID, pid)
	if err != nil {
		return nil, err
	}
	out := &SpaceFolderLevel{Path: lv.Path, LooseFiles: lv.LooseFiles, LooseBytes: lv.LooseBytes, Folders: make([]*Node, 0, len(lv.Folders))}
	for _, n := range lv.Folders {
		g := gqlNode(n)
		g.InList = true
		out.Folders = append(out.Folders, g)
	}
	return out, nil
}

func (r *Resolver) spaceLargeFiles(ctx context.Context, category *FileCategory, limit *int) ([]*SpaceFile, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	cat := ""
	if category != nil {
		cat = strings.ToLower(string(*category))
	}
	fs, err := r.Space.LargeFiles(ctx, ident.UserID, cat, deref(limit, 100))
	if err != nil {
		return nil, err
	}
	return gqlSpaceFiles(fs), nil
}

func deref[T any](p *T, def T) T {
	if p == nil {
		return def
	}
	return *p
}

func (r *Resolver) spaceStaleFiles(ctx context.Context, days *int, minSize *int64, limit *int) ([]*SpaceFile, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	fs, err := r.Space.StaleFiles(ctx, ident.UserID, deref(days, 365), deref(minSize, int64(100<<20)), deref(limit, 100))
	if err != nil {
		return nil, err
	}
	return gqlSpaceFiles(fs), nil
}

func (r *Resolver) spaceDuplicates(ctx context.Context, offset, limit *int) (*DuplicatePage, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	off, lim := deref(offset, 0), deref(limit, 50)
	groups, more, err := r.Space.Duplicates(ctx, ident.UserID, off, lim)
	if err != nil {
		return nil, err
	}
	out := &DuplicatePage{Items: make([]*DuplicateGroup, 0, len(groups))}
	for _, g := range groups {
		out.Items = append(out.Items, &DuplicateGroup{Sha256: g.Sha, Size: g.Size, Copies: int(g.Copies), Files: gqlSpaceFiles(g.Files)})
	}
	if more {
		n := off + len(groups)
		out.NextOffset = &n
	}
	return out, nil
}

func (r *Resolver) spaceEmptyFolders(ctx context.Context, limit *int) ([]*SpaceFile, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	fs, err := r.Space.EmptyFolders(ctx, ident.UserID, deref(limit, 200))
	if err != nil {
		return nil, err
	}
	return gqlSpaceFiles(fs), nil
}

func (r *Resolver) spaceIdle(ctx context.Context) (*SpaceIdle, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	idle, err := r.Space.Idle(ctx, ident.UserID)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	out := &SpaceIdle{Shares: []*IdleShare{}, Links: []*IdleLink{}, Requests: []*IdleRequest{}}
	for _, s := range idle.Shares {
		out.Shares = append(out.Shares, &IdleShare{
			ID: s.ID.String(), Token: s.Token, NodeID: s.NodeID.String(), Name: s.Name,
			Kind:      NodeKind(map[string]string{"file": "FILE", "folder": "FOLDER"}[s.Kind]),
			ExpiresAt: optTime(s.ExpiresAt.Valid, s.ExpiresAt.Time), CreatedAt: s.CreatedAt.Time,
			LastVisitAt: optTime(s.LastVisitAt.Valid, s.LastVisitAt.Time),
			Expired:     s.ExpiresAt.Valid && s.ExpiresAt.Time.Before(now),
		})
	}
	for _, l := range idle.Links {
		out.Links = append(out.Links, &IdleLink{
			ID: l.ID.String(), NodeID: l.NodeID.String(), Name: l.Name,
			ExpiresAt: optTime(l.ExpiresAt.Valid, l.ExpiresAt.Time), CreatedAt: l.CreatedAt.Time,
			LastHitAt: optTime(l.LastHitAt.Valid, l.LastHitAt.Time), Hits: l.HitsTotal,
			Expired: l.ExpiresAt.Valid && l.ExpiresAt.Time.Before(now),
		})
	}
	for _, q := range idle.Requests {
		out.Requests = append(out.Requests, &IdleRequest{
			ID: q.ID.String(), Title: q.Title, FolderID: q.FolderID.String(), ExpiresAt: q.ExpiresAt.Time, CreatedAt: q.CreatedAt.Time,
		})
	}
	return out, nil
}

func (r *Resolver) adminStorage(ctx context.Context) (*AdminStorage, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	if err := r.Admin.Require(ctx, ident.UserID); err != nil {
		return nil, err
	}
	s, err := r.Space.AdminStorage(ctx)
	if err != nil {
		return nil, err
	}
	return &AdminStorage{
		LogicalBytes: s.LogicalBytes, PhysicalBytes: s.PhysicalBytes, Blobs: s.Blobs, PendingGcBytes: s.PendingGcBytes,
		DerivativeBytes: s.DerivativeBytes, Derivatives: s.Derivatives, TextIndexBytes: s.TextBytes, ActivityBytes: s.ActivityBytes,
	}, nil
}

// ---------- 订阅 ----------

func gqlFeedRow(f store.Feed, items, fresh int64, latest *time.Time) *Feed {
	out := &Feed{
		ID: f.ID.String(), Kind: FeedKind(strings.ToUpper(f.Kind)), URL: f.Url, Title: f.Title, SiteURL: f.SiteUrl,
		Mode: FeedMode(strings.ToUpper(f.Mode)), IncludeWords: f.IncludeWords, ExcludeWords: f.ExcludeWords,
		AssetPattern: f.AssetPattern, Prerelease: f.Prerelease, KeepLast: int(f.KeepLast),
		IntervalMinutes: int(f.IntervalMinutes), Enabled: f.Enabled,
		LastCheckedAt: optTime(f.LastCheckedAt.Valid, f.LastCheckedAt.Time),
		LastOkAt:      optTime(f.LastOkAt.Valid, f.LastOkAt.Time), LastError: f.LastError,
		FailCount: int(f.FailCount), NextCheckAt: f.NextCheckAt.Time, CreatedAt: f.CreatedAt.Time,
		ItemCount: int(items), NewCount: int(fresh), LatestAt: latest, OwnerID: f.OwnerID.String(),
	}
	if f.FolderID != nil {
		out.FolderID = f.FolderID.String()
	}
	return out
}

func feedFromListRow(r store.ListFeedsRow) store.Feed {
	return store.Feed{
		ID: r.ID, OwnerID: r.OwnerID, Kind: r.Kind, Url: r.Url, Title: r.Title, SiteUrl: r.SiteUrl, FolderID: r.FolderID,
		Mode: r.Mode, IncludeWords: r.IncludeWords, ExcludeWords: r.ExcludeWords, AssetPattern: r.AssetPattern,
		Prerelease: r.Prerelease, KeepLast: r.KeepLast, IntervalMinutes: r.IntervalMinutes, Etag: r.Etag,
		LastModified: r.LastModified, NextCheckAt: r.NextCheckAt, LastCheckedAt: r.LastCheckedAt, LastOkAt: r.LastOkAt,
		LastError: r.LastError, FailCount: r.FailCount, Enabled: r.Enabled, CreatedAt: r.CreatedAt,
	}
}

// gqlFeed 单个订阅:条目统计从列表查询里取(一次查询,每人最多 100 个订阅)。
func (r *Resolver) gqlFeed(ctx context.Context, f store.Feed) (*Feed, error) {
	rows, err := r.Feeds.List(ctx, f.OwnerID)
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		if row.ID == f.ID {
			return gqlFeedRow(f, row.ItemCount, row.NewCount, optTime(row.LatestAt.Valid, row.LatestAt.Time)), nil
		}
	}
	return gqlFeedRow(f, 0, 0, nil), nil
}

func gqlFeedItem(it store.FeedItem, owner uuid.UUID) *FeedItem {
	out := &FeedItem{
		ID: it.ID.String(), Title: it.Title, URL: it.Url, EnclosureURL: it.EnclosureUrl, EnclosureType: it.EnclosureType,
		EnclosureSize: it.EnclosureSize, PublishedAt: optTime(it.PublishedAt.Valid, it.PublishedAt.Time),
		State: FeedItemState(strings.ToUpper(it.State)), Error: it.Error, JobID: idPtrStr(it.JobID),
		CreatedAt: it.CreatedAt.Time, OwnerID: owner.String(),
	}
	if it.NodeID != nil {
		out.NodeID = it.NodeID.String()
	}
	return out
}

func feedInput(in FeedInput) (service.FeedInput, error) {
	out := service.FeedInput{
		URL: deref(in.URL, ""), Title: deref(in.Title, ""), Include: deref(in.IncludeWords, ""),
		Exclude: deref(in.ExcludeWords, ""), AssetPattern: deref(in.AssetPattern, ""), Prerelease: deref(in.Prerelease, false),
		KeepLast: deref(in.KeepLast, 0), IntervalMinutes: deref(in.IntervalMinutes, 0), Enabled: deref(in.Enabled, true),
		Initial: deref(in.Initial, 1),
	}
	if in.Mode != nil {
		out.Mode = strings.ToLower(string(*in.Mode))
	}
	var err error
	out.FolderID, err = parseIDPtr(in.FolderID)
	return out, err
}

func (r *Resolver) listFeeds(ctx context.Context) ([]*Feed, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := r.Feeds.List(ctx, ident.UserID)
	if err != nil {
		return nil, err
	}
	out := make([]*Feed, 0, len(rows))
	for _, row := range rows {
		out = append(out, gqlFeedRow(feedFromListRow(row), row.ItemCount, row.NewCount, optTime(row.LatestAt.Valid, row.LatestAt.Time)))
	}
	return out, nil
}

func (r *Resolver) feedByID(ctx context.Context, id string, refresh bool) (*Feed, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	fid, err := parseID(id)
	if err != nil {
		return nil, err
	}
	var f store.Feed
	if refresh {
		f, err = r.Feeds.Refresh(ctx, ident.UserID, fid)
	} else {
		f, err = r.Feeds.Get(ctx, ident.UserID, fid)
	}
	if err != nil {
		return nil, err
	}
	return r.gqlFeed(ctx, f)
}

func (r *Resolver) feedItems(ctx context.Context, feedID string, cursor *string) (*FeedItemPage, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	fid, err := parseID(feedID)
	if err != nil {
		return nil, err
	}
	rows, next, err := r.Feeds.Items(ctx, ident.UserID, fid, cursor)
	if err != nil {
		return nil, err
	}
	out := &FeedItemPage{Items: make([]*FeedItem, 0, len(rows)), NextCursor: next}
	for _, it := range rows {
		out.Items = append(out.Items, gqlFeedItem(it, ident.UserID))
	}
	return out, nil
}

func gqlFeedPreview(p *service.FeedPreview) *FeedPreview {
	out := &FeedPreview{
		Kind: FeedKind(strings.ToUpper(p.Kind)), URL: p.URL, Title: p.Title, SiteURL: p.SiteURL, Podcast: p.Podcast,
		SuggestedMode: FeedMode(strings.ToUpper(p.SuggestedMode)), Items: make([]*FeedPreviewItem, 0, len(p.Items)),
	}
	for _, it := range p.Items {
		out.Items = append(out.Items, previewItem(it))
	}
	return out
}

func previewItem(it feedparse.Item) *FeedPreviewItem {
	out := &FeedPreviewItem{Title: it.Title, URL: it.Link, EnclosureURL: it.EnclosureURL, PublishedAt: optTime(!it.Published.IsZero(), it.Published)}
	if it.EnclosureSize > 0 {
		n := it.EnclosureSize
		out.EnclosureSize = &n
	}
	return out
}

func (r *Resolver) previewFeed(ctx context.Context, url string, assetPattern *string, prerelease *bool) (*FeedPreview, error) {
	if _, err := httpx.UserFrom(ctx); err != nil {
		return nil, err
	}
	p, err := r.Feeds.Preview(ctx, url, deref(assetPattern, ""), deref(prerelease, false))
	if err != nil {
		return nil, err
	}
	return gqlFeedPreview(p), nil
}

func (r *Resolver) createFeed(ctx context.Context, in FeedInput) (*Feed, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	si, err := feedInput(in)
	if err != nil {
		return nil, err
	}
	f, err := r.Feeds.Create(ctx, ident.UserID, si)
	if err != nil {
		return nil, err
	}
	return r.gqlFeed(ctx, f)
}

func (r *Resolver) updateFeed(ctx context.Context, id string, in FeedInput) (*Feed, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	fid, err := parseID(id)
	if err != nil {
		return nil, err
	}
	si, err := feedInput(in)
	if err != nil {
		return nil, err
	}
	f, err := r.Feeds.Update(ctx, ident.UserID, fid, si)
	if err != nil {
		return nil, err
	}
	return r.gqlFeed(ctx, f)
}

func (r *Resolver) deleteFeed(ctx context.Context, id string) (bool, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return false, err
	}
	fid, err := parseID(id)
	if err != nil {
		return false, err
	}
	return true, r.Feeds.Delete(ctx, ident.UserID, fid)
}

func (r *Resolver) fetchFeedItem(ctx context.Context, itemID string, mode FeedMode) (*FeedItem, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	id, err := parseID(itemID)
	if err != nil {
		return nil, err
	}
	it, err := r.Feeds.FetchItem(ctx, ident.UserID, id, strings.ToLower(string(mode)))
	if err != nil {
		return nil, err
	}
	return gqlFeedItem(it, ident.UserID), nil
}

func (r *Resolver) importOpml(ctx context.Context, src string) (*OpmlImportResult, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	res, err := r.Feeds.ImportOPML(ctx, ident.UserID, src)
	if err != nil {
		return nil, err
	}
	out := &OpmlImportResult{Added: res.Added, Existed: res.Existed, Failed: []*OpmlFailure{}}
	for _, f := range res.Failed {
		out.Failed = append(out.Failed, &OpmlFailure{URL: f.URL, Reason: f.Reason})
	}
	return out, nil
}

func (r *Resolver) exportOpml(ctx context.Context) (string, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return "", err
	}
	return r.Feeds.ExportOPML(ctx, ident.UserID)
}

// ---------- 剪藏 ----------

func gqlClip(c store.Clip) *Clip {
	return &Clip{
		ID: c.ID.String(), URL: c.Url, Title: c.Title, Site: c.Site, Byline: c.Byline, Excerpt: c.Excerpt,
		PublishedAt: optTime(c.PublishedAt.Valid, c.PublishedAt.Time), Words: int(c.Words), Images: int(c.Images),
		ReadAt: optTime(c.ReadAt.Valid, c.ReadAt.Time), CreatedAt: c.CreatedAt.Time,
		OwnerID: c.OwnerID.String(), NodeID: c.NodeID.String(), AssetsID: idStr(c.AssetsID),
	}
}

func idStr(id *uuid.UUID) string {
	if id == nil {
		return ""
	}
	return id.String()
}

func (r *Resolver) feedFolderPath(ctx context.Context, obj *Feed) (*string, error) {
	if obj.FolderID == "" {
		return nil, nil
	}
	owner, err1 := uuid.Parse(obj.OwnerID)
	id, err2 := uuid.Parse(obj.FolderID)
	if err1 != nil || err2 != nil {
		return nil, nil
	}
	p, err := r.Nodes.Path(ctx, owner, id)
	if err != nil {
		return nil, nil
	}
	return &p, nil
}

func (r *Resolver) listClips(ctx context.Context, unreadOnly *bool, site, cursor *string) (*ClipPage, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	rows, next, err := r.Clips.List(ctx, ident.UserID, deref(unreadOnly, false), deref(site, ""), cursor)
	if err != nil {
		return nil, err
	}
	out := &ClipPage{Items: make([]*Clip, 0, len(rows)), NextCursor: next}
	for _, c := range rows {
		out.Items = append(out.Items, gqlClip(c))
	}
	return out, nil
}

func (r *Resolver) clipSites(ctx context.Context) ([]*ClipSite, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := r.Clips.Sites(ctx, ident.UserID)
	if err != nil {
		return nil, err
	}
	out := make([]*ClipSite, 0, len(rows))
	for _, s := range rows {
		out = append(out, &ClipSite{Site: s.Site, Total: s.Total})
	}
	return out, nil
}

func (r *Resolver) clipByID(ctx context.Context, id string) (*Clip, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	cid, err := parseID(id)
	if err != nil {
		return nil, err
	}
	c, err := r.Clips.Get(ctx, ident.UserID, cid)
	if err != nil {
		return nil, err
	}
	return gqlClip(c), nil
}

func (r *Resolver) nodeClip(ctx context.Context, nodeID string) (*Clip, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	nid, err := parseID(nodeID)
	if err != nil {
		return nil, err
	}
	c, err := r.Clips.ByNode(ctx, ident.UserID, nid)
	if err != nil || c == nil {
		return nil, err
	}
	return gqlClip(*c), nil
}

func (r *Resolver) clipURL(ctx context.Context, url string, html *string, force *bool) (*ClipResult, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	var page []byte
	if html != nil {
		page = []byte(*html)
	}
	source := service.ChatSourceWeb
	if service.ActorFrom(ctx).Kind == service.ActorAndroid {
		source = "android"
	}
	job, existing, err := r.Clips.Submit(ctx, ident.UserID, url, page, deref(force, false), source)
	if err != nil {
		return nil, err
	}
	out := &ClipResult{}
	if job != nil {
		out.Job = gqlJob(*job)
	}
	if existing != nil {
		out.Existing = gqlClip(*existing)
	}
	return out, nil
}

func (r *Resolver) markClipRead(ctx context.Context, id string, read *bool) (*Clip, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	cid, err := parseID(id)
	if err != nil {
		return nil, err
	}
	c, err := r.Clips.SetRead(ctx, ident.UserID, cid, deref(read, true))
	if err != nil {
		return nil, err
	}
	return gqlClip(c), nil
}

func (r *Resolver) clipPrefs(ctx context.Context, in *ClipPrefsInput) (*ClipPrefs, error) {
	ident, err := httpx.UserFrom(ctx)
	if err != nil {
		return nil, err
	}
	var p service.ClipPrefs
	if in != nil {
		p, err = r.Clips.SetPrefs(ctx, ident.UserID, service.ClipPrefs{Folder: in.Folder, ByMonth: in.ByMonth})
	} else {
		p, err = r.Clips.Prefs(ctx, ident.UserID)
	}
	if err != nil {
		return nil, err
	}
	return &ClipPrefs{Folder: p.Folder, ByMonth: p.ByMonth}, nil
}
