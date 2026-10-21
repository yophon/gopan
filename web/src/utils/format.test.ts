import { describe, expect, it } from 'vitest'

import { formatBytes, formatRelative, formatTime } from './format'

describe('formatBytes', () => {
  it('空值给占位符(文件夹无大小)', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
  })
  it('负数与 NaN 不是合法大小,也给占位符', () => {
    expect(formatBytes(-5)).toBe('—')
    expect(formatBytes(Number.NaN)).toBe('—')
  })
  it('1024 边界与单位递进', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatBytes(1536 * 1024 * 1024)).toBe('1.5 GB')
  })
  it('超大值封顶在 PB 不越界', () => {
    expect(formatBytes(2 ** 62)).toMatch(/PB$/)
  })
})

describe('formatTime', () => {
  it('空值与非法输入给占位符', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatTime('')).toBe('—')
    expect(formatTime('not-a-date')).toBe('—')
  })
  it('ISO 串转本地 YYYY-MM-DD HH:mm', () => {
    expect(formatTime('2026-07-11T00:05:00+08:00')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
  })
})

describe('formatRelative', () => {
  const now = new Date(2026, 9, 21, 15, 30, 0).getTime()
  const at = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).toISOString()
  it('空值与非法输入给占位符', () => {
    expect(formatRelative(null, now)).toBe('—')
    expect(formatRelative('x', now)).toBe('—')
  })
  it('一分钟内、一小时内、今天', () => {
    expect(formatRelative(at(2026, 10, 21, 15, 29), now)).toBe('1 分钟前')
    expect(formatRelative(new Date(now - 20_000).toISOString(), now)).toBe('刚刚')
    expect(formatRelative(new Date(now + 60_000).toISOString(), now)).toBe('刚刚')
    expect(formatRelative(at(2026, 10, 21, 15, 0), now)).toBe('30 分钟前')
    expect(formatRelative(at(2026, 10, 21, 1, 0), now)).toBe('14 小时前')
  })
  it('昨天带时刻,一周内按天,再早给日期', () => {
    expect(formatRelative(at(2026, 10, 20, 23, 5), now)).toBe('昨天 23:05')
    expect(formatRelative(at(2026, 10, 18, 9, 0), now)).toBe('3 天前')
    expect(formatRelative(at(2026, 10, 1, 9, 0), now)).toBe('2026-10-01')
  })
})
