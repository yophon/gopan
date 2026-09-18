package config

import (
	"net"
	"testing"
)

func TestParseCIDRs(t *testing.T) {
	got, err := parseCIDRs(" 172.16.0.0/12 , 10.0.0.1 ,, ::1 ")
	if err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if len(got) != 3 {
		t.Fatalf("应解析出 3 段(空段跳过),得到 %d", len(got))
	}
	if !got[0].Contains(net.ParseIP("172.31.255.254")) {
		t.Fatalf("172.16.0.0/12 应覆盖 172.31.x.x")
	}
	// 裸 IP 按单机掩码补齐,不能顺带覆盖同网段的别人
	if !got[1].Contains(net.ParseIP("10.0.0.1")) {
		t.Fatalf("裸 IPv4 应包含自身")
	}
	if got[1].Contains(net.ParseIP("10.0.0.2")) {
		t.Fatalf("裸 IPv4 应按 /32 处理,不该覆盖 10.0.0.2")
	}
	if !got[2].Contains(net.ParseIP("::1")) {
		t.Fatalf("裸 IPv6 应包含自身")
	}
}

func TestParseCIDRsEmpty(t *testing.T) {
	got, err := parseCIDRs("")
	if err != nil || len(got) != 0 {
		t.Fatalf("空串应得到空列表且不报错,得到 %v / %v", got, err)
	}
}

// 非法输入必须报错。静默忽略的后果是"谁都不信",表现是"IP 全是网关地址",
// 不会报任何错,很难发现 —— 所以这里要钉死。
func TestParseCIDRsInvalid(t *testing.T) {
	if _, err := parseCIDRs("not-an-ip"); err == nil {
		t.Fatal("非法地址应报错")
	}
	if _, err := parseCIDRs("10.0.0.0/33"); err == nil {
		t.Fatal("非法掩码应报错")
	}
}
