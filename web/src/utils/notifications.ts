/** 通知列表的展示口径与跳转(doc/22 三)。纯函数,单测直接引。 */

export type NotificationTargetKindValue =
  | 'NODE'
  | 'FILE_REQUEST'
  | 'LINK'
  | 'JOBS'
  | 'SESSIONS'
  | 'CREDENTIALS'
  | 'ADMIN'
  | 'ACTIVITY'
  | 'FEED'
  | 'ASSISTANT'

export interface TargetLike {
  kind: NotificationTargetKindValue
  id?: string | null
  parentId?: string | null
}

export interface RouteTarget {
  path: string
  query?: Record<string, string>
}

/**
 * 点开通知跳到哪。推送消息里的链接(ntfy / Bark / Telegram 在浏览器里打开)也是这几个地址,
 * 页面加载时同样要认:/drive?open=、/drive?panel=、/shares?tab=&id=、/activity?actor=&since=、/feeds?id=。
 * CREDENTIALS 的 id 是凭据类型:webdav / mcp / img。
 * ACTIVITY(大量删除提醒,doc/23 二.3)的 id 是来源(kind:ref),parentId 是从什么时候起(RFC 3339)。
 */
export function notificationRoute(t: TargetLike | null | undefined): RouteTarget | null {
  if (!t) return null
  switch (t.kind) {
    case 'NODE':
      if (!t.id) return null
      return { path: t.parentId ? `/drive/${t.parentId}` : '/drive', query: { open: t.id } }
    case 'FILE_REQUEST':
      return { path: '/shares', query: t.id ? { tab: 'requests', id: t.id } : { tab: 'requests' } }
    case 'LINK':
      return { path: '/shares', query: t.id ? { tab: 'links', id: t.id } : { tab: 'links' } }
    case 'JOBS':
      return { path: '/drive', query: { panel: 'jobs' } }
    case 'SESSIONS':
      return { path: '/devices' }
    case 'CREDENTIALS':
      if (t.id === 'webdav') return { path: '/drive', query: { panel: 'webdav' } }
      if (t.id === 'img') return { path: '/shares', query: { tab: 'links', panel: 'keys' } }
      return { path: '/drive', query: { panel: 'mcp' } }
    case 'ADMIN':
      return { path: '/admin' }
    case 'ACTIVITY': {
      const query: Record<string, string> = {}
      if (t.id) query.actor = t.id
      if (t.parentId) query.since = t.parentId
      return Object.keys(query).length ? { path: '/activity', query } : { path: '/activity' }
    }
    case 'FEED':
      return t.id ? { path: '/feeds', query: { id: t.id } } : { path: '/feeds' }
    case 'ASSISTANT':
      // 服务器上跑的助手等确认 / 做完了(doc/34):打开助手面板里的这个对话
      return t.id ? { path: '/drive', query: { assistant: t.id } } : { path: '/drive' }
    default:
      return null
  }
}

/** AppShell 认的全局面板(?panel=);其余(如 keys)留给页面自己处理 */
export const SHELL_PANELS = ['jobs', 'webdav', 'mcp'] as const
export type ShellPanel = (typeof SHELL_PANELS)[number]

export function shellPanel(raw: unknown): ShellPanel | null {
  return SHELL_PANELS.includes(raw as ShellPanel) ? (raw as ShellPanel) : null
}

/** 去掉查询串里的若干键(动作做完后清掉,刷新页面不会再弹一次) */
export function withoutQuery(
  query: Record<string, unknown>,
  keys: string[],
): Record<string, string | (string | null)[] | null> {
  const out: Record<string, string | (string | null)[] | null> = {}
  for (const [k, v] of Object.entries(query)) {
    if (keys.includes(k)) continue
    out[k] = v as string | (string | null)[] | null
  }
  return out
}

// ---------- 按天分组 ----------

export interface DayGroup<T> {
  key: string
  label: string
  items: T[]
}

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/** 今天 / 昨天 / 10月18日(跨年带年份) */
export function dayLabel(d: Date, now: Date): string {
  if (dayKey(d) === dayKey(now)) return '今天'
  const y = new Date(now)
  y.setDate(y.getDate() - 1)
  if (dayKey(d) === dayKey(y)) return '昨天'
  const md = `${d.getMonth() + 1}月${d.getDate()}日`
  return d.getFullYear() === now.getFullYear() ? md : `${d.getFullYear()}年${md}`
}

/** 按 at(本地日期)分组;组的先后按第一次出现,组内保持原顺序(服务端新的在前) */
export function groupByDay<T>(items: T[], at: (item: T) => string, now: Date = new Date()): DayGroup<T>[] {
  const groups = new Map<string, DayGroup<T>>()
  for (const item of items) {
    const d = new Date(at(item))
    const valid = !Number.isNaN(d.getTime())
    const key = valid ? dayKey(d) : 'unknown'
    let g = groups.get(key)
    if (!g) {
      g = { key, label: valid ? dayLabel(d, now) : '更早', items: [] }
      groups.set(key, g)
    }
    g.items.push(item)
  }
  return [...groups.values()]
}

// ---------- 级别 ----------

export type NotificationLevelValue = 'INFO' | 'WARN' | 'ALERT'

export const levelMeta: Record<NotificationLevelValue, { label: string; type: 'info' | 'warning' | 'danger' }> = {
  INFO: { label: '提示', type: 'info' },
  WARN: { label: '注意', type: 'warning' },
  ALERT: { label: '严重', type: 'danger' },
}

/** 角标:超过 99 显示 99+ */
export function badgeText(n: number): string {
  if (!(n > 0)) return ''
  return n > 99 ? '99+' : String(n)
}
