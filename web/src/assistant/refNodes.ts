import { reactive } from 'vue'

import { request } from '@/api/client'
import { NodesByIdsDocument, NodesByPathsDocument, type NodesByIdsQuery } from '@/api/gen/graphql'
import { isPathRef } from './refs'

/**
 * 回答里引用的文件现查(doc/35 六):按 id 或按路径(引用里写的是哪个就按哪个,键也就是它);
 * 几条回答里的引用攒一下一次查完;查不到的记 null(显示「已不在」)。
 * 缩略图地址是预签名的,15 分钟过期,所以 10 分钟后再用到会重新查。
 */

export type RefNode = NodesByIdsQuery['nodesByIds'][number]

const FRESH_MS = 10 * 60_000

export const refNodes = reactive(new Map<string, RefNode | null>())
const fetchedAt = new Map<string, number>()
const queued = new Set<string>()
let timer: ReturnType<typeof setTimeout> | null = null

export function ensureRefs(keys: readonly string[]) {
  const now = Date.now()
  for (const key of keys) {
    const at = fetchedAt.get(key)
    if (at === undefined || now - at > FRESH_MS) queued.add(key)
  }
  if (queued.size && !timer) timer = setTimeout(() => void flush(), 30)
}

async function flush() {
  timer = null
  const batch = [...queued].slice(0, 100)
  for (const key of batch) {
    queued.delete(key)
    fetchedAt.set(key, Date.now()) // 查着的不再排
  }
  const ids = batch.filter((k) => !isPathRef(k))
  const paths = batch.filter(isPathRef)
  try {
    const [byId, byPath] = await Promise.all([
      ids.length ? request(NodesByIdsDocument, { ids }).then((r) => r.nodesByIds) : [],
      paths.length ? request(NodesByPathsDocument, { paths }).then((r) => r.nodesByPaths) : [],
    ])
    const found = new Map<string, RefNode>()
    for (const n of byId) found.set(n.id, n)
    for (const n of byPath) if (n.path) found.set(n.path, n)
    for (const key of batch) refNodes.set(key, found.get(key) ?? null)
  } catch {
    for (const key of batch) fetchedAt.delete(key) // 网络错误:下次再查
  }
  if (queued.size) timer = setTimeout(() => void flush(), 30)
}
