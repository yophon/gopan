import { describe, expect, it } from 'vitest'

import { matchesRecentFilter, recentLine } from './recent'

describe('最近页', () => {
  it('按类型筛', () => {
    expect(matchesRecentFilter('VIDEO', 'media')).toBe(true)
    expect(matchesRecentFilter('AUDIO', 'media')).toBe(true)
    expect(matchesRecentFilter('OFFICE', 'doc')).toBe(true)
    expect(matchesRecentFilter('IMAGE', 'doc')).toBe(false)
    expect(matchesRecentFilter('NONE', 'all')).toBe(true)
    expect(matchesRecentFilter('NONE', 'image')).toBe(false)
  })
  it('打开时间后面跟看到哪', () => {
    const now = Date.parse('2026-10-28T12:00:00Z')
    const at = '2026-10-28T11:59:30Z'
    expect(recentLine(at, null, 'VIDEO', now)).toBe('刚刚')
    expect(recentLine(at, { position: 75, duration: 6120, finished: false }, 'VIDEO', now)).toBe('刚刚 · 看到 01:15 / 1:42:00')
    expect(recentLine(at, { position: 12, duration: 80, finished: false }, 'OFFICE', now)).toBe('刚刚 · 第 12 / 80 页')
    expect(recentLine(at, { position: 80, duration: 80, finished: true }, 'PDF', now)).toBe('刚刚 · 已看完')
  })
})
