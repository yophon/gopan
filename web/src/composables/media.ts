import { request } from '@/api/client'
import { SaveProgressDocument } from '@/api/gen/graphql'
import { worthSavingProgress } from '@/utils/media'

/**
 * 报一次播放进度(doc/22 二.3):尽力而为,失败不提示。
 * 太短的(5 分钟以下的音视频、3 页以下的 PDF)服务端本来就不记,这里直接不报。
 */
export function reportProgress(nodeId: string, position: number, duration: number, isPdf = false): void {
  if (!Number.isFinite(position) || !Number.isFinite(duration) || duration <= 0) return
  if (!worthSavingProgress(isPdf, duration)) return
  void request(SaveProgressDocument, { nodeId, position: Math.max(0, position), duration }).catch(() => {
    // 进度丢一次没关系,下一次会补上
  })
}
