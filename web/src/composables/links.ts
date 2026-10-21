import { computed, h } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { CreateLinksDocument, LinksEnabledDocument } from '@/api/gen/graphql'
import { formatLinks, linkFormatLabels } from '@/utils/links'
import type { LinkFormat } from '@/utils/links'

/** 本站是否开了直链(GOPAN_LINKS);关掉时所有入口都藏起来。不常变,拉一次就够 */
export function useLinksEnabled() {
  const { data } = useQuery({
    queryKey: ['linksEnabled'],
    queryFn: () => request(LinksEnabledDocument),
    staleTime: Infinity,
  })
  return computed(() => data.value?.linksEnabled ?? false)
}

/**
 * 写剪贴板。内容要等请求回来才有,而 Safari 只认点击同一拍里发起的剪贴板操作:
 * 支持 ClipboardItem 就先把它交出去、内容晚点再填;不行再退回 writeText。
 * text 失败(请求出错)时原样抛出;剪贴板本身写不进去抛 ClipboardError,由调用方让用户手动复制。
 */
export class ClipboardError extends Error {
  constructor(readonly text: string) {
    super('clipboard')
  }
}

export async function writeClipboard(text: Promise<string>): Promise<string> {
  if (typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
    try {
      await navigator.clipboard.write([
        new ClipboardItem({ 'text/plain': text.then((t) => new Blob([t], { type: 'text/plain' })) }),
      ])
      return await text
    } catch {
      // 下面 await text 会把请求的错误抛出去;请求没错就再用 writeText 试一次
    }
  }
  const t = await text
  try {
    await navigator.clipboard.writeText(t)
  } catch {
    throw new ClipboardError(t)
  }
  return t
}

/** 剪贴板写不进去(http 页面、权限被拒)时把内容弹出来让人手动复制 */
export function showManualCopy(text: string, title = '复制失败,请手动复制') {
  // 多行(多选时一行一条)放进只读文本框,方便全选
  const message = h('textarea', {
    value: text,
    readonly: true,
    rows: Math.min(8, Math.max(2, text.split('\n').length)),
    style: 'width:100%;box-sizing:border-box;font:12px/1.6 ui-monospace,monospace;resize:vertical',
    onFocus: (e: FocusEvent) => (e.target as HTMLTextAreaElement).select(),
  })
  void ElMessageBox.alert(message, title, { confirmButtonText: '关闭' }).catch(() => {})
}

/**
 * 「复制直链」:给这些文件生成(或拿回已有的)直链,按格式复制,一行一条。
 * 带拍摄位置的照片提醒一句(doc/22 四.3)。
 */
export async function copyDirectLinks(nodes: { id: string }[], fmt: LinkFormat): Promise<void> {
  if (nodes.length === 0) return
  const created = request(CreateLinksDocument, { nodeIds: nodes.map((n) => n.id) })
  try {
    await writeClipboard(created.then((res) => formatLinks(res.createLinks, fmt, location.origin)))
    const n = nodes.length
    ElMessage.success(n > 1 ? `已复制 ${n} 条直链(${linkFormatLabels[fmt]})` : `直链已复制(${linkFormatLabels[fmt]})`)
  } catch (err) {
    if (err instanceof ClipboardError) showManualCopy(err.text)
    else {
      ElMessage.error(errorText(err, '生成直链失败'))
      return
    }
  }
  const res = await created.catch(() => null)
  const gps = res?.createLinks.filter((l) => l.hasGps).length ?? 0
  if (gps > 0) {
    ElMessage.warning({
      message: gps > 1 ? `有 ${gps} 张照片带拍摄位置,公开后别人能看到` : '这张照片带拍摄位置,公开后别人能看到',
      duration: 6000,
    })
  }
}
