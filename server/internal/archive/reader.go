package archive

import (
	"archive/tar"
	"archive/zip"
	"bufio"
	"compress/bzip2"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"sort"
	"strings"
	"time"

	"github.com/bodgit/sevenzip"
	"github.com/nwaples/rardecode/v2"
	"github.com/ulikunitz/xz"
)

func init() {
	zip.RegisterDecompressor(12, func(r io.Reader) io.ReadCloser { return io.NopCloser(bzip2.NewReader(r)) })
}

// Reader 打开的一个压缩包。zip 与 7z 随机可读;rar 与 tar 每次取条目都从头顺序读(rar、未压缩的 tar 靠 Seek 跳过数据区)。
type Reader struct {
	f    Format
	ra   io.ReaderAt
	size int64
	zr   *zip.Reader
	sz   *sevenzip.Reader
}

// Open 读包头。格式不对、包损坏返回错误;7z 文件头加密返回 ErrEncrypted。
func Open(ra io.ReaderAt, size int64, f Format) (*Reader, error) {
	r := &Reader{f: f, ra: ra, size: size}
	switch f {
	case Zip:
		zr, err := zip.NewReader(ra, size)
		if err != nil {
			return nil, fmt.Errorf("zip: %w", err)
		}
		r.zr = zr
	case SevenZip:
		sz, err := sevenzip.NewReader(ra, size)
		if err != nil {
			var re *sevenzip.ReadError
			if errors.As(err, &re) && re.Encrypted {
				return nil, ErrEncrypted
			}
			return nil, fmt.Errorf("7z: %w", err)
		}
		r.sz = sz
	case Rar, Tar, TarGz, TarBz2, TarXz:
	default:
		return nil, ErrUnsupported
	}
	return r, nil
}

func (r *Reader) Format() Format { return r.f }

// ---- 建索引 ----

// Index 列出全部条目(含补出来的目录)。
func (r *Reader) Index() (*Index, error) {
	b := newBuilder(r.f)
	var err error
	switch r.f {
	case Zip:
		r.indexZip(b)
	case SevenZip:
		r.index7z(b)
	case Rar:
		err = r.indexRar(b)
	default:
		err = r.indexTar(b)
	}
	if err != nil {
		return nil, err
	}
	return b.finish(), nil
}

func (r *Reader) indexZip(b *builder) {
	for i, f := range r.zr.File {
		flag := ""
		switch {
		case f.Flags&0x1 != 0:
			flag = FlagEncrypted
		case f.Mode()&(fs.ModeSymlink|fs.ModeDevice|fs.ModeNamedPipe|fs.ModeSocket) != 0:
			flag = FlagLink
		case f.Method != zip.Store && f.Method != zip.Deflate && f.Method != 12:
			flag = FlagUnsupported // deflate64、lzma、zstd、AES 等
		}
		isDir := strings.HasSuffix(f.Name, "/") || f.Mode().IsDir()
		if !b.add(i, f.Name, isDir, int64(f.UncompressedSize64), int64(f.CompressedSize64), f.Modified, flag, 0) {
			return
		}
	}
}

func (r *Reader) index7z(b *builder) {
	solidPos := map[int]int64{} // stream → 已经累计的解压字节
	streamFiles := map[int]int{}
	probed := map[int]string{} // stream → 打开探测的结果标记
	for i, f := range r.sz.File {
		info := f.FileInfo()
		isDir := info.IsDir()
		size := int64(f.UncompressedSize)
		flag := ""
		if f.Mode()&fs.ModeSymlink != 0 {
			flag = FlagLink
		}
		cost := int64(0)
		if !isDir && size > 0 {
			cost = solidPos[f.Stream]
			solidPos[f.Stream] += size
			streamFiles[f.Stream]++
			// 每个流打开一次探测:加密、字典太大、方法不支持都在建解码器时报出来(不解数据)
			if _, ok := probed[f.Stream]; !ok && len(probed) < 64 {
				probed[f.Stream] = probe7z(f)
			}
			if pf := probed[f.Stream]; pf != "" && flag == "" {
				flag = pf
			}
		}
		if !b.add(i, f.Name, isDir, size, 0, f.Modified, flag, cost) {
			return
		}
	}
	for _, n := range streamFiles {
		if n > 1 {
			b.idx.Solid = true
		}
	}
}

func probe7z(f *sevenzip.File) string {
	rc, err := f.Open()
	if err != nil {
		var re *sevenzip.ReadError
		switch {
		case errors.Is(err, ErrDictTooLarge):
			return FlagUnsupported
		case errors.As(err, &re) && re.Encrypted:
			return FlagEncrypted
		}
		return FlagUnsupported
	}
	rc.Close()
	return ""
}

func (r *Reader) rarReader() (*rardecode.Reader, error) {
	// SectionReader 实现了 Seek:非固实的条目,rardecode 直接跳过数据区
	return rardecode.NewReader(io.NewSectionReader(r.ra, 0, r.size), rardecode.MaxDictionarySize(MaxDict))
}

func (r *Reader) indexRar(b *builder) error {
	rr, err := r.rarReader()
	if err != nil {
		return rarErr(err)
	}
	var solidPos, scanned int64
	for i := 0; ; i++ {
		h, err := rr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			if i > 0 && (errors.Is(err, rardecode.ErrDictionaryTooLarge) || errors.Is(err, rardecode.ErrArchivedFileEncrypted)) {
				b.idx.Truncated = true // 固实包里前一个文件解不了,后面的也列不出来
				return nil
			}
			return rarErr(err)
		}
		cost := int64(0)
		if h.Solid {
			cost = solidPos
			b.idx.Solid = true
			scanned += h.UnPackedSize
		} else {
			solidPos = 0
		}
		flag := ""
		switch {
		case h.Encrypted:
			flag = FlagEncrypted
		case h.LinkType != 0 || h.Mode()&(fs.ModeSymlink|fs.ModeDevice|fs.ModeNamedPipe) != 0:
			flag = FlagLink
		case h.UnKnownSize:
			flag = FlagUnsupported
		}
		if !b.add(i, h.Name, h.IsDir, h.UnPackedSize, h.PackedSize, h.ModificationTime, flag, cost) {
			return nil
		}
		if !h.IsDir {
			solidPos += h.UnPackedSize
		}
		if scanned > MaxStreamScan {
			b.idx.Truncated = true
			return nil
		}
	}
}

func rarErr(err error) error {
	switch {
	case errors.Is(err, rardecode.ErrArchiveEncrypted), errors.Is(err, rardecode.ErrArchivedFileEncrypted):
		return ErrEncrypted
	case errors.Is(err, rardecode.ErrDictionaryTooLarge):
		return ErrDictTooLarge
	case errors.Is(err, rardecode.ErrMultiVolume), errors.Is(err, rardecode.ErrUnsupportedDecoder),
		errors.Is(err, rardecode.ErrUnknownDecoder), errors.Is(err, rardecode.ErrUnknownEncryptMethod):
		return fmt.Errorf("%w: %v", ErrUnsupported, err)
	}
	return fmt.Errorf("rar: %w", err)
}

// countReader 记读过的字节(压缩 tar 的条目开销按解压流里的位置算)。
type countReader struct {
	r io.Reader
	n int64
}

func (c *countReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n += int64(n)
	return n, err
}

// tarStream 打开 tar 的解压流。未压缩的 tar 直接给 SectionReader(tar.Reader 用 Seek 跳过数据区)。
func (r *Reader) tarStream() (io.Reader, func(), error) {
	sec := io.NewSectionReader(r.ra, 0, r.size)
	if r.f == Tar {
		return sec, func() {}, nil
	}
	br := bufio.NewReaderSize(sec, 256<<10)
	switch r.f {
	case TarGz:
		zr, err := gzip.NewReader(br)
		if err != nil {
			return nil, nil, fmt.Errorf("gzip: %w", err)
		}
		return zr, func() { zr.Close() }, nil
	case TarBz2:
		return bzip2.NewReader(br), func() {}, nil
	case TarXz:
		xr, err := xz.ReaderConfig{DictCap: MaxDict}.NewReader(br)
		if err != nil {
			return nil, nil, fmt.Errorf("xz: %w", err)
		}
		return xr, func() {}, nil
	}
	return nil, nil, ErrUnsupported
}

func (r *Reader) indexTar(b *builder) error {
	s, done, err := r.tarStream()
	if err != nil {
		return err
	}
	defer done()
	cr := &countReader{r: s}
	tr := tar.NewReader(cr)
	compressed := r.f != Tar
	for i := 0; ; i++ {
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			if i == 0 {
				return fmt.Errorf("tar: %w", err)
			}
			b.idx.Truncated = true // 读到一半坏了:前面的照样能看
			return nil
		}
		cost := int64(0)
		if compressed {
			cost = cr.n
			b.idx.Solid = true
		}
		flag := ""
		isDir := false
		switch h.Typeflag {
		case tar.TypeReg, tar.TypeRegA, tar.TypeGNUSparse:
		case tar.TypeDir:
			isDir = true
		default:
			flag = FlagLink // 软链接、硬链接、设备、fifo
		}
		if !b.add(i, h.Name, isDir, h.Size, h.Size, h.ModTime, flag, cost) {
			return nil
		}
		if compressed && cr.n > MaxStreamScan {
			b.idx.Truncated = true
			return nil
		}
	}
}

// ---- 取条目 ----

// OpenEntry 打开一个原生条目的内容,长度不符(多了或少了)读时报错。
// 固实包、压缩 tar 要先解掉前面的数据,开销由调用方按索引里的 Cost 事先判断。
func (r *Reader) OpenEntry(src int) (io.ReadCloser, error) {
	switch r.f {
	case Zip:
		if src < 0 || src >= len(r.zr.File) {
			return nil, ErrNoEntry
		}
		f := r.zr.File[src]
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		return exact(rc, int64(f.UncompressedSize64)), nil
	case SevenZip:
		if src < 0 || src >= len(r.sz.File) {
			return nil, ErrNoEntry
		}
		f := r.sz.File[src]
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		return exact(rc, int64(f.UncompressedSize)), nil
	}
	var found io.ReadCloser
	err := r.walkSequential(func(i int, size int64, rd io.Reader) (bool, error) {
		if i != src {
			return true, nil
		}
		found = exact(io.NopCloser(rd), size)
		return false, nil
	}, true)
	if err != nil {
		return nil, err
	}
	if found == nil {
		return nil, ErrNoEntry
	}
	return found, nil
}

// Walk 按包内顺序遍历原生条目(解压用):fn 拿到条目序号与内容(目录、链接为 nil),
// 返回 false 提前结束。zip 按数据偏移排序,读起来是顺序的。
func (r *Reader) Walk(fn func(src int, rd io.Reader) (bool, error)) error {
	switch r.f {
	case Zip:
		order := make([]int, len(r.zr.File))
		for i := range order {
			order[i] = i
		}
		offs := make([]int64, len(r.zr.File))
		for i, f := range r.zr.File {
			offs[i], _ = f.DataOffset()
		}
		sort.SliceStable(order, func(a, b int) bool { return offs[order[a]] < offs[order[b]] })
		for _, i := range order {
			if err := r.walkOne(i, fn); err != nil {
				if errors.Is(err, errStop) {
					return nil
				}
				return err
			}
		}
		return nil
	case SevenZip:
		for i, f := range r.sz.File {
			rc, err := r.OpenEntry(i)
			if err != nil {
				return fmt.Errorf("条目 %d: %w", i, err)
			}
			more, err := fn(i, rc)
			// 固实流:下一个文件在同一个流里时,把这个没读完的部分读掉,
			// 库会接着用这个解码器(否则下一个文件要从流头重新解一遍)
			if err == nil && more && i+1 < len(r.sz.File) && r.sz.File[i+1].Stream == f.Stream {
				_, err = io.Copy(io.Discard, rc)
			}
			rc.Close()
			if err != nil || !more {
				return err
			}
		}
		return nil
	}
	return r.walkSequential(func(i int, size int64, rd io.Reader) (bool, error) {
		if rd == nil {
			return fn(i, nil)
		}
		return fn(i, exact(io.NopCloser(rd), size))
	}, false)
}

var errStop = errors.New("stop")

func (r *Reader) walkOne(i int, fn func(int, io.Reader) (bool, error)) error {
	rc, err := r.OpenEntry(i)
	if err != nil {
		return fmt.Errorf("条目 %d: %w", i, err)
	}
	defer rc.Close()
	more, err := fn(i, rc)
	if err != nil {
		return err
	}
	if !more {
		return errStop
	}
	return nil
}

// walkSequential rar / tar:从头读,每个条目回调一次。filesOnly 时目录与链接不回调内容。
func (r *Reader) walkSequential(fn func(i int, size int64, rd io.Reader) (bool, error), filesOnly bool) error {
	if r.f == Rar {
		rr, err := r.rarReader()
		if err != nil {
			return rarErr(err)
		}
		for i := 0; ; i++ {
			h, err := rr.Next()
			if err == io.EOF {
				return nil
			}
			if err != nil {
				return rarErr(err)
			}
			var rd io.Reader = rr
			if h.IsDir || h.LinkType != 0 {
				rd = nil
			}
			if rd == nil && filesOnly {
				continue
			}
			more, err := fn(i, h.UnPackedSize, rd)
			if err != nil || !more {
				return err
			}
		}
	}
	s, done, err := r.tarStream()
	if err != nil {
		return err
	}
	defer done()
	tr := tar.NewReader(s)
	for i := 0; ; i++ {
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("tar: %w", err)
		}
		var rd io.Reader = tr
		if h.Typeflag != tar.TypeReg && h.Typeflag != tar.TypeRegA && h.Typeflag != tar.TypeGNUSparse {
			rd = nil
		}
		if rd == nil && filesOnly {
			continue
		}
		more, err := fn(i, h.Size, rd)
		if err != nil || !more {
			return err
		}
	}
}

// exact 条目内容恰好是声明的长度:多出来的算炸弹 / 损坏(ErrTooLong)。
func exact(rc io.ReadCloser, size int64) io.ReadCloser {
	return &exactReader{rc: rc, left: size}
}

type exactReader struct {
	rc   io.ReadCloser
	left int64
}

func (e *exactReader) Read(p []byte) (int, error) {
	if e.left <= 0 {
		// 多读一个字节:还有数据就是比声明的长;zip 的 CRC 也是读到 EOF 时才校验
		var one [1]byte
		n, err := e.rc.Read(one[:])
		if n > 0 {
			return 0, ErrTooLong
		}
		if err != nil && err != io.EOF {
			return 0, err
		}
		return 0, io.EOF
	}
	if int64(len(p)) > e.left {
		p = p[:e.left]
	}
	n, err := e.rc.Read(p)
	e.left -= int64(n)
	if err == io.EOF && e.left > 0 {
		return n, io.ErrUnexpectedEOF
	}
	if err == io.EOF {
		err = nil
	}
	return n, err
}

func (e *exactReader) Close() error { return e.rc.Close() }

// ModTime 帮手:零值与 DOS 纪元(1980-01-01,zip 没记时间时的默认值)不写库。
func ModTime(t time.Time) *time.Time {
	if t.IsZero() || t.Before(time.Date(1980, 1, 2, 0, 0, 0, 0, time.UTC)) {
		return nil
	}
	return &t
}
