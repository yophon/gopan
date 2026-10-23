/**
 * 桌面文件列表的纯逻辑(选中、排序偏好、菜单定位),不碰 DOM,可以单测。
 */

export type SortKey = 'NAME' | 'SIZE' | 'UPDATED_AT'

/** 站内拖动条目用的 dataTransfer 类型;和电脑里拖进来的 Files 区分开 */
export const DRAG_MIME = 'application/x-gopan-nodes'

/**
 * 一次点击之后的选中集合:
 * - 普通单击:只选这一条
 * - Ctrl / ⌘:加选或取消这一条
 * - Shift:从锚点连到这一条(锚点不在列表里就从这一条开始);同时按 Ctrl 则并到原有选中上
 */
export function applySelectClick(
  order: string[],
  current: string[],
  id: string,
  anchor: string | null,
  mods: { toggle: boolean; range: boolean },
): string[] {
  if (mods.range) {
    const from = anchor ? order.indexOf(anchor) : -1
    const to = order.indexOf(id)
    if (to < 0) return current
    const start = from < 0 ? to : Math.min(from, to)
    const end = from < 0 ? to : Math.max(from, to)
    const span = order.slice(start, end + 1)
    if (!mods.toggle) return span
    const set = new Set(current)
    for (const s of span) set.add(s)
    return order.filter((x) => set.has(x))
  }
  if (mods.toggle) return current.includes(id) ? current.filter((x) => x !== id) : [...current, id]
  return [id]
}

/** 键盘上下移动选中:没有选中时 ↓ 选第一条、↑ 选最后一条 */
export function stepSelection(order: string[], current: string[], delta: 1 | -1): string | null {
  if (order.length === 0) return null
  const last = current.length ? order.indexOf(current[current.length - 1]!) : -1
  if (last < 0) return delta > 0 ? order[0]! : order[order.length - 1]!
  const next = Math.min(order.length - 1, Math.max(0, last + delta))
  return order[next]!
}

export interface SortPref {
  key: SortKey
  desc: boolean
}

export const DEFAULT_SORT: SortPref = { key: 'NAME', desc: false }

/** localStorage 里的排序偏好;脏值回落到按名称升序 */
export function parseSortPref(raw: string | null): SortPref {
  if (!raw) return DEFAULT_SORT
  try {
    const v = JSON.parse(raw) as Partial<SortPref>
    if (v.key === 'NAME' || v.key === 'SIZE' || v.key === 'UPDATED_AT') return { key: v.key, desc: !!v.desc }
  } catch {
    // 落到默认
  }
  return DEFAULT_SORT
}

/** 点表头:点同一列换方向,换列时名称默认升序、大小与时间默认降序(大的、新的在前) */
export function nextSort(cur: SortPref, key: SortKey): SortPref {
  if (cur.key === key) return { key, desc: !cur.desc }
  return { key, desc: key !== 'NAME' }
}

/** 右键菜单的位置:贴着鼠标,但整块要留在视口里 */
export function clampMenu(x: number, y: number, w: number, h: number, vw: number, vh: number, margin = 8) {
  return {
    left: Math.max(margin, Math.min(x, vw - w - margin)),
    top: Math.max(margin, Math.min(y, vh - h - margin)),
  }
}

/** 重命名时默认只选中主名,不带扩展名(文件夹或没有扩展名就全选) */
export function baseNameRange(name: string, isFolder: boolean): [number, number] {
  if (isFolder) return [0, name.length]
  const dot = name.lastIndexOf('.')
  return dot > 0 ? [0, dot] : [0, name.length]
}
