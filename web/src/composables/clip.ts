import { request } from '@/api/client'
import { JobDocument } from '@/api/gen/graphql'
import type { JobFieldsFragment } from '@/api/gen/graphql'
import { isActiveJob } from '@/utils/jobs'

const POLL_MS = 1500

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

/**
 * 等一个后台任务结束(剪藏页与书签栏小窗用;上传抽屉有自己的列表轮询)。
 * 每次拿到新状态回调一次;alive() 为 false(页面关了)就不再查,返回 null。
 */
export async function waitForJob(
  id: string,
  onUpdate: (j: JobFieldsFragment) => void,
  alive: () => boolean,
): Promise<JobFieldsFragment | null> {
  for (;;) {
    if (!alive()) return null
    const j = (await request(JobDocument, { id })).job
    if (!alive()) return null
    onUpdate(j)
    if (!isActiveJob(j)) return j
    await sleep(POLL_MS)
  }
}
