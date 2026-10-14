/*
 * gopan 的 service worker:只做"已安装后二次打开更快"这一件事,不做离线。
 *
 * 策略是 allowlist:只有 /assets/ 下的带 hash 静态资源会进缓存,其余请求
 * (GraphQL /query、WebDAV /dav、打包 /pack、MCP /mcp、OAuth、访客页 /s/、
 * MinIO 反代 /gopan/*、预签名 URL)一个都不碰 —— 上传与下载在结构上不可能
 * 被 SW 影响,而不是靠记得排除。
 */

const CACHE = 'gopan-assets-v1'

// Web Share Target(POST):系统分享菜单把文件/文本 POST 到 /chat/share,这里不能把表单
// 交给服务端(服务端没有这个路由,也不该为分享多一条上传路径),而是落进 IndexedDB,
// 303 跳到 /chat?share=<id>,页面读出后走正常的传输助手上传。库名/表名与 src/utils/shareTarget.ts 一致。
const SHARE_DB = 'gopan-share'
const SHARE_STORE = 'pending'
const SHARE_PATH = '/chat/share'

function openShareDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(SHARE_DB, 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(SHARE_STORE)) db.createObjectStore(SHARE_STORE, { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function stashShare(formData) {
  const id = (self.crypto && self.crypto.randomUUID) ? self.crypto.randomUUID() : String(Date.now())
  const files = []
  for (const f of formData.getAll('files')) {
    if (f && typeof f === 'object' && 'size' in f) files.push({ name: f.name || '', type: f.type || '', blob: f })
  }
  const pick = (k) => {
    const v = formData.get(k)
    return typeof v === 'string' ? v : ''
  }
  const record = { id, title: pick('title'), text: pick('text'), url: pick('url'), files }
  const db = await openShareDb()
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction(SHARE_STORE, 'readwrite')
      tx.objectStore(SHARE_STORE).put(record)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
      tx.onabort = () => reject(tx.error)
    })
  } finally {
    db.close()
  }
  return id
}

self.addEventListener('install', (event) => {
  // 不预缓存任何东西;新 SW 立即接管
  self.skipWaiting()
  event.waitUntil(Promise.resolve())
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      // 清掉历史版本的缓存
      const keys = await caches.keys()
      await Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
      await self.clients.claim()
    })(),
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  // 0. 唯一处理的非 GET:同源 POST /chat/share(Web Share Target)。
  //    没有 IndexedDB 的环境直接放行,服务端 404,等于分享失败,但不影响别的请求。
  if (req.method === 'POST') {
    const url = new URL(req.url)
    if (url.origin === self.location.origin && url.pathname === SHARE_PATH && typeof indexedDB !== 'undefined') {
      event.respondWith(
        (async () => {
          try {
            const id = await stashShare(await req.formData())
            return Response.redirect(`/chat?share=${encodeURIComponent(id)}`, 303)
          } catch {
            return Response.redirect('/chat', 303)
          }
        })(),
      )
    }
    return
  }
  // 1. 只管 GET:POST /query 等一律放行
  if (req.method !== 'GET') return
  // 2. 只管同源:MinIO 独立域名部署时预签名 URL 天然不经过这里
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  // 3. 预签名 URL 双保险:就算哪天反代路径变了,带签名的也绝不进缓存
  if (url.searchParams.has('X-Amz-Signature')) return
  // 4. allowlist:只有带内容 hash 的构建产物可缓存
  if (!url.pathname.startsWith('/assets/')) return

  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE)
      const hit = await cache.match(req)
      if (hit) return hit
      const res = await fetch(req)
      // 只缓存完整成功的响应
      if (res && res.status === 200 && res.type === 'basic') {
        await cache.put(req, res.clone())
      }
      return res
    })(),
  )
})
