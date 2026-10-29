// Package airun 在服务器上跑一轮助手(doc/34):起一个 quickjs-ng 的 qjs 进程跑 runtime.js
// (web/src/assistant/qjs-entry.ts 打的包,和网页、App 是同一份运行时),经标准输入输出一行一条 JSON
// 替它发请求、计时、转交事件与确认。
//
// qjs 里什么都不用自己等:网络、等待都由这里做,到点或数据来了往标准输入写一行。
package airun

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed runtime.js
var runtimeJS []byte

// Host 一轮助手要的宿主:发请求、收事件、问人。
type Host interface {
	// Fetch 发一个请求;调用方读完 / 关掉 Body。ctx 结束时请求要跟着结束。
	Fetch(ctx context.Context, req Request) (*http.Response, error)
	// Emit 运行时交出来的事件(items / live / conversation / error),原样的 JSON。
	Emit(event json.RawMessage)
	// Approve 弹确认卡并等答复(once / session / deny);ctx 结束时返回错误。
	Approve(ctx context.Context, view json.RawMessage) (string, error)
}

type Request struct {
	URL     string            `json:"url"`
	Method  string            `json:"method"`
	Headers map[string]string `json:"headers"`
	Body    *string           `json:"body"`
}

type Options struct {
	// QJS 可执行文件路径
	QJS string
	// MemoryKB 进程的内存上限
	MemoryKB int
	// Origin 运行时请求 gopan 用的地址(本机回环),同源请求由 Host 加凭证
	Origin string
	// ConversationID 已经建好的对话
	ConversationID string
	// Args 运行时 send() 的参数(text / context / provider / model / mode)
	Args json.RawMessage
	// MaxActive 一轮最长跑多久,不算等确认的时间;0 = 不限
	MaxActive time.Duration
}

type Result struct {
	// Error 运行时报的错(模型出错、对话打不开……);空 = 正常跑完
	Error string
	// Stopped 被停止(用户点停止、超时)
	Stopped bool
	// Stderr qjs 进程的错误输出(最后一段),排查用
	Stderr string
}

var errNoQJS = errors.New("服务器上没装助手运行环境(qjs)")

// Available qjs 在不在。
func Available(path string) bool {
	st, err := os.Stat(path)
	return err == nil && !st.IsDir()
}

var scriptOnce struct {
	sync.Mutex
	path string
}

// scriptPath runtime.js 写到临时目录,按内容的 sha256 命名:换了版本就是另一个文件。
func scriptPath() (string, error) {
	scriptOnce.Lock()
	defer scriptOnce.Unlock()
	if scriptOnce.path != "" {
		if _, err := os.Stat(scriptOnce.path); err == nil {
			return scriptOnce.path, nil
		}
	}
	sum := sha256.Sum256(runtimeJS)
	p := filepath.Join(os.TempDir(), "gopan-airun-"+hex.EncodeToString(sum[:8])+".js")
	if b, err := os.ReadFile(p); err != nil || !bytes.Equal(b, runtimeJS) {
		tmp := p + ".tmp" + strconv.Itoa(os.Getpid())
		if err := os.WriteFile(tmp, runtimeJS, 0o644); err != nil {
			return "", err
		}
		if err := os.Rename(tmp, p); err != nil {
			_ = os.Remove(tmp)
			return "", err
		}
	}
	scriptOnce.path = p
	return p, nil
}

// msg 双向的一行;各 op 用到的字段不同
type msg struct {
	Op             string            `json:"op"`
	ID             int64             `json:"id,omitempty"`
	MS             int64             `json:"ms,omitempty"`
	URL            string            `json:"url,omitempty"`
	Method         string            `json:"method,omitempty"`
	Headers        map[string]string `json:"headers,omitempty"`
	Body           *string           `json:"body,omitempty"`
	Status         int               `json:"status,omitempty"`
	Data           string            `json:"data,omitempty"`
	Message        string            `json:"message,omitempty"`
	Event          json.RawMessage   `json:"event,omitempty"`
	View           json.RawMessage   `json:"view,omitempty"`
	Answer         string            `json:"answer,omitempty"`
	Error          string            `json:"error,omitempty"`
	Stopped        bool              `json:"stopped,omitempty"`
	Origin         string            `json:"origin,omitempty"`
	ConversationID string            `json:"conversationId,omitempty"`
	Args           json.RawMessage   `json:"args,omitempty"`
}

const chunkSize = 16 << 10

// tailBuffer 只留最后一段(qjs 的错误输出,排查用)
type tailBuffer struct {
	mu  sync.Mutex
	buf []byte
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.buf = append(t.buf, p...)
	if len(t.buf) > 4096 {
		t.buf = t.buf[len(t.buf)-4096:]
	}
	return len(p), nil
}

func (t *tailBuffer) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return string(t.buf)
}

// Run 跑一轮,直到运行时说完了、被停止或 ctx 结束。stop 关闭 = 用户点了停止。
func Run(ctx context.Context, opt Options, host Host, stop <-chan struct{}) (Result, error) {
	if !Available(opt.QJS) {
		return Result{}, errNoQJS
	}
	script, err := scriptPath()
	if err != nil {
		return Result{}, err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	mem := opt.MemoryKB
	if mem <= 0 {
		mem = 128 << 10
	}
	cmd := exec.CommandContext(ctx, opt.QJS, "--std", "--memory-limit", strconv.Itoa(mem), "--stack-size", "1024", script)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return Result{}, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return Result{}, err
	}
	stderr := &tailBuffer{}
	cmd.Stderr = stderr
	cmd.WaitDelay = 5 * time.Second
	if err := cmd.Start(); err != nil {
		return Result{}, fmt.Errorf("起 qjs: %w", err)
	}

	var wmu sync.Mutex
	debug := os.Getenv("GOPAN_AIRUN_DEBUG") != "" // 排查用:进出的每一行打到标准错误
	write := func(m msg) {
		b, _ := json.Marshal(m)
		if debug {
			fmt.Fprintf(os.Stderr, "airun → %.300s\n", b)
		}
		b = append(b, '\n')
		wmu.Lock()
		defer wmu.Unlock()
		_, _ = stdin.Write(b)
	}

	lines := make(chan msg, 64)
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		sc := bufio.NewScanner(stdout)
		sc.Buffer(make([]byte, 64<<10), 8<<20)
		for sc.Scan() {
			if debug {
				fmt.Fprintf(os.Stderr, "airun ← %.300s\n", sc.Bytes())
			}
			var m msg
			if json.Unmarshal(sc.Bytes(), &m) == nil {
				lines <- m
			}
		}
	}()

	var (
		mu       sync.Mutex
		fetches  = map[int64]context.CancelFunc{}
		sleeps   = map[int64]*time.Timer{}
		waiting  int // 正在等确认的个数
		active   time.Duration
		lastTick = time.Now()
	)
	cleanup := func() {
		mu.Lock()
		defer mu.Unlock()
		for _, c := range fetches {
			c()
		}
		for _, t := range sleeps {
			t.Stop()
		}
	}
	defer cleanup()

	stopping := false
	var stopDeadline <-chan time.Time
	requestStop := func() {
		if stopping {
			return
		}
		stopping = true
		write(msg{Op: "stop"})
		stopDeadline = time.After(5 * time.Second)
	}

	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	res := Result{}
	stopCh := stop
	for {
		select {
		case m := <-lines:
			switch m.Op {
			case "ready":
				write(msg{Op: "start", Origin: opt.Origin, ConversationID: opt.ConversationID, Args: opt.Args})
			case "fetch":
				fctx, fcancel := context.WithCancel(ctx)
				mu.Lock()
				fetches[m.ID] = fcancel
				mu.Unlock()
				go func(m msg) {
					defer func() {
						mu.Lock()
						delete(fetches, m.ID)
						mu.Unlock()
						fcancel()
					}()
					resp, err := host.Fetch(fctx, Request{URL: m.URL, Method: m.Method, Headers: m.Headers, Body: m.Body})
					if err != nil {
						write(msg{Op: "error", ID: m.ID, Message: err.Error()})
						return
					}
					defer resp.Body.Close()
					lower := map[string]string{}
					for k := range resp.Header {
						lower[strings.ToLower(k)] = resp.Header.Get(k)
					}
					write(msg{Op: "head", ID: m.ID, Status: resp.StatusCode, Headers: lower})
					buf := make([]byte, chunkSize)
					for {
						n, err := resp.Body.Read(buf)
						if n > 0 {
							write(msg{Op: "chunk", ID: m.ID, Data: base64.StdEncoding.EncodeToString(buf[:n])})
						}
						if err == io.EOF {
							write(msg{Op: "end", ID: m.ID})
							return
						}
						if err != nil {
							write(msg{Op: "error", ID: m.ID, Message: err.Error()})
							return
						}
					}
				}(m)
			case "cancel":
				mu.Lock()
				if c := fetches[m.ID]; c != nil {
					c()
				}
				mu.Unlock()
			case "sleep":
				id := m.ID
				mu.Lock()
				sleeps[id] = time.AfterFunc(time.Duration(m.MS)*time.Millisecond, func() {
					mu.Lock()
					delete(sleeps, id)
					mu.Unlock()
					write(msg{Op: "wake", ID: id})
				})
				mu.Unlock()
			case "unsleep":
				mu.Lock()
				if t := sleeps[m.ID]; t != nil {
					t.Stop()
					delete(sleeps, m.ID)
				}
				mu.Unlock()
			case "emit":
				host.Emit(m.Event)
			case "approve":
				mu.Lock()
				waiting++
				mu.Unlock()
				go func(view json.RawMessage) {
					answer, err := host.Approve(ctx, view)
					mu.Lock()
					waiting--
					mu.Unlock()
					if err != nil {
						answer = "deny"
					}
					write(msg{Op: "answer", Answer: answer})
				}(m.View)
			case "done":
				res.Error, res.Stopped = m.Error, m.Stopped || stopping
				_ = stdin.Close()
				cancel()
				<-readDone
				_ = cmd.Wait()
				res.Stderr = stderr.String()
				return res, nil
			}
		case <-readDone:
			// 进程自己退了(崩溃、超内存)却没说 done
			_ = cmd.Wait()
			res.Stderr = stderr.String()
			if stopping {
				res.Stopped = true
				return res, nil
			}
			return res, fmt.Errorf("助手进程意外退出: %s", lastLine(res.Stderr))
		case <-stopCh:
			stopCh = nil
			requestStop()
		case <-stopDeadline:
			cancel() // 5 秒还没收尾:结束进程
		case now := <-ticker.C:
			mu.Lock()
			if waiting == 0 {
				active += now.Sub(lastTick)
			}
			mu.Unlock()
			lastTick = now
			if opt.MaxActive > 0 && active > opt.MaxActive {
				requestStop()
			}
		case <-ctx.Done():
			_ = cmd.Wait()
			res.Stopped = true
			res.Stderr = stderr.String()
			return res, nil
		}
	}
}

func lastLine(s string) string {
	s = string(bytes.TrimSpace([]byte(s)))
	if i := bytes.LastIndexByte([]byte(s), '\n'); i >= 0 {
		s = s[i+1:]
	}
	if s == "" {
		return "没有输出"
	}
	return s
}
