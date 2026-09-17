/**
 * PWA 注册:只在生产环境注册 service worker(allowlist 策略见 public/sw.js),
 * 并处理"新版本已就绪"的一次性刷新。dev 环境不注册,避免脏缓存干扰开发。
 */

const RELOAD_KEY = 'gopan_sw_reloaded_at'

export async function registerPwa(): Promise<void> {
  if (!import.meta.env.PROD) return
  if (!('serviceWorker' in navigator)) return

  try {
    const registration = await navigator.serviceWorker.register('/sw.js', {
      scope: '/',
      // 让浏览器每次都真实请求 sw.js,配合服务端 no-cache 才能及时发版
      updateViaCache: 'none',
    })

    // 长期挂着的已安装 App:页面重新可见时顺手检查更新(节流 1 小时)
    let lastUpdate = Date.now()
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return
      if (Date.now() - lastUpdate < 60 * 60 * 1000) return
      lastUpdate = Date.now()
      void registration.update()
    })

    // 新 SW 装好并接管后,整页刷新一次拿到新资源;sessionStorage 防刷新循环
    registration.addEventListener('updatefound', () => {
      const worker = registration.installing
      if (!worker) return
      worker.addEventListener('statechange', () => {
        if (worker.state !== 'activated') return
        const last = Number(sessionStorage.getItem(RELOAD_KEY) ?? 0)
        if (Date.now() - last < 10_000) return
        sessionStorage.setItem(RELOAD_KEY, String(Date.now()))
        location.reload()
      })
    })
  } catch {
    // 非 https 且非 localhost 时 register 会抛:静默降级成普通网页
  }
}
