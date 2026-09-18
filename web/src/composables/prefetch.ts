/**
 * 预览翻页的邻居预取。
 *
 * 为什么要有:预览是"点开一张图、左右翻"。每翻一页都得先查元数据拿预签名 URL、
 * 再从对象存储拉字节,图大的时候能明显看到等待。把相邻那张提前拉进浏览器缓存,
 * 翻过去就是瞬开。
 *
 * 三条约束(都不是可选的):
 *  1. **只预取 IMAGE**。视频/PDF/文本是重资源,抢带宽会让正在看的内容变卡;
 *     这条也正好和 useSwipe 让位给播放器的逻辑一致。
 *  2. **必须用与 <img> 完全相同的 URL**。浏览器按 URL 建缓存条目,差一个查询
 *     参数就是另一份,预取等于白下。
 *  3. **只在当前内容加载完之后才预取**。预签名只有 15 分钟寿命,提前太久拿到
 *     的 URL 等到用户翻过去早已过期。连续翻页是秒级的,不会跨过那个窗口。
 */

export interface PrefetchPreview {
  kind: string
  largeUrl?: string | null
  contentUrl?: string | null
}

export interface RollingLoad {
  cancel: () => void
}

export interface PrefetchOptions {
  /** 当前预览序列(只读快照函数,不要传响应式对象本身) */
  ids: () => string[]
  index: () => number
  /** 当前内容的类型;不是 IMAGE 就完全不预取 */
  currentKind: () => string | undefined
  /** 查邻居的元数据(顺带充当类型过滤器) */
  fetchPreview: (id: string) => Promise<PrefetchPreview>
  /** 默认 new Image();单测注入假实现 */
  loadImage?: (url: string) => RollingLoad
  /** 本次允许预取几张 */
  budget?: () => number
}

const URL_TTL_MS = 10 * 60_000 // 预签名 15 分钟,留 5 分钟余量
const CACHE_MAX = 20

/**
 * 该预取哪几个下标。先下一个再上一个 —— 用户往后翻的概率更大,
 * 预算只有 1 时(弱网)就把名额给"下一张"。
 */
export function pickPrefetchIndices(len: number, index: number, budget: number): number[] {
  if (budget <= 0 || len <= 1) return []
  const out: number[] = []
  if (index + 1 < len) out.push(index + 1)
  if (out.length < budget && index - 1 >= 0) out.push(index - 1)
  return out.slice(0, budget)
}

/**
 * 按网络状况决定预取张数。没有 Network Information API 时按 2 张(桌面浏览器
 * 普遍不支持,但不支持通常意味着网络不差)。
 */
export function prefetchBudget(conn: { saveData?: boolean; effectiveType?: string } | undefined): number {
  if (conn?.saveData) return 0 // 用户明确省流量
  const t = conn?.effectiveType
  if (t === 'slow-2g' || t === '2g') return 0
  if (t === '3g') return 1
  return 2
}

function defaultLoadImage(url: string): RollingLoad {
  const img = new Image()
  img.decoding = 'async'
  img.src = url
  return {
    cancel() {
      // 置空 src 会让浏览器中止这次下载;已经下完的仍留在缓存里,不亏。
      img.removeAttribute('src')
    },
  }
}

export function useNeighborPrefetch(opts: PrefetchOptions) {
  const load = opts.loadImage ?? defaultLoadImage
  const budget = opts.budget ?? (() => 2)

  const urlCache = new Map<string, { url: string; at: number }>()
  let handles: RollingLoad[] = []
  /** 每次 run 自增,用于作废在途的旧一轮 */
  let generation = 0

  function clear() {
    generation++
    for (const h of handles) h.cancel()
    handles = []
  }

  function cachedUrl(id: string): string | undefined {
    const hit = urlCache.get(id)
    if (!hit) return undefined
    if (Date.now() - hit.at > URL_TTL_MS) {
      urlCache.delete(id)
      return undefined
    }
    return hit.url
  }

  function rememberUrl(id: string, url: string) {
    urlCache.set(id, { url, at: Date.now() })
    while (urlCache.size > CACHE_MAX) {
      const oldest = urlCache.keys().next().value
      if (oldest === undefined) break
      urlCache.delete(oldest)
    }
  }

  async function run(): Promise<void> {
    clear()
    const mine = generation
    if (opts.currentKind() !== 'IMAGE') return

    const ids = opts.ids()
    const targets = pickPrefetchIndices(ids.length, opts.index(), budget())
    if (targets.length === 0) return

    for (const i of targets) {
      const id = ids[i]
      if (!id) continue
      let url = cachedUrl(id)
      if (!url) {
        try {
          const p = await opts.fetchPreview(id)
          if (mine !== generation) return // 已切走,整轮作废
          if (p.kind !== 'IMAGE') continue
          url = p.largeUrl ?? p.contentUrl ?? undefined
          if (!url) continue
          rememberUrl(id, url)
        } catch {
          continue // 预取失败不打扰用户
        }
      }
      if (mine !== generation) return
      handles.push(load(url))
    }
  }

  return { run, clear }
}

/** navigator.connection 不在 TS 标准库里,这一处断言换掉一个类型依赖包。 */
export function currentConnection():
  | { saveData?: boolean; effectiveType?: string }
  | undefined {
  return (navigator as unknown as { connection?: { saveData?: boolean; effectiveType?: string } })
    .connection
}
