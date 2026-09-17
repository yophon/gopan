/*
 * gopan 的 service worker:只做"已安装后二次打开更快"这一件事,不做离线。
 *
 * 策略是 allowlist:只有 /assets/ 下的带 hash 静态资源会进缓存,其余请求
 * (GraphQL /query、WebDAV /dav、打包 /pack、MCP /mcp、OAuth、访客页 /s/、
 * MinIO 反代 /gopan/*、预签名 URL)一个都不碰 —— 上传与下载在结构上不可能
 * 被 SW 影响,而不是靠记得排除。
 */

const CACHE = 'gopan-assets-v1'

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
