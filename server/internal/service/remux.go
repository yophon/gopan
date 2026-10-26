package service

import (
	"bufio"
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/subtitle"
)

// 网页放 mkv(doc/31 一):浏览器放不了的视频容器(mkv / avi / ts / flv …)在服务器上转成 mp4 再放。
// 只换封装、不转画面:视频流原样拷贝,浏览器不认的音频(AC3 / DTS / FLAC …)转成 AAC,内嵌的文字字幕转成 WebVTT。
// 转好的放在本地缓存目录(不进对象存储、不占配额),按最近播放时间淘汰;重启、重新部署后缓存没了也不要紧,再放一次重新转。
// 任务排在内存里,一次只转一个。安卓 App 自己能放这些格式,不走这里。

// RemuxStatus 与 GraphQL 枚举同名。
const (
	RmNone        = "NONE"        // 没转过,requestRemux 开始
	RmQueued      = "QUEUED"      // 排队中
	RmRunning     = "RUNNING"     // 正在转,Progress 有值
	RmDone        = "DONE"        // 好了,URL 有值
	RmUnsupported = "UNSUPPORTED" // 画面编码浏览器放不了,转封装也没用
	RmFailed      = "FAILED"      // 转失败了(可以重试)
	RmUnavailable = "UNAVAILABLE" // 太大、服务器空间不够
)

type RemuxInfo struct {
	Status   string
	Progress *float64
	URL      *string
	// 浏览器多半能直接放原文件(mkv 里是 H.264 + AAC 之类、没有内嵌字幕):先直接放,放不了再转
	Direct    bool
	Message   *string
	Subtitles []RemuxSubtitle
}

type RemuxSubtitle struct {
	Label, Lang, URL string
	Default          bool
}

type RemuxConfig struct {
	Dir       string        // 缓存目录
	CacheSize int64         // 缓存总量上限,0 = 关掉;单个文件也不能超过它
	TTL       time.Duration // 多久没放就删
	FFmpeg    string
	FFprobe   string
}

const (
	remuxURLTTL   = 6 * time.Hour
	remuxSrcTTL   = 12 * time.Hour // 给 ffmpeg 的源地址:一部大片转半小时,中途还会断线重连
	remuxQueueMax = 8
	remuxMaxSubs  = 8
	// 转完以后磁盘上至少还要剩这么多:数据库、对象存储和它在同一块盘上
	remuxMinFree = 3 << 30
	// 失败记多久:过了这个时间再打开就当没转过,自动再试一次
	remuxFailMemory = time.Hour
)

var remuxExts = map[string]bool{
	".mkv": true, ".avi": true, ".ts": true, ".m2ts": true, ".mts": true, ".flv": true, ".f4v": true,
	".wmv": true, ".asf": true, ".mpg": true, ".mpeg": true, ".vob": true, ".rm": true, ".rmvb": true, ".divx": true,
}

var remuxMimes = map[string]bool{
	"video/x-matroska": true, "video/x-msvideo": true, "video/avi": true, "video/mp2t": true, "video/x-flv": true,
	"video/x-ms-wmv": true, "video/x-ms-asf": true, "video/mpeg": true, "application/vnd.rn-realmedia": true,
	"application/vnd.rn-realmedia-vbr": true,
}

// RemuxCandidate 浏览器原生放不了的容器。mp4 / webm / mov 不算(就算放不了,也是编码的问题,转封装没用)。
func RemuxCandidate(name string, mime *string) bool {
	if remuxExts[strings.ToLower(filepath.Ext(name))] {
		return true
	}
	return mime != nil && remuxMimes[*mime]
}

// ---------- 媒体信息(上传后 media_probe 记下的) ----------

// MediaMeta blobs.media_meta:时长,以及 3.17.0 起的主要流编码。Streams 为 false 的是老数据,只有时长。
type MediaMeta struct {
	DurationSec float64 `json:"duration_sec"`
	Streams     bool    `json:"streams,omitempty"`
	VCodec      string  `json:"vcodec,omitempty"`
	VPix        string  `json:"vpix,omitempty"`
	ACodec      string  `json:"acodec,omitempty"`
	TextSubs    int     `json:"tsubs,omitempty"`
}

type ffStream struct {
	Index       int               `json:"index"`
	CodecName   string            `json:"codec_name"`
	CodecType   string            `json:"codec_type"`
	PixFmt      string            `json:"pix_fmt"`
	Disposition map[string]int    `json:"disposition"`
	Tags        map[string]string `json:"tags"`
}

type ffProbe struct {
	Streams []ffStream `json:"streams"`
	Format  struct {
		Duration string `json:"duration"`
	} `json:"format"`
}

func (p ffProbe) duration() float64 {
	d, _ := strconv.ParseFloat(p.Format.Duration, 64)
	return d
}

// ProbeArgs ffprobe 的参数:时长 + 各条流。
func ProbeArgs(src string) []string {
	return []string{"-v", "quiet", "-show_format", "-show_streams", "-of", "json", src}
}

// MediaMetaFromProbe 把 ffprobe 的输出折成 media_meta。
func MediaMetaFromProbe(out []byte) ([]byte, error) {
	var p ffProbe
	if err := json.Unmarshal(out, &p); err != nil {
		return nil, err
	}
	m := MediaMeta{DurationSec: p.duration(), Streams: true}
	if v := pickVideo(p.Streams); v != nil {
		m.VCodec, m.VPix = v.CodecName, v.PixFmt
	}
	if a := pickAudio(p.Streams); a != nil {
		m.ACodec = a.CodecName
	}
	m.TextSubs = len(textSubs(p.Streams))
	return json.Marshal(m)
}

func parseMediaMeta(b []byte) MediaMeta {
	var m MediaMeta
	if len(b) > 0 {
		_ = json.Unmarshal(b, &m)
	}
	return m
}

func pickVideo(ss []ffStream) *ffStream {
	for i := range ss {
		if ss[i].CodecType == "video" && ss[i].Disposition["attached_pic"] != 1 {
			return &ss[i]
		}
	}
	return nil
}

// pickAudio 标了默认的那条,没有就第一条。
func pickAudio(ss []ffStream) *ffStream {
	var first *ffStream
	for i := range ss {
		if ss[i].CodecType != "audio" {
			continue
		}
		if ss[i].Disposition["default"] == 1 {
			return &ss[i]
		}
		if first == nil {
			first = &ss[i]
		}
	}
	return first
}

// 能转成 WebVTT 的文字字幕;PGS / DVD 这类图片字幕转不了
var textSubCodecs = map[string]bool{"subrip": true, "srt": true, "ass": true, "ssa": true, "webvtt": true, "mov_text": true, "text": true}

func textSubs(ss []ffStream) []ffStream {
	var out []ffStream
	for _, s := range ss {
		if s.CodecType == "subtitle" && textSubCodecs[s.CodecName] {
			out = append(out, s)
		}
	}
	return out
}

// eightBit H.264 只有 8 位 4:2:0 浏览器才放得了(动画常见的 10 位 Hi10P 不行)。
func eightBit(pix string) bool {
	return pix == "" || pix == "yuv420p" || pix == "yuvj420p" || pix == "nv12"
}

// videoCopyable 画面原样拷进 mp4 后浏览器有望放出来。HEVC 看显卡和浏览器,放不了时播放器会提示。
func videoCopyable(codec, pix string) bool {
	switch codec {
	case "h264":
		return eightBit(pix)
	case "hevc", "av1", "vp9", "vp8":
		return true
	}
	return false
}

// audioCopyable 能原样放进 mp4 给浏览器放的音频;其余转 AAC。
func audioCopyable(codec string) bool {
	return codec == "aac" || codec == "mp3" || codec == "opus"
}

// directFriendly mkv 里装的是浏览器认得的编码、又没有内嵌字幕:Chrome / Edge 能直接放原文件,不用等转换。
func directFriendly(name string, m MediaMeta) bool {
	if strings.ToLower(filepath.Ext(name)) != ".mkv" || !m.Streams || m.TextSubs > 0 {
		return false
	}
	switch m.VCodec {
	case "h264":
		if !eightBit(m.VPix) {
			return false
		}
	case "vp8", "vp9", "av1":
	default:
		return false
	}
	switch m.ACodec {
	case "", "aac", "mp3", "opus", "vorbis", "flac":
		return true
	}
	return false
}

// ---------- 转换计划 ----------

type remuxSub struct {
	index         int
	label, lang   string
	defaultInFile bool
}

type remuxPlan struct {
	video     ffStream
	audio     *ffStream
	audioCopy bool
	subs      []remuxSub
	duration  float64
}

type unsupportedError struct{ msg string }

func (e *unsupportedError) Error() string { return e.msg }

func planRemux(p ffProbe) (remuxPlan, error) {
	v := pickVideo(p.Streams)
	if v == nil {
		return remuxPlan{}, &unsupportedError{"文件里没有画面"}
	}
	if !videoCopyable(v.CodecName, v.PixFmt) {
		return remuxPlan{}, &unsupportedError{unsupportedVideoText(v.CodecName, v.PixFmt)}
	}
	pl := remuxPlan{video: *v, duration: p.duration()}
	if a := pickAudio(p.Streams); a != nil {
		pl.audio = a
		pl.audioCopy = audioCopyable(a.CodecName)
	}
	for i, s := range textSubs(p.Streams) {
		if i >= remuxMaxSubs {
			break
		}
		label, lang := embeddedSubLabel(s, i)
		pl.subs = append(pl.subs, remuxSub{index: s.Index, label: label, lang: lang, defaultInFile: s.Disposition["default"] == 1})
	}
	return pl, nil
}

func unsupportedVideoText(codec, pix string) string {
	if codec == "h264" && !eightBit(pix) {
		return "画面是 10 位的 H.264,浏览器放不了"
	}
	if codec == "" {
		codec = "未知"
	}
	return "画面编码是 " + strings.ToUpper(codec) + ",浏览器放不了"
}

// embeddedSubLabel 显示名优先用轨道标题(「简体中文」「简日双语」),其次按语言代码;语言按标题再认一次。
func embeddedSubLabel(s ffStream, i int) (string, string) {
	title := strings.TrimSpace(s.Tags["title"])
	langCode := strings.ToLower(strings.TrimSpace(s.Tags["language"]))
	if langCode == "zho" {
		langCode = "chi"
	}
	label, lang := subtitle.Lang(langCode)
	if tl, tg := subtitle.Lang(title); tg != "" {
		lang = tg
		if label == "" {
			label = tl
		}
	}
	if title != "" {
		label = title
	}
	if label == "" {
		label = fmt.Sprintf("内嵌字幕 %d", i+1)
	}
	return label, lang
}

// defaultSub 中文优先,其次文件里标了默认的,再其次第一条。
func defaultSub(subs []remuxSub) int {
	if len(subs) == 0 {
		return -1
	}
	best, bestRank := 0, 99
	for i, s := range subs {
		r := 5
		switch {
		case s.lang == "zh-Hans":
			r = 0
		case s.lang == "zh":
			r = 1
		case strings.HasPrefix(s.lang, "zh"):
			r = 2
		case s.defaultInFile:
			r = 3
		}
		if r < bestRank {
			best, bestRank = i, r
		}
	}
	return best
}

func remuxArgs(src, out string, pl remuxPlan) []string {
	a := []string{"-nostdin", "-hide_banner", "-v", "error", "-progress", "pipe:1"}
	if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
		a = append(a, "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10")
	}
	a = append(a, "-fflags", "+genpts", "-i", src,
		"-map", fmt.Sprintf("0:%d", pl.video.Index))
	if pl.audio != nil {
		a = append(a, "-map", fmt.Sprintf("0:%d", pl.audio.Index))
	}
	a = append(a, "-c:v", "copy")
	if pl.video.CodecName == "hevc" {
		a = append(a, "-tag:v", "hvc1") // 不打这个标签 Safari / Chrome 认不出 mp4 里的 HEVC
	}
	if pl.audio != nil {
		if pl.audioCopy {
			a = append(a, "-c:a", "copy")
		} else {
			a = append(a, "-c:a", "aac", "-b:a", "192k", "-ac", "2")
		}
	}
	a = append(a, "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
		"-max_muxing_queue_size", "4096", "-f", "mp4", filepath.Join(out, "video.mp4"))
	for i, s := range pl.subs {
		a = append(a, "-map", fmt.Sprintf("0:%d", s.index), "-c:s", "webvtt", "-f", "webvtt",
			filepath.Join(out, fmt.Sprintf("sub%d.vtt", i)))
	}
	return a
}

// ---------- 缓存与任务 ----------

type remuxJob struct {
	status   string
	progress float64
	msg      string
	at       time.Time
}

// remuxCached 缓存目录里 <sha>/meta.json
type remuxCached struct {
	Subs []struct {
		File    string `json:"file"`
		Label   string `json:"label"`
		Lang    string `json:"lang"`
		Default bool   `json:"default"`
	} `json:"subs"`
}

type Remuxer struct {
	cfg   RemuxConfig
	obj   *objstore.Store
	key   []byte // 播放地址签名,每次启动随机:重启后旧地址失效,播放器会重新取
	queue chan string

	mu   sync.Mutex
	jobs map[string]*remuxJob
}

// NewRemuxer CacheSize 为 0、或 ffmpeg / ffprobe 找不到时返回 nil(本次部署不转换,网页照旧提示下载)。
func NewRemuxer(cfg RemuxConfig, obj *objstore.Store) *Remuxer {
	if cfg.CacheSize <= 0 {
		return nil
	}
	if _, err := exec.LookPath(cfg.FFmpeg); err != nil {
		slog.Warn("remux disabled: ffmpeg not found", "path", cfg.FFmpeg)
		return nil
	}
	if _, err := exec.LookPath(cfg.FFprobe); err != nil {
		slog.Warn("remux disabled: ffprobe not found", "path", cfg.FFprobe)
		return nil
	}
	if err := os.MkdirAll(cfg.Dir, 0o750); err != nil {
		slog.Warn("remux disabled: cache dir", "dir", cfg.Dir, "err", err)
		return nil
	}
	key := make([]byte, 32)
	_, _ = rand.Read(key)
	return &Remuxer{cfg: cfg, obj: obj, key: key, queue: make(chan string, remuxQueueMax), jobs: map[string]*remuxJob{}}
}

var shaRe = regexp.MustCompile(`^[0-9a-f]{64}$`)

func (r *Remuxer) doneDir(sha string) string { return filepath.Join(r.cfg.Dir, sha) }
func (r *Remuxer) partDir(sha string) string { return filepath.Join(r.cfg.Dir, sha+".part") }

// Info 当前状态,不触发转换。
func (r *Remuxer) Info(sha, name string, size int64, metaJSON []byte) RemuxInfo {
	meta := parseMediaMeta(metaJSON)
	info := RemuxInfo{Status: RmNone, Direct: directFriendly(name, meta)}
	if !shaRe.MatchString(sha) {
		info.Status = RmUnavailable
		return info
	}
	if c, ok := r.cached(sha); ok {
		info.Status = RmDone
		u := r.signedURL(sha, "video.mp4")
		info.URL = &u
		for _, s := range c.Subs {
			info.Subtitles = append(info.Subtitles, RemuxSubtitle{Label: s.Label, Lang: s.Lang, Default: s.Default, URL: r.signedURL(sha, s.File)})
		}
		return info
	}
	r.mu.Lock()
	job := r.jobs[sha]
	if job != nil && (job.status == RmFailed || job.status == RmUnsupported) && time.Since(job.at) > remuxFailMemory {
		delete(r.jobs, sha)
		job = nil
	}
	if job != nil {
		info.Status = job.status
		if job.status == RmRunning {
			p := job.progress
			info.Progress = &p
		}
		if job.msg != "" {
			m := job.msg
			info.Message = &m
		}
	}
	r.mu.Unlock()
	if job != nil {
		return info
	}
	if meta.Streams && meta.VCodec != "" && !videoCopyable(meta.VCodec, meta.VPix) {
		info.Status = RmUnsupported
		m := unsupportedVideoText(meta.VCodec, meta.VPix)
		info.Message = &m
		return info
	}
	if size > r.cfg.CacheSize {
		info.Status = RmUnavailable
		m := fmt.Sprintf("文件超过 %s,不在服务器上转换", humanBytes(r.cfg.CacheSize))
		info.Message = &m
	}
	return info
}

// Request 没转过(或上次失败)就排上。
func (r *Remuxer) Request(sha, name string, size int64, metaJSON []byte) (RemuxInfo, error) {
	info := r.Info(sha, name, size, metaJSON)
	if info.Status != RmNone && info.Status != RmFailed {
		return info, nil
	}
	r.mu.Lock()
	if j := r.jobs[sha]; j != nil && (j.status == RmQueued || j.status == RmRunning) {
		r.mu.Unlock()
		return r.Info(sha, name, size, metaJSON), nil
	}
	select {
	case r.queue <- sha:
		r.jobs[sha] = &remuxJob{status: RmQueued, at: time.Now()}
	default:
		r.mu.Unlock()
		return info, errf("BUSY", "排队转换的视频太多了,过一会儿再试")
	}
	r.mu.Unlock()
	return r.Info(sha, name, size, metaJSON), nil
}

func (r *Remuxer) cached(sha string) (remuxCached, bool) {
	var c remuxCached
	dir := r.doneDir(sha)
	if _, err := os.Stat(filepath.Join(dir, "video.mp4")); err != nil {
		return c, false
	}
	if b, err := os.ReadFile(filepath.Join(dir, "meta.json")); err == nil {
		_ = json.Unmarshal(b, &c)
	}
	return c, true
}

// ---------- 播放地址 ----------

func (r *Remuxer) sign(sha string, exp int64) string {
	m := hmac.New(sha256.New, r.key)
	fmt.Fprintf(m, "%s|%d", sha, exp)
	return hex.EncodeToString(m.Sum(nil))[:32]
}

func (r *Remuxer) signedURL(sha, file string) string {
	exp := time.Now().Add(remuxURLTTL).Unix()
	return fmt.Sprintf("/remux/%s/%s?e=%d&s=%s", sha, file, exp, r.sign(sha, exp))
}

var remuxFileRe = regexp.MustCompile(`^(video\.mp4|sub[0-9]{1,2}\.vtt)$`)

// Open 校验签名,打开缓存里的文件(调用方负责关)。播放视频时顺手刷新「最近播放」。
func (r *Remuxer) Open(sha, file, e, s string) (*os.File, error) {
	if !shaRe.MatchString(sha) || !remuxFileRe.MatchString(file) {
		return nil, ErrNotFound
	}
	exp, err := strconv.ParseInt(e, 10, 64)
	if err != nil || time.Now().Unix() > exp || !hmac.Equal([]byte(s), []byte(r.sign(sha, exp))) {
		return nil, ErrForbidden
	}
	p := filepath.Join(r.doneDir(sha), file)
	f, err := os.Open(p)
	if err != nil {
		return nil, ErrNotFound
	}
	if file == "video.mp4" {
		if st, err := f.Stat(); err == nil && time.Since(st.ModTime()) > time.Hour {
			now := time.Now()
			_ = os.Chtimes(p, now, now)
		}
	}
	return f, nil
}

// ---------- 后台 ----------

// Run 一次转一个;每小时清一次过期缓存。阻塞到 ctx 取消。
func (r *Remuxer) Run(ctx context.Context) {
	r.clearParts()
	r.evict(0)
	tick := time.NewTicker(time.Hour)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			r.evict(0)
		case sha := <-r.queue:
			r.run(ctx, sha)
		}
	}
}

func (r *Remuxer) setJob(sha string, fn func(j *remuxJob)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	j := r.jobs[sha]
	if j == nil {
		j = &remuxJob{}
		r.jobs[sha] = j
	}
	fn(j)
	j.at = time.Now()
}

func (r *Remuxer) run(ctx context.Context, sha string) {
	r.setJob(sha, func(j *remuxJob) { j.status, j.progress = RmRunning, 0 })
	start := time.Now()
	err := r.convert(ctx, sha)
	var ue *unsupportedError
	switch {
	case err == nil:
		r.mu.Lock()
		delete(r.jobs, sha)
		r.mu.Unlock()
		slog.Info("remux done", "sha256", sha, "dur", time.Since(start).Round(time.Second))
	case errors.As(err, &ue):
		r.setJob(sha, func(j *remuxJob) { j.status, j.msg = RmUnsupported, ue.msg })
	default:
		slog.Error("remux failed", "sha256", sha, "err", err)
		msg := "转换失败,可以重试"
		var se *Error
		if errors.As(err, &se) {
			msg = se.Message
		}
		r.setJob(sha, func(j *remuxJob) { j.status, j.msg = RmFailed, msg })
	}
}

func (r *Remuxer) convert(ctx context.Context, sha string) error {
	src, err := r.obj.PresignInternalGetFor(ctx, objstore.BlobKey(sha), remuxSrcTTL)
	if err != nil {
		return err
	}
	size, err := r.obj.Stat(ctx, objstore.BlobKey(sha))
	if err != nil {
		return err
	}
	return r.convertFrom(ctx, sha, src, size)
}

// convertFrom 探测 src(地址或本地路径),转进 <sha>.part,成功后改名成 <sha>。
func (r *Remuxer) convertFrom(ctx context.Context, sha, src string, size int64) error {
	pctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	out, err := exec.CommandContext(pctx, r.cfg.FFprobe, ProbeArgs(src)...).Output()
	cancel()
	if err != nil {
		return fmt.Errorf("ffprobe: %w", err)
	}
	var probe ffProbe
	if err := json.Unmarshal(out, &probe); err != nil {
		return err
	}
	pl, err := planRemux(probe)
	if err != nil {
		return err
	}
	if size > r.cfg.CacheSize {
		return errf("TOO_LARGE", "文件超过 %s,不在服务器上转换", humanBytes(r.cfg.CacheSize))
	}
	// 输出和原文件差不多大(音频转码后通常更小);先腾地方
	r.evict(size)
	if free := diskFree(r.cfg.Dir); free >= 0 && free-size < remuxMinFree {
		return errf("NO_SPACE", "服务器空间不够,转换不了")
	}

	part := r.partDir(sha)
	_ = os.RemoveAll(part)
	if err := os.MkdirAll(part, 0o750); err != nil {
		return err
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.RemoveAll(part)
		}
	}()

	// 按 4 MB/s 估,最少 10 分钟
	timeout := 10*time.Minute + time.Duration(size/(4<<20))*time.Second
	cctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := exec.CommandContext(cctx, r.cfg.FFmpeg, remuxArgs(src, part, pl)...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		if p, ok := progressOf(sc.Text(), pl.duration); ok {
			r.setJob(sha, func(j *remuxJob) { j.progress = p })
		}
	}
	if err := cmd.Wait(); err != nil {
		return fmt.Errorf("ffmpeg: %v: %s", err, lastLineOf(stderr.String()))
	}

	var meta remuxCached
	pick := defaultSub(pl.subs)
	for i, s := range pl.subs {
		file := fmt.Sprintf("sub%d.vtt", i)
		if st, err := os.Stat(filepath.Join(part, file)); err != nil || st.Size() == 0 {
			continue
		}
		meta.Subs = append(meta.Subs, struct {
			File    string `json:"file"`
			Label   string `json:"label"`
			Lang    string `json:"lang"`
			Default bool   `json:"default"`
		}{file, s.label, s.lang, i == pick})
	}
	b, _ := json.Marshal(meta)
	if err := os.WriteFile(filepath.Join(part, "meta.json"), b, 0o640); err != nil {
		return err
	}
	done := r.doneDir(sha)
	_ = os.RemoveAll(done)
	if err := os.Rename(part, done); err != nil {
		return err
	}
	ok = true
	return nil
}

// progressOf ffmpeg -progress 的一行:out_time_us=… → 0~1。
func progressOf(line string, duration float64) (float64, bool) {
	if duration <= 0 {
		return 0, false
	}
	k, v, found := strings.Cut(line, "=")
	if !found || (k != "out_time_us" && k != "out_time_ms") {
		return 0, false
	}
	us, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
	if err != nil || us < 0 {
		return 0, false
	}
	return min(1, float64(us)/1e6/duration), true
}

func (r *Remuxer) clearParts() {
	ents, _ := os.ReadDir(r.cfg.Dir)
	for _, e := range ents {
		if strings.HasSuffix(e.Name(), ".part") {
			_ = os.RemoveAll(filepath.Join(r.cfg.Dir, e.Name()))
		}
	}
}

type remuxEntry struct {
	sha  string
	size int64
	used time.Time
}

func (r *Remuxer) entries() []remuxEntry {
	ents, _ := os.ReadDir(r.cfg.Dir)
	var out []remuxEntry
	for _, e := range ents {
		if !e.IsDir() || !shaRe.MatchString(e.Name()) {
			continue
		}
		dir := filepath.Join(r.cfg.Dir, e.Name())
		st, err := os.Stat(filepath.Join(dir, "video.mp4"))
		if err != nil {
			_ = os.RemoveAll(dir) // 残缺的
			continue
		}
		var size int64
		files, _ := os.ReadDir(dir)
		for _, f := range files {
			if fi, err := f.Info(); err == nil {
				size += fi.Size()
			}
		}
		out = append(out, remuxEntry{sha: e.Name(), size: size, used: st.ModTime()})
	}
	return out
}

// evict 删掉过期的;再按最近播放从旧到新删,直到放得下 need。
func (r *Remuxer) evict(need int64) {
	es := r.entries()
	sort.Slice(es, func(i, j int) bool { return es[i].used.Before(es[j].used) })
	var total int64
	for _, e := range es {
		total += e.size
	}
	for _, e := range es {
		if time.Since(e.used) <= r.cfg.TTL && total+need <= r.cfg.CacheSize {
			break
		}
		if err := os.RemoveAll(r.doneDir(e.sha)); err == nil {
			total -= e.size
		}
	}
}

func lastLineOf(s string) string {
	s = strings.TrimSpace(s)
	return s[strings.LastIndexByte(s, '\n')+1:]
}
