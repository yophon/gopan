import { describe, expect, it } from 'vitest'

import { trashExpiry, trashExpiryText } from './trash'

const NOW = new Date('2026-09-18T12:00:00Z').getTime()
const DAY = 86_400_000

function at(offsetMs: number): string {
  return new Date(NOW + offsetMs).toISOString()
}

describe('trashExpiry', () => {
  it('purgeAt 缺失时不展示', () => {
    expect(trashExpiry(null, NOW)).toEqual({ days: null, level: 'normal' })
    expect(trashExpiry(undefined, NOW)).toEqual({ days: null, level: 'normal' })
    expect(trashExpiry('', NOW)).toEqual({ days: null, level: 'normal' })
  })

  it('非法时间按缺失处理', () => {
    expect(trashExpiry('not-a-date', NOW).days).toBeNull()
  })

  it('已过期', () => {
    expect(trashExpiry(at(-1000), NOW)).toEqual({ days: 0, level: 'expired' })
  })

  it('不足一天算即将到期', () => {
    const r = trashExpiry(at(3 * 3600_000), NOW)
    expect(r.days).toBe(1)
    expect(r.level).toBe('soon')
  })

  it('恰好一天也是即将到期', () => {
    expect(trashExpiry(at(DAY), NOW).level).toBe('soon')
  })

  it('超过一天回到正常档', () => {
    const r = trashExpiry(at(DAY + 1), NOW)
    expect(r.days).toBe(2)
    expect(r.level).toBe('normal')
  })

  it('按天向上取整', () => {
    expect(trashExpiry(at(12 * DAY), NOW).days).toBe(12)
    expect(trashExpiry(at(12 * DAY + 1), NOW).days).toBe(13)
  })
})

describe('trashExpiryText', () => {
  it('各档文案', () => {
    expect(trashExpiryText(null, NOW)).toBe('—')
    expect(trashExpiryText(at(-1000), NOW)).toBe('已到期,待清理')
    expect(trashExpiryText(at(3 * 3600_000), NOW)).toBe('还剩 1 天')
    expect(trashExpiryText(at(5 * DAY), NOW)).toBe('还剩 5 天')
  })
})
