package fulltext

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

// PDFTimeout 单个 PDF 的提取时限(远小于 worker 认定任务滞留的 5 分钟)
const PDFTimeout = 60 * time.Second

// pdfMemKiB pdftotext 的虚拟内存上限:恶意 PDF 只会让这一个子进程失败,
// 不会把整个 gopan 容器(精简档 384MB)拖进 OOM
const pdfMemKiB = 512 << 10

// PDFText 调 pdftotext 把 PDF 转成文字,按换页符拆成页。输出超过 MaxRawText 就停,truncated 为真。
// 有 sh 时用 ulimit -v 限内存(容器里有;Windows 本机开发直接跑)。
func PDFText(ctx context.Context, bin, file string) (pages []string, truncated bool, err error) {
	ctx, cancel := context.WithTimeout(ctx, PDFTimeout)
	defer cancel()
	args := []string{"-enc", "UTF-8", "-q", file, "-"}
	var cmd *exec.Cmd
	if sh, e := exec.LookPath("sh"); e == nil && runtime.GOOS != "windows" {
		cmd = exec.CommandContext(ctx, sh, append([]string{"-c", fmt.Sprintf(`ulimit -v %d && exec "$0" "$@"`, pdfMemKiB), bin}, args...)...)
	} else {
		cmd = exec.CommandContext(ctx, bin, args...)
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, false, err
	}
	if err := cmd.Start(); err != nil {
		return nil, false, err
	}
	out, readErr := io.ReadAll(io.LimitReader(stdout, MaxRawText+1))
	if len(out) > MaxRawText {
		truncated = true
		out = out[:MaxRawText]
		_ = cmd.Process.Kill() // 后面的不要了
	}
	waitErr := cmd.Wait()
	if readErr != nil {
		return nil, false, readErr
	}
	if waitErr != nil && !truncated {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return nil, false, fmt.Errorf("pdftotext 超时(%s)", PDFTimeout)
		}
		return nil, false, fmt.Errorf("pdftotext: %w: %s", waitErr, strings.TrimSpace(lastLine(stderr.String())))
	}
	text, _ := DecodeText(out)
	pages = strings.Split(text, "\f")
	// pdftotext 在每页末尾都写一个换页符,最后一段是空的
	if n := len(pages); n > 1 && strings.TrimSpace(pages[n-1]) == "" {
		pages = pages[:n-1]
	}
	return pages, truncated, nil
}

func lastLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.LastIndexByte(s, '\n'); i >= 0 {
		return s[i+1:]
	}
	return s
}
