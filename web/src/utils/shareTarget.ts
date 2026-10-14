/**
 * PWA Web Share Target(POST)落地:sw.js 把系统分享菜单 POST 来的表单存进 IndexedDB
 * (库 gopan-share,表 pending),然后 303 到 /chat?share=<id>;页面这边按 id 读出、删掉,
 * 转成 IncomingShare 交给 stores/incomingShare。
 *
 * IndexedDB 的键名、库名必须与 public/sw.js 一致。
 */

export const SHARE_DB = 'gopan-share'
export const SHARE_STORE = 'pending'
export const SHARE_QUERY_KEY = 'share'

export interface SharedFileRecord {
  name: string
  type: string
  blob: Blob
}

export interface ShareRecord {
  id: string
  title?: string
  text?: string
  url?: string
  files?: SharedFileRecord[]
}

/** 从路由 query 里取 share id;没有 / 不是字符串返回 null */
export function shareIdFrom(query: Record<string, unknown>): string | null {
  const v = query[SHARE_QUERY_KEY]
  const s = Array.isArray(v) ? v[0] : v
  return typeof s === 'string' && s.length > 0 ? s : null
}

/** 把 SW 存的记录转成传输助手可直接消费的形态:标题/正文/链接拼成文本(去重),blob 包回 File */
export function recordToShare(rec: ShareRecord): { text?: string; files?: File[] } {
  const parts = [rec.title, rec.text, rec.url].map((s) => (typeof s === 'string' ? s.trim() : '')).filter((s) => s.length > 0)
  // 去重:被别的字段整个包含的(标题/链接常常已经在正文里)丢掉;完全相同的只留第一个
  const uniq = parts.filter(
    (p, i) => parts.indexOf(p) === i && !parts.some((q, j) => j !== i && q !== p && q.includes(p)),
  )
  const text = uniq.join('\n')
  const files = (rec.files ?? []).map((f) => new File([f.blob], f.name || 'shared', { type: f.type || f.blob.type }))
  return {
    ...(text ? { text } : {}),
    ...(files.length ? { files } : {}),
  }
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(SHARE_DB, 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(SHARE_STORE)) db.createObjectStore(SHARE_STORE, { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('indexedDB open failed'))
  })
}

function reqToPromise<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('indexedDB request failed'))
  })
}

/** 读出并删除一条分享记录;不存在 / IndexedDB 不可用返回 null */
export async function takeShareRecord(id: string): Promise<ShareRecord | null> {
  if (typeof indexedDB === 'undefined') return null
  let db: IDBDatabase
  try {
    db = await openDb()
  } catch {
    return null
  }
  try {
    const tx = db.transaction(SHARE_STORE, 'readwrite')
    const store = tx.objectStore(SHARE_STORE)
    const rec = (await reqToPromise(store.get(id))) as ShareRecord | undefined
    if (rec) await reqToPromise(store.delete(id))
    return rec ?? null
  } catch {
    return null
  } finally {
    db.close()
  }
}
