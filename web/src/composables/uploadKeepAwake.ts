import { onBeforeUnmount, onMounted, watch, type Ref } from 'vue'

/**
 * 访客页传文件时:屏幕别熄(手机熄屏后浏览器会停掉上传),关页面前问一声。
 * 收集链接与分享访客页共用(doc/20 二.5、doc/32 一)。
 */
export function useUploadKeepAwake(busy: Ref<boolean>) {
  let wakeLock: { release: () => Promise<void> } | null = null

  async function hold(on: boolean) {
    const wl = (navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }).wakeLock
    if (!wl) return
    try {
      if (on && !wakeLock) wakeLock = await wl.request('screen')
      if (!on && wakeLock) {
        await wakeLock.release()
        wakeLock = null
      }
    } catch {
      wakeLock = null // 省电模式等会拒绝,不影响上传
    }
  }

  watch(busy, (b) => void hold(b))

  function onVisibility() {
    // 切回前台时系统已经释放了屏幕常亮,重新申请
    if (document.visibilityState === 'visible' && busy.value) {
      wakeLock = null
      void hold(true)
    }
  }

  function onBeforeUnload(e: BeforeUnloadEvent) {
    if (busy.value) e.preventDefault()
  }

  onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
    document.addEventListener('visibilitychange', onVisibility)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
    document.removeEventListener('visibilitychange', onVisibility)
    void hold(false)
  })
}
