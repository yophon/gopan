<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download, EditPen, FolderOpened, RefreshRight, Scissor, VideoPause, VideoPlay, View } from '@element-plus/icons-vue'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  DeleteFeedDocument,
  FeedDocument,
  FeedItemsDocument,
  FetchFeedItemDocument,
  RefreshFeedDocument,
  UpdateFeedDocument,
} from '@/api/gen/graphql'
import type { FeedFieldsFragment, FeedItemFieldsFragment } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatBytes, formatRelative, formatTime } from '@/utils/format'
import { feedInputFrom, feedKindTag, feedModeLabel, feedStatus, intervalText, itemStateMeta } from '@/utils/feeds'
import { notificationRoute } from '@/utils/notifications'
import FeedFormDialog from './FeedFormDialog.vue'

/**
 * 订阅详情(doc/23 四.4):条目与状态、单条下载 / 剪藏 / 打开、立即检查、修改、暂停 / 恢复、退订(已下的文件不动)。
 * 有条目在处理时每 3 秒刷新一次。
 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ feedId: string | null }>()

const router = useRouter()
const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()
const open = computed(() => visible.value && !!props.feedId)

const { data, isFetching, isError, error } = useQuery({
  queryKey: computed(() => ['feed', props.feedId]),
  queryFn: () => request(FeedDocument, { id: props.feedId! }),
  enabled: open,
  staleTime: 0,
})
const feed = computed(() => (data.value?.feed.id === props.feedId ? data.value.feed : null))
const status = computed(() => (feed.value ? feedStatus(feed.value) : null))

const itemsQuery = useInfiniteQuery({
  queryKey: computed(() => ['feedItems', props.feedId]),
  queryFn: ({ pageParam }) => request(FeedItemsDocument, { feedId: props.feedId!, cursor: pageParam }),
  enabled: open,
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.feedItems.nextCursor ?? null,
  staleTime: 0,
  refetchInterval: (q) =>
    q.state.data?.pages.some((p) => p.feedItems.items.some((i) => i.state === 'QUEUED')) ? 3000 : false,
})
const items = computed<FeedItemFieldsFragment[]>(
  () => itemsQuery.data.value?.pages.flatMap((p) => p.feedItems.items) ?? [],
)

function refreshAll() {
  void queryClient.invalidateQueries({ queryKey: ['feeds'] })
  void queryClient.invalidateQueries({ queryKey: ['feed', props.feedId] })
  void queryClient.invalidateQueries({ queryKey: ['feedItems', props.feedId] })
}

function showError(err: unknown, fallback: string) {
  ElMessage.error({ message: getErrorMessage(err) ?? errorText(err, fallback), duration: 6000 })
}

// ---------- 订阅本身 ----------

const checking = ref(false)

async function onRefresh() {
  const f = feed.value
  if (!f) return
  checking.value = true
  try {
    const res = (await request(RefreshFeedDocument, { id: f.id })).refreshFeed
    if (res.lastError && res.failCount > 0) ElMessage.warning({ message: `检查失败:${res.lastError}`, duration: 6000 })
    else ElMessage.success(res.newCount ? `检查完了,有 ${res.newCount} 条新的` : '检查完了')
    void queryClient.invalidateQueries({ queryKey: ['jobs'] })
  } catch (err) {
    showError(err, '检查失败')
  } finally {
    checking.value = false
    refreshAll()
  }
}

const editVisible = ref(false)

async function onTogglePause() {
  const f = feed.value
  if (!f) return
  try {
    await request(UpdateFeedDocument, { id: f.id, input: feedInputFrom(f, { enabled: !f.enabled }) })
    ElMessage.success(f.enabled ? '已暂停,不再自动检查' : '已恢复')
  } catch (err) {
    showError(err, '操作失败')
  }
  refreshAll()
}

async function onDelete() {
  const f = feed.value
  if (!f) return
  try {
    await ElMessageBox.confirm('不再检查这个订阅,条目记录一起删掉。已经下好的文件不动。', `退订「${f.title}」?`, {
      confirmButtonText: '退订',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await request(DeleteFeedDocument, { id: f.id })
    ElMessage.success('已退订')
    visible.value = false
    void queryClient.invalidateQueries({ queryKey: ['feeds'] })
  } catch (err) {
    showError(err, '退订失败')
  }
}

function onSaved(f: FeedFieldsFragment) {
  queryClient.setQueryData(['feed', f.id], { feed: f })
  refreshAll()
}

function openFolder() {
  const fd = feed.value?.folder
  if (!fd) return
  visible.value = false
  void router.push(`/drive/${fd.id}`)
}

// ---------- 条目 ----------

const busyItem = ref<string | null>(null)

async function onFetch(it: FeedItemFieldsFragment, mode: 'DOWNLOAD' | 'CLIP') {
  busyItem.value = it.id
  try {
    await request(FetchFeedItemDocument, { itemId: it.id, mode })
    ElMessage.success(mode === 'DOWNLOAD' ? '已加入后台任务' : '开始剪藏')
    void queryClient.invalidateQueries({ queryKey: ['jobs'] })
  } catch (err) {
    showError(err, '操作失败')
  } finally {
    busyItem.value = null
    void queryClient.invalidateQueries({ queryKey: ['feedItems', props.feedId] })
  }
}

function onOpenNode(it: FeedItemFieldsFragment) {
  const n = it.node
  if (!n) return
  const to = notificationRoute({ kind: 'NODE', id: n.id, parentId: n.parentId })
  if (!to) return
  visible.value = false
  void router.push(to)
}

function itemMeta(it: FeedItemFieldsFragment): string {
  const parts: string[] = []
  if (it.publishedAt) parts.push(formatTime(it.publishedAt).slice(0, 10))
  if (it.enclosureSize) parts.push(formatBytes(it.enclosureSize))
  return parts.join(' · ')
}
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    :size="isMobile ? '100%' : '620px'"
    :title="feed?.title ?? '订阅'"
    append-to-body
    class="feed-drawer"
  >
    <div v-loading="isFetching && !feed" class="fd">
      <el-empty v-if="isError" :description="errorText(error, '这个订阅不在了')" />
      <template v-else-if="feed">
        <section class="fd-head">
          <div class="fd-tags">
            <el-tag size="small" effect="plain">{{ feedKindTag(feed) }}</el-tag>
            <el-tag v-if="status" size="small" :type="status.type">{{ status.label }}</el-tag>
            <span class="muted">{{ feedModeLabel[feed.mode] }} · {{ intervalText(feed.intervalMinutes) }}</span>
          </div>
          <a
            class="fd-url"
            :href="feed.kind === 'GITHUB' ? `https://github.com/${feed.url}/releases` : feed.siteUrl || feed.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ feed.url }}
          </a>
          <div class="muted">
            存到
            <el-button v-if="feed.folder" link type="primary" size="small" :icon="FolderOpened" @click="openFolder">
              {{ feed.folder.name }}
            </el-button>
            <span v-else class="danger">文件夹已经不在了(订阅已暂停),点「修改」换一个</span>
          </div>
          <div class="muted">
            上次检查 {{ feed.lastCheckedAt ? formatRelative(feed.lastCheckedAt) : '还没有' }}
            <template v-if="feed.enabled && feed.folder"> · 下次 {{ formatTime(feed.nextCheckAt) }}</template>
            · 共 {{ feed.itemCount }} 条
            <template v-if="feed.keepLast"> · 只留最近 {{ feed.keepLast }} 条</template>
          </div>
          <div v-if="feed.includeWords || feed.excludeWords || feed.assetPattern" class="muted">
            <template v-if="feed.includeWords">包含:{{ feed.includeWords }} </template>
            <template v-if="feed.excludeWords">不含:{{ feed.excludeWords }} </template>
            <template v-if="feed.assetPattern">资源:{{ feed.assetPattern }}</template>
            <template v-if="feed.kind === 'GITHUB' && feed.prerelease"> · 含预发布</template>
          </div>
        </section>

        <el-alert
          v-if="feed.lastError && feed.failCount > 0"
          type="error"
          :closable="false"
          show-icon
          :title="`连续 ${feed.failCount} 次检查失败`"
          :description="feed.lastError"
          class="fd-error"
        />

        <section class="fd-actions">
          <el-button size="small" :icon="RefreshRight" :loading="checking" @click="onRefresh">立即检查</el-button>
          <el-button size="small" :icon="EditPen" @click="editVisible = true">修改</el-button>
          <el-button size="small" :icon="feed.enabled ? VideoPause : VideoPlay" :disabled="!feed.folder && !feed.enabled" @click="onTogglePause">
            {{ feed.enabled ? '暂停' : '恢复' }}
          </el-button>
          <el-button size="small" type="danger" plain :icon="Delete" @click="onDelete">退订</el-button>
        </section>

        <section class="fd-items">
          <h4>条目</h4>
          <div v-loading="itemsQuery.isFetching.value && !itemsQuery.data.value">
            <p v-if="!items.length && !itemsQuery.isFetching.value" class="muted empty">还没有条目</p>
            <div v-for="it in items" :key="it.id" class="item">
              <div class="item-main">
                <div class="item-line">
                  <el-tag size="small" :type="itemStateMeta[it.state].type" effect="plain">
                    {{ itemStateMeta[it.state].label }}
                  </el-tag>
                  <a
                    v-if="it.url"
                    class="item-title"
                    :href="it.url"
                    target="_blank"
                    rel="noopener noreferrer"
                    :title="it.title"
                  >
                    {{ it.title || it.url }}
                  </a>
                  <span v-else class="item-title" :title="it.title">{{ it.title || it.enclosureUrl }}</span>
                </div>
                <div class="muted">{{ itemMeta(it) }}</div>
                <div v-if="it.state === 'FAILED' && it.error" class="item-error">{{ it.error }}</div>
              </div>
              <div class="item-ops">
                <el-button
                  v-if="it.node"
                  size="small"
                  link
                  type="primary"
                  :icon="View"
                  @click="onOpenNode(it)"
                >
                  打开
                </el-button>
                <template v-else-if="it.state !== 'QUEUED'">
                  <el-button
                    v-if="it.enclosureUrl"
                    size="small"
                    link
                    :icon="Download"
                    :loading="busyItem === it.id"
                    @click="onFetch(it, 'DOWNLOAD')"
                  >
                    下载
                  </el-button>
                  <el-button
                    v-if="it.url && feed.kind === 'RSS'"
                    size="small"
                    link
                    :icon="Scissor"
                    :loading="busyItem === it.id"
                    @click="onFetch(it, 'CLIP')"
                  >
                    剪藏
                  </el-button>
                </template>
              </div>
            </div>
          </div>
          <div v-if="itemsQuery.hasNextPage.value" class="more">
            <el-button link :loading="itemsQuery.isFetchingNextPage.value" @click="itemsQuery.fetchNextPage()">
              加载更多
            </el-button>
          </div>
        </section>
      </template>
    </div>

    <FeedFormDialog v-model="editVisible" :feed="feed" @saved="onSaved" />
  </el-drawer>
</template>

<style scoped>
.fd {
  min-height: 200px;
}
.muted {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.danger {
  color: var(--el-color-danger);
}
.fd-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.fd-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.fd-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}
.fd-error {
  margin-top: 12px;
}
.fd-error :deep(.el-alert__description) {
  word-break: break-word;
}
.fd-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0 6px;
}
.fd-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.fd-items h4 {
  margin: 12px 0 4px;
  font-size: 14px;
}
.item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.item-main {
  flex: 1;
  min-width: 0;
}
.item-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.item-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  color: var(--el-text-color-primary);
  text-decoration: none;
}
a.item-title:hover {
  color: var(--el-color-primary);
}
.item-error {
  font-size: 12px;
  color: var(--el-color-danger);
  word-break: break-word;
}
.item-ops {
  flex: none;
  display: flex;
  align-items: center;
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
