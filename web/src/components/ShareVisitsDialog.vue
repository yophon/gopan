<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { ShareVisitsDocument } from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'
import { describeUserAgent } from '@/utils/userAgent'
import { useBreakpoints } from '@/composables/breakpoints'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{ shareId: string; shareName: string }>()

const { isMobile } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: computed(() => ['shareVisits', props.shareId]),
  enabled: computed(() => visible.value && !!props.shareId),
  queryFn: () => request(ShareVisitsDocument, { shareId: props.shareId, limit: 50 }),
})
const items = computed(() => data.value?.shareVisits ?? [])

const KIND_TEXT: Record<string, string> = {
  verify: '打开分享',
  download: '下载文件',
  pack: '打包下载',
}

function kindText(kind: string): string {
  return KIND_TEXT[kind] ?? kind
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`访问记录 · ${shareName}`"
    width="min(700px, 94vw)"
    :fullscreen="isMobile"
  >
    <p class="hint">
      只保留最近 180 天的记录。「下载」统计的是发起下载的次数 —— 文件字节直连对象存储,
      服务端看不到传完的时刻。
    </p>
    <el-table
      v-loading="isFetching"
      :data="items"
      empty-text="还没有访问记录"
      max-height="420"
    >
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="动作" width="110">
        <template #default="{ row }">{{ kindText(row.kind) }}</template>
      </el-table-column>
      <el-table-column label="IP" width="140">
        <template #default="{ row }">{{ row.ip ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="客户端" min-width="180">
        <template #default="{ row }">
          <span :title="row.userAgent ?? ''">{{ describeUserAgent(row.userAgent) }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
