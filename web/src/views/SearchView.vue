<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, FolderOpened, Share, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { SearchNodesDocument } from '@/api/gen/graphql'
import type { SearchNodesQuery } from '@/api/gen/graphql'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import { useNodeDownload } from '@/composables/nodeDownload'
import { searchKeyword } from '@/utils/search'
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

// 文件名结果:手动累积分页,换关键词清空重来,nextCursor 驱动「加载更多」
const items = ref<Item[]>([])
const total = ref(0)
const nextCursor = ref<string | null>(null)
const loading = ref(false)

async function fetchPage(cursor: string | null) {
  if (!q.value) {
    items.value = []
    total.value = 0
    nextCursor.value = null
    return
  }
  loading.value = true
  try {
    const res = await request(SearchNodesDocument, { q: q.value, cursor })
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
  [q, tab],
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
const shareTarget = ref<{ id: string; name: string } | null>(null)
const shareVisible = ref(false)

function openShare(node: NodeListItem) {
  shareTarget.value = { id: node.id, name: node.name }
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
    { key: 'locate', label: '打开所在文件夹', icon: FolderOpened },
    { key: 'd', divider: true },
    { key: 'download', label: n.kind === 'FOLDER' ? '打包下载' : '下载', icon: Download },
    { key: 'share', label: '分享', icon: Share },
  ]
})

function onMenuSelect(key: string) {
  const n = menu.value.node
  if (!n) return
  if (key === 'open') onOpen(n)
  else if (key === 'locate') onLocate(n)
  else if (key === 'download') void download([n])
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
      搜索「{{ q }}」
      <span v-if="!loading && tab === 'name'" class="result-count">{{ total }} 个结果</span>
    </h3>

    <el-tabs v-model="tab" class="search-tabs">
      <el-tab-pane label="文件名" name="name" />
      <el-tab-pane label="内容" name="content" />
    </el-tabs>

    <ContentResults v-if="tab === 'content'" :q="q" />

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
</style>
