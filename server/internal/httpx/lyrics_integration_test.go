package httpx_test

// 3.17.0(doc/31):歌词接口 /lrc/、外部播放器的长效地址、「快传」文件夹、没开转换时的 requestRemux。

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/text/encoding/simplifiedchinese"

	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/lyrics"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestLyricsStreamAndQuickShare(t *testing.T) {
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := t.Context()
	q := store.New(pool)
	auth := newTestAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	shares := service.NewShares(q, auth)
	media := service.NewMedia(pool, obj)

	res, err := auth.Register(ctx, "lrc_owner", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	token, _ := auth.IssueAccess(owner, service.ScopeUser, uuid.Nil)
	put := func(parent *uuid.UUID, name string, body []byte) store.Node {
		key := "tmp/test/" + uuid.NewString()
		if err := obj.Put(ctx, key, bytes.NewReader(body), int64(len(body)), "application/octet-stream"); err != nil {
			t.Fatal(err)
		}
		sum := sha256.Sum256(body)
		n, err := uploads.CommitStaged(ctx, service.StagedFile{Owner: owner, Parent: parent, Name: name, Sha: hex.EncodeToString(sum[:]), Size: int64(len(body)), TmpKey: key})
		if err != nil {
			t.Fatal(err)
		}
		return *n
	}
	album, err := nodes.CreateFolder(ctx, owner, nil, "专辑")
	if err != nil {
		t.Fatal(err)
	}
	song := put(&album.ID, "晴天.mp3", []byte("ID3 fake mp3 bytes"))
	other := put(&album.ID, "七里香.mp3", []byte("ID3 another fake"))
	gbk, _ := simplifiedchinese.GBK.NewEncoder().Bytes([]byte("[ti:晴天]\r\n[00:01.50]故事的小黄花\r\n[00:03.00][00:09.00]从出生那年就飘着\r\n"))
	lrc := put(&album.ID, "晴天.LRC", gbk)

	mux := http.NewServeMux()
	mux.Handle("GET /lrc/{node}", httpx.LyricsHandler(auth, media, shares))
	srv := httptest.NewServer(mux)
	defer srv.Close()
	get := func(path, tok string) *http.Response {
		req, _ := http.NewRequest("GET", srv.URL+path, nil)
		if tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { resp.Body.Close() })
		return resp
	}

	r := get("/lrc/"+song.ID.String(), token)
	if r.StatusCode != 200 || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		t.Fatalf("lyrics %d %v", r.StatusCode, r.Header)
	}
	var got lyrics.Lyrics
	if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if !got.Synced || len(got.Lines) != 3 || got.Lines[0].Text != "故事的小黄花" || got.Lines[0].T != 1.5 || got.Lines[2].T != 9 {
		t.Fatalf("GBK 的 .LRC 解析:%+v", got)
	}
	if r := get("/lrc/"+other.ID.String(), token); r.StatusCode != 404 {
		t.Fatalf("没有歌词应 404:%d", r.StatusCode)
	}
	if r := get("/lrc/"+lrc.ID.String(), token); r.StatusCode != 415 {
		t.Fatalf("不是音频应 415:%d", r.StatusCode)
	}
	if r := get("/lrc/"+song.ID.String(), ""); r.StatusCode != 401 {
		t.Fatalf("不带凭证应 401:%d", r.StatusCode)
	}

	// 外部播放器的长效地址:音频有,歌词文件没有
	if u, err := uploads.StreamURL(ctx, owner, song.ID); err != nil || u == nil || !strings.Contains(*u, "X-Amz-Expires=43200") {
		t.Fatalf("streamUrl:%v %v", u, err)
	}
	if u, err := uploads.StreamURL(ctx, owner, lrc.ID); err != nil || u != nil {
		t.Fatalf("非音视频不给:%v %v", u, err)
	}

	// 「快传」:幂等;根目录有同名文件时另建「快传 (1)」
	a, err := nodes.QuickShareFolder(ctx, owner)
	if err != nil || a.Name != service.QuickShareFolderName || a.Kind != "folder" {
		t.Fatalf("quick share folder:%+v %v", a, err)
	}
	if b, err := nodes.QuickShareFolder(ctx, owner); err != nil || b.ID != a.ID {
		t.Fatalf("应复用:%+v %v", b, err)
	}
	res2, _ := auth.Register(ctx, "lrc_owner2", "password123", "1.1.1.1")
	owner2 := res2.User.ID
	key := "tmp/test/" + uuid.NewString()
	_ = obj.Put(ctx, key, bytes.NewReader([]byte("x")), 1, "text/plain")
	sum := sha256.Sum256([]byte("x"))
	if _, err := uploads.CommitStaged(ctx, service.StagedFile{Owner: owner2, Name: "快传", Sha: hex.EncodeToString(sum[:]), Size: 1, TmpKey: key}); err != nil {
		t.Fatal(err)
	}
	if c, err := nodes.QuickShareFolder(ctx, owner2); err != nil || c.Name != "快传 (1)" || c.Kind != "folder" {
		t.Fatalf("同名文件时:%+v %v", c, err)
	}

	// 本次部署没开转换:requestRemux 回 UNAVAILABLE
	previews := service.NewPreviews(pool, obj)
	mkv := put(&album.ID, "电影.mkv", []byte("not really matroska"))
	if info, err := previews.RequestRemux(ctx, owner, mkv.ID); err != nil || info.Status != service.RmUnavailable {
		t.Fatalf("remux off:%+v %v", info, err)
	}
}
