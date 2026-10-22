package service

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"net"
	"net/url"
	"path"
	"strings"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/fetcher"
)

var errOverLimit = errors.New("over limit")

// runFetch 离线下载一个链接(doc/21 三.1):边读边算 sha、边写暂存,下完走流式落库(撞名改名)。
func (j *Jobs) runFetch(ctx context.Context, run *jobRun) (*uuid.UUID, error) {
	var p fetchParams
	if err := json.Unmarshal(run.job.Params, &p); err != nil || p.URL == "" {
		return nil, errf("INVALID_INPUT", "任务参数损坏")
	}
	if !j.fetch.Enabled {
		return nil, errf("FETCH_DISABLED", "本站没有开启离线下载")
	}
	owner := run.job.OwnerID
	if err := checkTargetFolder(ctx, j.q, owner, run.job.ParentID); err != nil {
		return nil, err
	}
	st, err := fetcher.Open(ctx, j.client, p.URL, fetcher.Options{
		UserAgent: j.fetch.UserAgent, Rate: j.fetch.Rate, Idle: j.fetchIdle,
	})
	if err != nil {
		return nil, mapFetchErr(err)
	}
	defer st.Close()
	name := st.Name
	if p.Name != "" {
		// 订阅给的名字(「日期 标题」)没带扩展名时补上源站文件的
		name = fetcher.Clean(p.Name)
		if path.Ext(name) == "" || len(path.Ext(name)) > 6 {
			name += path.Ext(st.Name)
		}
	}
	run.setTitle(name)
	if st.Size >= 0 {
		run.setBytesTotal(st.Size)
	}

	u, err := j.q.GetUserByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	avail := max(u.QuotaBytes-u.UsedBytes, 0)
	if st.Size > j.fetch.MaxSize {
		return nil, errf("FILE_TOO_LARGE", "文件 %s,超过离线下载的单文件上限 %s", humanBytes(st.Size), humanBytes(j.fetch.MaxSize))
	}
	if st.Size > avail {
		return nil, errf("QUOTA_EXCEEDED", "存储配额不足:文件 %s,剩余 %s", humanBytes(st.Size), humanBytes(avail))
	}
	cr := &countingReader{r: st, h: sha256.New(), run: run, limit: min(avail, j.fetch.MaxSize)}
	key := jobTmpKey(run.job.ID)
	size, err := j.obj.PutStream(ctx, key, cr, "application/octet-stream")
	if err != nil {
		_ = j.obj.Remove(context.WithoutCancel(ctx), key)
		switch {
		case cr.over && cr.limit == avail && avail < j.fetch.MaxSize:
			return nil, errf("QUOTA_EXCEEDED", "存储配额不足:已下载 %s,剩余空间用完了", humanBytes(cr.n))
		case cr.over:
			return nil, errf("FILE_TOO_LARGE", "文件超过离线下载的单文件上限 %s", humanBytes(j.fetch.MaxSize))
		case ctx.Err() != nil:
			return nil, ctx.Err()
		case cr.err != nil:
			return nil, mapFetchErr(cr.err)
		}
		return nil, err
	}
	if st.Size >= 0 && size != st.Size {
		return nil, fmt.Errorf("下载不完整:%d / %d 字节", size, st.Size)
	}
	if fetcher.IsHTML(st.ContentType) && !looksLikePage(st.URL) {
		run.warning = errf("GOT_HTML", "拿到的是网页,不是文件:源站可能要求登录、或拦截了服务器的访问")
	}
	node, err := j.uploads.CommitStaged(ctx, StagedFile{
		Owner: owner, Parent: run.job.ParentID, Name: name,
		Sha: hex.EncodeToString(cr.h.Sum(nil)), Size: size, TmpKey: key,
	})
	if err != nil {
		return nil, err
	}
	run.setTitle(node.Name)
	return &node.ID, nil
}

// looksLikePage 链接本来就指向网页(没有扩展名的不算:/download?id=1 这种拿到网页多半是被拦了)。
func looksLikePage(u *url.URL) bool {
	if u == nil {
		return false
	}
	switch strings.ToLower(path.Ext(u.Path)) {
	case ".html", ".htm", ".shtml", ".xhtml", ".php", ".asp", ".aspx", ".jsp":
		return true
	}
	return false
}

// countingReader 边读边算 sha、记进度,超过 limit 就停。
type countingReader struct {
	r     io.Reader
	h     hash.Hash
	run   *jobRun
	limit int64
	n     int64
	over  bool
	err   error
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	if n > 0 {
		if c.n+int64(n) > c.limit {
			c.over = true
			return 0, errOverLimit
		}
		c.n += int64(n)
		c.h.Write(p[:n])
		c.run.bytesDone.Add(int64(n))
	}
	if err != nil && err != io.EOF {
		c.err = err
	}
	return n, err
}

// mapFetchErr 把内容 / 源站层面的问题收成终态错误(重试也没用);网络抖动之类原样返回,由 runner 重试。
func mapFetchErr(err error) error {
	var he *fetcher.HTTPError
	var dnsErr *net.DNSError
	switch {
	case errors.Is(err, fetcher.ErrBadURL):
		return errf("INVALID_URL", "链接格式不对,只支持 http / https")
	case errors.Is(err, fetcher.ErrBlocked):
		return errf("FETCH_BLOCKED", "这个地址不允许访问(内网、本机或保留地址)")
	case errors.Is(err, fetcher.ErrTooManyRedirects):
		return errf("FETCH_REDIRECTS", "重定向次数过多")
	case errors.As(err, &he):
		if he.Status >= 500 || he.Status == 429 || he.Status == 408 {
			return err // 源站临时出问题,稍后再试
		}
		return errf("FETCH_HTTP_STATUS", "源站返回 HTTP %d", he.Status)
	case errors.As(err, &dnsErr) && dnsErr.IsNotFound:
		return errf("FETCH_DNS", "找不到主机 %s", dnsErr.Name)
	}
	return err
}

// describeNetErr 网络错误转成一句中文,去掉 url.Error 里的完整链接(可能带签名)。
func describeNetErr(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		err = ue.Err
	}
	var ne net.Error
	var dnsErr *net.DNSError
	var certErr *tls.CertificateVerificationError
	var opErr *net.OpError
	switch {
	case errors.Is(err, fetcher.ErrChanged):
		return "源站文件在下载过程中变了,重新下载"
	case errors.As(err, &certErr):
		return "源站的 HTTPS 证书无效"
	case errors.As(err, &dnsErr):
		return "解析主机名失败"
	case errors.As(err, &ne) && ne.Timeout():
		return "连接源站超时"
	case errors.As(err, &opErr) && opErr.Op == "dial":
		return "连不上源站"
	case errors.Is(err, io.ErrUnexpectedEOF):
		return "源站连接中途断开"
	}
	msg := err.Error()
	if len(msg) > 300 {
		msg = msg[:300]
	}
	return msg
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit && exp < 4; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTP"[exp])
}
