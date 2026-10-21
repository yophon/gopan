package archive

// 7z 的几种解码器按方法号重新注册,在建解码器之前先看属性里的字典 / 内存大小:
// 库自带的实现照字典大小直接分配,一个声明 1.5 GB 字典的包就能把精简档容器撑爆(doc/21 四.4)。
// 适配代码改写自 github.com/bodgit/sevenzip/internal(BSD-3-Clause, Copyright (c) 2020 Matt Dainty)。

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"github.com/bodgit/sevenzip"
	"github.com/klauspost/compress/zstd"
	"github.com/stangelandcl/ppmd"
	"github.com/ulikunitz/xz/lzma"
)

func init() {
	sevenzip.RegisterDecompressor([]byte{0x03, 0x01, 0x01}, sevenzip.Decompressor(newLZMA))
	sevenzip.RegisterDecompressor([]byte{0x21}, sevenzip.Decompressor(newLZMA2))
	sevenzip.RegisterDecompressor([]byte{0x03, 0x04, 0x01}, sevenzip.Decompressor(newPPMD))
	sevenzip.RegisterDecompressor([]byte{0x04, 0xf7, 0x11, 0x01}, sevenzip.Decompressor(newZstd))
}

var errOneReader = errors.New("7z: need exactly one reader")

type rc struct {
	io.Reader
	c io.Closer
}

func (r *rc) Close() error { return r.c.Close() }

// lzmaDict LZMA 属性:1 字节 lc/lp/pb + 4 字节字典大小(小端)。
func lzmaDict(p []byte) (int64, error) {
	if len(p) != 5 {
		return 0, fmt.Errorf("7z lzma: bad properties")
	}
	return int64(binary.LittleEndian.Uint32(p[1:])), nil
}

// lzma2Dict LZMA2 属性:1 字节,公式来自 Lzma2Dec.c。
func lzma2Dict(p []byte) (int64, error) {
	if len(p) != 1 || p[0] > 40 {
		return 0, fmt.Errorf("7z lzma2: bad properties")
	}
	return int64(2|(p[0]&1)) << (p[0]/2 + 11), nil
}

func newLZMA(p []byte, size uint64, readers []io.ReadCloser) (io.ReadCloser, error) {
	if len(readers) != 1 {
		return nil, errOneReader
	}
	dict, err := lzmaDict(p)
	if err != nil {
		return nil, err
	}
	// 解压后比字典还小的,只需要那么大的字典(库里同样按这个分配)
	if effectiveDict(dict, size) > MaxDict {
		return nil, ErrDictTooLarge
	}
	// 拼成 .lzma 文件头:属性 + 8 字节解压后大小
	h := bytes.NewBuffer(append([]byte{}, p...))
	_ = binary.Write(h, binary.LittleEndian, size)
	lr, err := lzma.ReaderConfig{DictCap: MaxDict}.NewReader(io.MultiReader(h, readers[0]))
	if err != nil {
		return nil, fmt.Errorf("7z lzma: %w", err)
	}
	return &rc{Reader: lr, c: readers[0]}, nil
}

func newLZMA2(p []byte, size uint64, readers []io.ReadCloser) (io.ReadCloser, error) {
	if len(readers) != 1 {
		return nil, errOneReader
	}
	dict, err := lzma2Dict(p)
	if err != nil {
		return nil, err
	}
	dict = effectiveDict(dict, size)
	if dict > MaxDict {
		return nil, ErrDictTooLarge
	}
	cfg := lzma.Reader2Config{DictCap: int(max(dict, lzma.MinDictCap))}
	if err := cfg.Verify(); err != nil {
		return nil, fmt.Errorf("7z lzma2: %w", err)
	}
	lr, err := cfg.NewReader2(readers[0])
	if err != nil {
		return nil, fmt.Errorf("7z lzma2: %w", err)
	}
	return &rc{Reader: lr, c: readers[0]}, nil
}

// effectiveDict 解压后的大小比声明的字典还小时,字典用不满。
func effectiveDict(dict int64, size uint64) int64 {
	if size > 0 && size < uint64(dict) {
		return int64(size)
	}
	return dict
}

func newPPMD(p []byte, size uint64, readers []io.ReadCloser) (io.ReadCloser, error) {
	if len(readers) != 1 {
		return nil, errOneReader
	}
	if len(p) != 5 {
		return nil, fmt.Errorf("7z ppmd: bad properties")
	}
	order, mem := p[0], binary.LittleEndian.Uint32(p[1:])
	if int64(mem) > MaxDict {
		return nil, ErrDictTooLarge
	}
	pr, err := ppmd.NewH7zReader(readers[0], int(order), int(mem), int(size))
	if err != nil {
		return nil, fmt.Errorf("7z ppmd: %w", err)
	}
	return &rc{Reader: &pr, c: readers[0]}, nil
}

type zstdRC struct {
	d *zstd.Decoder
	c io.Closer
}

func (z *zstdRC) Read(p []byte) (int, error) { return z.d.Read(p) }
func (z *zstdRC) Close() error {
	z.d.Close()
	return z.c.Close()
}

func newZstd(_ []byte, _ uint64, readers []io.ReadCloser) (io.ReadCloser, error) {
	if len(readers) != 1 {
		return nil, errOneReader
	}
	d, err := zstd.NewReader(readers[0], zstd.WithDecoderMaxWindow(MaxDict), zstd.WithDecoderConcurrency(1), zstd.WithDecoderLowmem(true))
	if err != nil {
		return nil, fmt.Errorf("7z zstd: %w", err)
	}
	return &zstdRC{d: d, c: readers[0]}, nil
}
