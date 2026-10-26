/**
 * 桌面端(doc/29):网页在 gopan 桌面端的窗口里时,与外壳之间的桥。浏览器里 isDesktop() 为 false,什么都不做。
 *
 * - 登录凭据:原生那边不存凭据。token 变了就交过去;它要用而手上的快过期时发 desktop://need-token,这边刷新后再交。
 * - 外壳让网页跳路由:desktop://navigate(从小窗打开主窗口里的某个文件夹等)。
 * - 本机传输(下载到电脑、右键菜单上传)的任务列表:见 transfers.ts;右键菜单上传时选云端文件夹:见 pick.ts。
 * 外壳只对本站开放 desktop_* 这一小组命令(见 desktop/src-tauri/src/bridge.rs)。
 */
import { ref, watch } from 'vue'
import type { Router } from 'vue-router'

import { singleFlightRefresh } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useUploadsStore } from '@/stores/uploads'
import { tokenExpiring } from './token'
import { startDesktopEdits } from './edits'
import { addPick, type PickRequest } from './pick'
import { startDesktopTransfers } from './transfers'

interface TauriGlobal {
  core: { invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> }
  event: { listen<T>(event: string, handler: (e: { payload: T }) => void): Promise<() => void> }
}

function tauri(): TauriGlobal | null {
  return (window as unknown as { __TAURI__?: TauriGlobal }).__TAURI__ ?? null
}

/** 在桌面端的窗口里 */
export function isDesktop(): boolean {
  return tauri() !== null
}

/** 当前是哪个窗口:main 主窗口、mini 传输助手小窗;浏览器里(或外壳没给权限)为 null。响应式,菜单据此显示桌面端的项 */
const windowLabel = ref<string | null>(null)
export function desktopWindow(): string | null {
  return windowLabel.value
}

export function invokeDesktop<T = void>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const t = tauri()
  if (!t) return Promise.reject(new Error('不在桌面端里'))
  return t.core.invoke<T>(cmd, args)
}

export function onDesktop<T>(event: string, handler: (payload: T) => void): void {
  void tauri()
    ?.event.listen<T>(event, (e) => handler(e.payload))
    .catch(() => {})
}

/** 应用挂载后调一次:打招呼、交 token、接外壳的事件 */
export async function startDesktopBridge(router: Router): Promise<void> {
  if (!isDesktop()) return
  // 先挂上监听再打招呼:招呼的回复里带着还没答复的「选文件夹」,之后的走事件,两头都不漏
  onDesktop<PickRequest>('desktop://pick-folder', (r) => addPick(r))
  try {
    const hello = await invokeDesktop<{ version: string; window: string; picks?: PickRequest[] }>('desktop_hello')
    windowLabel.value = hello.window
    for (const p of hello.picks ?? []) addPick(p)
  } catch {
    // 外壳没给这个站点权限(比如服务器地址对不上):当普通网页用
    return
  }
  const auth = useAuthStore()

  // 只有主窗口交 token(小窗有自己的会话,外壳只认主窗口的)
  if (windowLabel.value === 'main') {
    const push = (token: string | null) => void invokeDesktop('desktop_set_token', { token }).catch(() => {})
    watch(
      () => auth.accessToken,
      (token, old) => {
        if (token) push(token)
        else if (old) push(null) // 真登出了才说;启动时还没刷新回来的 null 不算
      },
      { immediate: true },
    )
    onDesktop<null>('desktop://need-token', async () => {
      const current = auth.accessToken
      if (!current || tokenExpiring(current, 120)) await singleFlightRefresh(current ?? undefined)
      push(auth.accessToken)
    })
  }

  // 小窗只放会话(和没登录时的登录页);点到别处(打开文件所在的文件夹等)交给主窗口
  if (windowLabel.value === 'mini') {
    router.beforeEach((to) => {
      if (to.name === 'chat' || to.name === 'login') return true
      void invokeDesktop('desktop_open_main', { path: to.fullPath }).catch(() => {})
      return false
    })
  }

  onDesktop<string>('desktop://navigate', (path) => {
    if (typeof path === 'string' && path.startsWith('/')) void router.push(path)
  })

  const uploads = useUploadsStore()
  void startDesktopTransfers(() => {
    uploads.collapsed = false
  })
  void startDesktopEdits()
}
