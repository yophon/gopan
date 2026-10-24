import { ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { NodeDownloadUrlDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { markOpened } from '@/composables/quick'
import type { NodeListItem } from '@/components/nodes/types'

function clickA(href: string, download?: string) {
  const a = document.createElement('a')
  a.href = href
  if (download) a.download = download
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/**
 * 下载:单个文件走 15 分钟预签名直链(即查即用);文件夹或多项走服务端流式 zip(/pack,token 同样 15 分钟)。
 * 文件页、搜索结果共用这一份。
 */
export function useNodeDownload() {
  const auth = useAuthStore()
  const queryClient = useQueryClient()
  const downloadingId = ref<string | null>(null)

  async function download(nodes: NodeListItem[]) {
    if (nodes.length === 0) return
    const only = nodes.length === 1 ? nodes[0]! : null
    if (!only || only.kind === 'FOLDER') {
      clickA(`/pack?nodes=${nodes.map((n) => n.id).join(',')}&token=${encodeURIComponent(auth.accessToken ?? '')}`)
      return
    }
    downloadingId.value = only.id
    try {
      const res = await request(NodeDownloadUrlDocument, { id: only.id })
      const url = res.node.downloadUrl
      if (!url) {
        ElMessage.error('无法获取下载链接')
        return
      }
      clickA(url, only.name)
      markOpened(queryClient, only.id)
    } catch (err) {
      ElMessage.error(errorText(err, '获取下载链接失败'))
    } finally {
      downloadingId.value = null
    }
  }

  return { download, downloadingId }
}
