<script setup lang="ts">
import { computed } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Close, Document, Headset, VideoPlay } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ClearProgressDocument, RecentPlaysDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { openPreview } from '@/composables/preview'
import { formatRelative } from '@/utils/format'
import { progressPercent, progressText } from '@/utils/media'

/** 最近播放(doc/22 二.3):没看完的音视频与 PDF,点一下接着看 */

const visible = defineModel<boolean>({ required: true })
const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: ['recentPlays'],
  queryFn: () => request(RecentPlaysDocument, { limit: 20 }),
  enabled: visible,
  staleTime: 0,
})
const items = computed(() => data.value?.recentPlays ?? [])

const clearMutation = useMutation({
  mutationFn: (nodeId: string) => request(ClearProgressDocument, { nodeId }),
  onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['recentPlays'] }),
  onError: (err) => ElMessage.error(errorText(err, '移除失败')),
})

function kindIcon(kind: string | undefined) {
  if (kind === 'AUDIO') return Headset
  if (kind === 'VIDEO') return VideoPlay
  return Document
}

function open(nodeId: string) {
  visible.value = false
  openPreview([nodeId], 0)
}
</script>

<template>
  <el-dialog v-model="visible" title="最近播放" width="min(520px, 94vw)" :fullscreen="isMobile">
    <div v-loading="isFetching && !data" class="recent">
      <el-empty v-if="!items.length && !isFetching" description="没有看到一半的视频、音乐或 PDF" />
      <ul v-else class="list">
        <li v-for="it in items" :key="it.node.id" class="item" @click="open(it.node.id)">
          <el-icon class="kind-icon" :size="20"><component :is="kindIcon(it.node.preview.kind)" /></el-icon>
          <div class="main">
            <div class="name" :title="it.node.name">{{ it.node.name }}</div>
            <el-progress
              :percentage="progressPercent(it.progress)"
              :stroke-width="4"
              :show-text="false"
              class="bar"
            />
            <div class="meta">
              {{ progressText(it.progress, it.node.preview.kind === 'PDF') }} · {{ formatRelative(it.progress.updatedAt) }}
            </div>
          </div>
          <el-button
            link
            :icon="Close"
            title="从最近播放里去掉"
            aria-label="从最近播放里去掉"
            @click.stop="clearMutation.mutate(it.node.id)"
          />
        </li>
      </ul>
    </div>
  </el-dialog>
</template>

<style scoped>
.recent {
  min-height: 120px;
}
.list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.item:hover {
  background: var(--el-fill-color-light);
}
.kind-icon {
  flex: none;
  color: var(--el-color-primary);
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
</style>
