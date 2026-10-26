package service

import (
	"context"
	"errors"
	"io"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/fulltext"
	"github.com/yophon/gopan/server/internal/lyrics"
	"github.com/yophon/gopan/server/internal/objstore"
	"github.com/yophon/gopan/server/internal/store"
)

const maxLyricsBytes = 512 << 10

// ErrNoLyrics 这首歌旁边没有同名 .lrc。
var ErrNoLyrics = &Error{Code: "NOT_FOUND", Message: "没有歌词"}

// Lyrics 歌的同名 .lrc(同文件夹,名字不分大小写;doc/31 二),解码(UTF-8 / UTF-16 / GB18030)后解析好。
func (m *Media) Lyrics(ctx context.Context, owner, audioID uuid.UUID) (lyrics.Lyrics, error) {
	n, err := m.q.GetNodeWithBlob(ctx, store.GetNodeWithBlobParams{ID: audioID, OwnerID: owner})
	if errors.Is(err, pgx.ErrNoRows) {
		return lyrics.Lyrics{}, ErrNotFound
	}
	if err != nil {
		return lyrics.Lyrics{}, err
	}
	if n.Kind != "file" || ClassifyPreview(n.Name, n.BlobMime, n.BlobSize) != PvAudio {
		return lyrics.Lyrics{}, errf("UNSUPPORTED_TYPE", "不是音频文件")
	}
	stem := strings.TrimSuffix(n.Name, filepath.Ext(n.Name))
	lrc, err := m.q.FindFolderFileByName(ctx, store.FindFolderFileByNameParams{OwnerID: owner, ParentID: n.ParentID, Name: stem + ".lrc"})
	if errors.Is(err, pgx.ErrNoRows) {
		return lyrics.Lyrics{}, ErrNoLyrics
	}
	if err != nil {
		return lyrics.Lyrics{}, err
	}
	if lrc.BlobSize > maxLyricsBytes {
		return lyrics.Lyrics{}, errf("TOO_LARGE", "歌词文件太大")
	}
	r, err := m.obj.Open(ctx, objstore.BlobKey(lrc.BlobSha256))
	if err != nil {
		return lyrics.Lyrics{}, err
	}
	defer r.Close()
	b, err := io.ReadAll(io.LimitReader(r, maxLyricsBytes+1))
	if err != nil {
		return lyrics.Lyrics{}, err
	}
	text, ok := fulltext.DecodeText(b)
	if !ok {
		return lyrics.Lyrics{}, errf("UNSUPPORTED_ENCODING", "认不出歌词的文字编码")
	}
	return lyrics.Parse(text), nil
}
