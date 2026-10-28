/** 「最近」页(doc/24、doc/31 五):按类型筛、每行写打开时间与看到哪 */
import { formatRelative } from './format'
import { progressText } from './media'
import type { ProgressLike } from './media'

export type RecentFilter = 'all' | 'media' | 'doc' | 'image'

export const recentFilters: { value: RecentFilter; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'media', label: '影音' },
  { value: 'doc', label: '文档' },
  { value: 'image', label: '图片' },
]

export function matchesRecentFilter(previewKind: string | null | undefined, f: RecentFilter): boolean {
  switch (f) {
    case 'media':
      return previewKind === 'VIDEO' || previewKind === 'AUDIO'
    case 'doc':
      return previewKind === 'PDF' || previewKind === 'OFFICE' || previewKind === 'TEXT'
    case 'image':
      return previewKind === 'IMAGE'
    default:
      return true
  }
}

/** 按页记进度的:PDF 与转成 PDF 看的 Office */
export function pagedKind(previewKind: string | null | undefined): boolean {
  return previewKind === 'PDF' || previewKind === 'OFFICE'
}

/** 「3 小时前 · 看到 23:15 / 1:42:00」;没有进度就只有时间 */
export function recentLine(
  openedAt: string | null | undefined,
  progress: ProgressLike | null | undefined,
  previewKind: string | null | undefined,
  now: number = Date.now(),
): string {
  const t = formatRelative(openedAt, now)
  return progress ? `${t} · ${progressText(progress, pagedKind(previewKind))}` : t
}
