/** 字节数 → 人类可读大小;null/undefined(文件夹)返回占位符 */
export function formatBytes(size: number | null | undefined): string {
  if (size === null || size === undefined || !Number.isFinite(size) || size < 0) return '—'
  if (size < 1024) return `${size} B`
  const units = ['KB', 'MB', 'GB', 'TB', 'PB'] as const
  let value = size
  let idx = -1
  do {
    value /= 1024
    idx++
  } while (value >= 1024 && idx < units.length - 1)
  return `${value.toFixed(1)} ${units[idx]}`
}

/** ISO 时间串 → "YYYY-MM-DD HH:mm";无效输入返回占位符 */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 相对时间:刚刚 / N 分钟前 / N 小时前 / 昨天 HH:mm / N 天前,再早给日期。
 * now 由调用方传,方便单测;未来时间(时钟偏差)按刚刚算。
 */
export function formatRelative(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const t = d.getTime()
  if (Number.isNaN(t)) return '—'
  const diff = now - t
  const pad = (n: number) => String(n).padStart(2, '0')
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  const today = new Date(now)
  today.setHours(0, 0, 0, 0)
  if (t >= today.getTime()) return `${Math.floor(diff / 3_600_000)} 小时前`
  const yesterday = today.getTime() - 86_400_000
  if (t >= yesterday) return `昨天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  const days = Math.ceil((today.getTime() - t) / 86_400_000)
  if (days < 7) return `${days} 天前`
  return formatTime(iso).slice(0, 10)
}
