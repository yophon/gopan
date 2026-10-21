/** 直链的展示口径与复制格式(doc/22 四)。纯函数,单测直接引。 */

export type LinkFormat = 'url' | 'markdown' | 'html' | 'bbcode'

export const linkFormatLabels: Record<LinkFormat, string> = {
  url: '链接',
  markdown: 'Markdown',
  html: 'HTML',
  bbcode: 'BBCode',
}

/** DirectLink.url 在服务端没配站点地址时等于 path(/x/…),前面补上当前站点 */
export function absoluteLinkUrl(url: string, origin: string): string {
  return url.startsWith('/') ? `${origin.replace(/\/+$/, '')}${url}` : url
}

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'bmp', 'svg', 'ico']

export function isImageFile(name: string, mime?: string | null): boolean {
  if (mime) return mime.toLowerCase().startsWith('image/')
  const ext = name.toLowerCase().split('.').pop() ?? ''
  return IMAGE_EXTS.includes(ext)
}

export interface LinkLike {
  url: string
  name: string
  mime?: string | null
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** Markdown 里地址带空格、括号会断,转义掉;文字里的方括号加反斜杠 */
function mdUrl(url: string): string {
  return url.replace(/ /g, '%20').replace(/\(/g, '%28').replace(/\)/g, '%29')
}

function mdText(s: string): string {
  return s.replace(/([[\]\\])/g, '\\$1')
}

export function formatLink(link: LinkLike, fmt: LinkFormat, origin: string): string {
  const url = absoluteLinkUrl(link.url, origin)
  const image = isImageFile(link.name, link.mime)
  switch (fmt) {
    case 'url':
      return url
    case 'markdown':
      return image ? `![${mdText(link.name)}](${mdUrl(url)})` : `[${mdText(link.name)}](${mdUrl(url)})`
    case 'html':
      return image
        ? `<img src="${escapeHtml(url)}" alt="${escapeHtml(link.name)}">`
        : `<a href="${escapeHtml(url)}">${escapeHtml(link.name)}</a>`
    case 'bbcode':
      return image ? `[img]${url}[/img]` : `[url=${url}]${link.name}[/url]`
  }
}

/** 多选复制:一行一条 */
export function formatLinks(links: LinkLike[], fmt: LinkFormat, origin: string): string {
  return links.map((l) => formatLink(l, fmt, origin)).join('\n')
}

/** 图片直链的缩略图:?s=256 / ?s=2048 */
export function thumbLinkUrl(url: string, origin: string, size: 256 | 2048 = 256): string {
  const abs = absoluteLinkUrl(url, origin)
  return `${abs}${abs.includes('?') ? '&' : '?'}s=${size}`
}

// ---------- 来源白名单 ----------

/**
 * 用户随手粘的来源 → 主机名:去掉协议、账号、路径、端口,转小写;
 * 「*.example.com」「.example.com」按 example.com 算(本来就按主域名匹配)。认不出返回空串。
 */
export function normalizeHost(input: string): string {
  let s = input.trim().toLowerCase()
  if (!s) return ''
  s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
  s = s.replace(/^\/\//, '')
  s = s.split(/[/?#]/)[0]
  s = s.replace(/^[^@]*@/, '')
  if (s.startsWith('[')) {
    const end = s.indexOf(']')
    return end > 0 ? s.slice(1, end) : ''
  }
  s = s.replace(/:\d*$/, '')
  s = s.replace(/^\*\./, '').replace(/^\.+/, '').replace(/\.+$/, '')
  // 中文域名也认(浏览器发来的 Referer 是 punycode,这里不转换,只挡掉明显不是主机名的)
  if (!s || !/^[\p{L}\p{N}.-]+$/u.test(s)) return ''
  return s
}

/** 一行一个(也认逗号、空格分隔),规整、去重、保持顺序 */
export function parseHostList(text: string): string[] {
  const out: string[] = []
  for (const part of text.split(/[\s,,;;]+/)) {
    const h = normalizeHost(part)
    if (h && !out.includes(h)) out.push(h)
  }
  return out
}

/** 来源排行里的站点名:空串是直接访问,「*」是超出 50 个之外合起来的 */
export function refererLabel(host: string): string {
  if (host === '') return '直接访问'
  if (host === '*') return '其他'
  return host
}

// ---------- 状态 ----------

export type LinkStatusValue = 'ACTIVE' | 'PAUSED' | 'EXPIRED' | 'UNAVAILABLE'

const PAUSE_REASON: Record<string, string> = {
  manual: '手动暂停',
  daily_cap: '超了今天的流量上限,次日恢复',
  anomaly: '检测到异常,自动暂停',
}

export function linkStatusMeta(
  status: LinkStatusValue,
  pauseReason?: string | null,
): { label: string; type: 'success' | 'warning' | 'info' | 'danger'; detail: string } {
  switch (status) {
    case 'ACTIVE':
      return { label: '正常', type: 'success', detail: '' }
    case 'PAUSED':
      return {
        label: '已暂停',
        type: 'warning',
        detail: pauseReason ? (PAUSE_REASON[pauseReason] ?? pauseReason) : '',
      }
    case 'EXPIRED':
      return { label: '已过期', type: 'info', detail: '过了有效期' }
    case 'UNAVAILABLE':
      return { label: '不可用', type: 'danger', detail: '文件在回收站、本月全站直链流量用完或直链被关闭' }
  }
}

// ---------- 统计 ----------

export const GiB = 1024 ** 3

export function bytesToGiB(bytes: number): number {
  return Math.round((bytes / GiB) * 100) / 100
}

export function giBToBytes(gib: number): number {
  return Math.round(gib * GiB)
}

export interface HourStatLike {
  hour: string
  hits: number
  bytes: number
  blocked: number
}

export interface HourBucket {
  /** 整点的毫秒时间戳 */
  at: number
  hits: number
  bytes: number
  blocked: number
}

/** 服务端只给有访问的小时;补齐成最近 hours 个整点(含当前这一小时),旧的在前 */
export function fillHourBuckets(stats: HourStatLike[], hours: number, now: number = Date.now()): HourBucket[] {
  const HOUR = 3_600_000
  const end = Math.floor(now / HOUR) * HOUR
  const byHour = new Map<number, HourStatLike>()
  for (const s of stats) {
    const t = Date.parse(s.hour)
    if (!Number.isNaN(t)) byHour.set(Math.floor(t / HOUR) * HOUR, s)
  }
  const out: HourBucket[] = []
  for (let i = hours - 1; i >= 0; i--) {
    const at = end - i * HOUR
    const s = byHour.get(at)
    out.push({ at, hits: s?.hits ?? 0, bytes: s?.bytes ?? 0, blocked: s?.blocked ?? 0 })
  }
  return out
}

/** 访问明细里的来源:完整 Referer → 主机名;没有来源是「直接访问」 */
export function refererHost(referer: string): string {
  return refererLabel(referer ? normalizeHost(referer) || referer : '')
}

/** 访问明细的状态码 → 标签颜色 */
export function hitStatusType(status: number): 'success' | 'info' | 'warning' | 'danger' {
  if (status >= 500) return 'danger'
  if (status >= 400) return 'warning'
  if (status >= 300) return 'info'
  return 'success'
}

// ---------- 单条直链的设置表单 ----------

export type TriState = 'default' | 'allow' | 'deny'

export interface LinkSettingsForm {
  /** 来源白名单用默认规则 */
  useDefaultHosts: boolean
  /** 自定义白名单,一行一个;留空 = 不限来源 */
  hostsText: string
  noReferer: TriState
  capMode: 'default' | 'custom'
  capGiB: number
  expires: 'never' | 'at'
  expiresAt: Date | null
  autoPause: boolean
}

export interface LinkSettingsSource {
  refererHosts?: string[] | null
  allowNoReferer?: boolean | null
  dailyBytesCap?: number | null
  expiresAt?: string | null
  autoPause: boolean
}

export function linkSettingsFrom(l: LinkSettingsSource, defaultCapBytes: number): LinkSettingsForm {
  const expires = l.expiresAt ? new Date(l.expiresAt) : null
  return {
    useDefaultHosts: l.refererHosts === null || l.refererHosts === undefined,
    hostsText: (l.refererHosts ?? []).join('\n'),
    noReferer: l.allowNoReferer === null || l.allowNoReferer === undefined ? 'default' : l.allowNoReferer ? 'allow' : 'deny',
    capMode: l.dailyBytesCap ? 'custom' : 'default',
    capGiB: bytesToGiB(l.dailyBytesCap || defaultCapBytes),
    expires: expires && !Number.isNaN(expires.getTime()) ? 'at' : 'never',
    expiresAt: expires && !Number.isNaN(expires.getTime()) ? expires : null,
    autoPause: l.autoPause,
  }
}

export interface LinkSettingsInput {
  refererHosts: string[] | null
  allowNoReferer: boolean | null
  dailyBytesCap: number | null
  expiresAt: string | null
  autoPause: boolean
}

/** 表单 → DirectLinkInput(整表单提交,null = 用默认 / 永久) */
export function linkSettingsInput(f: LinkSettingsForm): LinkSettingsInput {
  return {
    refererHosts: f.useDefaultHosts ? null : parseHostList(f.hostsText),
    allowNoReferer: f.noReferer === 'default' ? null : f.noReferer === 'allow',
    dailyBytesCap: f.capMode === 'custom' ? giBToBytes(f.capGiB) : null,
    expiresAt: f.expires === 'at' && f.expiresAt ? f.expiresAt.toISOString() : null,
    autoPause: f.autoPause,
  }
}

export function validateLinkSettings(f: LinkSettingsForm, now: number = Date.now()): string | null {
  if (f.capMode === 'custom' && !(f.capGiB > 0)) return '每天的流量上限要大于 0'
  if (f.expires === 'at') {
    if (!f.expiresAt) return '请选择到期时间'
    if (f.expiresAt.getTime() <= now) return '到期时间要在现在之后'
  }
  return null
}
