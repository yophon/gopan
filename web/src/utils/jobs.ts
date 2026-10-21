import { formatBytes } from '@/utils/format'

/** 后台任务(离线下载、解压)的展示口径。纯函数,单测直接引。 */

export type JobStatusValue = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELED'

export interface JobLike {
  kind: 'FETCH_URL' | 'EXTRACT_ARCHIVE'
  status: JobStatusValue
  bytesDone: number
  bytesTotal?: number | null
  filesDone: number
  filesTotal?: number | null
  filesSkipped: number
  errorCode?: string | null
  errorMessage?: string | null
}

export const MAX_FETCH_URLS = 10

export function isActiveJob(j: { status: JobStatusValue }): boolean {
  return j.status === 'QUEUED' || j.status === 'RUNNING'
}

const RECENT_MS = 10 * 60 * 1000
const FAILED_MS = 24 * 60 * 60 * 1000

/** 抽屉里要不要列出来:进行中的、10 分钟内结束的、24 小时内失败的 */
export function jobWorthShowing(j: { status: JobStatusValue; finishedAt?: string | null }, now: number): boolean {
  if (isActiveJob(j)) return true
  const at = j.finishedAt ? Date.parse(j.finishedAt) : NaN
  if (Number.isNaN(at)) return false
  return now - at < (j.status === 'FAILED' ? FAILED_MS : RECENT_MS)
}

/** 进度百分比;总量未知时返回 null(进度条显示为不确定) */
export function jobPercent(j: JobLike): number | null {
  if (j.status === 'DONE') return 100
  if (j.kind === 'EXTRACT_ARCHIVE' && j.filesTotal) {
    return Math.min(100, Math.floor((j.filesDone / j.filesTotal) * 100))
  }
  if (j.bytesTotal) return Math.min(100, Math.floor((j.bytesDone / j.bytesTotal) * 100))
  return null
}

/** 状态一行字。speedBps 由调用方按两次轮询的差算出,没有就不显示速度。 */
export function jobStatusText(j: JobLike, speedBps?: number): string {
  switch (j.status) {
    case 'QUEUED':
      return j.errorMessage ? `等待重试:${j.errorMessage}` : '排队中'
    case 'RUNNING': {
      if (j.kind === 'EXTRACT_ARCHIVE') {
        const total = j.filesTotal ? ` / ${j.filesTotal}` : ''
        return `解压中 ${j.filesDone}${total} 个文件`
      }
      const done = formatBytes(j.bytesDone)
      const total = j.bytesTotal ? ` / ${formatBytes(j.bytesTotal)}` : ''
      const speed = speedBps && speedBps > 0 ? ` · ${formatBytes(speedBps)}/s` : ''
      return `${done}${total}${speed}`
    }
    case 'DONE': {
      if (j.errorCode === 'GOT_HTML') return '已完成,但拿到的是网页,不是文件'
      if (j.kind === 'EXTRACT_ARCHIVE') {
        const skipped = j.filesSkipped > 0 ? `,跳过 ${j.filesSkipped} 个` : ''
        return `已解压 ${j.filesDone} 个文件${skipped}`
      }
      return `已完成 · ${formatBytes(j.bytesDone)}`
    }
    case 'FAILED':
      return j.errorMessage || '失败'
    case 'CANCELED':
      return '已取消'
  }
}

/**
 * 从粘贴的一段文字里挑出链接:只要 http(s),去重,保持顺序。
 * 典型输入是一行一个,也兼容"【标题】链接"这种从别的 App 分享来的(链接和文字粘在一起)。
 */
export function parseUrls(text: string): string[] {
  const out: string[] = []
  for (const m of text.matchAll(/https?:\/\/[^\s<>"'\u300c\u300d\u3010\u3011()\uff08\uff09]+/gi)) {
    const t = m[0].replace(/[,.;!?\u3002\u3001\uff0c\uff1b\uff01\uff1f]+$/, '')
    if (!out.includes(t)) out.push(t)
  }
  return out
}
