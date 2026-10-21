// Package archive 读压缩包(doc/21 四):zip、7z、rar、tar 与 .tar.gz / .tar.bz2 / .tar.xz。
// 列目录、取单个条目、按顺序遍历给解压用。全部纯 Go,不落本地盘;字节来自 io.ReaderAt
// (生产上是 MinIO 对象,经 chunkReader 合并成块读)。不碰数据库。
package archive

import (
	"bytes"
	"errors"
	"path"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/yophon/gopan/server/internal/fulltext"
)

// Version 索引器版本:读取逻辑变了就加一,存量会逐步重建。
const Version = 1

// MaxEntries 超过只存前这么多条,记 truncated(测试里调小)。
var MaxEntries = maxEntriesDefault

// 上限(doc/21 四.2、五)。
const (
	maxEntriesDefault = 100_000 // 超过只存前这么多条,记 truncated
	// MaxDict 7z / rar / xz 的解码字典上限:解码内存约等于字典大小,精简档容器只有 384 MB
	MaxDict = 64 << 20
	// MaxStreamScan 列目录时最多顺序解多少字节(压缩的 tar、固实 rar 要整包解一遍),超过记 truncated
	MaxStreamScan = 4 << 30
)

type Format string

const (
	Zip      Format = "zip"
	SevenZip Format = "7z"
	Rar      Format = "rar"
	Tar      Format = "tar"
	TarGz    Format = "tar.gz"
	TarBz2   Format = "tar.bz2"
	TarXz    Format = "tar.xz"
)

var (
	ErrUnsupported  = errors.New("archive: unsupported format or method")
	ErrEncrypted    = errors.New("archive: encrypted")
	ErrDictTooLarge = errors.New("archive: dictionary too large")
	ErrTooLong      = errors.New("archive: entry longer than declared")
	ErrNoEntry      = errors.New("archive: no such entry")
)

// 条目标记:非空的不能预览、不能解压。
const (
	FlagEncrypted   = "encrypted"   // 内容加密
	FlagUnsafe      = "unsafe"      // 路径带 ..
	FlagLink        = "link"        // 软链接、硬链接、设备文件
	FlagUnsupported = "unsupported" // 压缩方法不支持,或字典太大
)

// Entry 包里的一个条目。Src 是读取库里的原生序号,补出来的目录为 -1。
type Entry struct {
	Src    int
	Path   string // "/" 分隔,不以 "/" 开头
	Parent string // 根目录为 ""
	Name   string
	IsDir  bool
	Size   int64
	Packed int64
	MTime  time.Time
	Flag   string
	// Cost 读到这个条目之前要先解掉的字节数(固实包、压缩 tar);随机可读的格式为 0
	Cost int64
}

// Index 一个压缩包的目录。
type Index struct {
	Format    Format
	Entries   []Entry
	Solid     bool
	Encrypted bool
	Truncated bool
	Files     int
	TotalSize int64
}

var partRe = regexp.MustCompile(`\.part0*([0-9]+)\.rar$`)

// ByName 按文件名判断格式;不是压缩包返回 ""。分卷 rar 只认第一卷(其余卷单独打不开)。
func ByName(name string) Format {
	n := strings.ToLower(name)
	switch {
	case strings.HasSuffix(n, ".tar.gz"), strings.HasSuffix(n, ".tgz"):
		return TarGz
	case strings.HasSuffix(n, ".tar.bz2"), strings.HasSuffix(n, ".tbz2"), strings.HasSuffix(n, ".tbz"):
		return TarBz2
	case strings.HasSuffix(n, ".tar.xz"), strings.HasSuffix(n, ".txz"):
		return TarXz
	case strings.HasSuffix(n, ".tar"):
		return Tar
	case strings.HasSuffix(n, ".zip"):
		return Zip
	case strings.HasSuffix(n, ".7z"):
		return SevenZip
	case strings.HasSuffix(n, ".rar"):
		if m := partRe.FindStringSubmatch(n); m != nil && m[1] != "1" {
			return ""
		}
		return Rar
	}
	return ""
}

// BaseName 去掉压缩包扩展名(解压目标文件夹的名字)。
func BaseName(name string) string {
	n := strings.ToLower(name)
	for _, ext := range []string{".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".tar", ".zip", ".7z", ".rar"} {
		if strings.HasSuffix(n, ext) {
			base := name[:len(name)-len(ext)]
			if m := partRe.FindStringIndex(strings.ToLower(name)); m != nil && ext == ".rar" {
				base = name[:m[0]]
			}
			if base = strings.TrimSpace(base); base != "" {
				return base
			}
		}
	}
	return name
}

var (
	magicZip   = []byte("PK\x03\x04")
	magicZipE  = []byte("PK\x05\x06") // 空 zip
	magic7z    = []byte{'7', 'z', 0xBC, 0xAF, 0x27, 0x1C}
	magicRar   = []byte("Rar!\x1A\x07")
	magicGzip  = []byte{0x1F, 0x8B}
	magicBzip2 = []byte("BZh")
	magicXz    = []byte{0xFD, '7', 'z', 'X', 'Z', 0x00}
)

// Detect 按文件头判断格式(名字说是 .zip、实际是 7z 的也认得);认不出返回 ""。
// gzip / bzip2 / xz 只能说明是压缩流,这里一律当 tar,读不出 tar 头时由 Build 报错。
func Detect(head []byte) Format {
	switch {
	case bytes.HasPrefix(head, magicZip), bytes.HasPrefix(head, magicZipE):
		return Zip
	case bytes.HasPrefix(head, magic7z):
		return SevenZip
	case bytes.HasPrefix(head, magicRar):
		return Rar
	case bytes.HasPrefix(head, magicXz):
		return TarXz
	case bytes.HasPrefix(head, magicGzip):
		return TarGz
	case bytes.HasPrefix(head, magicBzip2):
		return TarBz2
	case len(head) >= 262 && string(head[257:262]) == "ustar":
		return Tar
	}
	return ""
}

// Resolve 名字与文件头一起定格式:文件头认得出就以它为准;认不出而名字说是 tar 的(老式 tar 没有魔数)照名字来。
func Resolve(head []byte, name string) Format {
	if f := Detect(head); f != "" {
		return f
	}
	if ByName(name) == Tar {
		return Tar
	}
	return ""
}

// cleanPath 统一成 "/" 分隔,去掉开头的 "/"、盘符和 "."、控制字符;
// 含 ".." 的标记为不安全(能看不能解),显示路径里去掉 ".."。
func cleanPath(raw string) (string, bool) {
	s := strings.ReplaceAll(fulltext.DecodeName([]byte(raw)), "\\", "/")
	unsafe := false
	parts := strings.Split(s, "/")
	out := make([]string, 0, len(parts))
	for i, part := range parts {
		part = strings.Map(func(r rune) rune {
			if unicode.IsControl(r) || r == utf8.RuneError {
				return -1
			}
			return r
		}, part)
		switch {
		case part == "" || part == ".":
			continue
		case part == "..":
			unsafe = true
			continue
		case i == 0 && len(part) == 2 && part[1] == ':' && unicode.IsLetter(rune(part[0])):
			continue // 盘符
		}
		if len(part) > 255 {
			part = truncateUTF8(part, 255)
		}
		out = append(out, part)
	}
	return strings.Join(out, "/"), unsafe
}

func truncateUTF8(s string, n int) string {
	s = s[:n]
	for !utf8.ValidString(s) {
		s = s[:len(s)-1]
	}
	return s
}

// builder 收集原生条目,最后补出缺的目录。
type builder struct {
	idx   Index
	paths map[string]bool // 已有的目录路径
}

func newBuilder(f Format) *builder {
	return &builder{idx: Index{Format: f}, paths: map[string]bool{}}
}

// add 加一个原生条目;返回 false 表示条目数到上限了。
func (b *builder) add(src int, raw string, isDir bool, size, packed int64, mtime time.Time, flag string, cost int64) bool {
	if len(b.idx.Entries) >= MaxEntries {
		b.idx.Truncated = true
		return false
	}
	p, unsafe := cleanPath(raw)
	if unsafe && flag == "" {
		flag = FlagUnsafe
	}
	if p == "" {
		if isDir {
			return true // "./" 之类
		}
		p, flag = "(无名文件)", FlagUnsafe
	}
	if isDir {
		if b.paths[p] {
			return true
		}
		b.paths[p] = true
		size = 0
	} else {
		b.idx.Files++
		b.idx.TotalSize += size
	}
	if flag == FlagEncrypted {
		b.idx.Encrypted = true
	}
	parent, name := splitPath(p)
	b.idx.Entries = append(b.idx.Entries, Entry{
		Src: src, Path: p, Parent: parent, Name: name, IsDir: isDir, Size: size, Packed: packed,
		MTime: mtime, Flag: flag, Cost: cost,
	})
	return true
}

func (b *builder) finish() *Index {
	n := len(b.idx.Entries)
	for i := 0; i < n; i++ {
		for dir := b.idx.Entries[i].Parent; dir != "" && !b.paths[dir]; dir, _ = splitPath(dir) {
			b.paths[dir] = true
			parent, name := splitPath(dir)
			b.idx.Entries = append(b.idx.Entries, Entry{Src: -1, Path: dir, Parent: parent, Name: name, IsDir: true})
		}
	}
	return &b.idx
}

func splitPath(p string) (string, string) {
	dir, name := path.Split(p)
	return strings.TrimSuffix(dir, "/"), name
}
