/** 订阅(doc/23 四)的展示口径与表单规则。纯函数,单测直接引。 */

export type FeedKindValue = 'RSS' | 'GITHUB'
export type FeedModeValue = 'DOWNLOAD' | 'CLIP' | 'NONE'
export type FeedItemStateValue = 'NEW' | 'SKIPPED' | 'QUEUED' | 'DONE' | 'FAILED'
type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export const feedModeLabel: Record<FeedModeValue, string> = {
  DOWNLOAD: '下载附件',
  CLIP: '剪藏正文',
  NONE: '只记录',
}

export const feedModeHint: Record<FeedModeValue, string> = {
  DOWNLOAD: '播客的音频、视频,GitHub Release 里匹配的安装包,下进网盘',
  CLIP: '把文章正文存成 Markdown(图片一起存)',
  NONE: '只列出来,想要哪条在详情里手动下',
}

/** GitHub 只能下载附件或只记录(服务端同款限制) */
export function feedModes(kind: FeedKindValue): FeedModeValue[] {
  return kind === 'GITHUB' ? ['DOWNLOAD', 'NONE'] : ['DOWNLOAD', 'CLIP', 'NONE']
}

/** 列表上的类型标签。订阅本身不记是不是播客,按「RSS + 下载附件」认 */
export function feedKindTag(f: { kind: FeedKindValue; mode: FeedModeValue }): string {
  if (f.kind === 'GITHUB') return 'GitHub'
  return f.mode === 'DOWNLOAD' ? '播客' : 'RSS'
}

export const itemStateMeta: Record<FeedItemStateValue, { label: string; type: TagType }> = {
  NEW: { label: '新', type: 'primary' },
  SKIPPED: { label: '已跳过', type: 'info' },
  QUEUED: { label: '处理中', type: 'warning' },
  DONE: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

// ---------- 检查间隔 ----------

export const INTERVAL_MIN = 60
export const INTERVAL_MAX = 10080
export const INTERVAL_PRESETS = [60, 360, 1440] as const

/** 60 → 每小时,360 → 每 6 小时,1440 → 每天,90 → 每 90 分钟 */
export function intervalText(minutes: number): string {
  if (!(minutes > 0)) return '—'
  if (minutes % 1440 === 0) return minutes === 1440 ? '每天' : `每 ${minutes / 1440} 天`
  if (minutes % 60 === 0) return minutes === 60 ? '每小时' : `每 ${minutes / 60} 小时`
  return `每 ${minutes} 分钟`
}

// ---------- 地址 ----------

const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/

/** 和服务端一样认 GitHub 仓库:owner/repo,或 github.com 上的仓库链接(.atom / .rss 除外) */
export function githubRepo(input: string): string | null {
  let s = input.trim()
  if (/\.(atom|rss)$/i.test(s)) return null
  const hasScheme = s.includes('://')
  if (hasScheme) {
    let u: URL
    try {
      u = new URL(s)
    } catch {
      return null
    }
    if (u.hostname !== 'github.com' && u.hostname !== 'www.github.com') return null
    const parts = u.pathname.replace(/^\/+|\/+$/g, '').split('/')
    if (parts.length < 2) return null
    s = `${parts[0]}/${parts[1].replace(/\.git$/, '')}`
  }
  if (!REPO_RE.test(s) || s.includes('..')) return null
  return s
}

/** 新建时存放文件夹的默认说法(服务端不给 folderId 时用 /订阅/<标题>) */
export function defaultFolderText(title: string): string {
  return `/订阅/${title.trim() || '<标题>'}`
}

// ---------- 状态 ----------

export interface FeedStatusLike {
  enabled: boolean
  folder?: unknown | null
  failCount: number
  lastError?: string | null
}

/** 列表上的状态:暂停 / 文件夹没了 / 检查失败 / 正常 */
export function feedStatus(f: FeedStatusLike): { label: string; type: TagType } {
  if (!f.folder) return { label: '文件夹不在了', type: 'danger' }
  if (!f.enabled) return { label: '已暂停', type: 'info' }
  if (f.failCount > 0 && f.lastError) return { label: `检查失败 ${f.failCount} 次`, type: 'danger' }
  return { label: '正常', type: 'success' }
}

/** 标题关键词:逗号(中英文)或空白分隔,去重去空 */
export function splitWords(s: string): string[] {
  const out: string[] = []
  for (const w of s.split(/[,，\s]+/)) {
    const t = w.trim()
    if (t && !out.includes(t)) out.push(t)
  }
  return out
}

export interface FeedSettingsLike {
  title: string
  folder?: { id: string } | null
  mode: FeedModeValue
  includeWords: string
  excludeWords: string
  assetPattern: string
  prerelease: boolean
  keepLast: number
  intervalMinutes: number
  enabled: boolean
}

/**
 * updateFeed 是整体替换(服务端没给的字段按默认值算),暂停 / 恢复这类只改一处的也得把其余字段原样带上。
 * folderId 为 null = 不换文件夹。
 */
export function feedInputFrom(f: FeedSettingsLike, over: Partial<ReturnType<typeof baseInput>> = {}) {
  return { ...baseInput(f), ...over }
}

function baseInput(f: FeedSettingsLike) {
  return {
    title: f.title,
    folderId: f.folder?.id ?? null,
    mode: f.mode,
    includeWords: f.includeWords,
    excludeWords: f.excludeWords,
    assetPattern: f.assetPattern,
    prerelease: f.prerelease,
    keepLast: f.keepLast,
    intervalMinutes: f.intervalMinutes,
    enabled: f.enabled,
  }
}

/** 表单校验:有问题返回一句话,没问题返回 null */
export function validateFeedForm(f: { intervalMinutes: number; keepLast: number; initial?: number }): string | null {
  if (!Number.isInteger(f.intervalMinutes) || f.intervalMinutes < INTERVAL_MIN || f.intervalMinutes > INTERVAL_MAX) {
    return '检查间隔要在 1 小时到 7 天之间'
  }
  if (!Number.isInteger(f.keepLast) || f.keepLast < 0 || f.keepLast > 1000) {
    return '只留最近几条要在 0(不删)到 1000 之间'
  }
  if (f.initial !== undefined && (!Number.isInteger(f.initial) || f.initial < 0 || f.initial > 50)) {
    return '先处理几条要在 0 到 50 之间'
  }
  return null
}
