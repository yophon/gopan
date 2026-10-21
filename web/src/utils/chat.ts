import type { ChatMessagesQuery } from '@/api/gen/graphql'

export type ChatMsg = ChatMessagesQuery['chatMessages'][number]

/** 乐观上屏的本地消息:还没拿到服务端 id,status 决定气泡样式与可用操作 */
export interface PendingMsg {
  id: string
  body: string
  createdAt: string
  status: 'sending' | 'failed'
}

export const PENDING_PREFIX = 'pending-'

export function isPendingId(id: string): boolean {
  return id.startsWith(PENDING_PREFIX)
}

/**
 * 合并轮询增量与本地消息列表:按 id 去重,按 createdAt 升序(服务端 uuid v7
 * 时间有序,createdAt 与 id 序一致;用 createdAt 排序避免字符串 id 比较的误解)。
 * 输入顺序不作假设,重复 id 保留先出现的副本。
 */
export function mergeChatMessages(existing: ChatMsg[], incoming: ChatMsg[]): ChatMsg[] {
  const seen = new Set<string>()
  const out: ChatMsg[] = []
  for (const batch of [existing, incoming]) {
    for (const m of batch) {
      if (seen.has(m.id)) continue
      seen.add(m.id)
      out.push(m)
    }
  }
  out.sort((a, b) => (a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : 0))
  return out
}

/** 从列表里拿掉一条(删除成功后的本地即时反馈);不存在则原样返回 */
export function removeChatMessage(list: ChatMsg[], id: string): ChatMsg[] {
  const i = list.findIndex((m) => m.id === id)
  if (i < 0) return list
  return [...list.slice(0, i), ...list.slice(i + 1)]
}

/** 把 pending 消息转成与服务端消息同构的渲染项(node 恒为 null),尾随在正式消息之后 */
export function pendingToChatMsg(p: PendingMsg): ChatMsg {
  return { id: p.id, body: p.body, createdAt: p.createdAt, source: 'web', node: null }
}

// ---------- 来源标记 ----------

/** 非浏览器写入的消息在行尾标一下;web 不标(绝大多数) */
export function sourceLabel(source: string): string {
  switch (source) {
    case 'mcp':
      return 'Agent'
    case 'webdav':
      return 'WebDAV'
    case 'fetch':
      return '离线下载'
    default:
      return ''
  }
}

// ---------- 图片 / 视频连发合并成一格 ----------

/** 有缩略图的图片 / 视频消息(聊天里直接显示图) */
export function isMediaMsg(m: ChatMsg): boolean {
  const n = m.node
  if (!n) return false
  const k = n.preview?.kind
  return (k === 'IMAGE' || k === 'VIDEO') && !!n.preview?.thumbUrl
}

export type RenderItem =
  | { kind: 'single'; message: ChatMsg }
  | { kind: 'media'; id: string; messages: ChatMsg[] }

export const MEDIA_GROUP_WINDOW_MS = 2 * 60 * 1000
export const MEDIA_GROUP_MAX = 9

/**
 * 服务端每个文件一条消息(上传完成即落),一次发 5 张图就是 5 条。展示层把
 * 连续的图片/视频消息(前后相隔 ≤ 2 分钟、中间没有别的消息、pending 不算)
 * 合成一个九宫格项;满 9 个另起一组;只有一条的不合并,仍按单条渲染。
 * 输入须已升序。
 */
export function groupMedia(items: ChatMsg[]): RenderItem[] {
  const out: RenderItem[] = []
  let run: ChatMsg[] = []
  const flush = () => {
    if (run.length >= 2) {
      out.push({ kind: 'media', id: run[0]!.id, messages: run })
    } else if (run.length === 1) {
      out.push({ kind: 'single', message: run[0]! })
    }
    run = []
  }
  for (const m of items) {
    const groupable = isMediaMsg(m) && !isPendingId(m.id)
    if (!groupable) {
      flush()
      out.push({ kind: 'single', message: m })
      continue
    }
    const prev = run[run.length - 1]
    if (prev) {
      const gap = new Date(m.createdAt).getTime() - new Date(prev.createdAt).getTime()
      if (!(gap >= 0 && gap <= MEDIA_GROUP_WINDOW_MS) || run.length >= MEDIA_GROUP_MAX) flush()
    }
    run.push(m)
  }
  flush()
  return out
}

// ---------- 日期分隔 ----------

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 本地日历日键 YYYY-MM-DD */
export function dayKey(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 今天 / 昨天 / YYYY-MM-DD(同年省略年份:MM-DD) */
export function dayLabel(iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const key = dayKey(d)
  if (key === dayKey(now)) return '今天'
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (key === dayKey(yesterday)) return '昨天'
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`
  return key
}

export interface DayGroup<T> {
  key: string
  label: string
  items: T[]
}

/** 按本地日历日分组,输入须已升序;组顺序与输入一致 */
export function groupByDay<T extends { createdAt: string }>(
  items: T[],
  now: Date = new Date(),
): DayGroup<T>[] {
  const groups: DayGroup<T>[] = []
  for (const m of items) {
    const d = new Date(m.createdAt)
    const key = Number.isNaN(d.getTime()) ? 'invalid' : dayKey(d)
    const last = groups[groups.length - 1]
    if (last && last.key === key) {
      last.items.push(m)
    } else {
      groups.push({ key, label: dayLabel(m.createdAt, now), items: [m] })
    }
  }
  return groups
}

// ---------- 正文渲染:转义 + 链接 + 折叠 ----------

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

// 只认 http(s) 链接,末尾的中英文标点不算进 URL(常见于句末)
const URL_RE = /https?:\/\/[^\s<>"'）)\]】》，。！？；：、]+/g

/**
 * 纯文本 → 安全 HTML:先整体转义,再把 URL 包成 <a>。
 * 因为先转义,URL 里不会出现 < > " ',正则在转义后的文本上跑也安全;
 * &amp; 会出现在查询串里,href 用它浏览器照样解析。
 */
export function linkify(text: string): string {
  const escaped = escapeHtml(text)
  return escaped.replace(
    URL_RE,
    (url) => `<a href="${url}" target="_blank" rel="noopener noreferrer">${url}</a>`,
  )
}

export const COLLAPSE_LINES = 8

/** 超过 maxLines 行需要折叠 */
export function isCollapsible(text: string, maxLines = COLLAPSE_LINES): boolean {
  let lines = 1
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) === 10 && ++lines > maxLines) return true
  }
  return false
}

/** 取前 maxLines 行(折叠态显示) */
export function collapseText(text: string, maxLines = COLLAPSE_LINES): string {
  const lines = text.split('\n')
  if (lines.length <= maxLines) return text
  return lines.slice(0, maxLines).join('\n')
}

// ---------- 文件命名 ----------

function stamp(now: Date): string {
  return `${dayKey(now)} ${pad(now.getHours())}-${pad(now.getMinutes())}`
}

/**
 * 粘贴进来的文件常常叫 image.png / blob,给图片一个带时间的名字,
 * 其他文件名原样保留。
 */
export function pastedFileName(file: File, now: Date = new Date()): string {
  const generic = !file.name || /^(image|blob|unnamed|untitled|clipboard)?(\.\w+)?$/i.test(file.name)
  if (!generic) return file.name
  const ext = file.type.startsWith('image/')
    ? file.type.slice('image/'.length).replace('jpeg', 'jpg').replace(/[^a-z0-9]/gi, '') || 'png'
    : file.name.split('.').pop() || 'bin'
  return `${file.type.startsWith('image/') ? '截图' : '文件'} ${stamp(now)}.${ext}`
}

/** 超长文本转存的 .txt 文件名 */
export function textFileName(now: Date = new Date()): string {
  return `文本 ${stamp(now)}.txt`
}

/** 服务端对文本消息的上限(字节);超过就转成 .txt 文件发 */
export const MAX_TEXT_BYTES = 4000

export function textByteLength(s: string): number {
  return new TextEncoder().encode(s).length
}

// ---------- Web Share Target ----------

/** 系统分享菜单(GET share_target)带进来的 title/text/url 拼成草稿 */
export function sharePrefill(query: Record<string, unknown>): string {
  const pick = (k: string): string => {
    const v = query[k]
    const s = Array.isArray(v) ? v[0] : v
    return typeof s === 'string' ? s.trim() : ''
  }
  const parts = [pick('title'), pick('text'), pick('url')].filter((s) => s.length > 0)
  // text 里常常已经包含 url(Android Chrome 分享网页时),去重
  const uniq = parts.filter((p, i) => parts.findIndex((q) => q.includes(p)) === i)
  return uniq.join('\n')
}

// ---------- 上传任务进度(与 UploadDrawer 同口径) ----------

export function uploadPercentage(task: {
  status: string
  size: number
  hashedBytes: number
  uploadedBytes: number
}): number {
  if (task.status === 'done') return 100
  if (task.size === 0) return 0
  if (task.status === 'hashing') return Math.min(100, Math.floor((task.hashedBytes / task.size) * 100))
  return Math.min(100, Math.floor((task.uploadedBytes / task.size) * 100))
}
