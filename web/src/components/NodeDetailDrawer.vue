<script setup lang="ts">
import { computed, ref } from 'vue'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download, RefreshLeft, Star, StarFilled, Switch } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ActivitiesDocument,
  DeleteFileVersionsDocument,
  FileVersionsDocument,
  NodeDetailDocument,
  PurgeFileVersionsDocument,
  RestoreFileVersionDocument,
} from '@/api/gen/graphql'
import type { ActivityFieldsFragment } from '@/api/gen/graphql'
import { useStars } from '@/composables/quick'
import { useBreakpoints } from '@/composables/breakpoints'
import { useUndo } from '@/composables/undo'
import { formatBytes, formatRelative, formatTime } from '@/utils/format'
import { folderSizeText } from '@/components/nodes/nodeDisplay'
import NodeIcon from '@/components/nodes/NodeIcon.vue'
import ActivityItem from '@/components/activity/ActivityItem.vue'
import TextDiffDialog from '@/components/TextDiffDialog.vue'

/**
 * 文件详情(doc/23 二.4):基本信息 + 「历史版本」(doc/24,文件被同名覆盖时留下的旧内容,能下载、恢复)
 * + 「历史」—— 这个文件 / 文件夹自己的动态(改名、移动、删除、还原……),每条能撤回。
 * 子树里别的文件的改动不在这里,去动态页看。
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

// ---------- 收藏 ----------

const stars = useStars()

// ---------- 历史版本(只有文件有) ----------

const queryClient = useQueryClient()
const versions = useQuery({
  queryKey: computed(() => ['fileVersions', props.nodeId]),
  queryFn: () => request(FileVersionsDocument, { nodeId: props.nodeId! }),
  enabled: computed(() => open.value && node.value?.kind === 'FILE'),
  staleTime: 0,
})
const versionItems = computed(() => versions.data.value?.fileVersions.items ?? [])
const policy = computed(() => versions.data.value?.fileVersions ?? null)
const versionBusy = ref<string | null>(null)

function afterVersionChange() {
  void versions.refetch()
  void queryClient.invalidateQueries({ queryKey: ['nodeDetail'] })
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  void queryClient.invalidateQueries({ queryKey: ['activities'] })
  void queryClient.invalidateQueries({ queryKey: ['me'] })
}

async function runVersion(id: string, fn: () => Promise<unknown>, ok: string) {
  versionBusy.value = id
  try {
    await fn()
    ElMessage.success(ok)
    afterVersionChange()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err))
  } finally {
    versionBusy.value = null
  }
}

function restoreVersion(id: string, savedAt: string) {
  void runVersion(
    id,
    async () => {
      await ElMessageBox.confirm(`文件会换回 ${formatTime(savedAt)} 的内容,现在的内容留成一个历史版本。`, '恢复这个版本', {
        confirmButtonText: '恢复',
        cancelButtonText: '取消',
      })
      await request(RestoreFileVersionDocument, { id })
    },
    '已恢复',
  )
}

function deleteVersion(id: string) {
  void runVersion(
    `del:${id}`,
    async () => {
      await ElMessageBox.confirm('删了找不回来。', '删除这个版本?', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      })
      await request(DeleteFileVersionsDocument, { ids: [id] })
    },
    '已删除这个版本',
  )
}

// 文本文件:「比较」看这个版本到现在改了什么(两边都不超过 2 MB,与在线编辑同一个上限)
const MAX_DIFF_BYTES = 2 * 1024 * 1024
const comparable = computed(
  () => node.value?.preview?.kind === 'TEXT' && (node.value.size ?? 0) <= MAX_DIFF_BYTES,
)
const diffVisible = ref(false)
const diffVersion = ref<{ id: string; savedAt: string; downloadUrl: string } | null>(null)

function compareVersion(v: { id: string; savedAt: string; downloadUrl: string }) {
  diffVersion.value = v
  diffVisible.value = true
}

function restoreFromDiff(id: string, savedAt: string) {
  diffVisible.value = false
  restoreVersion(id, savedAt)
}

function purgeVersions() {
  void runVersion(
    'all',
    async () => {
      await ElMessageBox.confirm('这个文件的历史版本会全部删掉,删了找不回来。', '清空历史版本', {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning',
      })
      await request(PurgeFileVersionsDocument, { nodeId: props.nodeId })
    },
    '已清空历史版本',
  )
}

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
          <el-button
            class="nd-star"
            :icon="node.starred ? StarFilled : Star"
            :class="{ on: node.starred }"
            :title="node.starred ? '取消收藏' : '收藏'"
            :aria-label="node.starred ? '取消收藏' : '收藏'"
            circle
            @click="stars.setStarred([node], !node.starred)"
          />
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

        <section v-if="node.kind === 'FILE'" class="nd-versions">
          <h4>
            历史版本
            <el-button v-if="versionItems.length" link class="nd-purge" :loading="versionBusy === 'all'" @click="purgeVersions">清空</el-button>
          </h4>
          <p v-if="policy" class="muted">
            <template v-if="policy.keep > 0">同名覆盖时留下原来的内容,最多 {{ policy.keep }} 个,{{ policy.keepDays }} 天后自动清理;占用空间。</template>
            <template v-else>本站没有开启历史版本。</template>
          </p>
          <div v-loading="versions.isFetching.value && !versions.data.value" class="nd-list">
            <p v-if="!versionItems.length && !versions.isFetching.value" class="muted empty">还没有被覆盖过</p>
            <div v-for="v in versionItems" :key="v.id" class="ver">
              <div class="ver-info">
                <span class="ver-time">{{ formatTime(v.savedAt) }}</span>
                <span class="muted">{{ formatBytes(v.size) }} · {{ formatRelative(v.replacedAt) }}被替换</span>
              </div>
              <div class="ver-actions">
                <el-button
                  v-if="comparable && v.size <= MAX_DIFF_BYTES"
                  link
                  :icon="Switch"
                  title="和现在比较"
                  aria-label="和现在比较"
                  @click="compareVersion(v)"
                />
                <el-button link :icon="Download" title="下载这个版本" aria-label="下载这个版本" tag="a" :href="v.downloadUrl" />
                <el-button link :icon="RefreshLeft" :loading="versionBusy === v.id" @click="restoreVersion(v.id, v.savedAt)">恢复</el-button>
                <el-button link :icon="Delete" :loading="versionBusy === `del:${v.id}`" title="删除这个版本" aria-label="删除这个版本" @click="deleteVersion(v.id)" />
              </div>
            </div>
          </div>
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
    <TextDiffDialog
      v-if="node && diffVersion"
      v-model="diffVisible"
      :node-id="node.id"
      :name="node.name"
      :version="diffVersion"
      @restore="restoreFromDiff"
    />
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
.nd-star {
  flex: none;
  order: 3;
}
.nd-star.on {
  color: var(--gp-star);
}
.nd-versions,
.nd-history {
  margin-top: 20px;
}
.nd-versions h4,
.nd-history h4 {
  display: flex;
  align-items: center;
  margin: 0 0 4px;
  font-size: 14px;
}
.nd-purge {
  margin-left: auto;
  font-weight: 400;
}
.ver {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ver:last-child {
  border-bottom: none;
}
.ver-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}
.ver-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.ver-actions :deep(.el-button + .el-button) {
  margin-left: 0;
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
