import { describe, expect, it } from 'vitest'

import { nextIndex, nextPlayMode, parsePlayMode, prevIndex, shuffleOrder } from './player'
import type { QueuePos } from './player'

/** 可复现的伪随机:线性同余 */
function seeded(seed = 1): () => number {
  let s = seed
  return () => {
    s = (s * 1103515245 + 12345) % 2147483648
    return s / 2147483648
  }
}

const q = (over: Partial<QueuePos>): QueuePos => ({ len: 5, index: 0, mode: 'order', order: [], ...over })

describe('播放模式', () => {
  it('顺序 → 单曲循环 → 随机 → 顺序', () => {
    expect(nextPlayMode('order')).toBe('one')
    expect(nextPlayMode('one')).toBe('shuffle')
    expect(nextPlayMode('shuffle')).toBe('order')
  })
  it('存储里的脏值回落到顺序', () => {
    expect(parsePlayMode('shuffle')).toBe('shuffle')
    expect(parsePlayMode('xx')).toBe('order')
    expect(parsePlayMode(null)).toBe('order')
  })
})

describe('shuffleOrder', () => {
  it('是全排列,起点排第一', () => {
    const o = shuffleOrder(10, 3, seeded(7))
    expect(o[0]).toBe(3)
    expect([...o].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
  })
  it('起点越界时只洗牌', () => {
    expect(shuffleOrder(3, -1, seeded()).sort()).toEqual([0, 1, 2])
  })
})

describe('顺序播放', () => {
  it('自动切到下一首,最后一首放完停', () => {
    expect(nextIndex(q({ index: 1 }), true)).toEqual({ index: 2 })
    expect(nextIndex(q({ index: 4 }), true)).toBeNull()
  })
  it('手动下一首到头绕回第一首', () => {
    expect(nextIndex(q({ index: 4 }), false)).toEqual({ index: 0 })
  })
  it('上一首,第一首再往前绕到最后', () => {
    expect(prevIndex(q({ index: 2 }))).toEqual({ index: 1 })
    expect(prevIndex(q({ index: 0 }))).toEqual({ index: 4 })
  })
  it('空队列', () => {
    expect(nextIndex(q({ len: 0 }), true)).toBeNull()
    expect(prevIndex(q({ len: 0 }))).toBeNull()
  })
})

describe('单曲循环', () => {
  it('自动切重放本首,手动照顺序走', () => {
    expect(nextIndex(q({ mode: 'one', index: 4 }), true)).toEqual({ index: 4 })
    expect(nextIndex(q({ mode: 'one', index: 4 }), false)).toEqual({ index: 0 })
    expect(nextIndex(q({ mode: 'one', index: 1 }), false)).toEqual({ index: 2 })
  })
})

describe('随机播放', () => {
  it('按洗好的顺序走', () => {
    const order = [2, 0, 4, 1, 3]
    expect(nextIndex(q({ mode: 'shuffle', index: 2, order }), true)).toEqual({ index: 0 })
    expect(nextIndex(q({ mode: 'shuffle', index: 1, order }), false)).toEqual({ index: 3 })
    expect(prevIndex(q({ mode: 'shuffle', index: 4, order }))).toEqual({ index: 0 })
    expect(prevIndex(q({ mode: 'shuffle', index: 2, order }))).toEqual({ index: 2 })
  })
  it('转完一圈重新洗,新一圈不以刚放完的那首开头', () => {
    const order = [2, 0, 4, 1, 3]
    for (let seed = 1; seed < 30; seed++) {
      const m = nextIndex(q({ mode: 'shuffle', index: 3, order }), true, seeded(seed))!
      expect(m.order).toBeDefined()
      expect(m.order![0]).toBe(m.index)
      expect(m.index).not.toBe(3)
      expect([...m.order!].sort()).toEqual([0, 1, 2, 3, 4])
    }
  })
  it('顺序缺失或对不上时现洗一份,从当前这首往后走', () => {
    const m = nextIndex(q({ mode: 'shuffle', index: 1, order: [] }), true, seeded(3))!
    expect(m.order).toBeDefined()
    expect(m.order![0]).toBe(1)
    expect(m.index).toBe(m.order![1])
  })
  it('只有一首:自动切就停,手动切重放', () => {
    expect(nextIndex(q({ mode: 'shuffle', len: 1, index: 0, order: [0] }), true)).toBeNull()
    expect(nextIndex(q({ mode: 'shuffle', len: 1, index: 0, order: [0] }), false)).toEqual({ index: 0 })
  })
})
