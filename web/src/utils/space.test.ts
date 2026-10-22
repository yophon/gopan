import { describe, expect, it } from 'vitest'

import {
  barWidth,
  categoryLabel,
  categoryText,
  checkDupeSelection,
  defaultDupeSelection,
  fillCategories,
  idleReason,
  shareText,
} from './space'

describe('类型', () => {
  it('中文名,认不出的归其他', () => {
    expect(categoryLabel.TEXT).toBe('代码与文本')
    expect(categoryText('ARCHIVE')).toBe('压缩包')
    expect(categoryText(null)).toBe('其他')
    expect(categoryText('WHAT')).toBe('其他')
  })
  it('补齐没有的类型并按固定顺序', () => {
    const rows = fillCategories([
      { category: 'VIDEO', files: 2, bytes: 900 },
      { category: 'IMAGE', files: 5, bytes: 100 },
    ])
    expect(rows.map((r) => r.category)).toEqual(['IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'ARCHIVE', 'TEXT', 'OTHER'])
    expect(rows[1]).toEqual({ category: 'VIDEO', files: 2, bytes: 900 })
    expect(rows[2]).toEqual({ category: 'AUDIO', files: 0, bytes: 0 })
  })
})

describe('条形与占比', () => {
  it('相对最大值,有值的至少 1%', () => {
    expect(barWidth(50, 200)).toBe(25)
    expect(barWidth(200, 200)).toBe(100)
    expect(barWidth(1, 100000)).toBe(1)
    expect(barWidth(0, 100)).toBe(0)
    expect(barWidth(5, 0)).toBe(0)
  })
  it('占比', () => {
    expect(shareText(25, 100)).toBe('25%')
    expect(shareText(1, 1000)).toBe('<1%')
    expect(shareText(0, 100)).toBe('0%')
    expect(shareText(10, 0)).toBe('0%')
  })
})

describe('重复文件', () => {
  const groups = [
    { sha256: 'a', size: 100, files: [{ node: { id: 'a1' } }, { node: { id: 'a2' } }, { node: { id: 'a3' } }] },
    { sha256: 'b', size: 10, files: [{ node: { id: 'b1' } }, { node: { id: 'b2' } }] },
  ]
  it('默认每组留第一份', () => {
    expect(defaultDupeSelection(groups)).toEqual(['a2', 'a3', 'b2'])
  })
  it('能省多少;勾光一组要拦住', () => {
    expect(checkDupeSelection(groups, ['a2', 'a3', 'b2'])).toEqual({ ids: ['a2', 'a3', 'b2'], bytes: 210, allGone: [] })
    expect(checkDupeSelection(groups, ['b1', 'b2', 'zz']).allGone).toEqual(['b'])
    expect(checkDupeSelection(groups, ['zz'])).toEqual({ ids: [], bytes: 0, allGone: [] })
  })
})

describe('闲置', () => {
  it('过期优先,其次看有没有人访问过', () => {
    expect(idleReason({ expired: true, last: '2026-01-01T00:00:00Z' })).toBe('已过期')
    expect(idleReason({ expired: false, last: '2026-01-01T00:00:00Z' })).toBe('90 天没人访问')
    expect(idleReason({ expired: false, last: null })).toBe('从没人访问')
  })
})
