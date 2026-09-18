package httpx

import (
	"net"
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
