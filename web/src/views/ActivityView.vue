<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query'
import { useIntersectionObserver } from '@vueuse/core'
import { ArrowRight, RefreshLeft } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { ActivitiesDocument, ActivityActorsDocument } from '@/api/gen/graphql'
import type { ActivityFieldsFragment, ActivityFilter } from '@/api/gen/graphql'
import { useUndo } from '@/composables/undo'
import { formatBytes, formatTime } from '@/utils/format'
import { groupByDay, notificationRoute, withoutQuery } from '@/utils/notifications'
import {
  ACTIVITY_ACTIONS,
  actionVerb,
  actorKindLabel,
  actorName,
  actorParam,
  batchTitle,
  batchTotals,
  groupBatches,
  parseActorParam,
  presetSince,
  timePresetLabel,
} from '@/utils/activity'
import type { ActivityActionValue, BatchGroup, TimePreset } from '@/utils/activity'
import ActivityItem from '@/components/activity/ActivityItem.vue'

type Item = ActivityFieldsFragment

/**
 * 动态(doc/23 二.4):按天分组、同一批折叠、来源 / 操作 / 时间筛选、撤回。
 * 来源与起始时间放在地址里(?actor=kind:ref&since=,大量删除提醒点进来就是这样),刷新不丢;
 * 筛了来源时可以「全部撤回」(先预览)。
 */

const route = useRoute()
const router = useRouter()
const { busy, undoWithPreview, undoOne } = useUndo()

// ---------- 筛选 ----------

const actorFilter = computed(() => parseActorParam(route.query.actor))
const customSince = computed(() => {
  const s = route.query.since
  return typeof s === 'string' && !Number.isNaN(Date.parse(s)) ? s : null
})
const preset = ref<TimePreset>('all')
const actions = ref<ActivityActionValue[]>([])

function patchQuery(patch: Record<string, string | null>) {
  const next: Record<string, string | (string | null)[] | null> = withoutQuery(route.query, Object.keys(patch))
  for (const [k, v] of Object.entries(patch)) if (v) next[k] = v
  void router.replace({ query: next })
}

const actorValue = computed({
  get: () => (actorFilter.value ? actorParam({ kind: actorFilter.value.actorKind, ref: actorFilter.value.actorRef }) : ''),
  set: (v: string) => patchQuery({ actor: v || null }),
})

function onPreset(p: string | number | boolean) {
  preset.value = p as TimePreset
  if (customSince.value) patchQuery({ since: null })
}

const since = computed(() => customSince.value ?? presetSince(preset.value))

const filter = computed<ActivityFilter>(() => ({
  actorKind: actorFilter.value?.actorKind ?? null,
  actorRef: actorFilter.value ? actorFilter.value.actorRef : null,
  actions: actions.value.length ? actions.value : null,
  since: since.value,
}))

const { data: actorsData } = useQuery({
  queryKey: ['activities', 'actors'],
  queryFn: () => request(ActivityActorsDocument),
})

const actorOptions = computed(() => {
  const list = (actorsData.value?.activityActors ?? []).map((s) => ({
    value: actorParam(s.actor),
    label: actorName(s.actor),
    kind: actorKindLabel[s.actor.kind],
    total: s.total,
  }))
  // 地址里带来的来源近 90 天没出现过(或还没加载到):也给一项,不然选择框显示一串 id
  const cur = actorFilter.value
  if (cur && !list.some((o) => o.value === actorValue.value)) {
    list.unshift({ value: actorValue.value, label: actorKindLabel[cur.actorKind], kind: actorKindLabel[cur.actorKind], total: 0 })
  }
  return list
})

const actorText = computed(() => actorOptions.value.find((o) => o.value === actorValue.value)?.label ?? '')
const sinceText = computed(() => {
  if (customSince.value) return `${formatTime(customSince.value)} 以来`
  return { today: '今天', '7d': '近 7 天', '30d': '近 30 天', all: '' }[preset.value]
})

// ---------- 列表 ----------

const query = useInfiniteQuery({
  queryKey: computed(() => ['activities', 'list', filter.value]),
  queryFn: ({ pageParam }) => request(ActivitiesDocument, { filter: filter.value, cursor: pageParam, limit: 50 }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.activities.nextCursor ?? null,
  staleTime: 0,
})
const items = computed<Item[]>(() => query.data.value?.pages.flatMap((p) => p.activities.items) ?? [])
const days = computed(() =>
  groupByDay(items.value, (a) => a.at).map((d) => ({ ...d, batches: groupBatches(d.items) })),
)

const sentinel = ref<HTMLElement | null>(null)
useIntersectionObserver(sentinel, ([e]) => {
  if (e?.isIntersecting && query.hasNextPage.value && !query.isFetchingNextPage.value) void query.fetchNextPage()
})

const expanded = ref<Record<string, boolean>>({})

function hm(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return Number.isNaN(d.getTime()) ? '' : `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function batchSub(b: BatchGroup<Item>): string {
  const first = b.items[0]
  const t = batchTotals(b.items)
  const parts = [actorKindLabel[first.actor.kind], t.bytes > 0 ? `共 ${t.files} 个文件 · ${formatBytes(t.bytes)}` : '']
  const undone = b.items.filter((i) => i.undoneAt).length
  if (undone) parts.push(undone === b.items.length ? '已撤回' : `${undone} 项已撤回`)
  return parts.filter(Boolean).join(' · ')
}

// ---------- 动作 ----------

function onOpen(a: Item) {
  if (!a.node) return
  const to = notificationRoute({ kind: 'NODE', id: a.node.id, parentId: a.node.parentId })
  if (to) void router.push(to)
}

function onUndo(a: Item) {
  void undoOne(a.id)
}

function onUndoBatch(b: BatchGroup<Item>) {
  void undoWithPreview({ batchId: b.batchId }, '撤回这一组')
}

function onUndoAll() {
  void undoWithPreview({ filter: filter.value }, `撤回「${actorText.value}」${sinceText.value}的改动`)
}
</script>

<template>
  <div class="activity">
    <h2 class="page-title">动态</h2>
    <p class="intro">
      网页、App、WebDAV、Agent、收集链接、后台任务对网盘做的改动都记在这里,保留 90 天。移动、改名、删除、新建能撤回;覆盖和彻底删除撤不回。
    </p>

    <div class="filters">
      <el-select
        v-model="actorValue"
        class="f-actor"
        placeholder="全部来源"
        clearable
        filterable
      >
        <el-option v-for="o in actorOptions" :key="o.value" :value="o.value" :label="o.label">
          <span class="opt-label">{{ o.label }}</span>
          <span class="opt-kind">{{ o.kind }}<template v-if="o.total"> · {{ o.total }}</template></span>
        </el-option>
      </el-select>
      <el-select
        v-model="actions"
        class="f-action"
        placeholder="全部操作"
        multiple
        collapse-tags
        clearable
      >
        <el-option v-for="act in ACTIVITY_ACTIONS" :key="act" :value="act" :label="actionVerb[act]" />
      </el-select>
      <el-segmented
        :model-value="customSince ? '' : preset"
        :options="(['today', '7d', '30d', 'all'] as TimePreset[]).map((p) => ({ label: timePresetLabel[p], value: p }))"
        @change="onPreset"
      />
      <el-tag v-if="customSince" closable @close="patchQuery({ since: null })">
        {{ formatTime(customSince) }} 以来
      </el-tag>
    </div>

    <div v-if="actorFilter" class="undo-all">
      <span class="undo-all-text">
        只看「{{ actorText }}」{{ sinceText }}的改动。不是你做的?可以一次撤回(先列出会撤回哪些,一次最多 1000 条)。
      </span>
      <el-button type="danger" plain :icon="RefreshLeft" :disabled="busy || !items.length" @click="onUndoAll">
        全部撤回
      </el-button>
    </div>

    <div v-loading="query.isFetching.value && !query.data.value" class="list">
      <el-empty
        v-if="!items.length && !query.isFetching.value"
        :description="actorFilter || actions.length || since ? '这段时间没有符合条件的改动' : '还没有动态'"
      />
      <section v-for="d in days" :key="d.key" class="day">
        <div class="day-label">{{ d.label }}</div>
        <template v-for="b in d.batches" :key="b.key">
          <ActivityItem
            v-if="b.items.length === 1"
            :activity="b.items[0]"
            :busy="busy"
            @undo="onUndo"
            @open="onOpen"
          />
          <div v-else class="batch" :class="{ open: expanded[b.key] }">
            <div
              class="batch-head"
              role="button"
              tabindex="0"
              :aria-expanded="!!expanded[b.key]"
              @click="expanded[b.key] = !expanded[b.key]"
              @keydown.enter="expanded[b.key] = !expanded[b.key]"
            >
              <el-icon class="caret"><ArrowRight /></el-icon>
              <span class="batch-time">{{ hm(b.items[0].at) }}</span>
              <span class="batch-main">
                <span class="batch-title">{{ batchTitle(b.items) }}</span>
                <span class="batch-sub">{{ batchSub(b) }}</span>
              </span>
              <el-button
                size="small"
                :disabled="busy || !b.items.some((i) => i.undoable)"
                @click.stop="onUndoBatch(b)"
              >
                撤回这一组
              </el-button>
            </div>
            <div v-if="expanded[b.key]" class="batch-items">
              <ActivityItem
                v-for="a in b.items"
                :key="a.id"
                :activity="a"
                hide-actor
                :busy="busy"
                @undo="onUndo"
                @open="onOpen"
              />
            </div>
          </div>
        </template>
      </section>
      <div ref="sentinel" class="more">
        <el-button
          v-if="query.hasNextPage.value"
          link
          :loading="query.isFetchingNextPage.value"
          @click="query.fetchNextPage()"
        >
          加载更多
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
}
.intro {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.f-actor {
  width: 240px;
}
.f-action {
  width: 200px;
}
.opt-label {
  margin-right: 12px;
}
.opt-kind {
  float: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.undo-all {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  font-size: 13px;
  line-height: 1.6;
}
.undo-all-text {
  flex: 1;
  min-width: 0;
}
.list {
  min-height: 200px;
}
.day-label {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 10px 4px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color);
}
.batch {
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.batch-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 4px;
  cursor: pointer;
}
.batch-head:hover {
  background: var(--el-fill-color-light);
}
.caret {
  flex: none;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  transition: transform 0.15s ease;
}
.batch.open .caret {
  transform: rotate(90deg);
}
.batch-time {
  flex: none;
  min-width: 40px;
  padding-top: 1px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}
.batch-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.batch-title {
  font-size: 14px;
  word-break: break-word;
}
.batch-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.batch-items {
  padding-left: 22px;
  background: var(--el-fill-color-lighter);
}
.batch-items :deep(.act-row:last-child) {
  border-bottom: none;
}
.more {
  padding: 12px 0;
  text-align: center;
}

@media (max-width: 767px) {
  .f-actor,
  .f-action {
    width: calc(50% - 4px);
  }
  .undo-all {
    flex-direction: column;
    align-items: stretch;
  }
  .batch-items {
    padding-left: 8px;
  }
}
</style>
