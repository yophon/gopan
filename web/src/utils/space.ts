/** 空间分析(doc/23 三)的展示口径。纯函数,单测直接引。 */

export type FileCategoryValue = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'DOCUMENT' | 'ARCHIVE' | 'TEXT' | 'OTHER'

/** 固定顺序:总览与大文件的筛选按钮都按它排 */
export const FILE_CATEGORIES: FileCategoryValue[] = ['IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'ARCHIVE', 'TEXT', 'OTHER']

export const categoryLabel: Record<FileCategoryValue, string> = {
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频',
  DOCUMENT: '文档',
  ARCHIVE: '压缩包',
  TEXT: '代码与文本',
  OTHER: '其他',
}

export function categoryText(c: string | null | undefined): string {
  return (c && categoryLabel[c as FileCategoryValue]) || '其他'
}

/** 条形的宽度百分比(相对这组里最大的那个);有值的至少给 1%,免得看不见 */
export function barWidth(value: number, max: number): number {
  if (!(max > 0) || !(value > 0)) return 0
  return Math.max(1, Math.min(100, Math.round((value / max) * 100)))
}

/** 占比:「12%」,不足 1% 的写「<1%」 */
export function shareText(value: number, total: number): string {
  if (!(total > 0) || !(value > 0)) return '0%'
  const p = (value / total) * 100
  if (p < 1) return '<1%'
  return `${Math.round(p)}%`
}

/** 按类型合计,补齐没有的类型(条数 0),按固定顺序 */
export function fillCategories<T extends { category: string; files: number; bytes: number }>(
  rows: T[],
): { category: FileCategoryValue; files: number; bytes: number }[] {
  return FILE_CATEGORIES.map((c) => {
    const hit = rows.find((r) => r.category === c)
    return { category: c, files: hit?.files ?? 0, bytes: hit?.bytes ?? 0 }
  })
}

// ---------- 重复文件 ----------

export interface DupeGroupLike {
  sha256: string
  size: number
  files: { node: { id: string } }[]
}

/** 默认勾选:每组留第一份(服务端最早的在前),其余都勾上 */
export function defaultDupeSelection(groups: DupeGroupLike[]): string[] {
  return groups.flatMap((g) => g.files.slice(1).map((f) => f.node.id))
}

export interface DupeCheck {
  /** 选中的、属于这些组的节点 */
  ids: string[]
  /** 删掉能省下的配额 */
  bytes: number
  /** 一份都没留的组(删之前要拦住) */
  allGone: string[]
}

/** 汇总勾选:能省多少、有没有哪组被勾光了 */
export function checkDupeSelection(groups: DupeGroupLike[], selected: string[]): DupeCheck {
  const set = new Set(selected)
  const out: DupeCheck = { ids: [], bytes: 0, allGone: [] }
  for (const g of groups) {
    const picked = g.files.filter((f) => set.has(f.node.id))
    out.ids.push(...picked.map((f) => f.node.id))
    out.bytes += picked.length * g.size
    if (g.files.length > 0 && picked.length === g.files.length) out.allGone.push(g.sha256)
  }
  return out
}

// ---------- 分享与直链 ----------

/** 闲置的一句话:已过期 / 90 天没人访问 / 从没人访问 */
export function idleReason(x: { expired: boolean; last?: string | null }): string {
  if (x.expired) return '已过期'
  return x.last ? '90 天没人访问' : '从没人访问'
}
