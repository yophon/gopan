package service

import (
	"context"
	"errors"
	"io"
	"math"
	"path/filepath"
	"sort"
	"strings"
	"unicode"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
	"github.com/yophon/gopan/server/internal/subtitle"
)

const (
	minProgressMedia = 300 // 5 分钟以下的音视频不记进度
	minProgressPages = 3
	maxSubtitleBytes = 2 << 20
	maxPlaylist      = 1000
	recentPlaysMax   = 50
)

// Media 影音(doc/22 二):播放进度、连播列表、外挂字幕。
type Media struct {
	q   *store.Queries
	obj *objstore.Store
}

func NewMedia(pool *pgxpool.Pool, obj *objstore.Store) *Media {
	return &Media{q: store.New(pool), obj: obj}
}

// ---------- 播放进度 ----------

// progressUnit 这个文件按什么记进度:秒(音视频)、页(PDF、转成 PDF 预览的 Office),其余不记。
func progressUnit(name string, mime *string, size *int64) string {
	switch ClassifyPreview(name, mime, size) {
	case PvVideo, PvAudio:
		return "sec"
	case PvPDF, PvOffice:
		return "page"
	}
	return ""
}

// IsFinished 剩余不到 3% 或不到 20 秒算看完;PDF 翻到最后一页算看完。
func IsFinished(unit string, position, duration float64) bool {
	if unit == "page" {
		return position >= duration
	}
	return duration-position <= math.Max(20, duration*0.03)
}

// SaveProgress 不记的(太短、不是音视频 / PDF)返回 nil。
func (m *Media) SaveProgress(ctx context.Context, owner, nodeID uuid.UUID, position, duration float64) (*store.PlayProgress, error) {
	if math.IsNaN(position) || math.IsNaN(duration) || math.IsInf(position, 0) || math.IsInf(duration, 0) {
		return nil, errf("INVALID_INPUT", "进度不对")
	}
	n, err := m.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if n.Kind != "file" {
		return nil, nil
	}
	unit := progressUnit(n.Name, n.BlobMime, n.BlobSize)
	switch unit {
	case "sec":
		if duration < minProgressMedia {
			return nil, nil
		}
		position = math.Max(0, math.Min(position, duration))
	case "page":
		duration = math.Round(duration)
		if duration < minProgressPages {
			return nil, nil
		}
		position = math.Max(1, math.Min(math.Round(position), duration))
	default:
		return nil, nil
	}
	p, err := m.q.UpsertPlayProgress(ctx, store.UpsertPlayProgressParams{
		OwnerID: owner, NodeID: nodeID, Position: position, Duration: duration, Finished: IsFinished(unit, position, duration),
	})
	if err != nil {
		return nil, err
	}
	return &p, nil
}

func (m *Media) Progress(ctx context.Context, owner, nodeID uuid.UUID) (*store.PlayProgress, error) {
	p, err := m.q.GetPlayProgress(ctx, store.GetPlayProgressParams{OwnerID: owner, NodeID: nodeID})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &p, nil
}

func (m *Media) ClearProgress(ctx context.Context, owner, nodeID uuid.UUID) error {
	_, err := m.q.DeletePlayProgress(ctx, store.DeletePlayProgressParams{OwnerID: owner, NodeID: nodeID})
	return err
}

func (m *Media) RecentPlays(ctx context.Context, owner uuid.UUID, limit int) ([]store.ListRecentPlaysRow, error) {
	limit = max(1, min(limit, recentPlaysMax))
	return m.q.ListRecentPlays(ctx, store.ListRecentPlaysParams{OwnerID: owner, Limit: int32(limit)})
}

func (m *Media) Cleanup(ctx context.Context) error {
	_, err := m.q.CleanupPlayProgress(ctx)
	return err
}

// ---------- 连播 ----------

// Playlist 同文件夹里同类(视频或音频)的文件,自然顺序,最多 1000 个(多了取它前后的一段)。
// onlySelf:分享访客、且这个文件本身就是分享根(兄弟文件不在分享范围里)。
func (m *Media) Playlist(ctx context.Context, owner, nodeID uuid.UUID, onlySelf bool) ([]store.ListFolderMediaRow, error) {
	n, err := m.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	self := store.ListFolderMediaRow{Node: nodeOf(n), BlobSize: n.BlobSize, BlobMime: n.BlobMime, BlobSha256: n.BlobSha256}
	var prefix string
	switch ClassifyPreview(n.Name, n.BlobMime, n.BlobSize) {
	case PvVideo:
		prefix = "video/"
	case PvAudio:
		prefix = "audio/"
	default:
		return []store.ListFolderMediaRow{self}, nil
	}
	if onlySelf {
		return []store.ListFolderMediaRow{self}, nil
	}
	rows, err := m.q.ListFolderMedia(ctx, store.ListFolderMediaParams{OwnerID: owner, ParentID: n.ParentID, MimePrefix: prefix})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(rows, func(i, j int) bool { return NaturalLess(rows[i].Node.Name, rows[j].Node.Name) })
	if len(rows) > maxPlaylist {
		at := 0
		for i, r := range rows {
			if r.Node.ID == nodeID {
				at = i
			}
		}
		lo := max(0, min(at-maxPlaylist/2, len(rows)-maxPlaylist))
		rows = rows[lo : lo+maxPlaylist]
	}
	return rows, nil
}

func nodeOf(r store.GetNodeWithBlobRow) store.Node {
	return store.Node{
		ID: r.ID, OwnerID: r.OwnerID, ParentID: r.ParentID, Name: r.Name, Kind: r.Kind, BlobID: r.BlobID,
		DeletedAt: r.DeletedAt, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt,
		SubtreeBytes: r.SubtreeBytes, SubtreeCount: r.SubtreeCount, StatsStale: r.StatsStale,
	}
}

// NaturalLess 按自然顺序比较文件名:数字串按数值比(第2集 < 第10集),其余按不分大小写的字符。
func NaturalLess(a, b string) bool {
	ra, rb := []rune(strings.ToLower(a)), []rune(strings.ToLower(b))
	i, j := 0, 0
	for i < len(ra) && j < len(rb) {
		if unicode.IsDigit(ra[i]) && unicode.IsDigit(rb[j]) {
			si := i
			for i < len(ra) && unicode.IsDigit(ra[i]) {
				i++
			}
			sj := j
			for j < len(rb) && unicode.IsDigit(rb[j]) {
				j++
			}
			na := strings.TrimLeft(string(ra[si:i]), "0")
			nb := strings.TrimLeft(string(rb[sj:j]), "0")
			if len(na) != len(nb) {
				return len(na) < len(nb)
			}
			if na != nb {
				return na < nb
			}
			continue
		}
		if ra[i] != rb[j] {
			return ra[i] < rb[j]
		}
		i++
		j++
	}
	if len(ra)-i != len(rb)-j {
		return len(ra)-i < len(rb)-j
	}
	return a < b
}

// ---------- 外挂字幕 ----------

type SubtitleTrack struct {
	NodeID      uuid.UUID
	Name        string
	Label, Lang string
	Default     bool
}

var subtitleExts = map[string]bool{".srt": true, ".ass": true, ".ssa": true, ".vtt": true}

// Subtitles 视频同文件夹里的字幕:文件名是「视频名去扩展名」加字幕扩展名,或中间多一段语言标记;
// 文件夹里只有一个视频时,所有字幕都算它的。中文排前面、默认选中文。
func (m *Media) Subtitles(ctx context.Context, owner uuid.UUID, parent *uuid.UUID, videoName string) ([]SubtitleTrack, error) {
	rows, err := m.q.ListFolderSubtitles(ctx, store.ListFolderSubtitlesParams{OwnerID: owner, ParentID: parent})
	if err != nil || len(rows) == 0 {
		return nil, err
	}
	stem := strings.ToLower(strings.TrimSuffix(videoName, filepath.Ext(videoName)))
	type cand struct {
		row store.ListFolderSubtitlesRow
		tag string
	}
	var matched, all []cand
	for _, r := range rows {
		if r.BlobSize != nil && *r.BlobSize > maxSubtitleBytes {
			continue
		}
		s := strings.ToLower(strings.TrimSuffix(r.Name, filepath.Ext(r.Name)))
		tag := ""
		if i := strings.LastIndex(s, "."); i >= 0 {
			tag = s[i+1:]
		}
		all = append(all, cand{r, tag})
		switch {
		case s == stem:
			matched = append(matched, cand{r, ""})
		case strings.HasPrefix(s, stem+"."):
			matched = append(matched, cand{r, s[len(stem)+1:]})
		}
	}
	if len(matched) == 0 {
		n, err := m.q.CountFolderVideos(ctx, store.CountFolderVideosParams{OwnerID: owner, ParentID: parent})
		if err != nil {
			return nil, err
		}
		if n != 1 {
			return nil, nil
		}
		matched = all
	}
	out := make([]SubtitleTrack, 0, len(matched))
	for _, c := range matched {
		label, lang := subtitle.Lang(c.tag)
		if label == "" {
			if label = c.tag; label == "" {
				label = "字幕"
			}
		}
		out = append(out, SubtitleTrack{NodeID: c.row.ID, Name: c.row.Name, Label: label, Lang: lang})
	}
	rank := func(t SubtitleTrack) int {
		switch {
		case t.Lang == "zh-Hans":
			return 0
		case t.Lang == "zh":
			return 1
		case strings.HasPrefix(t.Lang, "zh"):
			return 2
		case t.Lang == "":
			return 3
		}
		return 4
	}
	sort.SliceStable(out, func(i, j int) bool { return rank(out[i]) < rank(out[j]) })
	out[0].Default = true
	return out, nil
}

// SubtitleVTT 读一个字幕文件,解码(UTF-8 / UTF-16 / GB18030)并转成 WebVTT。
func (m *Media) SubtitleVTT(ctx context.Context, owner, nodeID uuid.UUID) (string, error) {
	n, err := m.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: nodeID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	ext := strings.ToLower(filepath.Ext(n.Name))
	if n.Kind != "file" || n.BlobSha256 == nil || !subtitleExts[ext] {
		return "", errf("UNSUPPORTED_TYPE", "不是字幕文件")
	}
	if n.BlobSize != nil && *n.BlobSize > maxSubtitleBytes {
		return "", errf("TOO_LARGE", "字幕文件太大")
	}
	r, err := m.obj.Open(ctx, objstore.BlobKey(*n.BlobSha256))
	if err != nil {
		return "", err
	}
	defer r.Close()
	b, err := io.ReadAll(io.LimitReader(r, maxSubtitleBytes+1))
	if err != nil {
		return "", err
	}
	text, ok := fulltext.DecodeText(b)
	if !ok {
		return "", errf("UNSUPPORTED_ENCODING", "认不出字幕的文字编码")
	}
	vtt, err := subtitle.ToVTT(ext, text)
	if err != nil {
		return "", errf("UNSUPPORTED_TYPE", "%s", err.Error())
	}
	return vtt, nil
}
