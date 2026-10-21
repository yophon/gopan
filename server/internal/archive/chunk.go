package archive

import (
	"io"
	"sync"
)

const (
	minChunk = 64 << 10
	maxChunk = 1 << 20
)

// ChunkReader 把零碎的 ReadAt 合并成块读:minio 的 Object 每次 ReadAt 都是一个新的 GET。
// 顺序读时块逐步加大到 1 MB,跳着读(rar、tar 跳过数据区去读下一个文件头)回到 64 KB,
// 免得为一个 512 字节的头拉 1 MB。来源的读错误记在 Err 里:调用方据此区分
// "包本身坏了"(不重试)和"存储读不到"(稍后重试)。
type ChunkReader struct {
	ra   io.ReaderAt
	size int64

	mu    sync.Mutex
	buf   []byte
	start int64 // buf 在对象里的起点
	next  int64 // 当前块大小
	Err   error
}

func NewChunkReader(ra io.ReaderAt, size int64) *ChunkReader {
	return &ChunkReader{ra: ra, size: size, next: minChunk, start: -1}
}

func (c *ChunkReader) Size() int64 { return c.size }

func (c *ChunkReader) ReadAt(p []byte, off int64) (int, error) {
	if off < 0 {
		return 0, io.ErrUnexpectedEOF
	}
	if off >= c.size {
		return 0, io.EOF
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for n < len(p) && off < c.size {
		if c.start < 0 || off < c.start || off >= c.start+int64(len(c.buf)) {
			if err := c.fill(off); err != nil {
				return n, err
			}
		}
		k := copy(p[n:], c.buf[off-c.start:])
		n += k
		off += int64(k)
	}
	if n < len(p) {
		return n, io.EOF
	}
	return n, nil
}

func (c *ChunkReader) fill(off int64) error {
	if c.start >= 0 && off == c.start+int64(len(c.buf)) {
		c.next = min(c.next*2, maxChunk) // 顺序读
	} else {
		c.next = minChunk
	}
	want := min(c.next, c.size-off)
	if int64(cap(c.buf)) < want {
		c.buf = make([]byte, want, max(want, maxChunk/4))
	}
	c.buf = c.buf[:want]
	got, err := c.ra.ReadAt(c.buf, off)
	if int64(got) < want && err == nil {
		err = io.ErrUnexpectedEOF
	}
	if err != nil && !(err == io.EOF && int64(got) == want) {
		c.start = -1
		c.Err = err
		return err
	}
	c.start = off
	return nil
}
