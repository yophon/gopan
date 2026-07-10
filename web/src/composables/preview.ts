import { reactive } from 'vue'

/**
 * 全局预览状态:PreviewModal 是挂在 AppShell 下的单例,
 * 列表页通过 openPreview(ids, index) 唤起,模态内部左右切换只改 index。
 * 服务端数据(node.preview)不在这里,归 vue-query 管;这里只有客户端 UI 态。
 */
interface PreviewState {
  visible: boolean
  /** 可预览节点 id 列表(当前列表里的全部文件,保持列表顺序) */
  ids: string[]
  /** 当前预览的下标 */
  index: number
}

export const previewState = reactive<PreviewState>({
  visible: false,
  ids: [],
  index: 0,
})

export function openPreview(ids: string[], index: number): void {
  if (ids.length === 0) return
  previewState.ids = [...ids]
  previewState.index = Math.min(Math.max(index, 0), ids.length - 1)
  previewState.visible = true
}

export function closePreview(): void {
  previewState.visible = false
}

export function previewPrev(): void {
  if (previewState.index > 0) previewState.index--
}

export function previewNext(): void {
  if (previewState.index < previewState.ids.length - 1) previewState.index++
}
