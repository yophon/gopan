<script setup lang="ts">
import { computed } from 'vue'
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ActivitiesDocument, NodeDetailDocument } from '@/api/gen/graphql'
import type { ActivityFieldsFragment } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { useUndo } from '@/composables/undo'
import { formatBytes, formatTime } from '@/utils/format'
import { folderSizeText } from '@/components/nodes/nodeDisplay'
import NodeIcon from '@/components/nodes/NodeIcon.vue'
import ActivityItem from '@/components/activity/ActivityItem.vue'

/**
 * 文件详情(doc/23 二.4):基本信息 + 「历史」—— 这个文件 / 文件夹自己的动态(改名、移动、删除、还原……),
 * 每条能撤回。子树里别的文件的改动不在这里,去动态页看。
 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ nodeId: string | null }>()

const { isMobile } = useBreakpoints()
const { busy, undoOne } = useUndo()
const open = computed(() => visible.value && !!props.nodeId)

const { data, isFetching, isError, error } = useQuery({
  queryKey: computed(() => ['nodeDetail', props.nodeId]),
  queryFn: () => request(NodeDetailDocument, { id: props.nodeId! }),
  enabled: open,
  staleTime: 0,
})
const node = computed(() => data.value?.node ?? null)

const history = useInfiniteQuery({
  queryKey: computed(() => ['activities', 'node', props.nodeId]),
  queryFn: ({ pageParam }) =>
    request(ActivitiesDocument, { filter: { nodeId: props.nodeId! }, cursor: pageParam, limit: 50 }),
  enabled: open,
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.activities.nextCursor ?? null,
  staleTime: 0,
})
const items = computed<ActivityFieldsFragment[]>(
  () => history.data.value?.pages.flatMap((p) => p.activities.items) ?? [],
)

const sizeLine = computed(() => {
  const n = node.value
  if (!n) return ''
  if (n.kind === 'FOLDER') {
    const count = n.subtreeCount != null ? `${n.subtreeCount} 个文件 · ` : ''
    return `${count}${folderSizeText(n)}`
  }
  return formatBytes(n.size ?? 0)
})
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    :size="isMobile ? '100%' : '480px'"
    :title="node?.name ?? '详情'"
    append-to-body
    class="node-detail-drawer"
  >
    <div v-loading="isFetching && !node" class="nd">
      <el-empty v-if="isError" :description="errorText(error, '文件不存在或已被删除')" />
      <template v-else-if="node">
        <section class="nd-head">
          <NodeIcon :node="node" :size="44" />
          <dl class="nd-info">
            <dt>大小</dt>
            <dd>{{ sizeLine }}<span v-if="node.kind === 'FOLDER' && node.statsStale" class="muted">(统计更新中)</span></dd>
            <template v-if="node.mime">
              <dt>类型</dt>
              <dd>{{ node.mime }}</dd>
            </template>
            <dt>创建</dt>
            <dd>{{ formatTime(node.createdAt) }}</dd>
            <dt>修改</dt>
            <dd>{{ formatTime(node.updatedAt) }}</dd>
            <template v-if="node.sha256">
              <dt>SHA-256</dt>
              <dd class="mono" :title="node.sha256">{{ node.sha256.slice(0, 16) }}…</dd>
            </template>
          </dl>
        </section>

        <section class="nd-history">
          <h4>历史</h4>
          <p class="muted">这个{{ node.kind === 'FOLDER' ? '文件夹' : '文件' }}自己的改动,保留 90 天。</p>
          <div v-loading="history.isFetching.value && !history.data.value" class="nd-list">
            <p v-if="!items.length && !history.isFetching.value" class="muted empty">90 天内没有改动</p>
            <ActivityItem
              v-for="a in items"
              :key="a.id"
              :activity="a"
              compact
              :busy="busy"
              @undo="undoOne(a.id)"
            />
          </div>
          <div v-if="history.hasNextPage.value" class="more">
            <el-button link :loading="history.isFetchingNextPage.value" @click="history.fetchNextPage()">
              加载更多
            </el-button>
          </div>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.nd {
  min-height: 200px;
}
.muted {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.nd-head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.nd-info {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 4px 12px;
  margin: 0;
  font-size: 13px;
}
.nd-info dt {
  color: var(--el-text-color-secondary);
}
.nd-info dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.nd-history {
  margin-top: 20px;
}
.nd-history h4 {
  margin: 0 0 4px;
  font-size: 14px;
}
.nd-list {
  min-height: 60px;
  margin-top: 8px;
}
.empty {
  padding: 16px 0;
  text-align: center;
}
.more {
  padding: 6px 0;
  text-align: center;
}
</style>
