import { beforeEach, describe, expect, it } from 'vitest'

import { closePreview, openPreview, previewNext, previewPrev, previewState } from './preview'

// previewState 是模块级单例,每个用例前手动复位
beforeEach(() => {
  previewState.visible = false
  previewState.ids = []
  previewState.index = 0
})

describe('openPreview', () => {
  it('正常打开:拷贝 ids、定位 index、置 visible', () => {
    const ids = ['a', 'b', 'c']
    openPreview(ids, 1)
    expect(previewState.visible).toBe(true)
    expect(previewState.ids).toEqual(['a', 'b', 'c'])
    expect(previewState.index).toBe(1)
    // 防外部数组变异:内部持有的是拷贝
    ids.push('d')
    expect(previewState.ids).toEqual(['a', 'b', 'c'])
  })
  it('index 越界钳制到 [0, len-1]', () => {
    openPreview(['a', 'b'], 99)
    expect(previewState.index).toBe(1)
    openPreview(['a', 'b'], -3)
    expect(previewState.index).toBe(0)
  })
  it('空列表 no-op,不置 visible', () => {
    openPreview([], 0)
    expect(previewState.visible).toBe(false)
    expect(previewState.ids).toEqual([])
  })
})

describe('previewPrev / previewNext', () => {
  it('区间内正常移动', () => {
    openPreview(['a', 'b', 'c'], 1)
    previewNext()
    expect(previewState.index).toBe(2)
    previewPrev()
    previewPrev()
    expect(previewState.index).toBe(0)
  })
  it('到边界后不越界', () => {
    openPreview(['a', 'b'], 0)
    previewPrev()
    expect(previewState.index).toBe(0)
    previewNext()
    previewNext()
    previewNext()
    expect(previewState.index).toBe(1)
  })
})

describe('closePreview', () => {
  it('只收起 visible,保留 ids 与 index(供恢复)', () => {
    openPreview(['a', 'b'], 1)
    closePreview()
    expect(previewState.visible).toBe(false)
    expect(previewState.ids).toEqual(['a', 'b'])
    expect(previewState.index).toBe(1)
  })
})
