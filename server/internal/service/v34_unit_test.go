package service

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"io"
	"slices"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/yophon/gopan/server/internal/store"
)

// testJPEG 拼一个最小的 JPEG 头:SOI + APP1(Exif:IFD0 里有 Orientation=6 与 GPSInfo 指针,GPS IFD 有纬度)+ 一段假数据。
func testJPEG(t *testing.T, withGPS bool, bo binary.ByteOrder) []byte {
	t.Helper()
	var tiff bytes.Buffer
	if bo == binary.LittleEndian {
		tiff.WriteString("II")
	} else {
		tiff.WriteString("MM")
	}
	w := func(v any) { _ = binary.Write(&tiff, bo, v) }
	w(uint16(42))
	w(uint32(8)) // IFD0 在偏移 8
	n := uint16(1)
	if withGPS {
		n = 2
	}
	w(n)
	w(uint16(0x0112)) // Orientation SHORT 1 = 6
	w(uint16(3))
	w(uint32(1))
	w(uint16(6))
	w(uint16(0))
	gpsOff := uint32(8 + 2 + int(n)*12 + 4)
	if withGPS {
		w(uint16(0x8825)) // GPSInfo LONG → gps IFD
		w(uint16(4))
		w(uint32(1))
		w(gpsOff)
	}
	w(uint32(0)) // next IFD
	if withGPS {
		w(uint16(1))
		w(uint16(2)) // GPSLatitude RATIONAL ×3,数据放在后面
		w(uint16(5))
		w(uint32(3))
		w(gpsOff + 2 + 12 + 4)
		w(uint32(0))
		for _, v := range []uint32{31, 1, 14, 1, 2500, 100} {
			w(v)
		}
	}
	seg := append([]byte("Exif\x00\x00"), tiff.Bytes()...)
	var b bytes.Buffer
	b.Write([]byte{0xFF, 0xD8})
	b.Write([]byte{0xFF, 0xE0, 0x00, 0x10})
	b.WriteString("JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
	b.Write([]byte{0xFF, 0xE1})
	_ = binary.Write(&b, binary.BigEndian, uint16(len(seg)+2))
	b.Write(seg)
	b.Write([]byte{0xFF, 0xDA, 0x00, 0x02, 0x11, 0x22, 0x33, 0xFF, 0xD9})
	return b.Bytes()
}

func TestJPEGGPS(t *testing.T) {
	for _, bo := range []binary.ByteOrder{binary.LittleEndian, binary.BigEndian} {
		j := testJPEG(t, true, bo)
		if !JPEGGPS(j, false) {
			t.Fatal("应检测到 GPS")
		}
		orig := slices.Clone(j)
		if !JPEGGPS(j, true) {
			t.Fatal("抹之前有 GPS,应返回 true")
		}
		if len(j) != len(orig) {
			t.Fatal("长度不能变")
		}
		if JPEGGPS(j, false) {
			t.Fatal("抹完不应再有 GPS")
		}
		if bytes.Contains(j, []byte{0, 0, 0, 31}) || bytes.Contains(j, []byte{31, 0, 0, 0}) {
			t.Fatal("纬度数据应被清零")
		}
		if !bytes.Equal(j[:30], orig[:30]) || !bytes.Equal(j[len(j)-9:], orig[len(orig)-9:]) {
			t.Fatal("GPS 以外的字节不该动")
		}
	}
	if JPEGGPS(testJPEG(t, false, binary.LittleEndian), false) {
		t.Fatal("没有 GPS 的不应报有")
	}
	for _, junk := range [][]byte{nil, {0xFF}, []byte("not a jpeg"), {0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xFF}} {
		if JPEGGPS(junk, true) {
			t.Fatal("坏数据不应报有 GPS")
		}
	}
	xmp := append([]byte{0xFF, 0xD8, 0xFF, 0xE1}, 0, 0)
	body := append(slices.Clone(xmpHeader), []byte(`<x:xmpmeta><rdf:Description exif:GPSLatitude="31,14.25N"/></x:xmpmeta>`)...)
	binary.BigEndian.PutUint16(xmp[4:], uint16(len(body)+2))
	xmp = append(xmp, body...)
	xmp = append(xmp, 0xFF, 0xD9)
	if !JPEGGPS(xmp, true) || JPEGGPS(xmp, false) || bytes.Contains(xmp, []byte("GPSLatitude")) {
		t.Fatal("XMP 里的位置应检测到并抹掉")
	}
}

func TestSniffImageAndName(t *testing.T) {
	cases := []struct {
		head []byte
		name string
		want string
	}{
		{[]byte{0xFF, 0xD8, 0xFF, 0xE0}, "a.jpeg", ".jpg"},
		{[]byte("\x89PNG\r\n\x1a\n...."), "x", ".png"},
		{[]byte("GIF89a..."), "", ".gif"},
		{[]byte("RIFF\x00\x00\x00\x00WEBPVP8 "), "", ".webp"},
		{[]byte("\x00\x00\x00\x1cftypavif\x00\x00"), "", ".avif"},
		{[]byte("BM\x00\x00"), "", ".bmp"},
		{[]byte("\xEF\xBB\xBF <?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"), "logo.SVG", ".svg"},
		{[]byte("<svg/>"), "logo.txt", ""},
		{[]byte("<html><body>hi"), "a.svg", ""},
		{[]byte("%PDF-1.7"), "a.jpg", ""},
	}
	for _, c := range cases {
		if got := sniffImage(c.head, c.name); got != c.want {
			t.Errorf("sniffImage(%q, %q) = %q, want %q", c.head, c.name, got, c.want)
		}
	}
	now := time.Date(2026, 10, 21, 15, 30, 12, 0, time.UTC)
	for in, want := range map[[2]string]string{
		{"猫.jpeg", ".jpg"}:            "猫.jpeg",
		{"猫.PNG", ".png"}:             "猫.PNG",
		{"猫.png", ".jpg"}:             "猫.jpg", // 扩展名跟着文件头走
		{`C:\Users\a\图.gif`, ".gif"}:   "图.gif",
		{"../../etc/passwd.png", ".png"}: "passwd.png",
	} {
		if got := imageName(in[0], in[1], now); got != want {
			t.Errorf("imageName(%q) = %q, want %q", in[0], got, want)
		}
	}
	for _, generic := range []string{"", "image.png", "blob", "Clipboard.jpg", "screenshot.png"} {
		got := imageName(generic, ".png", now)
		if !strings.HasPrefix(got, "20261021-153012-") || !strings.HasSuffix(got, ".png") || len(got) != len("20261021-153012-abcd.png") {
			t.Errorf("imageName(%q) = %q, 应换成时间戳名", generic, got)
		}
	}
}

func TestNormalizeHosts(t *testing.T) {
	got, err := NormalizeHosts([]string{" https://www.Example.com/a?b ", "example.com", "*.blog.cn", "http://u:p@x.org:8080", "", "[::1]"})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"example.com", "blog.cn", "x.org", "[::1]"}
	if !slices.Equal(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
	if _, err := NormalizeHosts([]string{"a b.com"}); err == nil {
		t.Fatal("带空格的应拒绝")
	}
	many := make([]string, 51)
	for i := range many {
		many[i] = strings.Repeat("a", i+1) + ".com"
	}
	if _, err := NormalizeHosts(many); err == nil {
		t.Fatal("超过 50 个应拒绝")
	}
	if !hostAllowed("blog.example.com", []string{"example.com"}) || hostAllowed("badexample.com", []string{"example.com"}) {
		t.Fatal("子域匹配不对")
	}
	if RefererHost("https://WWW.Example.com:8443/p?q") != "example.com" || RefererHost("not a url") != "" || RefererHost("") != "" {
		t.Fatal("RefererHost 不对")
	}
	l := &Links{cfg: LinkConfig{PublicURL: "https://drive.test"}}
	if !l.refererAllowed("drive.test", []string{"blog.cn"}, false) {
		t.Fatal("自己站点的引用总是放行")
	}
	if l.refererAllowed("", nil, false) || !l.refererAllowed("", nil, true) {
		t.Fatal("无来源按 allowNoReferer")
	}
	if !l.refererAllowed("any.com", nil, false) || l.refererAllowed("any.com", []string{"blog.cn"}, true) {
		t.Fatal("白名单为空 = 全部放行;非空 = 只放行名单里的")
	}
}

func TestNaturalLessAndFinished(t *testing.T) {
	names := []string{"第10集.mp4", "第2集.mp4", "第1集.mp4", "EP03.mkv", "ep1.mkv", "a.mp4", "第02集.mp4"}
	sort.SliceStable(names, func(i, j int) bool { return NaturalLess(names[i], names[j]) })
	want := []string{"a.mp4", "ep1.mkv", "EP03.mkv", "第1集.mp4", "第02集.mp4", "第2集.mp4", "第10集.mp4"}
	if !slices.Equal(names, want) {
		t.Fatalf("got %v", names)
	}
	if !IsFinished("sec", 3590, 3600) || IsFinished("sec", 3400, 3600) || !IsFinished("sec", 3500, 3600) {
		t.Fatal("剩余 ≤ max(20 秒, 3%) 算看完")
	}
	if !IsFinished("sec", 290, 305) || IsFinished("sec", 280, 305) {
		t.Fatal("短的按 20 秒")
	}
	if !IsFinished("page", 10, 10) || IsFinished("page", 9, 10) {
		t.Fatal("PDF 翻到最后一页算看完")
	}
}

func TestTargetPathAndDeviceLabel(t *testing.T) {
	cases := map[Target]string{
		{Kind: TgNode, ID: "n1", ParentID: "p1"}: "/drive/p1?open=n1",
		{Kind: TgNode, ID: "n1"}:                 "/drive?open=n1",
		{Kind: TgFileRequest, ID: "r"}:           "/shares?tab=requests&id=r",
		{Kind: TgLink, ID: "l"}:                  "/shares?tab=links&id=l",
		{Kind: TgJobs}:                           "/drive?panel=jobs",
		{Kind: TgSessions}:                       "/devices",
		{Kind: TgCredentials, ID: "img"}:         "/shares?tab=links&panel=keys",
		{Kind: TgCredentials, ID: "webdav"}:      "/drive?panel=webdav",
		{Kind: TgCredentials, ID: "mcp"}:         "/drive?panel=mcp",
		{Kind: TgAdmin}:                          "/admin",
		{Kind: "??"}:                             "/",
	}
	for in, want := range cases {
		if got := TargetPath(in); got != want {
			t.Errorf("TargetPath(%+v) = %q, want %q", in, got, want)
		}
	}
	for ua, want := range map[string]string{
		"gopan-android/3.4.0 (Android 14; Pixel 8)":                                                                         "安卓 App · Pixel 8",
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36":      "Chrome · Windows",
		"Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 Edg/130.0":        "Edge · Windows",
		"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605": "Safari · macOS",
		"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1":     "Safari · iOS",
		"Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0":                                           "Firefox · Linux",
		"": "未知设备",
	} {
		if got := DeviceLabel(ua); got != want {
			t.Errorf("DeviceLabel(%q) = %q, want %q", ua, got, want)
		}
	}
}

func TestBuildPush(t *testing.T) {
	m := pushMessage{ID: 7, Kind: EvLinkAlert, Level: LvAlert, Title: "标题", Body: "正文", URL: "https://drive.test/x", CreatedAt: time.Unix(0, 0)}
	read := func(t *testing.T, ch store.NotifyChannel) (string, map[string]any, string) {
		t.Helper()
		req, err := buildPush(context.Background(), ch, m)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(req.Body)
		var v map[string]any
		if err := json.Unmarshal(b, &v); err != nil {
			t.Fatal(err)
		}
		return req.URL.String(), v, string(b)
	}

	u, v, raw := read(t, store.NotifyChannel{Kind: ChWebhook, Url: "https://hook.test/in", Secret: "s3cret"})
	if u != "https://hook.test/in" || v["title"] != "标题" || v["level"] != LvAlert || v["url"] != m.URL {
		t.Fatalf("webhook: %s %v", u, v)
	}
	req, _ := buildPush(context.Background(), store.NotifyChannel{Kind: ChWebhook, Url: "https://hook.test/in", Secret: "s3cret"}, m)
	mac := hmac.New(sha256.New, []byte("s3cret"))
	mac.Write([]byte(raw))
	if req.Header.Get("X-Gopan-Signature") != "sha256="+hex.EncodeToString(mac.Sum(nil)) {
		t.Fatal("webhook 签名不对")
	}

	u, v, _ = read(t, store.NotifyChannel{Kind: ChNtfy, Url: "https://ntfy.sh", Topic: "gopan-me", Secret: "tk"})
	if u != "https://ntfy.sh" || v["topic"] != "gopan-me" || v["priority"].(float64) != 5 || v["click"] != m.URL {
		t.Fatalf("ntfy: %s %v", u, v)
	}
	req, _ = buildPush(context.Background(), store.NotifyChannel{Kind: ChNtfy, Url: "https://ntfy.sh", Topic: "t", Secret: "tk"}, m)
	if req.Header.Get("Authorization") != "Bearer tk" {
		t.Fatal("ntfy 令牌")
	}

	u, v, _ = read(t, store.NotifyChannel{Kind: ChBark, Url: "https://api.day.app", Secret: "devkey"})
	if u != "https://api.day.app/push" || v["device_key"] != "devkey" || v["level"] != "timeSensitive" {
		t.Fatalf("bark: %s %v", u, v)
	}

	u, v, _ = read(t, store.NotifyChannel{Kind: ChTelegram, Url: "https://api.telegram.org", Secret: "123:ABC", Topic: "-100"})
	if u != "https://api.telegram.org/bot123:ABC/sendMessage" || v["chat_id"] != "-100" || !strings.Contains(v["text"].(string), "正文") {
		t.Fatalf("telegram: %s %v", u, v)
	}
	if _, err := buildPush(context.Background(), store.NotifyChannel{Kind: "email"}, m); err == nil {
		t.Fatal("未知渠道应报错")
	}
}
