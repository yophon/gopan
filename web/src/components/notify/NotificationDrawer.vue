<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Finished, Setting } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ClearNotificationsDocument,
  MarkNotificationsReadDocument,
  MeDocument,
  NotificationsDocument,
} from '@/api/gen/graphql'
import type { NotificationsQuery } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatRelative } from '@/utils/format'
import { groupByDay, levelMeta, notificationRoute } from '@/utils/notifications'
import NotifyChannelsDialog from './NotifyChannelsDialog.vue'

type Item = NotificationsQuery['notifications']['items'][number]

/**
 * 通知抽屉(doc/22 三.4):按天分组;打开不自动标已读,点一条才标并跳过去。
 * 未读数随 App.vue 里 30 秒一次的 me 轮询带回,这里不另起轮询。
 */

const visible = defineModel<boolean>({ required: true })
const router = useRouter()
const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const unreadOnly = ref(false)

const query = useInfiniteQuery({
  queryKey: computed(() => ['notifications', unreadOnly.value]),
  enabled: visible,
  queryFn: ({ pageParam }) => request(NotificationsDocument, { cursor: pageParam, unreadOnly: unreadOnly.value }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.notifications.nextCursor ?? null,
  staleTime: 0,
})
const items = computed<Item[]>(() => query.data.value?.pages.flatMap((p) => p.notifications.items) ?? [])
const groups = computed(() => groupByDay(items.value, (n) => n.updatedAt))

const { data: meData } = useQuery({ queryKey: ['me'], queryFn: () => request(MeDocument) })
const unread = computed(() => meData.value?.me.unreadNotifications ?? 0)

// 抽屉开着时轮询发现新的未读,顺手刷新列表
watch(unread, (n, old) => {
  if (visible.value && n > old) void queryClient.invalidateQueries({ queryKey: ['notifications'] })
})

function refreshAll() {
  void queryClient.invalidateQueries({ queryKey: ['notifications'] })
  void queryClient.invalidateQueries({ queryKey: ['me'] })
}

async function onItem(n: Item) {
  if (!n.read) {
    try {
      await request(MarkNotificationsReadDocument, { ids: [n.id] })
    } catch {
      // 标不上不影响跳转
    }
    refreshAll()
  }
  const to = notificationRoute(n.target)
  if (!to) return
  visible.value = false
  void router.push(to)
}

async function onMarkAll() {
  try {
    const res = await request(MarkNotificationsReadDocument, { ids: null })
    if (res.markNotificationsRead > 0) ElMessage.success(`已把 ${res.markNotificationsRead} 条标为已读`)
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  }
  refreshAll()
}

async function onClear() {
  try {
    await ElMessageBox.confirm('所有通知都会被删掉,推送渠道不受影响。', '清空通知?', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await request(ClearNotificationsDocument)
    ElMessage.success('已清空')
  } catch (err) {
    ElMessage.error(errorText(err, '清空失败'))
  }
  refreshAll()
}

const channelsVisible = ref(false)
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    :size="isMobile ? '100%' : '420px'"
    :with-header="false"
    class="notify-drawer"
  >
    <div class="nd">
      <header class="nd-header">
        <span class="nd-title">通知</span>
        <span class="nd-actions">
          <el-button link :icon="Finished" :disabled="unread === 0" @click="onMarkAll">全部已读</el-button>
          <el-button link :icon="Delete" :disabled="items.length === 0" @click="onClear">清空</el-button>
          <el-button link type="primary" :icon="Setting" @click="channelsVisible = true">推送设置</el-button>
          <el-button link class="nd-close" @click="visible = false">关闭</el-button>
        </span>
      </header>

      <div class="nd-filter">
        <el-segmented
          v-model="unreadOnly"
          size="small"
          :options="[
            { label: '全部', value: false },
            { label: `未读${unread ? ` ${unread}` : ''}`, value: true },
          ]"
        />
      </div>

      <div v-loading="query.isFetching.value && !query.data.value" class="nd-body">
        <el-empty
          v-if="!items.length && !query.isFetching.value"
          :description="unreadOnly ? '没有未读的通知' : '还没有通知'"
        />
        <section v-for="g in groups" :key="g.key" class="nd-group">
          <div class="nd-day">{{ g.label }}</div>
          <button
            v-for="n in g.items"
            :key="n.id"
            type="button"
            class="nd-item"
            :class="[`lv-${n.level.toLowerCase()}`, { unread: !n.read, clickable: !!n.target }]"
            @click="onItem(n)"
          >
            <span class="nd-dot" />
            <span class="nd-main">
              <span class="nd-line">
                <el-tag v-if="n.level !== 'INFO'" size="small" :type="levelMeta[n.level].type" effect="plain">
                  {{ levelMeta[n.level].label }}
                </el-tag>
                <span class="nd-item-title">{{ n.title }}</span>
                <span v-if="n.count > 1" class="nd-count">×{{ n.count }}</span>
              </span>
              <span v-if="n.body" class="nd-text">{{ n.body }}</span>
              <span class="nd-time">{{ formatRelative(n.updatedAt) }}</span>
            </span>
          </button>
        </section>
        <div v-if="query.hasNextPage.value" class="nd-more">
          <el-button link :loading="query.isFetchingNextPage.value" @click="query.fetchNextPage()">加载更多</el-button>
        </div>
      </div>
    </div>

    <NotifyChannelsDialog v-model="channelsVisible" />
  </el-drawer>
</template>

<style scoped>
.nd {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.nd-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px 8px;
}
.nd-title {
  font-size: 16px;
  font-weight: 600;
}
.nd-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.nd-actions :deep(.el-button + .el-button) {
  margin-left: 10px;
}
.nd-close {
  display: none;
}
.nd-filter {
  padding: 0 16px 8px;
}
.nd-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 16px;
}
.nd-day {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 8px 8px 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color);
}
.nd-item {
  display: flex;
  gap: 10px;
  width: 100%;
  padding: 10px 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-primary);
  text-align: left;
  font: inherit;
  cursor: default;
}
.nd-item.clickable {
  cursor: pointer;
}
.nd-item:hover {
  background: var(--el-fill-color-light);
}
.nd-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: transparent;
}
.nd-item.unread .nd-dot {
  background: var(--el-color-primary);
}
.nd-item.unread.lv-warn .nd-dot {
  background: var(--el-color-warning);
}
.nd-item.unread.lv-alert .nd-dot {
  background: var(--el-color-danger);
}
.nd-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.nd-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.nd-item-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.nd-item.unread .nd-item-title {
  font-weight: 600;
}
.nd-item.lv-alert .nd-item-title {
  color: var(--el-color-danger);
}
.nd-count {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.nd-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
  word-break: break-word;
}
.nd-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.nd-more {
  padding: 8px 0;
  text-align: center;
}

@media (max-width: 767px) {
  .nd-header {
    padding-top: calc(12px + var(--sat));
    flex-wrap: wrap;
  }
  .nd-close {
    display: inline-flex;
  }
  .nd-body {
    padding-bottom: calc(16px + var(--sab));
  }
}
</style>

<style>
.notify-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
