import { describe, expect, it } from 'vitest'

import { splitHighlights } from './highlight'

describe('splitHighlights', () => {
  it('按区间切段', () => {
    expect(splitHighlights('…签订的租房合同,合同期', [{ start: 6, length: 2 }, { start: 9, length: 2 }])).toEqual([
      { text: '…签订的租房', hit: false },
      { text: '合同', hit: true },
      { text: ',', hit: false },
      { text: '合同', hit: true },
      { text: '期', hit: false },
    ])
  })
  it('按码点计:表情符号不算两个字', () => {
    expect(splitHighlights('😀合同', [{ start: 1, length: 2 }])).toEqual([
      { text: '😀', hit: false },
      { text: '合同', hit: true },
    ])
  })
  it('越界与重叠的区间忽略,没有区间就是一整段', () => {
    expect(splitHighlights('abc', [{ start: 2, length: 5 }, { start: 0, length: 1 }, { start: 0, length: 2 }])).toEqual([
      { text: 'a', hit: true },
      { text: 'bc', hit: false },
    ])
    expect(splitHighlights('abc', [])).toEqual([{ text: 'abc', hit: false }])
  })
})
