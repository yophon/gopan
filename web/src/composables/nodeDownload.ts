import { ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { NodeDownloadUrlDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { useUploadsStore } from '@/stores/uploads'
import { markOpened } from '@/composables/quick'
import type { NodeListItem } from '@/components/nodes/types'
import { desktopDownload, nativeDownloads } from '@/desktop/transfers'

function clickA(href: string, download?: string) {
  const a = document.createElement('a')
  a.href = href
  if (download) a.download = download
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** 菜单里「下载」的叫法:浏览器里文件夹、多项是打包成 zip;桌面端原样下到电脑上 */
export function downloadLabel(nodes: { kind: string }[]): string {
  if (nativeDownloads()) return '下载'
  return nodes.length === 1 && nodes[0]!.kind === 'FILE' ? '下载' : '打包下载'
}

/**
 * 桌面端:交给外壳的传输引擎,文件夹按原来的目录结构下到电脑上(doc/29 D2)。
 * [choose] 先选存到哪个文件夹;否则存到设置里的下载目录。
 */
export async function downloadToComputer(nodes: { id: string; name: string; kind: string; size?: number | null }[], choose = false) {
  if (nodes.length === 0) return
  try {
    const dir = await desktopDownload(nodes, choose)
    if (!dir) return
    ElMessage.success(`开始下载,存到 ${dir}`)
    useUploadsStore().collapsed = false
  } catch (err) {
    ElMessage.error(typeof err === 'string' ? err : errorText(err, '下载失败'))
  }
}

/**
 * 下载:单个文件走 15 分钟预签名直链(即查即用);文件夹或多项走服务端流式 zip(/pack,token 同样 15 分钟)。
 * 桌面端交给外壳(见 downloadToComputer)。文件页、搜索结果共用这一份。
 */
export function useNodeDownload() {
  const auth = useAuthStore()
  const queryClient = useQueryClient()
  const downloadingId = ref<string | null>(null)

  async function download(nodes: NodeListItem[]) {
    if (nodes.length === 0) return
    if (nativeDownloads()) {
      await downloadToComputer(nodes)
      for (const n of nodes) if (n.kind === 'FILE') markOpened(queryClient, n.id)
      return
    }
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
