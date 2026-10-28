package httpx

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// 包了日志中间件以后,长响应仍要能放开 Server 级写超时、能 Flush(打包下载、WebDAV、推送连接都靠它)。
func TestLoggingKeepsResponseController(t *testing.T) {
	errs := make(chan error, 2)
	h := WithLogging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rc := http.NewResponseController(w)
		errs <- rc.SetWriteDeadline(time.Time{})
		_, _ = w.Write([]byte("x"))
		errs <- rc.Flush()
	}))
	srv := httptest.NewServer(h)
	defer srv.Close()
	res, err := http.Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	for range 2 {
		if err := <-errs; err != nil {
			t.Fatalf("response controller through WithLogging: %v", err)
		}
	}
}
