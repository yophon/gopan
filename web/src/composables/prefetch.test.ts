import { describe, expect, it, vi } from 'vitest'

import {
  pickPrefetchIndices,
  prefetchBudget,
  useNeighborPrefetch,
  type PrefetchPreview,
} from './prefetch'

type Opts = Parameters<typeof useNeighborPrefetch>[0]

function fakeLoader() {
  const calls: string[] = []
  const cancelled: number[] = []
  let seq = 0
  const loadImage = (url: string) => {
    const idx = seq++
    calls.push(url)
    return { cancel: () => cancelled.push(idx) }
  }
  return { loadImage, calls, cancelled }
}

describe('pickPrefetchIndices', () => {
  it('预算为 0 或只有一个元素时什么都不取', () => {
    expect(pickPrefetchIndices(5, 2, 0)).toEqual([])
    expect(pickPrefetchIndices(1, 0, 2)).toEqual([])
  })

  it('优先下一个,再上一个', () => {
    expect(pickPrefetchIndices(5, 2, 1)).toEqual([3])
    expect(pickPrefetchIndices(5, 2, 2)).toEqual([3, 1])
  })

  it('两端不越界', () => {
    expect(pickPrefetchIndices(3, 0, 2)).toEqual([1])
    expect(pickPrefetchIndices(3, 2, 2)).toEqual([1])
  })
})

describe('prefetchBudget', () => {
  it('不知道网络时按 2 张', () => {
    expect(prefetchBudget(undefined)).toBe(2)
  })

  it('省流量模式与 2G 一张都不取', () => {
    expect(prefetchBudget({ saveData: true })).toBe(0)
    expect(prefetchBudget({ effectiveType: '2g' })).toBe(0)
    expect(prefetchBudget({ effectiveType: 'slow-2g' })).toBe(0)
  })

  it('3G 只取下一张', () => {
    expect(prefetchBudget({ effectiveType: '3g' })).toBe(1)
  })
})

describe('useNeighborPrefetch', () => {
  const IDS = ['a', 'b', 'c']

  function setup(over: Partial<Opts> = {}, previews: Record<string, PrefetchPreview> = {}) {
    const loader = fakeLoader()
    const fetchPreview = vi.fn(
      async (id: string) => previews[id] ?? { kind: 'IMAGE', largeUrl: `url-${id}` },
    )
    const prefetch = useNeighborPrefetch({
      ids: () => IDS,
      index: () => 0,
      currentKind: () => 'IMAGE',
      fetchPreview,
      loadImage: loader.loadImage,
      ...over,
    })
    return { prefetch, loader, fetchPreview }
  }

  it('预取图片邻居,优先 largeUrl', async () => {
    const { prefetch, loader } = setup({}, { b: { kind: 'IMAGE', largeUrl: 'big-b', contentUrl: 'raw-b' } })
    await prefetch.run()
    expect(loader.calls).toEqual(['big-b'])
  })

  it('没有 largeUrl 时退回 contentUrl', async () => {
    const { prefetch, loader } = setup({}, { b: { kind: 'IMAGE', largeUrl: null, contentUrl: 'raw-b' } })
    await prefetch.run()
    expect(loader.calls).toEqual(['raw-b'])
  })

  it('非图片邻居一次都不下载', async () => {
    const { prefetch, loader, fetchPreview } = setup({}, { b: { kind: 'VIDEO', contentUrl: 'v' } })
    await prefetch.run()
    expect(fetchPreview).toHaveBeenCalledWith('b')
    expect(loader.calls).toEqual([])
  })

  it('当前不是图片时连元数据都不查', async () => {
    const { prefetch, loader, fetchPreview } = setup({ currentKind: () => 'PDF' })
    await prefetch.run()
    expect(fetchPreview).not.toHaveBeenCalled()
    expect(loader.calls).toEqual([])
  })

  it('省流量下不预取', async () => {
    const { prefetch, loader } = setup({ budget: () => 0 })
    await prefetch.run()
    expect(loader.calls).toEqual([])
  })

  it('同一个邻居不重复查元数据', async () => {
    const { prefetch, fetchPreview } = setup()
    await prefetch.run()
    await prefetch.run()
    expect(fetchPreview.mock.calls.filter((c) => c[0] === 'b')).toHaveLength(1)
  })

  it('切走时取消在途的预取', async () => {
    const { prefetch, loader } = setup()
    await prefetch.run()
    expect(loader.cancelled).toEqual([])
    prefetch.clear()
    expect(loader.cancelled).toHaveLength(1)
  })

  it('末位下标只回头预取一个', async () => {
    const { prefetch, loader } = setup({ index: () => 2 })
    await prefetch.run()
    expect(loader.calls).toEqual(['url-b'])
  })
})
