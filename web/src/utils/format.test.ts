import { describe, expect, it } from 'vitest'

import { formatBytes, formatTime } from './format'

describe('formatBytes', () => {
  it('空值给占位符(文件夹无大小)', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
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
