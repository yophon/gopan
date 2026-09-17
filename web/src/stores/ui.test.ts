import { describe, expect, it } from 'vitest'

import { parseViewMode } from './ui'

// 只测纯函数部分:store 本身依赖 pinia 与 localStorage,
// 按仓库惯例(node 环境、不引 jsdom)不在这里建实例。
describe('parseViewMode', () => {
  it('只认 grid,其余一律回落到 list', () => {
    expect(parseViewMode('grid')).toBe('grid')
    expect(parseViewMode('list')).toBe('list')
    expect(parseViewMode(undefined)).toBe('list')
    expect(parseViewMode(null)).toBe('list')
    expect(parseViewMode('')).toBe('list')
    expect(parseViewMode('GRID')).toBe('list')
    expect(parseViewMode({})).toBe('list')
  })
})
