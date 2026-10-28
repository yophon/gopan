<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, Download, Folder, FolderOpened, Monitor, Share, VideoPlay, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { NodeDocument, SearchNodesDocument } from '@/api/gen/graphql'
import type { SearchNodesQuery } from '@/api/gen/graphql'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import { downloadLabel, downloadToComputer, openWithLocalApp, playWithLocalPlayer, useNodeDownload } from '@/composables/nodeDownload'
import { canPlayExternal } from '@/desktop/play'
import { nativeDownloads } from '@/desktop/transfers'
import { searchKeyword } from '@/utils/search'
import {
  filterQuery,
  hasConditions,
  parseFilterQuery,
  SIZE_OPTIONS,
  SORT_OPTIONS,
  TIME_OPTIONS,
  toContentFilter,
  toSearchFilter,
  TYPE_OPTIONS,
  type FilterState,
} from '@/utils/searchFilter'
import ContentResults from '@/components/ContentResults.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type Item = SearchNodesQuery['searchNodes']['items'][number]

const route = useRoute()
const router = useRouter()
const { isMobile } = useBreakpoints()

const q = computed(() => searchKeyword(route.query).trim())

// 「文件名」「内容」两个标签;?in=content 直达内容(换关键词时保留)
const tab = computed({
  get: () => (route.query.in === 'content' ? 'content' : 'name'),
  set: (v: string) => void router.replace({ query: { ...route.query, in: v === 'content' ? 'content' : undefined } }),
})

// ---------- 筛选(doc/30):条件都在地址里 ----------

const filter = computed(() => parseFilterQuery(route.query))
const filtering = computed(() => hasConditions(filter.value))

function setFilter(patch: Partial<FilterState>) {
  void router.replace({ query: { ...route.query, ...filterQuery({ ...filter.value, ...patch }) } })
}

function clearFilter() {
  setFilter({ types: [], time: '', size: '', sort: '', folder: null })
}

// 修改时间:预设之外「自己选…」弹日期范围
const customRange = ref(false)
const timeChoice = computed(() => (filter.value.time.includes('~') || customRange.value ? 'range' : filter.value.time))
const rangeValue = computed<[string, string] | null>(() => {
  const [a, b] = filter.value.time.split('~')
  return a && b ? [a, b] : null
})

function onTimeChoice(v: string) {
  customRange.value = v === 'range'
  if (v !== 'range') setFilter({ time: v })
}

function onRange(v: [string, string] | null) {
  setFilter({ time: v ? `${v[0]}~${v[1]}` : '' })
  if (!v) customRange.value = false
}

// 只搜某个文件夹:标出文件夹的名字
const folderName = ref('')
watch(
  () => filter.value.folder,
  async (id) => {
    folderName.value = ''
    if (!id) return
    try {
      folderName.value = (await request(NodeDocument, { id })).node.name
    } catch {
      folderName.value = '某个文件夹'
    }
  },
  { immediate: true },
)

const contentFilter = computed(() => toContentFilter(filter.value))

// 文件名结果:手动累积分页,换关键词清空重来,nextCursor 驱动「加载更多」
const items = ref<Item[]>([])
const total = ref(0)
const nextCursor = ref<string | null>(null)
const loading = ref(false)

async function fetchPage(cursor: string | null) {
  // 没关键词也能只按筛选找(比如「所有大于 1 GB 的视频」)
  if (!q.value && !filtering.value) {
    items.value = []
    total.value = 0
    nextCursor.value = null
    return
  }
  loading.value = true
  try {
    const res = await request(SearchNodesDocument, { q: q.value, cursor, filter: toSearchFilter(filter.value) })
    if (cursor) {
      items.value = [...items.value, ...res.searchNodes.items]
    } else {
      items.value = [...res.searchNodes.items]
    }
    total.value = res.searchNodes.total
    nextCursor.value = res.searchNodes.nextCursor ?? null
  } catch (err) {
    ElMessage.error(errorText(err, '搜索失败'))
  } finally {
    loading.value = false
  }
}

watch(
  [q, tab, () => JSON.stringify(filterQuery(filter.value))],
  () => {
    if (tab.value === 'name') void fetchPage(null)
  },
  { immediate: true },
)

function onLoadMore() {
  if (nextCursor.value) void fetchPage(nextCursor.value)
}

// ---------- 交互 ----------

function onOpen(row: NodeListItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
    return
  }
  const files = items.value.filter((n) => n.kind === 'FILE')
  const idx = files.findIndex((n) => n.id === row.id)
  if (idx >= 0) {
    openPreview(
      files.map((n) => n.id),
      idx,
    )
  }
}

/** 跳到所在文件夹 */
function onLocate(row: NodeListItem) {
  void router.push(row.parentId ? `/drive/${row.parentId}` : '/drive')
}

/** 「位置」:所在文件夹的路径,根目录叫「我的文件」 */
function locationOf(node: NodeListItem): string {
  const path = (node as Item).path
  if (!path) return ''
  const segs = path.split('/').filter(Boolean).slice(0, -1)
  return ['我的文件', ...segs].join(' / ')
}

// ---------- 桌面:选中、右键菜单、下载、分享 ----------

const selectedIds = ref<string[]>([])
watch(q, () => (selectedIds.value = []))
const { download } = useNodeDownload()
const shareTarget = ref<{ id: string; name: string; kind: string } | null>(null)
const shareVisible = ref(false)

function openShare(node: NodeListItem) {
  shareTarget.value = { id: node.id, name: node.name, kind: node.kind }
  shareVisible.value = true
}

const menu = ref<{ visible: boolean; x: number; y: number; node: NodeListItem | null }>({ visible: false, x: 0, y: 0, node: null })

function onMenu(node: NodeListItem | null, x: number, y: number) {
  if (node) menu.value = { visible: true, x, y, node }
}

const menuItems = computed<MenuEntry[]>(() => {
  const n = menu.value.node
  if (!n) return []
  return [
    { key: 'open', label: n.kind === 'FOLDER' ? '打开' : '预览', icon: n.kind === 'FOLDER' ? FolderOpened : View },
    ...(n.kind === 'FILE' && nativeDownloads() ? [{ key: 'openLocal', label: '用本地程序打开', icon: Monitor }] : []),
    ...(n.kind === 'FILE' && nativeDownloads() && canPlayExternal(n.preview?.kind)
      ? [{ key: 'playLocal', label: '用播放器播放', icon: VideoPlay }]
      : []),
    { key: 'locate', label: '打开所在文件夹', icon: FolderOpened },
    { key: 'd', divider: true },
    { key: 'download', label: downloadLabel([n]), icon: Download },
    ...(nativeDownloads() ? [{ key: 'downloadTo', label: '下载到…', icon: Download }] : []),
    { key: 'share', label: '分享', icon: Share },
  ]
})

function onMenuSelect(key: string) {
  const n = menu.value.node
  if (!n) return
  if (key === 'open') onOpen(n)
  else if (key === 'openLocal') void openWithLocalApp(n)
  else if (key === 'playLocal') void playWithLocalPlayer(n)
  else if (key === 'locate') onLocate(n)
  else if (key === 'download') void download([n])
  else if (key === 'downloadTo') void downloadToComputer([n], true)
  else if (key === 'share') openShare(n)
}

// ---------- 手机 ----------

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({
  visible: false,
  node: null,
})

const sheetItems: SheetItem[] = [
  { key: 'open', label: '打开 / 预览' },
  { key: 'locate', label: '所在目录', icon: FolderOpened },
]

function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  if (key === 'open') onOpen(node)
  if (key === 'locate') onLocate(node)
}
</script>

<template>
  <div class="search-view">
    <h3 class="page-title">
      <template v-if="q">搜索「{{ q }}」</template>
      <template v-else>按条件找</template>
      <span v-if="!loading && tab === 'name' && (q || filtering)" class="result-count">{{ total }} 个结果</span>
    </h3>

    <el-tabs v-model="tab" class="search-tabs">
      <el-tab-pane label="文件名" name="name" />
      <el-tab-pane label="内容" name="content" />
    </el-tabs>

    <div class="filter-bar">
      <span v-if="filter.folder" class="scope-chip" :title="`只搜「${folderName}」里的(含子文件夹)`">
        <el-icon><Folder /></el-icon>
        <span class="scope-name">只在「{{ folderName || '…' }}」里</span>
        <el-icon class="scope-x" title="搜全部" @click="setFilter({ folder: null })"><Close /></el-icon>
      </span>
      <el-select
        :model-value="filter.types"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        placeholder="类型"
        size="small"
        class="f-type"
        @update:model-value="(v: FilterState['types']) => setFilter({ types: v })"
      >
        <el-option
          v-for="o in TYPE_OPTIONS"
          :key="o.value"
          :label="o.label"
          :value="o.value"
          :disabled="tab === 'content' && o.value === 'FOLDER'"
        />
      </el-select>
      <el-select :model-value="timeChoice" size="small" class="f-time" @update:model-value="onTimeChoice">
        <el-option v-for="o in TIME_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-date-picker
        v-if="timeChoice === 'range'"
        :model-value="rangeValue"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="从"
        end-placeholder="到"
        size="small"
        class="f-range"
        @update:model-value="onRange"
      />
      <el-select
        :model-value="filter.size"
        size="small"
        class="f-size"
        @update:model-value="(v: string) => setFilter({ size: v })"
      >
        <el-option v-for="o in SIZE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select
        v-if="tab === 'name'"
        :model-value="filter.sort"
        size="small"
        class="f-sort"
        @update:model-value="(v: FilterState['sort']) => setFilter({ sort: v })"
      >
        <el-option v-for="o in SORT_OPTIONS" :key="o.value" :label="`按${o.label}`" :value="o.value" />
      </el-select>
      <el-button v-if="filtering || filter.sort" link size="small" @click="clearFilter">清除筛选</el-button>
    </div>

    <ContentResults v-if="tab === 'content'" :q="q" :filter="contentFilter" />

    <template v-else>
      <NodeBrowser
        v-if="!isMobile"
        v-model:selected-ids="selectedIds"
        :items="items"
        :loading="loading"
        time-label="位置"
        :time-of="locationOf"
        time-link
        empty-text="没有匹配的文件"
        style="--nb-time-w: 300px"
        @open="onOpen"
        @menu="onMenu"
        @download="(n) => download([n])"
        @share="openShare"
        @time-click="onLocate"
      />

      <NodeCardList
        v-else
        :items="items"
        mode="list"
        :selection-mode="false"
        :selected-ids="[]"
        :loading="loading && items.length === 0"
        @open="onOpen"
        @menu="(node) => (sheet = { visible: true, node })"
      />

      <div v-if="nextCursor" class="load-more">
        <el-button :loading="loading" @click="onLoadMore">加载更多</el-button>
      </div>
    </template>

    <ContextMenu v-model:visible="menu.visible" :x="menu.x" :y="menu.y" :items="menuItems" @select="onMenuSelect" />
    <ShareDialog v-model="shareVisible" :node="shareTarget" />

    <NodeActionSheet
      v-model:visible="sheet.visible"
      :title="sheet.node?.name"
      :items="sheetItems"
      @select="onSheetSelect"
    />
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 8px;
}
.search-tabs {
  margin-bottom: 4px;
}
.result-count {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.load-more {
  display: flex;
  justify-content: center;
  padding: 16px;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.f-type {
  width: 180px;
}
.f-time,
.f-size,
.f-sort {
  width: 128px;
}
.f-range {
  max-width: 260px;
}
.scope-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 260px;
  height: 24px;
  padding: 0 6px 0 8px;
  border-radius: 12px;
  font-size: 12px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.scope-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.scope-x {
  cursor: pointer;
}
@media (max-width: 767px) {
  .f-type {
    width: 150px;
  }
  .f-time,
  .f-size,
  .f-sort {
    width: 112px;
  }
}
</style>
