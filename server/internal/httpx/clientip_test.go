package httpx

import (
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func mustCIDRs(t *testing.T, cidrs ...string) []*net.IPNet {
	t.Helper()
	out := make([]*net.IPNet, 0, len(cidrs))
	for _, c := range cidrs {
		_, n, err := net.ParseCIDR(c)
		if err != nil {
			t.Fatalf("测试用例里的 CIDR 写错了 %q: %v", c, err)
		}
		out = append(out, n)
	}
	return out
}

// TestClientIPFrom 钉住"什么时候才肯相信 X-Forwarded-For"这条边界。
// 默认(trusted 为空)必须与历史行为一致:只看 RemoteAddr,不理会任何转发头。
func TestClientIPFrom(t *testing.T) {
	lan := mustCIDRs(t, "172.16.0.0/12")

	cases := []struct {
		name    string
		remote  string
		xff     string
		trusted []*net.IPNet
		want    string
	}{
		{"不信任任何代理时忽略 XFF", "10.0.0.1:1234", "1.2.3.4", nil, "10.0.0.1"},
		{"来源不在可信网段时忽略 XFF", "203.0.113.7:1234", "1.2.3.4", lan, "203.0.113.7"},
		{"可信代理转发时取 XFF 最右一段", "172.17.0.1:1234", "1.2.3.4", lan, "1.2.3.4"},
		{"客户端伪造的前缀不影响结果", "172.17.0.1:1234", "9.9.9.9, 1.2.3.4", lan, "1.2.3.4"},
		{"多级代理时回退到第一个非可信地址", "172.17.0.1:1234", "1.2.3.4, 172.18.0.5", lan, "1.2.3.4"},
		{"XFF 缺失时回退 RemoteAddr", "172.17.0.1:1234", "", lan, "172.17.0.1"},
		{"XFF 全是可信代理时回退 RemoteAddr", "172.17.0.1:1234", "172.18.0.5", lan, "172.17.0.1"},
		{"XFF 里混着空段也能跳过", "172.17.0.1:1234", " , 1.2.3.4", lan, "1.2.3.4"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest("POST", "/query", nil)
			r.RemoteAddr = tc.remote
			if tc.xff != "" {
				r.Header.Set("X-Forwarded-For", tc.xff)
			}
			if got := ClientIPFrom(r, tc.trusted); got != tc.want {
				t.Fatalf("ClientIPFrom() = %q, want %q", got, tc.want)
			}
		})
	}
}

// TestWithForwardedTrust:只有可信反代转来的请求才保留 X-Forwarded-Proto / Host,
// 直连端口的客户端伪造的会被删掉(trusted 为空时一律删)。
func TestWithForwardedTrust(t *testing.T) {
	trusted := mustCIDRs(t, "172.16.0.0/12")
	cases := []struct {
		name, remote string
		trusted      []*net.IPNet
		wantProto    string
		wantHost     string
	}{
		{"可信反代", "172.18.0.1:5555", trusted, "https", "pan.example.com"},
		{"直连伪造", "203.0.113.9:5555", trusted, "", ""},
		{"未配可信代理", "172.18.0.1:5555", nil, "", ""},
	}
	for _, c := range cases {
		var gotProto, gotHost string
		h := WithForwardedTrust(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotProto, gotHost = r.Header.Get("X-Forwarded-Proto"), r.Header.Get("X-Forwarded-Host")
		}), c.trusted)
		req := httptest.NewRequest("GET", "/.well-known/oauth-authorization-server", nil)
		req.RemoteAddr = c.remote
		req.Header.Set("X-Forwarded-Proto", "https")
		req.Header.Set("X-Forwarded-Host", "pan.example.com")
		h.ServeHTTP(httptest.NewRecorder(), req)
		if gotProto != c.wantProto || gotHost != c.wantHost {
			t.Fatalf("%s: proto=%q host=%q,want %q %q", c.name, gotProto, gotHost, c.wantProto, c.wantHost)
		}
	}
}
