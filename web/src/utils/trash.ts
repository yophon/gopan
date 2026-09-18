/**
 * 回收站条目的到期状态。
 *
 * 到期时刻由服务端给(deleted_at + GOPAN_TRASH_TTL)——前端不该知道运维把保留期
 * 配成了多少,否则改了 env 会出现"界面说还剩 3 天、内容已经被删"。
 */
export type TrashExpiry = {
  /** 距彻删还剩几天(向上取整);purgeAt 缺失时为 null */
  days: number | null
  level: 'normal' | 'soon' | 'expired'
}

const DAY_MS = 86_400_000

/**
 * 已过期却仍在列表里是正常的:清理任务每小时才扫一次,最多滞留一小时。
 */
export function trashExpiry(purgeAt: string | null | undefined, now = Date.now()): TrashExpiry {
  if (!purgeAt) return { days: null, level: 'normal' }
  const left = new Date(purgeAt).getTime() - now
  if (Number.isNaN(left)) return { days: null, level: 'normal' }
  if (left <= 0) return { days: 0, level: 'expired' }
  const days = Math.ceil(left / DAY_MS)
  return { days, level: days <= 1 ? 'soon' : 'normal' }
}

/** 表格与卡片共用的一句话文案。 */
export function trashExpiryText(purgeAt: string | null | undefined, now = Date.now()): string {
  const { days, level } = trashExpiry(purgeAt, now)
  if (days === null) return '—'
  if (level === 'expired') return '已到期,待清理'
  return `还剩 ${days} 天`
}
