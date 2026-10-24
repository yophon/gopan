/**
 * 桌面端右键菜单「上传到 gopan…」(doc/29 D5):外壳收到本地文件后让主窗口里的网页选云端文件夹,
 * 选好(或取消)交回去,外壳的传输引擎接着上传。外壳发 desktop://pick-folder;网页刚打开时从 desktop_hello 里取还没答复的。
 */
import { ref } from 'vue'

import { invokeDesktop } from './bridge'
import type { PickRequest } from './format'

export type { PickRequest } from './format'
export { pickTitle } from './format'

export const pickQueue = ref<PickRequest[]>([])

export function addPick(r: PickRequest): void {
  if (typeof r?.requestId !== 'number' || pickQueue.value.some((p) => p.requestId === r.requestId)) return
  pickQueue.value = [...pickQueue.value, r]
}

/** [folderId]:选中的文件夹(null = 根目录「我的文件」);undefined = 取消 */
export async function finishPick(r: PickRequest, folderId: string | null | undefined): Promise<void> {
  pickQueue.value = pickQueue.value.filter((p) => p.requestId !== r.requestId)
  await invokeDesktop('desktop_pick_folder_done', {
    requestId: r.requestId,
    folderId: folderId ?? null,
    cancelled: folderId === undefined,
  }).catch(() => {})
}
