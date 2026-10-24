package service_test

// 在线编辑文本(doc/25)的集成测试:要 TEST_DB_URL 与 TEST_S3_ENDPOINT。

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

func TestTextEditCreateReadSave(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()

	// 新建:空内容可以,同名自动改名
	n, err := f.uploads.CreateTextFile(ctx, f.owner, nil, "笔记.md", "")
	if err != nil {
		t.Fatal(err)
	}
	again, err := f.uploads.CreateTextFile(ctx, f.owner, nil, "笔记.md", "# 第二篇\n")
	if err != nil || again.Name != "笔记 (1).md" {
		t.Fatalf("同名应改名:%+v %v", again, err)
	}
	tf, err := f.uploads.ReadTextFile(ctx, f.owner, n.ID)
	if err != nil || tf.Content != "" || tf.Sha256 != shaHex(nil) {
		t.Fatalf("读空文件:%+v %v", tf, err)
	}

	// 保存:带着打开时的 sha,旧内容进历史版本,动态记「在线编辑」
	saved, err := f.uploads.SaveTextFile(ctx, f.owner, n.ID, "# 笔记\n\n第一版", tf.Sha256)
	if err != nil || saved.ID != n.ID {
		t.Fatalf("保存:%+v %v", saved, err)
	}
	tf2, err := f.uploads.ReadTextFile(ctx, f.owner, n.ID)
	if err != nil || tf2.Content != "# 笔记\n\n第一版" {
		t.Fatalf("读回:%+v %v", tf2, err)
	}
	if vs := f.list(t, n.ID); len(vs) != 1 || vs[0].Size != 0 {
		t.Fatalf("空的旧内容也留成版本:%+v", vs)
	}
	var note string
	if err := f.pool.QueryRow(ctx, `SELECT detail->>'note' FROM activities WHERE owner_id = $1 AND action = 'overwrite' ORDER BY id DESC LIMIT 1`, f.owner).Scan(&note); err != nil || note != "在线编辑" {
		t.Fatalf("动态的说明:%q %v", note, err)
	}

	// 用旧的 sha 再存:冲突;不带 sha:照存
	_, err = f.uploads.SaveTextFile(ctx, f.owner, n.ID, "别处的改动", tf.Sha256)
	requireCode(t, err, "EDIT_CONFLICT")
	if _, err := f.uploads.SaveTextFile(ctx, f.owner, n.ID, "强行保存", ""); err != nil {
		t.Fatal(err)
	}
	// 内容没变:不多出版本
	before := len(f.list(t, n.ID))
	tf3, _ := f.uploads.ReadTextFile(ctx, f.owner, n.ID)
	if _, err := f.uploads.SaveTextFile(ctx, f.owner, n.ID, "强行保存", tf3.Sha256); err != nil {
		t.Fatal(err)
	}
	if len(f.list(t, n.ID)) != before {
		t.Fatal("内容没变不该多出版本")
	}
	if got := f.used(t); got != int64(len("强行保存")+len("# 第二篇\n")+len("# 笔记\n\n第一版")) {
		t.Fatalf("已用空间:%d", got)
	}
}

func TestTextEditRejects(t *testing.T) {
	f := newVerFixture(t, 10)
	ctx := context.Background()

	bin := f.put(t, nil, "logo.png", []byte{0x89, 'P', 'N', 'G', 0, 0, 0xff, 0xfe})
	_, err := f.uploads.ReadTextFile(ctx, f.owner, bin.ID)
	requireCode(t, err, "NOT_TEXT")

	big := f.put(t, nil, "big.txt", bytes.Repeat([]byte("a"), service.MaxTextEdit+1))
	_, err = f.uploads.ReadTextFile(ctx, f.owner, big.ID)
	requireCode(t, err, "TOO_LARGE")
	_, err = f.uploads.SaveTextFile(ctx, f.owner, bin.ID, strings.Repeat("a", service.MaxTextEdit+1), "")
	requireCode(t, err, "TOO_LARGE")

	folder, err := f.nodes.CreateFolder(ctx, f.owner, nil, "dir")
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.uploads.ReadTextFile(ctx, f.owner, folder.ID)
	requireCode(t, err, "NOT_A_FILE")
	_, err = f.uploads.SaveTextFile(ctx, f.owner, folder.ID, "x", "")
	requireCode(t, err, "NOT_A_FILE")

	// 别人的文件:当作不存在
	_, err = f.uploads.ReadTextFile(ctx, uuid.Must(uuid.NewV7()), bin.ID)
	if err != service.ErrNotFound {
		t.Fatalf("别人的文件应 NOT_FOUND,got %v", err)
	}
}
