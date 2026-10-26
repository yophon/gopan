package httpx_test

// Markdown 渲染(/md/,doc/23 五.2)在 HTTP 层的行为:沙箱 CSP、相对路径改写与跳转、分享访客的范围、错误页。

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/httpx"
	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func TestMarkdownHTTP(t *testing.T) {
	pool := setupPool(t)
	obj := setupObj(t)
	ctx := t.Context()
	q := store.New(pool)
	auth := newTestAuth(pool)
	nodes := service.NewNodes(pool)
	uploads := service.NewUploads(pool, obj, nodes, 5<<20, time.Hour)
	shares := service.NewShares(q, auth)
	md := service.NewMarkdown(pool, obj)

	res, err := auth.Register(ctx, "md_owner", "password123", "1.1.1.1")
	if err != nil {
		t.Fatal(err)
	}
	owner := res.User.ID
	token, _ := auth.IssueAccess(owner, service.ScopeUser, uuid.Nil)
	folder, err := nodes.CreateFolder(ctx, owner, nil, "笔记")
	if err != nil {
		t.Fatal(err)
	}
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
	doc := put(&folder.ID, "读我.md", []byte("---\ntitle: \"读我\"\nsource: \"https://example.com/p\"\n---\n\n# 读我\n\n![图](图片/a.png) [另一篇](二.md) <script>alert(1)</script>\n"))
	put(&folder.ID, "二.md", []byte("# 二"))
	imgDir, _ := nodes.CreateFolder(ctx, owner, &folder.ID, "图片")
	put(&imgDir.ID, "a.png", []byte("\x89PNG\r\n\x1a\nfake"))
	outside := put(nil, "外面.md", []byte("# 外面"))

	mux := http.NewServeMux()
	h := httpx.MarkdownHandler(auth, md, shares)
	mux.Handle("GET /md/{node}", h)
	mux.Handle("GET /md/{node}/f", h)
	mux.Handle("POST /md/{node}/preview", h)
	srv := httptest.NewServer(httpx.WithSecurityHeaders(mux, false))
	defer srv.Close()
	noRedirect := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	get := func(path string) (*http.Response, string) {
		resp, err := noRedirect.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		return resp, string(b)
	}

	resp, body := get("/md/" + doc.ID.String() + "?token=" + token + "&theme=dark")
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/html") {
		t.Fatalf("render = %d %s", resp.StatusCode, body)
	}
	csp := resp.Header.Get("Content-Security-Policy")
	if !strings.HasPrefix(csp, "sandbox allow-popups") || !strings.Contains(csp, "frame-ancestors 'self'") || resp.Header.Get("X-Frame-Options") != "SAMEORIGIN" {
		t.Fatalf("headers = %q %q", csp, resp.Header.Get("X-Frame-Options"))
	}
	if strings.Contains(body, "<script>") || !strings.Contains(body, `data-theme="dark"`) || !strings.Contains(body, "原文:") {
		t.Fatalf("body = %s", body)
	}
	imgPath := "/md/" + doc.ID.String() + "/f?p=%E5%9B%BE%E7%89%87%2Fa.png&amp;token=" + token + "&amp;theme=dark"
	if !strings.Contains(body, `src="`+imgPath+`"`) {
		t.Fatalf("image not rewritten (want %s):\n%s", imgPath, body)
	}
	// 相对路径:图片 302 到签名地址,.md 跳到它的渲染页
	f := func(rel, tok string) string {
		return "/md/" + doc.ID.String() + "/f?p=" + url.QueryEscape(rel) + "&token=" + tok
	}
	resp, _ = get(f("图片/a.png", token))
	if resp.StatusCode != http.StatusFound || !strings.Contains(resp.Header.Get("Location"), "X-Amz-Signature") {
		t.Fatalf("asset = %d %s", resp.StatusCode, resp.Header.Get("Location"))
	}
	resp, _ = get(f("二.md", token))
	if resp.StatusCode != http.StatusFound || !strings.HasPrefix(resp.Header.Get("Location"), "/md/") {
		t.Fatalf("md link = %d %s", resp.StatusCode, resp.Header.Get("Location"))
	}
	resp, _ = get(f("没有.png", token))
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("missing = %d", resp.StatusCode)
	}
	// 没登录 / 不是 Markdown
	resp, body = get("/md/" + doc.ID.String())
	if resp.StatusCode != http.StatusUnauthorized || !strings.Contains(body, "登录") {
		t.Fatalf("anon = %d %s", resp.StatusCode, body)
	}
	resp, _ = get("/md/" + imgDir.ID.String() + "?token=" + token)
	if resp.StatusCode != http.StatusUnsupportedMediaType {
		t.Fatalf("folder = %d", resp.StatusCode)
	}

	// 分享访客:分享范围内能看、能跟相对路径;范围外的 404
	sh, err := shares.Create(ctx, owner, folder.ID, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	visitor, _ := auth.IssueAccess(owner, "share:"+sh.ID.String(), uuid.Nil)
	if resp, _ = get("/md/" + doc.ID.String() + "?token=" + visitor); resp.StatusCode != 200 {
		t.Fatalf("visitor = %d", resp.StatusCode)
	}
	if resp, _ = get(f("图片/a.png", visitor)); resp.StatusCode != http.StatusFound {
		t.Fatalf("visitor asset = %d", resp.StatusCode)
	}
	if resp, _ = get(f("../外面.md", visitor)); resp.StatusCode != http.StatusNotFound {
		t.Fatalf("visitor escape = %d", resp.StatusCode)
	}
	if resp, _ = get(f("../外面.md", token)); resp.StatusCode != http.StatusFound { // 属主可以往上找
		t.Fatalf("owner parent = %d", resp.StatusCode)
	}
	if resp, _ = get("/md/" + outside.ID.String() + "?token=" + visitor); resp.StatusCode != http.StatusNotFound {
		t.Fatalf("visitor outside = %d", resp.StatusCode)
	}

	// 编辑时边写边看:渲染请求体里还没保存的文字,相对路径照样改写;访客不行
	preview := func(tok, src string) (*http.Response, string) {
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/md/"+doc.ID.String()+"/preview?theme=light", strings.NewReader(src))
		req.Header.Set("Authorization", "Bearer "+tok)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		return resp, string(b)
	}
	resp, body = preview(token, "# 还没保存\n\n![图](图片/a.png)\n")
	if resp.StatusCode != 200 || !strings.Contains(body, "还没保存") || !strings.Contains(body, "/md/"+doc.ID.String()+"/f?p=%E5%9B%BE%E7%89%87%2Fa.png") {
		t.Fatalf("preview = %d %s", resp.StatusCode, body)
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Security-Policy"), "sandbox allow-popups") {
		t.Fatalf("preview csp = %q", resp.Header.Get("Content-Security-Policy"))
	}
	if resp, _ = preview(visitor, "# x"); resp.StatusCode != http.StatusForbidden {
		t.Fatalf("visitor preview = %d", resp.StatusCode)
	}
}
