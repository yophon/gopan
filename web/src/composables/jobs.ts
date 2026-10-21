import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { FetchEnabledDocument, MyJobsDocument } from '@/api/gen/graphql'
import type { MyJobsQuery } from '@/api/gen/graphql'
import { isActiveJob, jobWorthShowing } from '@/utils/jobs'

export type JobItem = MyJobsQuery['myJobs']['items'][number]

/**
 * 后台任务列表(离线下载、解压):有进行中的就每 2 秒轮询,没有就停。
 * 任务变成已完成时刷新目标文件夹与用量。全局只挂一处(上传抽屉)。
 */
export function useJobs(enabled: Ref<boolean>) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['jobs'],
    enabled,
    queryFn: () => request(MyJobsDocument, { active: false, cursor: null }),
    refetchInterval: (q) => (q.state.data?.myJobs.items.some(isActiveJob) ? 2000 : false),
    refetchOnWindowFocus: true,
  })
  // 服务端保留 7 天;抽屉只放值得看的:进行中、刚结束的、一天内失败的(失败了得让人看见)
  const items = computed<JobItem[]>(() =>
    (query.data.value?.myJobs.items ?? []).filter((j) => jobWorthShowing(j, Date.now())),
  )
  const activeCount = computed(() => items.value.filter(isActiveJob).length)
  /** 服务端保留的全部(7 天内),通知点进来时看 */
  const allItems = computed<JobItem[]>(() => query.data.value?.myJobs.items ?? [])

  // 速度:两次轮询之间的字节差
  const speeds = ref<Record<string, number>>({})
  const last = new Map<string, { status: string; bytes: number; at: number }>()
  watch(items, (list) => {
    const now = Date.now()
    const next: Record<string, number> = {}
    for (const j of list) {
      const prev = last.get(j.id)
      if (prev && j.status === 'RUNNING' && prev.status === 'RUNNING' && now > prev.at) {
        next[j.id] = Math.max(0, ((j.bytesDone - prev.bytes) * 1000) / (now - prev.at))
      }
      if (prev && prev.status !== j.status && j.status === 'DONE') {
        void queryClient.invalidateQueries({ queryKey: ['children', j.parentId ?? null] })
        if (j.kind === 'EXTRACT_ARCHIVE' && j.result) {
          void queryClient.invalidateQueries({ queryKey: ['children', j.result.id] })
        }
        void queryClient.invalidateQueries({ queryKey: ['me'] })
        void queryClient.invalidateQueries({ queryKey: ['chatMessages'] })
      }
      last.set(j.id, { status: j.status, bytes: j.bytesDone, at: now })
    }
    speeds.value = next
  })

  return { ...query, items, allItems, activeCount, speeds }
}

/** 本站是否开了离线下载;不常变,拉一次就够 */
export function useFetchEnabled() {
  const { data } = useQuery({
    queryKey: ['fetchEnabled'],
    queryFn: () => request(FetchEnabledDocument),
    staleTime: Infinity,
  })
  return computed(() => data.value?.fetchEnabled ?? false)
}
