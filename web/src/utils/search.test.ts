import { describe, expect, it } from 'vitest'

import { searchKeyword } from './search'

describe('searchKeyword', () => {
  it('先认 k,再认旧的 q,都没有就是空串', () => {
    expect(searchKeyword({ k: '合同' })).toBe('合同')
    expect(searchKeyword({ q: '合同' })).toBe('合同')
    expect(searchKeyword({ k: '新', q: '旧' })).toBe('新')
    expect(searchKeyword({})).toBe('')
    expect(searchKeyword({ k: ['a'] })).toBe('')
  })
})
