package service

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/store"
)

// linkMeter 直链计数的进程内聚合(doc/22 四.2):每 30 秒批量落库一次,退出时冲刷;崩溃最多丢 30 秒。
// 同时维护「今天每条用了多少」「本月全站用了多少」(上限判断不能每次都查库)、当前小时的来源与单 IP 计数(异常检测用)。
type linkMeter struct {
	s  *Links
	mu sync.Mutex

	hours    map[hourKey]*hourAgg
	refs     map[refKey]*refAgg
	hits     []store.InsertLinkHitsParams
	sampled  map[hourKey]int
	ips      map[hourKey]map[string]struct{}
	totals   map[uuid.UUID]*totalAgg
	refHosts map[dayKey]map[string]bool // 已经落库的来源(每条每天最多 50 个)

	today map[uuid.UUID]*todayUsage
	month monthUsage

	hourRefs     map[uuid.UUID]map[string]int64 // 当前小时各来源的次数(异常检测)
	hourRefsAt   time.Time
	prevHourRefs map[uuid.UUID]map[string]int64
	ipWin        map[string]*ipCount // 10 分钟窗口的单 IP 计数
	ipWinAt      time.Time
}

type hourKey struct {
	link uuid.UUID
	hour time.Time
}
type refKey struct {
	link uuid.UUID
	day  string
	host string
}
type dayKey struct {
	link uuid.UUID
	day  string
}
type hourAgg struct{ hits, bytes, blocked int64 }
type refAgg struct{ hits, bytes int64 }
type totalAgg struct {
	hits, bytes int64
	last        time.Time
}
type todayUsage struct {
	day         string
	base, delta int64
}
type monthUsage struct {
	month       string
	base, delta int64
	loaded      bool
}
type ipCount struct {
	n     int
	link  uuid.UUID
	owner uuid.UUID
}

const (
	maxSampledPerHour = 200
	maxIPsPerHour     = 10000
	maxIPWindow       = 50000
	ipAlertThreshold  = 600
	refAlertThreshold = 100
	spikeMinHits      = 300
	spikeFactor       = 10
)

func newLinkMeter(s *Links) *linkMeter {
	m := &linkMeter{s: s, today: map[uuid.UUID]*todayUsage{}, refHosts: map[dayKey]map[string]bool{}}
	m.reset()
	m.hourRefs, m.prevHourRefs, m.ipWin = map[uuid.UUID]map[string]int64{}, map[uuid.UUID]map[string]int64{}, map[string]*ipCount{}
	return m
}

func (m *linkMeter) reset() {
	m.hours = map[hourKey]*hourAgg{}
	m.refs = map[refKey]*refAgg{}
	m.hits = nil
	m.totals = map[uuid.UUID]*totalAgg{}
}

// HitInfo 一次访问的来访者信息。
type HitInfo struct {
	IP, UA, Referer, RefererHost string
}

// record 记一次访问。served=false 是被拦下的(白名单、限流、上限、暂停):只计次数不计流量。
func (m *linkMeter) record(link, owner uuid.UUID, h HitInfo, status int, bytes int64, served bool) {
	now := time.Now()
	hour := now.Truncate(time.Hour)
	day := m.s.dayStart(now).Format("2006-01-02")
	k := hourKey{link, hour}

	m.mu.Lock()
	defer m.mu.Unlock()
	a := m.hours[k]
	if a == nil {
		a = &hourAgg{}
		m.hours[k] = a
	}
	if served {
		a.hits++
		a.bytes += bytes
		t := m.totals[link]
		if t == nil {
			t = &totalAgg{}
			m.totals[link] = t
		}
		t.hits++
		t.bytes += bytes
		t.last = now
		if u := m.today[link]; u != nil && u.day == day {
			u.delta += bytes
		}
		if m.month.loaded {
			m.month.delta += bytes
		}
	} else {
		a.blocked++
	}
	if m.ips == nil || len(m.ips) > 0 && !m.ipsHour(hour) {
		m.ips = map[hourKey]map[string]struct{}{}
	}
	set := m.ips[k]
	if set == nil {
		set = map[string]struct{}{}
		m.ips[k] = set
	}
	if len(set) < maxIPsPerHour {
		set[h.IP] = struct{}{}
	}
	rk := refKey{link, day, h.RefererHost}
	r := m.refs[rk]
	if r == nil {
		r = &refAgg{}
		m.refs[rk] = r
	}
	r.hits++
	r.bytes += bytes
	if m.sampled == nil || !m.sampledHour(hour) {
		m.sampled = map[hourKey]int{}
	}
	if m.sampled[k] < maxSampledPerHour {
		m.sampled[k]++
		m.hits = append(m.hits, store.InsertLinkHitsParams{
			LinkID: link, At: tsz(now), Ip: h.IP, UserAgent: truncateRunes(h.UA, sessionMaxUA),
			Referer: truncateRunes(h.Referer, 512), Status: int32(status), Bytes: bytes,
		})
	}
	// 异常检测用的当前小时来源计数与 10 分钟单 IP 计数
	if !m.hourRefsAt.Equal(hour) {
		m.prevHourRefs, m.hourRefs, m.hourRefsAt = m.hourRefs, map[uuid.UUID]map[string]int64{}, hour
	}
	if h.RefererHost != "" {
		hr := m.hourRefs[link]
		if hr == nil {
			hr = map[string]int64{}
			m.hourRefs[link] = hr
		}
		hr[h.RefererHost]++
	}
	win := now.Truncate(10 * time.Minute)
	if !m.ipWinAt.Equal(win) {
		m.ipWin, m.ipWinAt = map[string]*ipCount{}, win
	}
	c := m.ipWin[h.IP]
	if c == nil && len(m.ipWin) < maxIPWindow {
		c = &ipCount{}
		m.ipWin[h.IP] = c
	}
	if c != nil {
		c.n++
		c.link, c.owner = link, owner
		if c.n == ipAlertThreshold {
			go m.s.alertIP(h.IP, link, owner)
		}
	}
}

// ips / sampled 只留当前小时的(上一小时的独立 IP 数在落库时已写好)。
func (m *linkMeter) ipsHour(hour time.Time) bool {
	for k := range m.ips {
		return k.hour.Equal(hour)
	}
	return true
}

func (m *linkMeter) sampledHour(hour time.Time) bool {
	for k := range m.sampled {
		return k.hour.Equal(hour)
	}
	return true
}

// todayBytes 某条直链今天已用的流量:第一次问时从库里读底数,之后加上进程内的增量。
func (m *linkMeter) todayBytes(ctx context.Context, link uuid.UUID) int64 {
	day := m.s.dayStart(time.Now())
	key := day.Format("2006-01-02")
	m.mu.Lock()
	u := m.today[link]
	if u != nil && u.day == key {
		v := u.base + u.delta
		m.mu.Unlock()
		return v
	}
	m.mu.Unlock()
	base, err := m.s.q.LinkBytesSince(ctx, store.LinkBytesSinceParams{LinkID: link, Hour: tsz(day)})
	if err != nil {
		slog.Warn("link today bytes", "err", err)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.today) > 10000 {
		clear(m.today)
	}
	m.today[link] = &todayUsage{day: key, base: base}
	return base
}

// loadMonth 全站本月用量的底数(启动后、跨月时读一次)。
func (m *linkMeter) loadMonth(ctx context.Context) {
	start := m.s.monthStart(time.Now())
	key := start.Format("2006-01")
	m.mu.Lock()
	ok := m.month.loaded && m.month.month == key
	m.mu.Unlock()
	if ok {
		return
	}
	u, err := m.s.q.SiteLinkUsageSince(ctx, tsz(start))
	if err != nil {
		slog.Warn("link month usage", "err", err)
		return
	}
	m.mu.Lock()
	m.month = monthUsage{month: key, base: u.Bytes, loaded: true}
	m.mu.Unlock()
}

func (m *linkMeter) monthBytes() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.month.base + m.month.delta
}

func (m *linkMeter) monthExceeded() bool {
	return m.monthBytes() >= m.s.cfg.MonthlyBytes
}

// flush 把攒下的计数一笔写进库。写失败就丢掉这一批(只是计数),不重试。
func (m *linkMeter) flush(ctx context.Context) {
	m.mu.Lock()
	hours, refs, hits, totals := m.hours, m.refs, m.hits, m.totals
	ipCounts := map[hourKey]int{}
	for k, set := range m.ips {
		ipCounts[k] = len(set)
	}
	m.reset()
	m.mu.Unlock()
	m.loadMonth(ctx)
	if len(hours) == 0 && len(refs) == 0 {
		return
	}
	tx, err := m.s.pool.Begin(ctx)
	if err != nil {
		slog.Warn("link meter flush", "err", err)
		return
	}
	defer tx.Rollback(ctx)
	q := m.s.q.WithTx(tx)
	for k, a := range hours {
		if err := q.UpsertLinkStat(ctx, store.UpsertLinkStatParams{
			LinkID: k.link, Hour: tsz(k.hour), Hits: a.hits, Bytes: a.bytes, Blocked: a.blocked, Ips: int32(ipCounts[k]),
		}); err != nil {
			slog.Warn("link meter flush stats", "err", err)
			return
		}
	}
	for k, r := range m.capReferers(ctx, refs) {
		day, _ := time.Parse("2006-01-02", k.day)
		if err := q.UpsertLinkReferer(ctx, store.UpsertLinkRefererParams{
			LinkID: k.link, Day: pgDate(day), Host: k.host, Hits: r.hits, Bytes: r.bytes,
		}); err != nil {
			slog.Warn("link meter flush referers", "err", err)
			return
		}
	}
	if len(hits) > 0 {
		if _, err := q.InsertLinkHits(ctx, hits); err != nil {
			slog.Warn("link meter flush hits", "err", err)
			return
		}
	}
	for id, t := range totals {
		if err := q.AddLinkTotals(ctx, store.AddLinkTotalsParams{ID: id, Hits: t.hits, Bytes: t.bytes, LastHitAt: tsz(t.last)}); err != nil {
			slog.Warn("link meter flush totals", "err", err)
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		slog.Warn("link meter flush commit", "err", err)
	}
}

// capReferers 每条直链每天最多记 50 个来源,多出来的并进 "*"。
func (m *linkMeter) capReferers(ctx context.Context, refs map[refKey]*refAgg) map[refKey]*refAgg {
	out := make(map[refKey]*refAgg, len(refs))
	keys := make([]refKey, 0, len(refs))
	for k := range refs {
		keys = append(keys, k)
	}
	// 次数多的先占名额
	sort.Slice(keys, func(i, j int) bool { return refs[keys[i]].hits > refs[keys[j]].hits })
	for _, k := range keys {
		dk := dayKey{k.link, k.day}
		known := m.refHosts[dk]
		if known == nil {
			known = map[string]bool{}
			day, _ := time.Parse("2006-01-02", k.day)
			if hosts, err := m.s.q.ListRefererHostsForDay(ctx, store.ListRefererHostsForDayParams{LinkID: k.link, Day: pgDate(day)}); err == nil {
				for _, h := range hosts {
					known[h] = true
				}
			}
			if len(m.refHosts) > 20000 {
				clear(m.refHosts)
			}
			m.refHosts[dk] = known
		}
		host := k.host
		if !known[host] {
			if len(known) >= maxRefererHosts && host != "" {
				host = "*"
			} else {
				known[host] = true
			}
		}
		ok := refKey{k.link, k.day, host}
		a := out[ok]
		if a == nil {
			a = &refAgg{}
			out[ok] = a
		}
		a.hits += refs[k].hits
		a.bytes += refs[k].bytes
	}
	return out
}

// snapshotRefs 当前与上一小时各来源的次数(异常检测)。
func (m *linkMeter) snapshotRefs() map[uuid.UUID]map[string]int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := map[uuid.UUID]map[string]int64{}
	for _, src := range []map[uuid.UUID]map[string]int64{m.prevHourRefs, m.hourRefs} {
		for link, hosts := range src {
			dst := out[link]
			if dst == nil {
				dst = map[string]int64{}
				out[link] = dst
			}
			for h, n := range hosts {
				dst[h] = max(dst[h], n) // 按单个小时算,不叠加
			}
		}
	}
	return out
}

// ---------- 异常检测(每 5 分钟) ----------

// Check 突增、陌生来源、本月上限,以及把超日上限暂停的恢复过来。
func (s *Links) Check(ctx context.Context) {
	s.meter.flush(ctx)
	if toks, err := s.q.ResumeDailyCapLinks(ctx, tsz(s.dayStart(time.Now()))); err != nil {
		slog.Warn("resume daily cap links", "err", err)
	} else {
		for _, t := range toks {
			s.forget(t)
		}
	}
	s.checkSpikes(ctx)
	s.checkReferers(ctx)
	s.checkMonth(ctx)
}

func (s *Links) checkSpikes(ctx context.Context) {
	now := time.Now()
	hour := now.Truncate(time.Hour)
	rows, err := s.q.LinkRecentHours(ctx, tsz(hour.Add(-time.Hour)))
	if err != nil {
		slog.Warn("link spikes", "err", err)
		return
	}
	// 最近 60 分钟 ≈ 当前小时 + 上一小时按还没过去的比例折算
	frac := now.Sub(hour).Minutes() / 60
	recent := map[uuid.UUID]float64{}
	for _, r := range rows {
		if r.Hour.Time.Equal(hour) {
			recent[r.LinkID] += float64(r.Hits)
		} else {
			recent[r.LinkID] += float64(r.Hits) * (1 - frac)
		}
	}
	var ids []uuid.UUID
	for id, n := range recent {
		if n > spikeMinHits {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		return
	}
	base, err := s.q.LinkHitsBetween(ctx, store.LinkHitsBetweenParams{
		Ids: ids, Since: tsz(hour.Add(-7 * 24 * time.Hour)), Until: tsz(hour.Add(-time.Hour)),
	})
	if err != nil {
		slog.Warn("link spikes baseline", "err", err)
		return
	}
	avg := map[uuid.UUID]float64{}
	for _, b := range base {
		avg[b.LinkID] = float64(b.Hits) / (7*24 - 1)
	}
	for _, id := range ids {
		n := recent[id]
		if n <= max(spikeMinHits, spikeFactor*avg[id]) {
			continue
		}
		info, err := s.q.GetLinkOwnerAndName(ctx, id)
		if err != nil {
			continue
		}
		body := fmt.Sprintf("最近一小时约 %d 次(平时约 %d 次 / 小时)。", int64(n), int64(avg[id]+0.5))
		if r, err := s.q.LinkTopRefererForDay(ctx, store.LinkTopRefererForDayParams{LinkID: id, Day: pgDate(s.dayStart(now))}); err == nil {
			body += "主要来源 " + r.Host + "。"
		}
		if ip, err := s.q.LinkTopIPSince(ctx, store.LinkTopIPSinceParams{LinkID: id, At: tsz(now.Add(-time.Hour))}); err == nil {
			body += fmt.Sprintf("访问最多的 IP %s(%d 次)。", ip.Ip, ip.Hits)
		}
		level := LvWarn
		if info.AutoPause {
			if n, _ := s.q.AutoPauseLink(ctx, store.AutoPauseLinkParams{ID: id, PauseReason: strPtrOrNil("anomaly")}); n > 0 {
				s.forget(info.Token)
				body += "已按设置自动暂停。"
				level = LvAlert
			}
		}
		s.notify.Notify(ctx, info.OwnerID, Event{
			Kind: EvLinkAlert, Level: level, Title: "直链访问突增:" + info.NodeName, Body: body,
			Target: &Target{Kind: TgLink, ID: id.String()}, GroupKey: "link_spike:" + id.String(), Once: 6 * time.Hour,
		})
	}
}

func (s *Links) checkReferers(ctx context.Context) {
	own := hostOf(s.cfg.PublicURL)
	for link, hosts := range s.meter.snapshotRefs() {
		for host, n := range hosts {
			if n <= refAlertThreshold || host == own || host == "*" {
				continue
			}
			row, err := s.q.GetLinkRow(ctx, link)
			if err != nil {
				continue
			}
			prefs, err := s.Prefs(ctx, row.Link.OwnerID)
			if err != nil {
				continue
			}
			allow := row.Link.RefererHosts
			if allow == nil {
				allow = prefs.RefererHosts
			}
			if len(allow) > 0 && hostAllowed(host, allow) {
				continue // 白名单里的站点,本来就是给它用的
			}
			s.notify.Notify(ctx, row.Link.OwnerID, Event{
				Kind: EvLinkAlert, Level: LvWarn, Title: "有站点在大量引用你的直链:" + row.NodeName,
				Body:   fmt.Sprintf("%s 一小时内引用了 %d 次。不认识的话可以在直链设置里加来源白名单,或者暂停。", host, n),
				Target: &Target{Kind: TgLink, ID: link.String()}, GroupKey: "link_ref:" + link.String() + ":" + host, Once: 30 * 24 * time.Hour,
			})
		}
	}
}

func (s *Links) checkMonth(ctx context.Context) {
	used, limit := s.meter.monthBytes(), s.cfg.MonthlyBytes
	month := s.monthStart(time.Now()).Format("2006-01")
	switch {
	case used >= limit:
		s.notify.NotifyAdmins(ctx, Event{
			Kind: EvLinkAlert, Level: LvAlert, Title: "本月直链流量已用完",
			Body:   fmt.Sprintf("本月已用 %s,上限 %s(GOPAN_LINK_MONTHLY_BYTES)。所有直链停到月底,调高上限后立即恢复。", humanBytes(used), humanBytes(limit)),
			Target: &Target{Kind: TgAdmin}, GroupKey: "link_month100:" + month, Once: 40 * 24 * time.Hour,
		})
	case used >= limit/10*8:
		s.notify.NotifyAdmins(ctx, Event{
			Kind: EvLinkAlert, Level: LvWarn, Title: "本月直链流量已用 80%",
			Body:   fmt.Sprintf("本月已用 %s,上限 %s。", humanBytes(used), humanBytes(limit)),
			Target: &Target{Kind: TgAdmin}, GroupKey: "link_month80:" + month, Once: 40 * 24 * time.Hour,
		})
	}
}

func (s *Links) alertIP(ip string, link, owner uuid.UUID) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	name := ""
	if info, err := s.q.GetLinkOwnerAndName(ctx, link); err == nil {
		name = ":" + info.NodeName
	}
	s.notify.Notify(ctx, owner, Event{
		Kind: EvLinkAlert, Level: LvWarn, Title: "有 IP 在狂刷直链" + name,
		Body:   fmt.Sprintf("%s 十分钟内请求了 %d 次以上,已按每分钟 120 次限流。", ip, ipAlertThreshold),
		Target: &Target{Kind: TgLink, ID: link.String()}, GroupKey: "link_ip:" + ip, Once: 24 * time.Hour,
	})
}
