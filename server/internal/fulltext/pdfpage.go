package fulltext

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// PDFFirstPage 调 pdftoppm 把 PDF 的第一页渲染成 JPEG,长边不超过 maxPx(助手看扫描件,doc/35)。
// 和 PDFText 一样限时、有 sh 时用 ulimit -v 限内存。
func PDFFirstPage(ctx context.Context, bin, file string, maxPx int) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, PDFTimeout)
	defer cancel()
	dir, err := os.MkdirTemp("", "gopan-pdfpage-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	out := filepath.Join(dir, "page")
	args := []string{"-jpeg", "-jpegopt", "quality=80", "-f", "1", "-l", "1", "-singlefile", "-scale-to", strconv.Itoa(maxPx), file, out}
	var cmd *exec.Cmd
	if sh, e := exec.LookPath("sh"); e == nil && runtime.GOOS != "windows" {
		cmd = exec.CommandContext(ctx, sh, append([]string{"-c", fmt.Sprintf(`ulimit -v %d && exec "$0" "$@"`, pdfMemKiB), bin}, args...)...)
	} else {
		cmd = exec.CommandContext(ctx, bin, args...)
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return nil, fmt.Errorf("pdftoppm 超时(%s)", PDFTimeout)
		}
		return nil, fmt.Errorf("pdftoppm: %w: %s", err, strings.TrimSpace(lastLine(stderr.String())))
	}
	return os.ReadFile(out + ".jpg")
}
