package archive

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"errors"
	"io"
	"io/fs"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/ulikunitz/xz"
	"golang.org/x/text/encoding/simplifiedchinese"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func index(t *testing.T, b []byte, name string) (*Reader, *Index) {
	t.Helper()
	f := Resolve(b[:min(len(b), 512)], name)
	if f == "" {
		t.Fatalf("%s: 认不出格式", name)
	}
	r, err := Open(readerAt(b), int64(len(b)), f)
	if err != nil {
		t.Fatalf("%s: open: %v", name, err)
	}
	idx, err := r.Index()
	if err != nil {
		t.Fatalf("%s: index: %v", name, err)
	}
	return r, idx
}

func entry(t *testing.T, idx *Index, p string) Entry {
	t.Helper()
	for _, e := range idx.Entries {
		if e.Path == p {
			return e
		}
	}
	t.Fatalf("没有条目 %q", p)
	return Entry{}
}

func readEntry(t *testing.T, r *Reader, src int) []byte {
	t.Helper()
	rc, err := r.OpenEntry(src)
	if err != nil {
		t.Fatalf("open %d: %v", src, err)
	}
	defer rc.Close()
	b, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("read %d: %v", src, err)
	}
	return b
}

func TestByName(t *testing.T) {
	cases := map[string]Format{
		"a.zip": Zip, "A.ZIP": Zip, "x.7z": SevenZip, "x.rar": Rar, "x.part1.rar": Rar, "x.part01.rar": Rar,
		"x.part2.rar": "", "x.tar": Tar, "x.tar.gz": TarGz, "x.tgz": TarGz, "x.tar.bz2": TarBz2, "x.tar.xz": TarXz,
		"x.docx": "", "x.apk": "", "x.gz": "", "x.7z.001": "", "readme": "",
	}
	for n, want := range cases {
		if got := ByName(n); got != want {
			t.Errorf("ByName(%q)=%q,应为 %q", n, got, want)
		}
	}
	for n, want := range map[string]string{"报告.zip": "报告", "src.tar.gz": "src", "a.part1.rar": "a", ".zip": ".zip", "x": "x"} {
		if got := BaseName(n); got != want {
			t.Errorf("BaseName(%q)=%q,应为 %q", n, got, want)
		}
	}
	if Detect(fixture(t, "zh-solid.7z")) != SevenZip || Detect(fixture(t, "rar5-solid.rar")) != Rar || Detect([]byte("hello")) != "" {
		t.Fatal("Detect")
	}
	// 名字是 .zip、内容是 7z:以文件头为准
	if Resolve(fixture(t, "t1.7z"), "fake.zip") != SevenZip {
		t.Fatal("Resolve 应以文件头为准")
	}
}

func TestCleanPath(t *testing.T) {
	cases := []struct {
		in, out string
		unsafe  bool
	}{
		{"a/b/c.txt", "a/b/c.txt", false},
		{"/abs/x", "abs/x", false},
		{`C:\win\y.txt`, "win/y.txt", false},
		{"./a/./b", "a/b", false},
		{"../../etc/passwd", "etc/passwd", true},
		{"a/../b", "a/b", true},
		{"a\x00b/c\x1f", "ab/c", false},
	}
	for _, c := range cases {
		got, unsafe := cleanPath(c.in)
		if got != c.out || unsafe != c.unsafe {
			t.Errorf("cleanPath(%q)=%q,%v,应为 %q,%v", c.in, got, unsafe, c.out, c.unsafe)
		}
	}
	long := strings.Repeat("长", 100)
	if got, _ := cleanPath(long); len(got) > 255 {
		t.Fatalf("超长名字没截断:%d", len(got))
	}
}

// 在内存里现做一个 zip:UTF-8 与 GBK 名字、显式与隐式目录、zip slip、软链接、加密标记、重名、反斜杠路径。
func buildZip(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	add := func(h *zip.FileHeader, body string) {
		f, err := w.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		if body != "" {
			f.Write([]byte(body))
		}
	}
	mt := time.Date(2024, 5, 1, 8, 0, 0, 0, time.UTC)
	add(&zip.FileHeader{Name: "dir/", Modified: mt}, "")
	add(&zip.FileHeader{Name: "dir/a.txt", Method: zip.Deflate, Modified: mt}, strings.Repeat("hello ", 1000))
	gbk, _ := simplifiedchinese.GBK.NewEncoder().String("报告/年度总结.txt")
	add(&zip.FileHeader{Name: gbk, NonUTF8: true, Method: zip.Deflate}, "gbk 名字")
	add(&zip.FileHeader{Name: "../evil.txt"}, "evil")
	link := &zip.FileHeader{Name: "lnk"}
	link.SetMode(fs.ModeSymlink | 0o777)
	add(link, "/etc/passwd")
	add(&zip.FileHeader{Name: "secret.txt", Flags: 0x1}, "xxxx")
	add(&zip.FileHeader{Name: "dup.txt"}, "one")
	add(&zip.FileHeader{Name: "dup.txt"}, "two")
	add(&zip.FileHeader{Name: `win\path\w.txt`}, "win")
	add(&zip.FileHeader{Name: "deep/er/z.txt", Method: zip.Deflate}, "z")
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestZip(t *testing.T) {
	b := buildZip(t)
	r, idx := index(t, b, "x.zip")
	if idx.Format != Zip || idx.Solid || !idx.Encrypted || idx.Files != 9 {
		t.Fatalf("idx: %+v", idx)
	}
	a := entry(t, idx, "dir/a.txt")
	if a.Size != 6000 || a.Packed >= a.Size || a.Parent != "dir" || a.Name != "a.txt" || a.Flag != "" || a.MTime.Year() != 2024 {
		t.Fatalf("a: %+v", a)
	}
	if got := readEntry(t, r, a.Src); string(got) != strings.Repeat("hello ", 1000) {
		t.Fatal("a 内容不对")
	}
	g := entry(t, idx, "报告/年度总结.txt")
	if string(readEntry(t, r, g.Src)) != "gbk 名字" {
		t.Fatal("GBK 名字的条目内容不对")
	}
	if e := entry(t, idx, "evil.txt"); e.Flag != FlagUnsafe {
		t.Fatalf("zip slip: %+v", e)
	}
	if e := entry(t, idx, "lnk"); e.Flag != FlagLink {
		t.Fatalf("软链接: %+v", e)
	}
	if e := entry(t, idx, "secret.txt"); e.Flag != FlagEncrypted {
		t.Fatalf("加密: %+v", e)
	}
	entry(t, idx, "win/path/w.txt")
	// 隐式目录补出来了,显式目录不重复
	for _, d := range []string{"报告", "deep", "deep/er", "win", "win/path"} {
		if e := entry(t, idx, d); !e.IsDir || e.Src != -1 {
			t.Fatalf("补出的目录 %s: %+v", d, e)
		}
	}
	dirs := 0
	for _, e := range idx.Entries {
		if e.Path == "dir" {
			dirs++
		}
	}
	if dirs != 1 {
		t.Fatalf("dir 出现 %d 次", dirs)
	}
	// Walk 按顺序给出每个原生条目
	var seen []int
	err := r.Walk(func(src int, rd io.Reader) (bool, error) {
		seen = append(seen, src)
		if rd != nil {
			io.Copy(io.Discard, rd)
		}
		return true, nil
	})
	if err != nil || len(seen) != 10 {
		t.Fatalf("walk: %v %v", seen, err)
	}
}

// 声明的大小比实际内容小:读的时候报错,不会多吐数据。
func TestZipLengthMismatch(t *testing.T) {
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	h := &zip.FileHeader{Name: "bomb.txt", Method: zip.Store, CRC32: 0, CompressedSize64: 10, UncompressedSize64: 4}
	f, err := w.CreateRaw(h)
	if err != nil {
		t.Fatal(err)
	}
	f.Write([]byte("0123456789"))
	w.Close()
	r, idx := index(t, buf.Bytes(), "b.zip")
	rc, err := r.OpenEntry(entry(t, idx, "bomb.txt").Src)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(rc)
	if err == nil || len(got) > 4 {
		t.Fatalf("应报错且不超过声明长度:%q %v", got, err)
	}
}

func buildTar(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := tar.NewWriter(&buf)
	write := func(h *tar.Header, body []byte) {
		if err := w.WriteHeader(h); err != nil {
			t.Fatal(err)
		}
		w.Write(body)
	}
	big := bytes.Repeat([]byte("0123456789abcdef"), 4096) // 64 KB
	write(&tar.Header{Name: "src/", Typeflag: tar.TypeDir, Mode: 0o755}, nil)
	write(&tar.Header{Name: "src/big.bin", Size: int64(len(big)), Mode: 0o644}, big)
	write(&tar.Header{Name: "src/中文.txt", Size: 6, Mode: 0o644, Format: tar.FormatPAX}, []byte("中文"))
	write(&tar.Header{Name: "src/ln", Typeflag: tar.TypeSymlink, Linkname: "/etc/shadow"}, nil)
	write(&tar.Header{Name: "../../up.txt", Size: 2, Mode: 0o644}, []byte("up"))
	w.Close()
	return buf.Bytes()
}

func TestTarFamily(t *testing.T) {
	raw := buildTar(t)
	var gz, xzb bytes.Buffer
	zw := gzip.NewWriter(&gz)
	zw.Write(raw)
	zw.Close()
	xw, err := xz.NewWriter(&xzb)
	if err != nil {
		t.Fatal(err)
	}
	xw.Write(raw)
	xw.Close()
	for _, c := range []struct {
		name string
		b    []byte
		f    Format
	}{{"a.tar", raw, Tar}, {"a.tar.gz", gz.Bytes(), TarGz}, {"a.tar.xz", xzb.Bytes(), TarXz}} {
		r, idx := index(t, c.b, c.name)
		if idx.Format != c.f || idx.Files != 4 { // 软链接也算一个(非目录)条目
			t.Fatalf("%s: %+v", c.name, idx)
		}
		zh := entry(t, idx, "src/中文.txt")
		if string(readEntry(t, r, zh.Src)) != "中文" {
			t.Fatalf("%s: 内容不对", c.name)
		}
		if c.f == Tar && (zh.Cost != 0 || idx.Solid) {
			t.Fatalf("未压缩 tar 应能跳读:%+v", zh)
		}
		if c.f != Tar && (zh.Cost < 64<<10 || !idx.Solid) {
			t.Fatalf("%s: 压缩 tar 的开销应包括前面的大文件:%+v", c.name, zh)
		}
		if e := entry(t, idx, "src/ln"); e.Flag != FlagLink {
			t.Fatalf("软链接: %+v", e)
		}
		if e := entry(t, idx, "up.txt"); e.Flag != FlagUnsafe {
			t.Fatalf("越界: %+v", e)
		}
		n := 0
		err := r.Walk(func(src int, rd io.Reader) (bool, error) {
			if rd != nil {
				b, err := io.ReadAll(rd)
				if err != nil {
					return false, err
				}
				n += len(b)
			}
			return true, nil
		})
		if err != nil || n != 64<<10+6+2 {
			t.Fatalf("%s walk: %d %v", c.name, n, err)
		}
	}
	// bzip2 样本(Python 生成):PAX 中文名、软链接
	r, idx := index(t, fixture(t, "zh.tar.bz2"), "zh.tar.bz2")
	e := entry(t, idx, "资料/说明.txt")
	if idx.Format != TarBz2 || string(readEntry(t, r, e.Src)) != strings.Repeat("你好 bz2\n", 50) {
		t.Fatalf("bz2: %+v", idx)
	}
	if entry(t, idx, "资料/link").Flag != FlagLink || e.MTime.Unix() != 1700000000 {
		t.Fatal("bz2 链接 / 时间")
	}
}

func TestBrokenTar(t *testing.T) {
	if _, err := Open(readerAt([]byte("not a tar")), 9, TarGz); err != nil {
		t.Fatal("Open 不读数据,应成功")
	}
	r, _ := Open(readerAt([]byte("not a gzip")), 10, TarGz)
	if _, err := r.Index(); err == nil {
		t.Fatal("坏的 gzip 应报错")
	}
	// 一半截断:前面的条目照样能看,记 truncated
	raw := buildTar(t)
	r, _ = Open(readerAt(raw[:len(raw)/2+700]), int64(len(raw)/2+700), Tar)
	idx, err := r.Index()
	if err != nil || len(idx.Entries) == 0 {
		t.Fatalf("截断的 tar:%v %v", idx, err)
	}
}

func Test7z(t *testing.T) {
	for _, name := range []string{"lzma2.7z", "ppmd.7z", "bzip2.7z", "zstd.7z", "copy.7z"} {
		r, idx := index(t, fixture(t, name), name)
		if idx.Files != 10 || idx.TotalSize != 36054 {
			t.Fatalf("%s: %+v", name, idx)
		}
		last := entry(t, idx, "10")
		if got := readEntry(t, r, last.Src); int64(len(got)) != last.Size {
			t.Fatalf("%s: 最后一个文件 %d 字节", name, len(got))
		}
		if name != "copy.7z" && (!idx.Solid || last.Cost == 0) {
			t.Fatalf("%s 应是固实的:%+v", name, last)
		}
	}
	r, idx := index(t, fixture(t, "zh-solid.7z"), "zh-solid.7z")
	if got := readEntry(t, r, entry(t, idx, "文档/报告.txt").Src); string(got) != strings.Repeat("年度报告\r\n", 200) { // 样本在 Windows 上生成
		t.Fatal("中文 7z 内容不对")
	}
	if !entry(t, idx, "文档/图片").IsDir {
		t.Fatal("目录")
	}
	// 文件头加密:列不出
	if _, err := Open(readerAt(fixture(t, "aes7z.7z")), int64(len(fixture(t, "aes7z.7z"))), SevenZip); !errors.Is(err, ErrEncrypted) {
		t.Fatalf("aes7z: %v", err)
	}
	_, idx = index(t, fixture(t, "empty.7z"), "empty.7z")
	if idx.Files != 5 || idx.TotalSize != 0 {
		t.Fatalf("empty: %+v", idx)
	}
}

func TestRar(t *testing.T) {
	for _, name := range []string{"rar3-solid.rar", "rar5-solid.rar"} {
		r, idx := index(t, fixture(t, name), name)
		e := entry(t, idx, "stest2.txt")
		if !idx.Solid || e.Cost != 2048 {
			t.Fatalf("%s: %+v", name, e)
		}
		if got := readEntry(t, r, e.Src); len(got) != 2048 {
			t.Fatalf("%s: %d", name, len(got))
		}
	}
	r, idx := index(t, fixture(t, "rar5-subdirs.rar"), "rar5-subdirs.rar")
	if string(readEntry(t, r, entry(t, idx, "sub/dir1/file1.txt").Src)) == "" || !entry(t, idx, "sub/empty").IsDir {
		t.Fatal("subdirs")
	}
	_, idx = index(t, fixture(t, "rar5-psw.rar"), "rar5-psw.rar")
	if !idx.Encrypted || entry(t, idx, "stest1.txt").Flag != FlagEncrypted {
		t.Fatalf("psw: %+v", idx)
	}
	hp := fixture(t, "rar5-hpsw.rar")
	r, err := Open(readerAt(hp), int64(len(hp)), Rar)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.Index(); !errors.Is(err, ErrEncrypted) {
		t.Fatalf("hpsw: %v", err)
	}
	_, idx = index(t, fixture(t, "rar5-evil-symlink-traversal.rar"), "evil.rar")
	if entry(t, idx, "up").Flag != FlagLink && !entry(t, idx, "up").IsDir {
		t.Fatal("evil symlink")
	}
	_, idx = index(t, fixture(t, "unicode.rar"), "unicode.rar")
	entry(t, idx, "уииоотивл.txt")
}

func TestMaxEntries(t *testing.T) {
	old := MaxEntries
	MaxEntries = 3
	defer func() { MaxEntries = old }()
	_, idx := index(t, buildZip(t), "x.zip")
	if !idx.Truncated {
		t.Fatal("超过上限应记 truncated")
	}
	native := 0
	for _, e := range idx.Entries {
		if e.Src >= 0 {
			native++
		}
	}
	if native != 3 {
		t.Fatalf("应只留 3 条原生条目:%d", native)
	}
}

func TestDictLimits(t *testing.T) {
	// LZMA2 属性 → 字典大小
	for p, want := range map[byte]int64{0: 4 << 10, 18: 2 << 20, 24: 16 << 20, 28: 64 << 20, 29: 96 << 20, 40: 4<<30 - 1} {
		if p == 40 {
			continue // 特殊值,不测
		}
		if got, _ := lzma2Dict([]byte{p}); got != want {
			t.Errorf("lzma2Dict(%d)=%d,应为 %d", p, got, want)
		}
	}
	src := io.NopCloser(bytes.NewReader(nil))
	if _, err := newLZMA2([]byte{30}, 1<<30, []io.ReadCloser{src}); !errors.Is(err, ErrDictTooLarge) {
		t.Fatalf("128 MB 字典应拒:%v", err)
	}
	// 解压后只有 1 MB:字典用不满,照常解
	if _, err := newLZMA2([]byte{30}, 1<<20, []io.ReadCloser{src}); err != nil {
		t.Fatalf("小文件不该拒:%v", err)
	}
	if _, err := newLZMA([]byte{0x5d, 0, 0, 0, 0x10}, 1<<30, []io.ReadCloser{src}); !errors.Is(err, ErrDictTooLarge) {
		t.Fatalf("LZMA 256 MB 字典应拒:%v", err)
	}
	if _, err := newPPMD([]byte{6, 0, 0, 0, 0x10}, 1<<20, []io.ReadCloser{src}); !errors.Is(err, ErrDictTooLarge) {
		t.Fatalf("PPMD 256 MB 内存应拒:%v", err)
	}
}

// countingRA 数底层 ReadAt 次数。
type countingRA struct {
	ra    io.ReaderAt
	calls int
	bytes int64
}

func (c *countingRA) ReadAt(p []byte, off int64) (int, error) {
	c.calls++
	n, err := c.ra.ReadAt(p, off)
	c.bytes += int64(n)
	return n, err
}

func TestChunkReader(t *testing.T) {
	data := bytes.Repeat([]byte("abcdefghij"), 1<<20) // 10 MB
	cra := &countingRA{ra: bytes.NewReader(data)}
	c := NewChunkReader(cra, int64(len(data)))
	// 4 KB 一次顺序读完:块逐步加大到 1 MB,调用次数远少于 2560
	got, err := io.ReadAll(io.NewSectionReader(c, 0, c.Size()))
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("顺序读内容不对")
	}
	if cra.calls > 20 {
		t.Fatalf("顺序读调了 %d 次底层", cra.calls)
	}
	// 跳着读小块:每次只拉 64 KB
	cra.calls, cra.bytes = 0, 0
	buf := make([]byte, 512)
	for off := int64(0); off < int64(len(data)); off += 1 << 20 {
		if _, err := c.ReadAt(buf, off); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(buf, data[off:off+512]) {
			t.Fatal("跳读内容不对")
		}
	}
	if cra.bytes > 10*(64<<10) {
		t.Fatalf("跳读拉了 %d 字节", cra.bytes)
	}
	// 读到末尾
	tail := make([]byte, 100)
	n, err := c.ReadAt(tail, int64(len(data))-10)
	if n != 10 || err != io.EOF {
		t.Fatalf("末尾:%d %v", n, err)
	}
	// 来源出错记在 Err 里
	bad := NewChunkReader(errRA{}, 100)
	if _, err := bad.ReadAt(buf[:10], 0); err == nil || bad.Err == nil {
		t.Fatal("来源错误应记下")
	}
}

type errRA struct{}

func (errRA) ReadAt([]byte, int64) (int, error) { return 0, errors.New("storage down") }
