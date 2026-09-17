import { describe, expect, it } from 'vitest'

import { isLongPress, pinchScale, pinchTranslate, twoPointerMetrics } from './gesture'

describe('isLongPress', () => {
  it('按够时间且没怎么动才算长按', () => {
    expect(isLongPress(0, 0, 500)).toBe(true)
    expect(isLongPress(6, 8, 800)).toBe(true) // 位移 10,正好在阈值上
  })

  it('时间不够不算', () => {
    expect(isLongPress(0, 0, 499)).toBe(false)
  })

  it('位移超阈值不算(在滚动列表)', () => {
    expect(isLongPress(0, 40, 900)).toBe(false)
    expect(isLongPress(11, 0, 900)).toBe(false)
  })

  it('阈值可覆盖', () => {
    expect(isLongPress(0, 30, 900, 500, 40)).toBe(true)
  })
})

describe('pinchScale', () => {
  it('按两指间距比例缩放', () => {
    expect(pinchScale(100, 200, 1)).toBe(2)
    expect(pinchScale(200, 100, 2)).toBe(1)
  })

  it('钳制到 [0.2, 8]', () => {
    expect(pinchScale(100, 1000, 1)).toBe(8)
    expect(pinchScale(1000, 1, 1)).toBe(0.2)
  })

  it('基准间距非法时保持原缩放', () => {
    expect(pinchScale(0, 100, 3)).toBe(3)
  })
})

describe('pinchTranslate', () => {
  it('锚点在屏幕上不动', () => {
    // 缩放前:锚点 (100,100),平移 (0,0),缩放 1 → 内容坐标 (100,100)
    const { tx, ty } = pinchTranslate({ x: 100, y: 100 }, 0, 0, 1, 2)
    // 缩放后要让内容点 (100,100) 仍落在 (100,100):t' = p - c*s' = 100 - 200 = -100
    expect(tx).toBe(-100)
    expect(ty).toBe(-100)
  })

  it('平移后按同一公式回算', () => {
    const { tx } = pinchTranslate({ x: 0, y: 0 }, 10, 0, 2, 4)
    // c = (0 - 10) / 2 = -5;t' = 0 - (-5 * 4) = 20
    expect(tx).toBe(20)
  })

  it('缩放非法时保持原平移', () => {
    expect(pinchTranslate({ x: 1, y: 2 }, 3, 4, 0, 2)).toEqual({ tx: 3, ty: 4 })
  })
})

describe('twoPointerMetrics', () => {
  it('中点与间距', () => {
    const m = twoPointerMetrics({ x: 0, y: 0 }, { x: 30, y: 40 })
    expect(m.center).toEqual({ x: 15, y: 20 })
    expect(m.distance).toBe(50)
  })
})
