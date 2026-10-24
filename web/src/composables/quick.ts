import { useQueryClient, type QueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { MarkOpenedDocument, SetStarredDocument } from '@/api/gen/graphql'
import type { NodeListItem } from '@/components/nodes/types'

/**
 * 收藏与最近打开(doc/24)。收藏改完刷新列表与收藏页;打开一个文件(预览、下载)上报给服务端,进「最近」。
 */
export function useStars() {
  const queryClient = useQueryClient()

  /** 选中的里面只要有一个没收藏,就是「收藏」;全收藏了才是「取消收藏」 */
  function allStarred(nodes: NodeListItem[]): boolean {
    return nodes.length > 0 && nodes.every((n) => !!n.starred)
  }

  async function setStarred(nodes: NodeListItem[], starred: boolean) {
    if (!nodes.length) return
    try {
      await request(SetStarredDocument, { ids: nodes.map((n) => n.id), starred })
      refreshAfterStar(queryClient)
      ElMessage.success(starred ? (nodes.length > 1 ? `已收藏 ${nodes.length} 项` : '已收藏') : '已取消收藏')
    } catch (err) {
      ElMessage.error(errorText(err, starred ? '收藏失败' : '取消收藏失败'))
    }
  }

  function toggle(nodes: NodeListItem[]) {
    return setStarred(nodes, !allStarred(nodes))
  }

  return { allStarred, setStarred, toggle }
}

export function refreshAfterStar(queryClient: QueryClient) {
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  void queryClient.invalidateQueries({ queryKey: ['starred'] })
  void queryClient.invalidateQueries({ queryKey: ['nodeDetail'] })
}

/** 记一次打开;失败无所谓(只影响「最近」列表) */
export function markOpened(queryClient: QueryClient, nodeId: string) {
  void request(MarkOpenedDocument, { nodeId })
    .then(() => queryClient.invalidateQueries({ queryKey: ['recent'] }))
    .catch(() => {})
}
