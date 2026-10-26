package service

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func probeOf(t *testing.T, js string) ffProbe {
	t.Helper()
	var p ffProbe
	if err := json.Unmarshal([]byte(js), &p); err != nil {
		t.Fatal(err)
	}
	return p
}

const mkvProbe = `{"streams":[
 {"index":0,"codec_name":"h264","codec_type":"video","pix_fmt":"yuv420p","disposition":{"default":1}},
 {"index":1,"codec_name":"aac","codec_type":"audio","disposition":{"default":0},"tags":{"language":"jpn"}},
 {"index":2,"codec_name":"ac3","codec_type":"audio","disposition":{"default":1},"tags":{"language":"chi"}},
 {"index":3,"codec_name":"hdmv_pgs_subtitle","codec_type":"subtitle","disposition":{"default":0}},
 {"index":4,"codec_name":"ass","codec_type":"subtitle","disposition":{"default":1},"tags":{"language":"eng"}},
 {"index":5,"codec_name":"subrip","codec_type":"subtitle","disposition":{"default":0},"tags":{"language":"chi","title":"简体中文"}},
 {"index":6,"codec_name":"mjpeg","codec_type":"video","disposition":{"attached_pic":1}}
],"format":{"duration":"1440.5"}}`

func TestPlanRemuxPicksStreamsAndTranscodesUnsupportedAudio(t *testing.T) {
	pl, err := planRemux(probeOf(t, mkvProbe))
	if err != nil {
		t.Fatal(err)
	}
	if pl.video.Index != 0 || pl.audio == nil || pl.audio.Index != 2 || pl.audioCopy {
		t.Fatalf("video %d audio %+v copy %v", pl.video.Index, pl.audio, pl.audioCopy)
	}
	if len(pl.subs) != 2 || pl.subs[0].index != 4 || pl.subs[1].index != 5 {
		t.Fatalf("subs %+v", pl.subs)
	}
	if pl.subs[1].label != "简体中文" || pl.subs[1].lang != "zh-Hans" || pl.subs[0].label != "English" {
		t.Fatalf("labels %+v", pl.subs)
	}
	if defaultSub(pl.subs) != 1 {
		t.Fatalf("default should be the Chinese track, got %d", defaultSub(pl.subs))
	}
	args := strings.Join(remuxArgs("SRC", "OUT", pl), " ")
	for _, want := range []string{"-map 0:0", "-map 0:2", "-c:v copy", "-c:a aac", "-ac 2", "-map 0:4 -c:s webvtt", "-map 0:5 -c:s webvtt"} {
		if !strings.Contains(args, want) {
			t.Errorf("args missing %q: %s", want, args)
		}
	}
	if strings.Contains(args, "-map 0:3") || strings.Contains(args, "hvc1") {
		t.Errorf("unexpected args: %s", args)
	}
	if pl.duration != 1440.5 {
		t.Fatalf("duration %v", pl.duration)
	}
}

func TestPlanRemuxRejectsCodecsBrowsersCannotPlay(t *testing.T) {
	for _, tc := range []struct{ codec, pix, want string }{
		{"mpeg4", "yuv420p", "MPEG4"},
		{"h264", "yuv420p10le", "10 位"},
		{"wmv3", "", "WMV3"},
	} {
		p := probeOf(t, `{"streams":[{"index":0,"codec_name":"`+tc.codec+`","codec_type":"video","pix_fmt":"`+tc.pix+`"}]}`)
		_, err := planRemux(p)
		var ue *unsupportedError
		if !errors.As(err, &ue) || !strings.Contains(ue.msg, tc.want) {
			t.Errorf("%s/%s: %v", tc.codec, tc.pix, err)
		}
	}
	pl, err := planRemux(probeOf(t, `{"streams":[{"index":0,"codec_name":"hevc","codec_type":"video","pix_fmt":"yuv420p10le"},{"index":1,"codec_name":"aac","codec_type":"audio"}]}`))
	if err != nil || !pl.audioCopy || !strings.Contains(strings.Join(remuxArgs("S", "O", pl), " "), "-tag:v hvc1 -c:a copy") {
		t.Fatalf("hevc plan %+v %v", pl, err)
	}
}

func TestMediaMetaAndDirectPlay(t *testing.T) {
	b, err := MediaMetaFromProbe([]byte(mkvProbe))
	if err != nil {
		t.Fatal(err)
	}
	m := parseMediaMeta(b)
	if !m.Streams || m.VCodec != "h264" || m.ACodec != "ac3" || m.TextSubs != 2 || m.DurationSec != 1440.5 {
		t.Fatalf("meta %+v", m)
	}
	if d := durationFromMeta(b); d == nil || *d != 1440 {
		t.Fatalf("duration from meta %v", d)
	}
	ok := MediaMeta{Streams: true, VCodec: "h264", VPix: "yuv420p", ACodec: "aac"}
	for _, tc := range []struct {
		name string
		m    MediaMeta
		want bool
	}{
		{"a.mkv", ok, true},
		{"a.MKV", ok, true},
		{"a.avi", ok, false},                        // Chrome 不认 avi 容器
		{"a.mkv", MediaMeta{DurationSec: 1}, false}, // 老数据不知道编码:转一下最稳
		{"a.mkv", MediaMeta{Streams: true, VCodec: "h264", ACodec: "ac3"}, false},
		{"a.mkv", MediaMeta{Streams: true, VCodec: "h264", ACodec: "aac", TextSubs: 1}, false},
		{"a.mkv", MediaMeta{Streams: true, VCodec: "hevc", ACodec: "aac"}, false},
		{"a.mkv", MediaMeta{Streams: true, VCodec: "vp9", ACodec: "opus"}, true},
	} {
		if got := directFriendly(tc.name, tc.m); got != tc.want {
			t.Errorf("%s %+v: got %v", tc.name, tc.m, got)
		}
	}
	if !RemuxCandidate("电影.MKV", nil) || RemuxCandidate("a.mp4", nil) || !RemuxCandidate("x", strPtr("video/x-msvideo")) {
		t.Fatal("candidate")
	}
}

func TestProgressLine(t *testing.T) {
	if p, ok := progressOf("out_time_us=60000000", 120); !ok || p != 0.5 {
		t.Fatalf("got %v %v", p, ok)
	}
	if p, ok := progressOf("out_time_us=999000000", 120); !ok || p != 1 {
		t.Fatalf("clamp %v", p)
	}
	for _, l := range []string{"out_time_us=N/A", "progress=continue", "frame=12"} {
		if _, ok := progressOf(l, 120); ok {
			t.Errorf("%q should not parse", l)
		}
	}
	if _, ok := progressOf("out_time_us=1", 0); ok {
		t.Error("unknown duration")
	}
}

func testRemuxer(t *testing.T, cache int64) *Remuxer {
	t.Helper()
	return &Remuxer{cfg: RemuxConfig{Dir: t.TempDir(), CacheSize: cache, TTL: 24 * time.Hour},
		key: []byte("k"), queue: make(chan string, 2), jobs: map[string]*remuxJob{}}
}

func fakeCached(t *testing.T, r *Remuxer, sha string, size int, used time.Time) {
	t.Helper()
	dir := r.doneDir(sha)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "video.mp4")
	if err := os.WriteFile(p, make([]byte, size), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "meta.json"), []byte(`{"subs":[{"file":"sub0.vtt","label":"简体中文","lang":"zh-Hans","default":true}]}`), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(p, used, used); err != nil {
		t.Fatal(err)
	}
}

func TestRemuxInfoRequestAndSignedURL(t *testing.T) {
	r := testRemuxer(t, 1<<20)
	sha := strings.Repeat("a", 64)
	if got := r.Info(sha, "a.mkv", 10, nil); got.Status != RmNone {
		t.Fatalf("fresh: %+v", got)
	}
	if got := r.Info(sha, "a.mkv", 2<<20, nil); got.Status != RmUnavailable || got.Message == nil {
		t.Fatalf("too big: %+v", got)
	}
	meta, _ := json.Marshal(MediaMeta{Streams: true, VCodec: "mpeg4"})
	if got := r.Info(sha, "a.avi", 10, meta); got.Status != RmUnsupported {
		t.Fatalf("mpeg4: %+v", got)
	}
	got, err := r.Request(sha, "a.mkv", 10, nil)
	if err != nil || got.Status != RmQueued {
		t.Fatalf("request: %+v %v", got, err)
	}
	if again, _ := r.Request(sha, "a.mkv", 10, nil); again.Status != RmQueued || len(r.queue) != 1 {
		t.Fatalf("second request should not enqueue twice: %+v %d", again, len(r.queue))
	}
	// 队列满了
	_, _ = r.Request(strings.Repeat("b", 64), "b.mkv", 10, nil)
	if _, err := r.Request(strings.Repeat("c", 64), "c.mkv", 10, nil); err == nil {
		t.Fatal("full queue should refuse")
	}

	fakeCached(t, r, sha, 100, time.Now())
	done := r.Info(sha, "a.mkv", 10, nil)
	if done.Status != RmDone || done.URL == nil || len(done.Subtitles) != 1 || !done.Subtitles[0].Default {
		t.Fatalf("done: %+v", done)
	}
	u := *done.URL
	q := u[strings.Index(u, "?")+1:]
	var e, s string
	for _, kv := range strings.Split(q, "&") {
		k, v, _ := strings.Cut(kv, "=")
		if k == "e" {
			e = v
		} else if k == "s" {
			s = v
		}
	}
	f, err := r.Open(sha, "video.mp4", e, s)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	f.Close()
	if _, err := r.Open(sha, "video.mp4", e, strings.Repeat("0", 32)); !errors.Is(err, ErrForbidden) {
		t.Fatalf("bad sig: %v", err)
	}
	if _, err := r.Open(sha, "../meta.json", e, s); !errors.Is(err, ErrNotFound) {
		t.Fatalf("path: %v", err)
	}
	if _, err := r.Open(sha, "video.mp4", "1", r.sign(sha, 1)); !errors.Is(err, ErrForbidden) {
		t.Fatalf("expired: %v", err)
	}
}

func TestRemuxEvictsExpiredThenOldest(t *testing.T) {
	r := testRemuxer(t, 1000)
	old, mid, fresh := strings.Repeat("1", 64), strings.Repeat("2", 64), strings.Repeat("3", 64)
	fakeCached(t, r, old, 400, time.Now().Add(-48*time.Hour)) // 过期
	fakeCached(t, r, mid, 400, time.Now().Add(-2*time.Hour))
	fakeCached(t, r, fresh, 400, time.Now())
	r.evict(0)
	if _, ok := r.cached(old); ok {
		t.Fatal("expired entry kept")
	}
	if _, ok := r.cached(mid); !ok {
		t.Fatal("mid evicted too early")
	}
	r.evict(500) // 800 + 500 > 1000:腾掉最久没放的
	if _, ok := r.cached(mid); ok {
		t.Fatal("mid should go to make room")
	}
	if _, ok := r.cached(fresh); !ok {
		t.Fatal("fresh evicted")
	}
}

// TestRemuxRealFFmpeg 用真的 ffmpeg 走一遍:生成一个 H.264 + AC3 + 两条文字字幕的 mkv,转成 mp4。
// 本机没有 ffmpeg 时跳过;发版前在带 ffmpeg 的镜像里跑(doc/31 一)。
func TestRemuxRealFFmpeg(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("no ffmpeg")
	}
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Skip("no ffprobe")
	}
	dir := t.TempDir()
	srt := filepath.Join(dir, "zh.srt")
	if err := os.WriteFile(srt, []byte("1\n00:00:00,500 --> 00:00:02,000\n你好,世界\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	ass := filepath.Join(dir, "en.ass")
	if err := os.WriteFile(ass, []byte("[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\b1}Hello{\\b0} world\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(dir, "in.mkv")
	gen := exec.Command(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=4",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=4", "-i", srt, "-i", ass,
		"-map", "0", "-map", "1", "-map", "2", "-map", "3",
		"-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "ac3", "-c:s:0", "subrip", "-c:s:1", "ass",
		"-metadata:s:s:0", "language=chi", "-metadata:s:s:0", "title=简体中文", "-metadata:s:s:1", "language=eng", src)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Fatalf("generate: %v %s", err, out)
	}
	st, _ := os.Stat(src)
	r := testRemuxer(t, 1<<30)
	r.cfg.FFmpeg, r.cfg.FFprobe = ffmpeg, ffprobe
	sha := strings.Repeat("d", 64)
	if err := r.convertFrom(context.Background(), sha, src, st.Size()); err != nil {
		t.Fatal(err)
	}
	info := r.Info(sha, "in.mkv", st.Size(), nil)
	if info.Status != RmDone || len(info.Subtitles) != 2 || info.Subtitles[0].Label != "简体中文" || !info.Subtitles[0].Default {
		t.Fatalf("info %+v", info)
	}
	out, err := exec.Command(ffprobe, "-v", "quiet", "-show_streams", "-of", "json", filepath.Join(r.doneDir(sha), "video.mp4")).Output()
	if err != nil {
		t.Fatal(err)
	}
	var p ffProbe
	_ = json.Unmarshal(out, &p)
	if len(p.Streams) != 2 || p.Streams[0].CodecName != "h264" || p.Streams[1].CodecName != "aac" {
		t.Fatalf("output streams %s", out)
	}
	vtt, _ := os.ReadFile(filepath.Join(r.doneDir(sha), "sub0.vtt"))
	en, _ := os.ReadFile(filepath.Join(r.doneDir(sha), "sub1.vtt"))
	if !strings.HasPrefix(string(vtt), "WEBVTT") || !strings.Contains(string(vtt), "你好,世界") || !strings.Contains(string(en), "Hello") || strings.Contains(string(en), "{") {
		t.Fatalf("vtt:\n%s\n%s", vtt, en)
	}
	if _, err := os.Stat(r.partDir(sha)); !os.IsNotExist(err) {
		t.Fatal("part dir left behind")
	}
	t.Logf("ok:\n%s", en)
}
