/**
 * 桌面端的本机传输(doc/29 D2):下载落到电脑上的文件夹、右键菜单发起的上传都由外壳的传输引擎做,
 * 这里只镜像它的任务列表(desktop://tasks,每半秒一次)给传输抽屉显示、转发操作。
 */
import { ref } from 'vue'

import { desktopWindow, invokeDesktop, onDesktop } from './bridge'
import type { DesktopTask, DesktopTaskAction } from './format'

export type { DesktopTask, DesktopTaskAction, DesktopTaskState } from './format'
export { desktopPercent, desktopStatusText, isActiveDesktopTask } from './format'

export const desktopTasks = ref<DesktopTask[]>([])
/** 每个在传任务的速度(字节/秒),按两次推送之间的差估 */
export const desktopSpeeds = ref<Record<number, number>>({})

const samples = new Map<number, { done: number; at: number }>()

function receive(list: DesktopTask[]) {
  const now = Date.now()
  const speeds: Record<number, number> = {}
  for (const t of list) {
    const prev = samples.get(t.id)
    if (t.state === 'running' && t.phase === 'transferring' && prev && now > prev.at) {
      const inst = (Math.max(0, t.done - prev.done) * 1000) / (now - prev.at)
      const old = desktopSpeeds.value[t.id]
      speeds[t.id] = old === undefined ? inst : old * 0.7 + inst * 0.3
    }
    samples.set(t.id, { done: t.done, at: now })
  }
  for (const id of samples.keys()) if (!list.some((t) => t.id === id)) samples.delete(id)
  desktopSpeeds.value = speeds
  desktopTasks.value = list
}

/** 桥接起来后调一次:取一份现状,之后跟着推送走 */
export async function startDesktopTransfers(onOpen: () => void): Promise<void> {
  onDesktop<DesktopTask[]>('desktop://tasks', (list) => receive(Array.isArray(list) ? list : []))
  onDesktop<null>('desktop://open-transfers', () => onOpen())
  try {
    receive(await invokeDesktop<DesktopTask[]>('desktop_tasks'))
  } catch {
    // 外壳旧版本没有这条命令:当没有本机任务
  }
}

/** 外壳接管下载(桥接成功了才算,权限没给到时退回浏览器下载) */
export function nativeDownloads(): boolean {
  return desktopWindow() !== null
}

interface DownloadNode {
  id: string
  name: string
  kind: string
  size?: number | null
}

/** 交给外壳下载;[choose] 先选存到哪个文件夹。返回存到的目录,选目录时取消了返回 null */
export function desktopDownload(nodes: DownloadNode[], choose = false): Promise<string | null> {
  const items = nodes.map((n) => ({ id: n.id, name: n.name, isFolder: n.kind === 'FOLDER', size: n.size ?? null }))
  return invokeDesktop<string | null>('desktop_download', { items, choose })
}

export function desktopTaskAction(id: number | null, action: DesktopTaskAction): Promise<void> {
  return invokeDesktop('desktop_task_action', { id, action })
}
