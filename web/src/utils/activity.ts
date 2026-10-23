import { formatBytes } from '@/utils/format'

/** 动态与撤回(doc/23 二)的展示口径。纯函数,单测直接引。 */

export type ActorKindValue =
  | 'WEB'
  | 'ANDROID'
  | 'WEBDAV'
  | 'AGENT'
  | 'IMG'
  | 'COLLECT'
  | 'JOB'
  | 'FEED'
  | 'CLIP'
  | 'SYSTEM'

export type ActivityActionValue =
  | 'MKDIR'
  | 'UPLOAD'
  | 'OVERWRITE'
  | 'RENAME'
  | 'MOVE'
  | 'COPY'
  | 'DELETE'
  | 'RESTORE'
  | 'PURGE'
  | 'EXTRACT'

export interface ActorLike {
  kind: ActorKindValue
  ref: string
  label: string
}

export interface ActivityLike {
  id: string
  at: string
  actor: ActorLike
  batchId: string
  action: ActivityActionValue
  nodeKind?: 'FILE' | 'FOLDER' | null
  name: string
  parentPath: string
  fromPath?: string | null
  toPath?: string | null
  oldName?: string | null
  newName?: string | null
  files: number
  bytes: number
  undoOf?: string | null
  undoneAt?: string | null
  undoable: boolean
}

export const ACTOR_KINDS: ActorKindValue[] = [
  'WEB',
  'ANDROID',
  'WEBDAV',
  'AGENT',
  'IMG',
  'COLLECT',
  'JOB',
  'FEED',
  'CLIP',
  'SYSTEM',
]

/** 来源类型的小标签 */
export const actorKindLabel: Record<ActorKindValue, string> = {
  WEB: '网页',
  ANDROID: '安卓 App',
  WEBDAV: 'WebDAV',
  AGENT: 'Agent',
  IMG: '图床',
  COLLECT: '收集链接',
  JOB: '后台任务',
  FEED: '订阅',
  CLIP: '剪藏',
  SYSTEM: '系统',
}

export const ACTIVITY_ACTIONS: ActivityActionValue[] = [
  'MKDIR',
  'UPLOAD',
  'OVERWRITE',
  'RENAME',
  'MOVE',
  'COPY',
  'DELETE',
  'RESTORE',
  'PURGE',
  'EXTRACT',
]

/** 单条记录上的动词 */
export const actionVerb: Record<ActivityActionValue, string> = {
  MKDIR: '新建文件夹',
  UPLOAD: '上传',
  OVERWRITE: '覆盖',
  RENAME: '改名',
  MOVE: '移动',
  COPY: '复制',
  DELETE: '删除',
  RESTORE: '还原',
  PURGE: '彻底删除',
  EXTRACT: '解压',
}

/** 一组的一句话里用的动词(「新建了 3 个文件夹」,不是「新建文件夹了」) */
const groupVerb: Record<ActivityActionValue, string> = { ...actionVerb, MKDIR: '新建' }

/** 来源显示名:快照里没名字(系统、旧记录)就用类型名 */
export function actorName(actor: ActorLike): string {
  return actor.label.trim() || actorKindLabel[actor.kind] || actor.kind
}

/** 位置一行:移动是「从 → 到」,改名是「旧名 → 新名」,其余是所在文件夹 */
export function activityPlace(a: ActivityLike): string {
  if (a.action === 'MOVE' && (a.fromPath || a.toPath)) return `${a.fromPath ?? '?'} → ${a.toPath ?? a.parentPath}`
  if (a.action === 'RENAME' && (a.oldName || a.newName)) return `${a.oldName ?? '?'} → ${a.newName ?? a.name}`
  return a.parentPath || '/'
}

/** 涉及多个文件(删文件夹、复制、解压)时的「37 个文件 · 1.2 GB」;单个文件不显示 */
export function activitySize(a: { files: number; bytes: number }): string {
  if (a.files <= 1) return ''
  return a.bytes > 0 ? `${a.files} 个文件 · ${formatBytes(a.bytes)}` : `${a.files} 个文件`
}

/** 撤回按钮灰掉时的原因 */
export function notUndoableReason(a: ActivityLike): string {
  if (a.undoneAt) return '已经撤回过了'
  if (a.action === 'OVERWRITE') return '覆盖前的内容没有保留,撤不回'
  if (a.action === 'PURGE') return '彻底删除撤不回'
  return '这条撤不了'
}

// ---------- 筛选 ----------

/** 地址里的来源 ?actor=webdav:<ref>(与服务端通知链接一致)。ref 自己可能带冒号,按第一个拆 */
export function parseActorParam(raw: unknown): { actorKind: ActorKindValue; actorRef: string } | null {
  if (typeof raw !== 'string' || !raw) return null
  const i = raw.indexOf(':')
  const kind = (i < 0 ? raw : raw.slice(0, i)).toUpperCase() as ActorKindValue
  if (!ACTOR_KINDS.includes(kind)) return null
  return { actorKind: kind, actorRef: i < 0 ? '' : raw.slice(i + 1) }
}

export function actorParam(actor: { kind: ActorKindValue; ref: string }): string {
  return `${actor.kind.toLowerCase()}:${actor.ref}`
}

export type TimePreset = 'today' | '7d' | '30d' | 'all'

export const timePresetLabel: Record<TimePreset, string> = {
  today: '今天',
  '7d': '7 天',
  '30d': '30 天',
  all: '全部',
}

/** 时间预设 → since(ISO);「全部」不限 */
export function presetSince(p: TimePreset, now: Date = new Date()): string | null {
  if (p === 'all') return null
  if (p === 'today') {
    const d = new Date(now)
    d.setHours(0, 0, 0, 0)
    return d.toISOString()
  }
  const days = p === '7d' ? 7 : 30
  return new Date(now.getTime() - days * 86_400_000).toISOString()
}

// ---------- 同一批折叠 ----------

export interface BatchGroup<T> {
  key: string
  batchId: string
  items: T[]
  /** 并进了别的批次(零散上传):撤回时按 id 选,不能只按 batchId */
  mixed?: boolean
}

/**
 * 连着的零散上传也算一组:网页、App 一次选几十个文件,每个文件各自一批,
 * 同一来源、同一个文件夹、前后不超过这么久的上传 / 新建文件夹,折成一组
 */
export const LOOSE_GROUP_MS = 5 * 60_000
const LOOSE_ACTIONS: ActivityActionValue[] = ['UPLOAD', 'MKDIR']

function looselySame(a: GroupableActivity, b: GroupableActivity): boolean {
  if (!a.action || a.action !== b.action || !LOOSE_ACTIONS.includes(a.action)) return false
  if (a.parentPath !== b.parentPath || !a.actor || !b.actor) return false
  if (a.actor.kind !== b.actor.kind || (a.actor.ref ?? '') !== (b.actor.ref ?? '')) return false
  if (!a.at || !b.at) return false
  return Math.abs(Date.parse(a.at) - Date.parse(b.at)) <= LOOSE_GROUP_MS
}

interface GroupableActivity {
  id: string
  batchId: string
  action?: ActivityActionValue
  parentPath?: string
  at?: string
  actor?: { kind: string; ref?: string | null }
}

/**
 * 相邻且 batchId 相同的折成一组(服务端新的在前,同一批本来就挨着);
 * 连着的零散上传(见 looselySame)也并进去,这时一组里有多个 batchId,撤回按 id 来。单条也是一组,只是不折
 */
export function groupBatches<T extends GroupableActivity>(items: T[]): BatchGroup<T>[] {
  const out: BatchGroup<T>[] = []
  for (const it of items) {
    const last = out[out.length - 1]
    const prev = last?.items[last.items.length - 1]
    if (last && (last.batchId === it.batchId || (prev && looselySame(prev, it)))) {
      last.items.push(it)
      if (last.batchId !== it.batchId) last.mixed = true
    } else out.push({ key: it.id, batchId: it.batchId, items: [it] })
  }
  return out
}

function same<T>(list: T[]): T | undefined {
  return list.every((v) => v === list[0]) ? list[0] : undefined
}

/** 一组的一句话:「rclone 移动了 37 个文件到 /照片」「rclone 在 /照片 删除了 312 个文件」 */
export function batchTitle(items: ActivityLike[]): string {
  if (items.length === 0) return ''
  const who = actorName(items[0].actor)
  const kind = same(items.map((a) => a.nodeKind ?? null))
  const noun = kind === 'FILE' ? '个文件' : kind === 'FOLDER' ? '个文件夹' : '项'
  const action = same(items.map((a) => a.action))
  if (!action) return `${who} 改动了 ${items.length} 项`
  const n = `${items.length} ${noun}`
  const verb = groupVerb[action]
  if (action === 'MOVE' || action === 'COPY') {
    const to = same(items.map((a) => (action === 'MOVE' ? (a.toPath ?? a.parentPath) : a.parentPath)))
    return to ? `${who} ${verb}了 ${n}到 ${to}` : `${who} ${verb}了 ${n}`
  }
  const at = same(items.map((a) => a.parentPath))
  return at ? `${who} 在 ${at} ${verb}了 ${n}` : `${who} ${verb}了 ${n}`
}

/** 一组的合计:文件数取 files(删文件夹是整棵子树),至少按 1 算 */
export function batchTotals(items: { files: number; bytes: number }[]): { files: number; bytes: number } {
  return items.reduce(
    (acc, a) => ({ files: acc.files + Math.max(1, a.files), bytes: acc.bytes + a.bytes }),
    { files: 0, bytes: 0 },
  )
}

// ---------- 撤回预览与结果 ----------

export interface UndoItemLike {
  ok: boolean
  reason?: string | null
}

export interface UndoResultLike {
  done: number
  failed: number
  items: UndoItemLike[]
}

/** 撤不了的原因按条数从多到少,最多列 3 种 */
export function undoReasons(items: UndoItemLike[], max = 3): string {
  const counts = new Map<string, number>()
  for (const it of items) {
    if (it.ok) continue
    const r = it.reason?.trim() || '原因不明'
    counts.set(r, (counts.get(r) ?? 0) + 1)
  }
  const list = [...counts.entries()].sort((a, b) => b[1] - a[1])
  if (list.length === 0) return ''
  if (list.length === 1) return list[0][0]
  const shown = list.slice(0, max).map(([r, n]) => `${r} ${n} 项`)
  return list.length > max ? `${shown.join(';')} 等` : shown.join(';')
}

/** 预览的一句话:「会撤回 12 项、3 项撤不了(原名已被占用)」 */
export function undoPreviewText(res: UndoResultLike): string {
  const why = undoReasons(res.items)
  if (res.done === 0 && res.failed === 0) return '没有要撤回的'
  if (res.failed === 0) return `会撤回 ${res.done} 项`
  if (res.done === 0) return `${res.failed} 项都撤不了(${why})`
  return `会撤回 ${res.done} 项、${res.failed} 项撤不了(${why})`
}

/** 执行后的一句话 */
export function undoResultText(res: UndoResultLike): string {
  const why = undoReasons(res.items)
  if (res.failed === 0) return `已撤回 ${res.done} 项`
  if (res.done === 0) return `没撤成:${why}`
  return `已撤回 ${res.done} 项,${res.failed} 项没撤成(${why})`
}
