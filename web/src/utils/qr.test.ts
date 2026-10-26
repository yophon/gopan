import { describe, expect, it } from 'vitest'

import { qrPath } from './qr'

describe('qrPath', () => {
  it('短链接生成带边框的方阵路径', () => {
    const { size, d } = qrPath('https://drive.example.com/s/abc123')
    expect(size).toBeGreaterThanOrEqual(23) // 21 格 + 两边各 1 格边框
    expect(d.startsWith('M')).toBe(true)
    expect(d).toMatch(/^(M\d+ \d+h\d+v1h-\d+z)+$/)
  })
})
