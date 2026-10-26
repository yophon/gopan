import { describe, expect, it } from 'vitest'

import { currentLyricIndex } from './lyrics'

describe('currentLyricIndex', () => {
  const lines = [
    { t: 1, text: 'a' },
    { t: 5, text: 'b' },
    { t: 5, text: 'b2' },
    { t: 9.5, text: 'c' },
  ]
  it('取最后一个已经开始的行', () => {
    expect(currentLyricIndex(lines, 0)).toBe(-1)
    expect(currentLyricIndex(lines, 1)).toBe(0)
    expect(currentLyricIndex(lines, 4.99)).toBe(2) // 提前 50 毫秒亮
    expect(currentLyricIndex(lines, 7)).toBe(2)
    expect(currentLyricIndex(lines, 100)).toBe(3)
    expect(currentLyricIndex([], 3)).toBe(-1)
  })
})
