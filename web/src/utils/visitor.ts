import { formatBytes } from '@/utils/format'

/**
 * 访客页(分享 /s/、收集 /c/)的几句文案与判断,纯函数可单测。
 */

const DAY_MS = 86_400_000

/** 「3 天后过期」「今天过期」;没有期限或已过期为空串。verb 换成「截止」给收集页用 */
export function expiryText(iso: string | null | undefined, now = Date.now(), verb = '过期'): string {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  if (Number.isNaN(t) || t <= now) return ''
  const sameDay = new Date(t).toDateString() === new Date(now).toDateString()
  if (sameDay) return `今天${verb}`
  return `${Math.ceil((t - now) / DAY_MS)} 天后${verb}`
}

/** 分享头部那一行:「24 个文件 · 1.2 GB · 3 天后过期」(单文件只有大小) */
export function shareSummary(
  info: { kind: string; size?: number | null; fileCount?: number | null; expiresAt?: string | null },
  now = Date.now(),
): string {
  const parts: string[] = []
  if (info.kind === 'FOLDER' && info.fileCount != null) parts.push(`${info.fileCount} 个文件`)
  if (info.size != null) parts.push(formatBytes(info.size))
  const exp = expiryText(info.expiresAt, now)
  if (exp) parts.push(exp)
  return parts.join(' · ')
}

/** 一屏里多半是图片 / 视频(比如分享了一个相册):默认用网格看 */
export function mostlyMedia(items: { kind: string; preview?: { kind: string } | null }[]): boolean {
  const files = items.filter((n) => n.kind === 'FILE')
  if (files.length < 4) return false
  const media = files.filter((n) => n.preview?.kind === 'IMAGE' || n.preview?.kind === 'VIDEO').length
  return media / files.length >= 0.6
}
