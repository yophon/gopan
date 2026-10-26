/**
 * 桌面端「用本地程序打开」(doc/29):外壳把文件下到缓存、用系统默认程序打开,存盘后自动传回新版本。
 * 这里镜像外壳那边的列表(desktop://edits)给传输抽屉显示、转发操作。
 */
import { ref } from 'vue'

import { invokeDesktop, onDesktop } from './bridge'
import type { DesktopEdit } from './format'

export type { DesktopEdit } from './format'
export { editStatusText } from './format'

export const desktopEdits = ref<DesktopEdit[]>([])

export async function startDesktopEdits(): Promise<void> {
  onDesktop<DesktopEdit[]>('desktop://edits', (list) => {
    desktopEdits.value = Array.isArray(list) ? list : []
  })
  try {
    desktopEdits.value = await invokeDesktop<DesktopEdit[]>('desktop_edits')
  } catch {
    // 外壳旧版本没有这条命令
  }
}

/** 用本地程序打开;返回 true = 直接打开了,false = 先下载,下完自动打开 */
export function openLocal(nodeId: string): Promise<boolean> {
  return invokeDesktop<boolean>('desktop_open_local', { id: nodeId })
}

/** open / reveal / stop;stop 时本地还有没传回的改动,副本留着,返回它的位置 */
export function editAction(nodeId: string, action: 'open' | 'reveal' | 'stop'): Promise<string | null> {
  return invokeDesktop<string | null>('desktop_edit_action', { nodeId, action })
}
