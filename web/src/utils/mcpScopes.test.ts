import { describe, expect, it } from 'vitest'

import { mcpScopeLabel, mcpScopeOptions } from './mcpScopes'

describe('mcpScopeLabel', () => {
  it('已知 scope 返回中文标签', () => {
    expect(mcpScopeLabel('files:read')).toBe('浏览与搜索')
    expect(mcpScopeLabel('files:delete')).toBe('移入回收站与恢复')
  })
  it('每个选项都能查回自己的标签', () => {
    for (const opt of mcpScopeOptions) {
      expect(mcpScopeLabel(opt.value)).toBe(opt.label)
    }
  })
  it('未知 scope 原样返回(向前兼容服务端新增权限)', () => {
    expect(mcpScopeLabel('files:admin')).toBe('files:admin')
    expect(mcpScopeLabel('')).toBe('')
  })
})
