import { useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ExtractArchiveDocument } from '@/api/gen/graphql'
import { useUploadsStore } from '@/stores/uploads'

export interface ExtractTarget {
  nodeId: string
  /** 包内当前目录:解出的路径相对它 */
  base?: string
  /** 包内完整路径;空 = base 下的全部 */
  paths?: string[]
  /** 新文件夹建在哪;null = 根目录 */
  parentId: string | null
}

/** 提交解压(doc/21 四.4):后台任务,进度在上传抽屉的「后台任务」里。 */
export function useExtract() {
  const queryClient = useQueryClient()
  const uploads = useUploadsStore()
  async function extract(t: ExtractTarget): Promise<boolean> {
    try {
      await request(ExtractArchiveDocument, {
        nodeId: t.nodeId,
        base: t.base || null,
        paths: t.paths && t.paths.length > 0 ? t.paths : null,
        parentId: t.parentId,
      })
      ElMessage.success('已开始解压,进度见右下角「传输」')
      uploads.collapsed = false
      void queryClient.invalidateQueries({ queryKey: ['jobs'] })
      return true
    } catch (err) {
      ElMessage.error(errorText(err, '解压失败'))
      return false
    }
  }
  return { extract }
}
