/**
 * 搜索筛选(doc/30):地址栏里的筛选条件 ↔ GraphQL 的 SearchFilter。条件都写进地址,刷新、返回、分享链接都还在。
 *
 * - type=image,video  类型(多选)
 * - t=today | 7d | 30d | year | older | 2026-01-01~2026-02-01  修改时间
 * - size=lt1m | 1m-100m | 100m-1g | gt1g  大小
 * - sort=name | size  排序(默认最近修改)
 * - folder=<id>  只搜这个文件夹里的(含子文件夹)
 */
import type { SearchFilter, SearchType } from '@/api/gen/graphql'

export const TYPE_OPTIONS: { value: SearchType; label: string }[] = [
  { value: 'FOLDER', label: '文件夹' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
  { value: 'AUDIO', label: '音频' },
  { value: 'DOCUMENT', label: '文档' },
  { value: 'TEXT', label: '文本与代码' },
  { value: 'ARCHIVE', label: '压缩包与安装包' },
  { value: 'OTHER', label: '其他' },
]

export const TIME_OPTIONS = [
  { value: '', label: '不限时间' },
  { value: 'today', label: '今天' },
  { value: '7d', label: '最近 7 天' },
  { value: '30d', label: '最近 30 天' },
  { value: 'year', label: '今年' },
  { value: 'older', label: '一年以前' },
  { value: 'range', label: '自己选…' },
] as const

export const SIZE_OPTIONS = [
  { value: '', label: '不限大小' },
  { value: 'lt1m', label: '小于 1 MB' },
  { value: '1m-100m', label: '1 ~ 100 MB' },
  { value: '100m-1g', label: '100 MB ~ 1 GB' },
  { value: 'gt1g', label: '大于 1 GB' },
] as const

export const SORT_OPTIONS = [
  { value: '', label: '最近修改' },
  { value: 'name', label: '名称' },
  { value: 'size', label: '大小' },
] as const

export interface FilterState {
  types: SearchType[]
  /** 预设,或 YYYY-MM-DD~YYYY-MM-DD */
  time: string
  size: string
  sort: '' | 'name' | 'size'
  folder: string | null
}

const MB = 1024 * 1024
const GB = 1024 * MB
const SIZES: Record<string, { min?: number; max?: number }> = {
  lt1m: { max: MB - 1 },
  '1m-100m': { min: MB, max: 100 * MB },
  '100m-1g': { min: 100 * MB, max: GB },
  gt1g: { min: GB },
}
const RANGE = /^(\d{4}-\d{2}-\d{2})~(\d{4}-\d{2}-\d{2})$/

function str(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

export function parseFilterQuery(query: Record<string, unknown>): FilterState {
  const known = new Set(TYPE_OPTIONS.map((o) => o.value))
  const types = str(query.type)
    .split(',')
    .map((s) => s.trim().toUpperCase())
    .filter((s): s is SearchType => known.has(s as SearchType))
  const time = str(query.t)
  const size = str(query.size)
  const sort = str(query.sort)
  return {
    types: [...new Set(types)],
    time: TIME_OPTIONS.some((o) => o.value === time && o.value !== 'range') || RANGE.test(time) ? time : '',
    size: size in SIZES ? size : '',
    sort: sort === 'name' || sort === 'size' ? sort : '',
    folder: str(query.folder) || null,
  }
}

/** 合进路由 query 的那几项(没设的给 undefined,router 会去掉) */
export function filterQuery(s: FilterState): Record<string, string | undefined> {
  return {
    type: s.types.length ? s.types.map((t) => t.toLowerCase()).join(',') : undefined,
    t: s.time || undefined,
    size: s.size || undefined,
    sort: s.sort || undefined,
    folder: s.folder ?? undefined,
  }
}

/** 设了条件(只换排序不算) */
export function hasConditions(s: FilterState): boolean {
  return s.types.length > 0 || !!s.time || !!s.size || !!s.folder
}

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function parseDay(s: string): Date | null {
  const [y, m, d] = s.split('-').map(Number)
  if (!y || !m || !d) return null
  return new Date(y, m - 1, d)
}

/** 修改时间的区间 [after, before) */
export function timeRange(time: string, now = new Date()): { after?: Date; before?: Date } {
  const day = 24 * 3600 * 1000
  switch (time) {
    case 'today':
      return { after: startOfDay(now) }
    case '7d':
      return { after: new Date(now.getTime() - 7 * day) }
    case '30d':
      return { after: new Date(now.getTime() - 30 * day) }
    case 'year':
      return { after: new Date(now.getFullYear(), 0, 1) }
    case 'older':
      return { before: new Date(now.getTime() - 365 * day) }
  }
  const m = RANGE.exec(time)
  if (!m) return {}
  const from = parseDay(m[1]!)
  const to = parseDay(m[2]!)
  if (!from || !to) return {}
  const [a, b] = from <= to ? [from, to] : [to, from]
  return { after: a, before: new Date(b.getFullYear(), b.getMonth(), b.getDate() + 1) }
}

/** 传给 searchNodes / searchContent 的筛选;什么都没设返回 null */
export function toSearchFilter(s: FilterState, now = new Date()): SearchFilter | null {
  const f: SearchFilter = {}
  if (s.types.length) f.types = s.types
  const { after, before } = timeRange(s.time, now)
  if (after) f.modifiedAfter = after.toISOString()
  if (before) f.modifiedBefore = before.toISOString()
  const size = SIZES[s.size]
  if (size?.min !== undefined) f.minSize = size.min
  if (size?.max !== undefined) f.maxSize = size.max
  if (s.folder) f.folderId = s.folder
  if (s.sort === 'name') f.order = 'NAME'
  if (s.sort === 'size') f.order = 'SIZE'
  return Object.keys(f).length ? f : null
}

/** 内容搜索只按修改时间排,也搜不到文件夹 */
export function toContentFilter(s: FilterState, now = new Date()): SearchFilter | null {
  const f = toSearchFilter({ ...s, sort: '', types: s.types.filter((t) => t !== 'FOLDER') }, now)
  return f
}
