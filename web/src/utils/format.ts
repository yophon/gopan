/** 字节数 → 人类可读大小;null/undefined(文件夹)返回占位符 */
export function formatBytes(size: number | null | undefined): string {
  if (size === null || size === undefined) return '—'
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
