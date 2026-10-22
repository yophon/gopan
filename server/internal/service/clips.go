package service

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/net/html/charset"

	"github.com/yophon/gopan/server/internal/clipper"
	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

// 剪藏(doc/23 五):抓网页 → 提取正文 → 转 Markdown → 图片下到「标题.assets/」→ 存「标题.md」。

const (
	clipHTMLMax      = 5 << 20
	clipImageMax     = 10 << 20
	clipImagesTotal  = 50 << 20
	clipFetchTime    = 30 * time.Second
	clipImageWorkers = 4
	defaultClipDir   = "/剪藏"
	clipsPageSize    = 30
	orderClips       = "CLIPS"
)

type clipParams struct {
	URL       string     `json:"url"`
	WithHTML  bool       `json:"with_html,omitempty"` // 书签栏小工具发来了页面内容(暂存在 jobTmpKey + ".html")
	FolderID  *uuid.UUID `json:"folder_id,omitempty"` // 订阅:存到订阅的文件夹
	FeedID    *uuid.UUID `json:"feed_id,omitempty"`
	FeedItem  *uuid.UUID `json:"feed_item_id,omitempty"`
	FeedTitle string     `json:"feed_title,omitempty"`
}

func clipHTMLKey(job uuid.UUID) string { return jobTmpKey(job) + ".html" }

type Clips struct {
	q       *store.Queries
	obj     *objstore.Store
	jobs    *Jobs
	uploads *Uploads
	client  *http.Client
	ua      string
}

func NewClips(pool *pgxpool.Pool, obj *objstore.Store, jobs *Jobs, uploads *Uploads, guard fetcher.Guard, ua string) *Clips {
	return &Clips{q: store.New(pool), obj: obj, jobs: jobs, uploads: uploads, client: guard.Client(), ua: ua}
}

// SetClient 测试用。
func (c *Clips) SetClient(cl *http.Client) { c.client = cl }

// Submit 剪藏一个网页。同一个地址剪过、那篇还在时默认直接给出已有的(force 重新剪)。
// page 非空 = 书签栏小工具发来的页面内容,这时不再抓取。
func (c *Clips) Submit(ctx context.Context, owner uuid.UUID, rawURL string, page []byte, force bool, source string) (*store.UserJob, *store.Clip, error) {
	if !c.jobs.FetchEnabled() {
		return nil, nil, errf("FETCH_DISABLED", "本站没有开启离线下载,剪藏也用不了")
	}
	u, err := fetcher.ParseURL(rawURL)
	if err != nil {
		return nil, nil, errf("INVALID_URL", "链接格式不对,只支持 http / https")
	}
	if len(page) > clipHTMLMax {
		return nil, nil, errf("TOO_LARGE", "页面内容超过 5 MB")
	}
	if !force {
		if existing, err := c.q.FindClipByURL(ctx, store.FindClipByURLParams{OwnerID: owner, Url: u.String()}); err == nil {
			return nil, &existing, nil
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, err
		}
	}
	id := uuid.Must(uuid.NewV7())
	p := clipParams{URL: u.String(), WithHTML: len(page) > 0}
	if p.WithHTML {
		if err := c.obj.Put(ctx, clipHTMLKey(id), bytes.NewReader(page), int64(len(page)), "text/html"); err != nil {
			return nil, nil, err
		}
	}
	b, _ := json.Marshal(p)
	jobs, err := c.jobs.create(ctx, owner, nil, []JobSpec{{ID: id, Kind: JobClip, Params: b, Title: fetcher.Host(u.String())}}, source)
	if err != nil {
		if p.WithHTML {
			_ = c.obj.Remove(context.WithoutCancel(ctx), clipHTMLKey(id))
		}
		return nil, nil, err
	}
	return &jobs[0], nil, nil
}

// enqueueForFeed 订阅「剪藏正文」模式:存到订阅的文件夹,不查重(订阅条目本身已去重)。
func (c *Clips) enqueueForFeed(ctx context.Context, feed store.Feed, item store.FeedItem) (store.UserJob, error) {
	b, _ := json.Marshal(clipParams{URL: item.Url, FolderID: feed.FolderID, FeedID: &feed.ID, FeedItem: &item.ID, FeedTitle: feed.Title})
	jobs, err := c.jobs.create(ctx, feed.OwnerID, nil, []JobSpec{{Kind: JobClip, Params: b, Title: truncateRunes(item.Title, 100)}}, JobSourceFeed)
	if err != nil {
		return store.UserJob{}, err
	}
	return jobs[0], nil
}

// runClip 执行剪藏任务,返回那篇 .md。
func (c *Clips) runClip(ctx context.Context, j *Jobs, run *jobRun) (*uuid.UUID, error) {
	var p clipParams
	if err := json.Unmarshal(run.job.Params, &p); err != nil || p.URL == "" {
		return nil, errf("INVALID_INPUT", "任务参数损坏")
	}
	owner := run.job.OwnerID
	page, err := url.Parse(p.URL)
	if err != nil {
		return nil, errf("INVALID_URL", "链接格式不对")
	}
	var doc []byte
	if p.WithHTML {
		r, err := c.obj.Open(ctx, clipHTMLKey(run.job.ID))
		if err != nil {
			return nil, errf("CLIP_EXPIRED", "页面内容已过期,请重新剪藏")
		}
		doc, err = io.ReadAll(io.LimitReader(r, clipHTMLMax))
		r.Close()
		if err != nil {
			return nil, err
		}
		defer func() { _ = c.obj.Remove(context.WithoutCancel(ctx), clipHTMLKey(run.job.ID)) }()
	} else {
		if doc, page, err = c.fetchPage(ctx, p.URL); err != nil {
			return nil, err
		}
	}
	run.setBytesTotal(int64(len(doc)))
	run.bytesDone.Store(int64(len(doc)))

	art, err := clipper.Extract(doc, page)
	if err != nil {
		return nil, errf("CLIP_FAILED", "%s", err.Error())
	}
	run.setTitle(art.Title)

	folder, err := c.targetFolder(ctx, owner, p.FolderID)
	if err != nil {
		return nil, err
	}
	quiet := withQuiet(ctx)
	base := clipBaseName(art.Title)
	mdName, err := freeName(ctx, c.q, owner, folder, base+".md")
	if err != nil {
		return nil, err
	}

	// 图片:下到「标题.assets/」,文中换成相对路径;下不了的留原地址
	mapping := map[string]string{}
	var assets *store.Node
	var imgCount int64
	var imgBytes int64
	if len(art.Images) > 0 {
		run.setFilesTotal(int32(len(art.Images)))
		assets, err = c.uploads.CreateFolderIn(quiet, owner, folder, strings.TrimSuffix(mdName, ".md")+".assets")
		if err != nil {
			return nil, err
		}
		// 4 张一起下;文件名按在文中的顺序编号,与谁先下完无关
		var mu sync.Mutex
		sem := make(chan struct{}, clipImageWorkers)
		var wg sync.WaitGroup
		for i, im := range art.Images {
			if ctx.Err() != nil {
				break
			}
			sem <- struct{}{}
			wg.Add(1)
			go func(seq int, im clipper.Image) {
				defer wg.Done()
				defer func() { <-sem }()
				mu.Lock()
				budget := clipImagesTotal - imgBytes
				mu.Unlock()
				if budget <= 0 {
					run.fileSkipped()
					return
				}
				n, size, err := c.saveImage(quiet, run, owner, assets.ID, seq, im.URL, page.String(), budget)
				if err != nil {
					slog.Info("clip image skipped", "job", run.job.ID, "url", fetcher.Host(im.URL), "err", err)
					run.fileSkipped()
					return
				}
				mu.Lock()
				imgCount++
				imgBytes += size
				mapping[im.Placeholder] = mdPathEscape(assets.Name) + "/" + mdPathEscape(n.Name)
				mu.Unlock()
				run.fileDone()
			}(i+1, im)
		}
		wg.Wait()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if imgCount == 0 {
			_ = c.uploads.nodes.Purge(quiet, owner, []uuid.UUID{assets.ID})
			assets = nil
		}
	}

	body := clipper.ReplaceImages(art.Markdown, art.Images, mapping)
	text := []byte(clipper.Document(art, p.URL, time.Now(), body))
	sum := sha256.Sum256(text)
	key := jobTmpKey(run.job.ID)
	if err := c.obj.Put(ctx, key, bytes.NewReader(text), int64(len(text)), "text/markdown"); err != nil {
		return nil, err
	}
	md, err := c.uploads.CommitStaged(quiet, StagedFile{
		Owner: owner, Parent: folder, Name: mdName, Sha: hex.EncodeToString(sum[:]), Size: int64(len(text)), TmpKey: key,
	})
	if err != nil {
		return nil, err
	}
	pub := pgtype.Timestamptz{}
	if !art.Published.IsZero() {
		pub = tstz(art.Published)
	}
	if _, err := c.q.CreateClip(ctx, store.CreateClipParams{
		ID: uuid.Must(uuid.NewV7()), OwnerID: owner, Url: p.URL, Title: truncateRunes(art.Title, 300), Site: truncateRunes(art.Site, 100),
		Byline: truncateRunes(art.Byline, 100), Excerpt: art.Excerpt, PublishedAt: pub, NodeID: md.ID,
		Words: int32(art.Words), Images: int32(imgCount),
	}); err != nil {
		return nil, err
	}
	acts := []actItem{fileAct(OpUpload, *md, int64(len(text)))}
	if assets != nil {
		acts = append(acts, nodeAct(OpMkdir, *assets, actDetail{Files: imgCount, Bytes: imgBytes}))
	}
	if err := recordActs(ctx, c.q, owner, acts...); err != nil {
		slog.Warn("record clip activity", "job", run.job.ID, "err", err)
	}
	if art.Fallback {
		run.warning = errf("CLIP_FALLBACK", "没认出正文,存的是整页文字")
	}
	return &md.ID, nil
}

// fetchPage 抓网页(≤ 5 MB,只收 HTML,跟随跳转),转成 UTF-8;返回最终地址。
func (c *Clips) fetchPage(ctx context.Context, raw string) ([]byte, *url.URL, error) {
	ctx, cancel := context.WithTimeout(ctx, clipFetchTime)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return nil, nil, errf("INVALID_URL", "链接格式不对")
	}
	req.Header.Set("User-Agent", c.ua)
	req.Header.Set("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
	resp, err := c.client.Do(req)
	if err != nil {
		if e := mapFetchErr(err); e != err {
			return nil, nil, e
		}
		return nil, nil, errf("FETCH_FAILED", "%s", describeNetErr(err))
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, nil, errf("FETCH_HTTP_STATUS", "网站返回 HTTP %d(可能要登录或拦了服务器;试试书签栏小工具的「连同页面内容」)", resp.StatusCode)
	}
	ct := resp.Header.Get("Content-Type")
	if mt, _, _ := mime.ParseMediaType(ct); mt != "" && mt != "text/html" && mt != "application/xhtml+xml" {
		return nil, nil, errf("NOT_HTML", "这个链接不是网页(%s),要存文件请用离线下载", mt)
	}
	r, err := charset.NewReader(io.LimitReader(resp.Body, clipHTMLMax+1), ct)
	if err != nil {
		r = io.LimitReader(resp.Body, clipHTMLMax+1)
	}
	b, err := io.ReadAll(r)
	if err != nil {
		return nil, nil, errf("FETCH_FAILED", "%s", describeNetErr(err))
	}
	if len(b) > clipHTMLMax*2 {
		return nil, nil, errf("TOO_LARGE", "网页超过 5 MB")
	}
	return b, resp.Request.URL, nil
}

// saveImage 下一张图存进 assets 文件夹,名字是序号加按类型定的扩展名。
func (c *Clips) saveImage(ctx context.Context, run *jobRun, owner, folder uuid.UUID, seq int, src, referer string, budget int64) (*store.Node, int64, error) {
	ctx, cancel := context.WithTimeout(ctx, clipFetchTime)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, src, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("User-Agent", c.ua)
	req.Header.Set("Accept", "image/avif,image/webp,image/*,*/*;q=0.5")
	req.Header.Set("Referer", referer) // 不少图床按来源防盗链
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	limit := min(int64(clipImageMax), budget)
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, 0, err
	}
	if int64(len(b)) > limit {
		return nil, 0, errors.New("too large")
	}
	ext := imageExt(http.DetectContentType(b), resp.Header.Get("Content-Type"), src)
	if ext == "" {
		return nil, 0, errors.New("not an image")
	}
	sum := sha256.Sum256(b)
	key := fmt.Sprintf("%s.img%d", jobTmpKey(run.job.ID), seq)
	if err := c.obj.Put(ctx, key, bytes.NewReader(b), int64(len(b)), "application/octet-stream"); err != nil {
		return nil, 0, err
	}
	n, err := c.uploads.CommitStaged(ctx, StagedFile{
		Owner: owner, Parent: &folder, Name: fmt.Sprintf("%02d%s", seq, ext), Sha: hex.EncodeToString(sum[:]), Size: int64(len(b)), TmpKey: key,
	})
	if err != nil {
		return nil, 0, err
	}
	return n, int64(len(b)), nil
}

var imageExts = map[string]string{
	"image/jpeg": ".jpg", "image/png": ".png", "image/gif": ".gif", "image/webp": ".webp", "image/avif": ".avif",
	"image/svg+xml": ".svg", "image/bmp": ".bmp", "image/x-icon": ".ico", "image/vnd.microsoft.icon": ".ico",
}

// imageExt 先按文件头认,认不出(svg、avif)再看响应头与地址里的扩展名;不是图片返回空串。
func imageExt(sniffed, header, src string) string {
	if e, ok := imageExts[sniffed]; ok {
		return e
	}
	if mt, _, _ := mime.ParseMediaType(header); strings.HasPrefix(mt, "image/") {
		if e, ok := imageExts[mt]; ok {
			return e
		}
	}
	if u, err := url.Parse(src); err == nil {
		switch e := strings.ToLower(path.Ext(u.Path)); e {
		case ".svg", ".avif", ".webp", ".jpg", ".jpeg", ".png", ".gif":
			if strings.HasPrefix(sniffed, "text/") || sniffed == "application/octet-stream" {
				return e
			}
		}
	}
	return ""
}

// mdPathEscape Markdown 链接地址里不能有空格、括号与尖括号,其余字符(包括中文)原样保留,方便在别的编辑器里看。
func mdPathEscape(s string) string {
	return strings.NewReplacer(" ", "%20", "(", "%28", ")", "%29", "<", "%3C", ">", "%3E", "#", "%23", "?", "%3F").Replace(s)
}

// clipBaseName 文件名:标题去掉不能用的字符,最长 80 个字。
func clipBaseName(title string) string {
	s := strings.Map(func(r rune) rune {
		switch r {
		case '/', '\\', ':', '*', '?', '"', '<', '>', '|', '\x00':
			return ' '
		}
		if r < 0x20 {
			return -1
		}
		return r
	}, title)
	s = strings.Join(strings.Fields(s), " ")
	s = strings.Trim(s, ". ")
	if s == "" {
		s = "剪藏 " + time.Now().Format("20060102-150405")
	}
	return truncateRunesPlain(s, 80)
}

func truncateRunesPlain(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n]))
}

// targetFolder 订阅给了文件夹就用它,否则按剪藏设置找到或建出「剪藏文件夹[/年-月]」。
func (c *Clips) targetFolder(ctx context.Context, owner uuid.UUID, given *uuid.UUID) (*uuid.UUID, error) {
	if given != nil {
		if err := checkTargetFolder(ctx, c.q, owner, given); err != nil {
			return nil, err
		}
		return given, nil
	}
	prefs, err := c.Prefs(ctx, owner)
	if err != nil {
		return nil, err
	}
	_, segs, err := normalizeFolderPath(prefs.Folder)
	if err != nil {
		_, segs, _ = normalizeFolderPath(defaultClipDir)
	}
	if prefs.ByMonth {
		segs = append(segs, time.Now().Format("2006-01"))
	}
	return c.uploads.EnsurePath(withQuiet(ctx), owner, segs)
}

// ---------- 列表与设置 ----------

func (c *Clips) List(ctx context.Context, owner uuid.UUID, unreadOnly bool, site string, cursor *string) ([]store.Clip, *string, error) {
	p := store.ListClipsParams{OwnerID: owner, UnreadOnly: unreadOnly, Site: site, Lim: clipsPageSize}
	if cur := decodeCursor(cursor, orderClips, false); cur != nil {
		p.BeforeID, p.BeforeAt = cur.ID, tstz(time.Unix(0, cur.T))
	}
	rows, err := c.q.ListClips(ctx, p)
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) == clipsPageSize {
		last := rows[len(rows)-1]
		next = encodeCursor(cursorPayload{O: orderClips, T: last.CreatedAt.Time.UnixNano(), ID: last.ID})
	}
	return rows, next, nil
}

func (c *Clips) Sites(ctx context.Context, owner uuid.UUID) ([]store.ListClipSitesRow, error) {
	return c.q.ListClipSites(ctx, owner)
}

func (c *Clips) Unread(ctx context.Context, owner uuid.UUID) (int64, error) {
	return c.q.CountUnreadClips(ctx, owner)
}

func (c *Clips) Get(ctx context.Context, owner, id uuid.UUID) (store.Clip, error) {
	cl, err := c.q.GetClip(ctx, store.GetClipParams{ID: id, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return cl, ErrNotFound
	}
	return cl, err
}

// ByNode 某篇 .md 是不是剪藏(预览时显示来源与「标记已读」)。
func (c *Clips) ByNode(ctx context.Context, owner, nodeID uuid.UUID) (*store.Clip, error) {
	cl, err := c.q.GetClipByNode(ctx, store.GetClipByNodeParams{NodeID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &cl, nil
}

func (c *Clips) SetRead(ctx context.Context, owner, id uuid.UUID, read bool) (store.Clip, error) {
	cl, err := c.q.SetClipRead(ctx, store.SetClipReadParams{ID: id, OwnerID: owner, Read: read})
	if errors.Is(err, pgx.ErrNoRows) {
		return cl, ErrNotFound
	}
	return cl, err
}

type ClipPrefs struct {
	Folder  string
	ByMonth bool
}

func (c *Clips) Prefs(ctx context.Context, owner uuid.UUID) (ClipPrefs, error) {
	p, err := c.q.GetClipPrefs(ctx, owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return ClipPrefs{Folder: defaultClipDir, ByMonth: true}, nil
	}
	if err != nil {
		return ClipPrefs{}, err
	}
	return ClipPrefs{Folder: p.Folder, ByMonth: p.ByMonth}, nil
}

func (c *Clips) SetPrefs(ctx context.Context, owner uuid.UUID, in ClipPrefs) (ClipPrefs, error) {
	clean, _, err := normalizeFolderPath(in.Folder)
	if err != nil {
		return ClipPrefs{}, err
	}
	p, err := c.q.SetClipPrefs(ctx, store.SetClipPrefsParams{OwnerID: owner, Folder: clean, ByMonth: in.ByMonth})
	if err != nil {
		return ClipPrefs{}, err
	}
	return ClipPrefs{Folder: p.Folder, ByMonth: p.ByMonth}, nil
}
