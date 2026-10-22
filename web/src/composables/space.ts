import { useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { DeleteNodesDocument } from '@/api/gen/graphql'
import { formatBytes } from '@/utils/format'

/**
 * 空间页的清理(doc/23 三.1):一律移进回收站(能还原,也记进动态),删完把空间分析与相关列表都刷新。
 */
export function useSpaceCleanup() {
  const queryClient = useQueryClient()

  function refresh(extra: string[] = []) {
    for (const key of ['space', 'me', 'children', 'breadcrumb', 'trash', 'activities', ...extra]) {
      void queryClient.invalidateQueries({ queryKey: [key] })
    }
  }

  /** 确认后移进回收站;bytes 给了就在确认框里说能省多少配额。返回是否删了 */
  async function trash(ids: string[], what: string, bytes?: number): Promise<boolean> {
    if (ids.length === 0) return false
    const save = bytes ? `,能省 ${formatBytes(bytes)} 配额(清空回收站或到期后才真正释放)` : ''
    try {
      await ElMessageBox.confirm(`把选中的 ${ids.length} ${what}移进回收站${save}。可以在回收站还原。`, '移进回收站?', {
        confirmButtonText: '移进回收站',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return false
    }
    try {
      await request(DeleteNodesDocument, { ids })
      ElMessage.success(`已把 ${ids.length} ${what}移进回收站`)
      return true
    } catch (err) {
      ElMessage.error(errorText(err, '删除失败'))
      return false
    } finally {
      refresh()
    }
  }

  return { refresh, trash }
}
