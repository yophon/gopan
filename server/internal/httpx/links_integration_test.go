package httpx_test

// 直链(/x/)、图床接口(/api/img/upload)与字幕(/sub/)在 HTTP 层的行为(doc/22):
// Range 与 304、内联与下载、SVG 的 CSP、来源白名单;图床密钥与登录凭证都能传、普通 API Key 不行;字幕回 WebVTT。

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fetcher"
	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestLinksHTTP(t *testing.T) {
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := t.Context()
	q := store.New(pool)
	auth := newTestAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	shares := service.NewShares(q, auth)
	tokens := service.NewMCPTokens(q)
	notify := service.NewNotifier(pool, fetcher.Guard{}, "")
	links := service.NewLinks(pool, obj, uploads, notify, service.LinkConfig{
		Enabled: true, DailyBytes: 1 << 30, MonthlyBytes: 1 << 40, ImgMaxSize: 1 << 20, Loc: time.UTC,
	})
	media := service.NewMedia(pool, obj)

	res, err := auth.Register(ctx, "link_owner", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	userToken, _ := auth.IssueAccess(owner, service.ScopeUser, uuid.Nil)
	put := func(name string, body []byte) store.Node {
		key := "tmp/test/" + uuid.NewString()
		if err := obj.Put(ctx, key, bytes.NewReader(body), int64(len(body)), "application/octet-stream"); err != nil {
			t.Fatal(err)
		}
		sum := sha256.Sum256(body)
		n, err := uploads.CommitStaged(ctx, service.StagedFile{Owner: owner, Name: name, Sha: hex.EncodeToString(sum[:]), Size: int64(len(body)), TmpKey: key})
		if err != nil {
			t.Fatal(err)
		}
		return *n
	}
	link := func(n store.Node) service.LinkView {
		vs, err := links.CreateLinks(ctx, owner, []uuid.UUID{n.ID}, service.LinkMeta{Source: "web"})
		if err != nil {
			t.Fatal(err)
		}
		return vs[0]
	}

	mux := http.NewServeMux()
	h := httpx.LinkHandler(links, obj, nil)
	mux.Handle("GET /x/{token}", h)
	mux.Handle("GET /x/{token}/{name...}", h)
	mux.Handle("POST /api/img/upload", httpx.ImageUploadHandler(auth, tokens, links, nil))
	mux.Handle("GET /sub/{node}", httpx.SubtitleHandler(auth, media, shares))
	srv := httptest.NewServer(httpx.WithSecurityHeaders(mux, false))
	defer srv.Close()
	get := func(path string, hdr map[string]string) *http.Response {
		req, _ := http.NewRequest("GET", srv.URL+path, nil)
		for k, v := range hdr {
			req.Header.Set(k, v)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { resp.Body.Close() })
		return resp
	}
	body := func(r *http.Response) string {
		var b bytes.Buffer
		_, _ = b.ReadFrom(r.Body)
		return b.String()
	}

	txt := put("说明.txt", []byte("0123456789abcdef"))
	tl := link(txt)
	r := get(links.Path(tl.Token, tl.NodeName), nil)
	if r.StatusCode != 200 || body(r) != "0123456789abcdef" {
		t.Fatalf("GET %d", r.StatusCode)
	}
	if !strings.HasPrefix(r.Header.Get("Content-Disposition"), "attachment") || r.Header.Get("Access-Control-Allow-Origin") != "*" ||
		r.Header.Get("X-Robots-Tag") != "noindex" || r.Header.Get("X-Frame-Options") != "" {
		t.Fatalf("文本走下载、跨域放开、不让收录:%v", r.Header)
	}
	etag := r.Header.Get("ETag")
	if r := get("/x/"+tl.Token+"/改过的名字.txt", map[string]string{"Range": "bytes=2-5"}); r.StatusCode != 206 || body(r) != "2345" {
		t.Fatalf("Range:%d", r.StatusCode)
	}
	if r := get("/x/"+tl.Token, map[string]string{"If-None-Match": etag}); r.StatusCode != 304 {
		t.Fatalf("304:%d", r.StatusCode)
	}
	if r := get("/x/nope/a.txt", nil); r.StatusCode != 404 || r.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("不存在:%d", r.StatusCode)
	}

	svg := put("logo.svg", []byte(`<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`))
	sl := link(svg)
	r = get(links.Path(sl.Token, sl.NodeName), nil)
	if r.Header.Get("Content-Type") != "image/svg+xml" || !strings.HasPrefix(r.Header.Get("Content-Disposition"), "inline") ||
		!strings.Contains(r.Header.Get("Content-Security-Policy"), "sandbox") {
		t.Fatalf("SVG 内联但沙箱:%v", r.Header)
	}
	page := put("页.html", []byte("<script>alert(1)</script>"))
	pl := link(page)
	if r := get(links.Path(pl.Token, pl.NodeName), nil); r.Header.Get("Content-Type") != "application/octet-stream" || !strings.HasPrefix(r.Header.Get("Content-Disposition"), "attachment") {
		t.Fatalf("HTML 永远不内联:%v", r.Header)
	}

	hosts := []string{"blog.cn"}
	if _, err := links.Update(ctx, owner, tl.ID, service.LinkSettings{RefererHosts: &hosts}); err != nil {
		t.Fatal(err)
	}
	if r := get("/x/"+tl.Token, map[string]string{"Referer": "https://evil.com/p"}); r.StatusCode != 403 {
		t.Fatalf("白名单外 403:%d", r.StatusCode)
	}
	if r := get("/x/"+tl.Token, map[string]string{"Referer": "https://www.blog.cn/p"}); r.StatusCode != 200 {
		t.Fatalf("白名单内 200:%d", r.StatusCode)
	}

	// 图床接口
	upload := func(token, name string, data []byte) (*http.Response, map[string]any) {
		var buf bytes.Buffer
		mw := multipart.NewWriter(&buf)
		_ = mw.WriteField("note", "ignored")
		fw, _ := mw.CreateFormFile("image", name)
		fw.Write(data)
		mw.Close()
		req, _ := http.NewRequest("POST", srv.URL+"/api/img/upload", &buf)
		req.Header.Set("Content-Type", mw.FormDataContentType())
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return resp, out
	}
	gif := []byte("GIF89a\x01\x00\x01\x00\x00\x00\x00;")
	imgKey, _, err := tokens.Create(ctx, owner, "ShareX", []string{service.ScopeImgUpload})
	if err != nil {
		t.Fatal(err)
	}
	resp, out := upload(imgKey, "a.gif", gif)
	if resp.StatusCode != 200 || !strings.HasPrefix(out["url"].(string), srv.URL+"/x/") || !strings.HasPrefix(out["markdown"].(string), "![a](") {
		t.Fatalf("图床密钥上传:%d %v", resp.StatusCode, out)
	}
	if r := get(strings.TrimPrefix(out["url"].(string), srv.URL), nil); r.StatusCode != 200 || r.Header.Get("Content-Type") != "image/gif" {
		t.Fatalf("传上去的能直接看:%d", r.StatusCode)
	}
	lid, _ := uuid.Parse(out["link_id"].(string))
	if v, _ := links.Get(ctx, owner, lid); v.Source != "api" || v.ApiKeyID == nil {
		t.Fatal("记下来源与密钥")
	}
	if resp, _ := upload(userToken, "b.gif", gif); resp.StatusCode != 200 {
		t.Fatalf("登录凭证也能传:%d", resp.StatusCode)
	}
	plainKey, _, _ := tokens.Create(ctx, owner, "files", []string{"files:upload"})
	if resp, out := upload(plainKey, "c.gif", gif); resp.StatusCode != 403 {
		t.Fatalf("普通 API Key 不能传图床:%d %v", resp.StatusCode, out)
	}
	if resp, _ := upload("", "c.gif", gif); resp.StatusCode != 401 {
		t.Fatal("没带凭证 401")
	}
	if resp, _ := upload("gopan_key_wrong", "c.gif", gif); resp.StatusCode != 401 {
		t.Fatal("错的密钥 401")
	}
	if resp, out := upload(imgKey, "c.txt", []byte("hello")); resp.StatusCode != 415 || out["error"].(map[string]any)["code"] != "UNSUPPORTED_TYPE" {
		t.Fatalf("非图片 415:%d %v", resp.StatusCode, out)
	}
	// 直接把图片当请求体
	req, _ := http.NewRequest("POST", srv.URL+"/api/img/upload?name=raw.gif", bytes.NewReader(gif))
	req.Header.Set("Authorization", "Bearer "+imgKey)
	req.Header.Set("Content-Type", "image/gif")
	rr, err := http.DefaultClient.Do(req)
	if err != nil || rr.StatusCode != 200 {
		t.Fatalf("raw body:%v %d", err, rr.StatusCode)
	}
	rr.Body.Close()

	// 字幕
	srt := put("片.srt", []byte("1\r\n00:00:01,000 --> 00:00:02,000\r\n你好\r\n"))
	r = get("/sub/"+srt.ID.String()+"?token="+userToken, nil)
	if r.StatusCode != 200 || r.Header.Get("Content-Type") != "text/vtt; charset=utf-8" || !strings.Contains(body(r), "00:00:01.000 --> 00:00:02.000") {
		t.Fatalf("字幕:%d", r.StatusCode)
	}
	if r := get("/sub/"+srt.ID.String(), map[string]string{"Authorization": "Bearer " + userToken}); r.StatusCode != 200 {
		t.Fatal("也接受 Authorization 头")
	}
	if r := get("/sub/"+srt.ID.String(), nil); r.StatusCode != 401 {
		t.Fatal("未登录 401")
	}
	if r := get("/sub/"+txt.ID.String()+"?token="+userToken, nil); r.StatusCode != 415 {
		t.Fatalf("不是字幕 415:%d", r.StatusCode)
	}
}
