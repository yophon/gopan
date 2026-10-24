<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Download, FolderOpened, Share, Star, StarFilled, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ClearRecentDocument, RecentFilesDocument, StarredNodesDocument } from '@/api/gen/graphql'
import type { QuickNodeFragment } from '@/api/gen/graphql'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import { useNodeDownload } from '@/composables/nodeDownload'
import { useStars } from '@/composables/quick'
import { formatRelative } from '@/utils/format'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

/**
 * 「收藏」与「最近」(doc/24)两页共用:收藏是常去的文件和文件夹,最近是在网页 / App 里打开过的文件。
 * 都不分页(收藏最多 500、最近最多 100)。
 */

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()
const stars = useStars()

const isRecent = computed(() => route.name === 'recent')

const { data, isPending } = useQuery({
  queryKey: computed(() => [isRecent.value ? 'recent' : 'starred']),
  queryFn: async (): Promise<QuickNodeFragment[]> =>
    isRecent.value ? (await request(RecentFilesDocument)).recentFiles : (await request(StarredNodesDocument)).starredNodes,
  staleTime: 0,
})
const items = computed<QuickNodeFragment[]>(() => data.value ?? [])

const selectedIds = ref<string[]>([])
watch(isRecent, () => (selectedIds.value = []))
const selection = computed(() => items.value.filter((n) => selectedIds.value.includes(n.id)))

// ---------- 打开、定位 ----------

function onOpen(row: NodeListItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
    return
  }
  const files = items.value.filter((n) => n.kind === 'FILE')
  const idx = files.findIndex((n) => n.id === row.id)
  if (idx >= 0) openPreview(files.map((n) => n.id), idx)
}

function onLocate(row: NodeListItem) {
  void router.push(row.parentId ? `/drive/${row.parentId}` : '/drive')
}

/** 所在文件夹,根目录叫「我的文件」 */
function locationOf(node: NodeListItem): string {
  const path = (node as QuickNodeFragment).path
  if (!path) return ''
  return ['我的文件', ...path.split('/').filter(Boolean).slice(0, -1)].join(' / ')
}

function openedOf(node: NodeListItem): string {
  return formatRelative((node as QuickNodeFragment).openedAt)
}

// ---------- 下载、分享、收藏、从最近里拿掉 ----------

const { download, downloadingId } = useNodeDownload()
const shareTarget = ref<{ id: string; name: string } | null>(null)
const shareVisible = ref(false)

function openShare(node: NodeListItem) {
  shareTarget.value = { id: node.id, name: node.name }
  shareVisible.value = true
}

async function removeRecent(nodes: NodeListItem[] | null) {
  try {
    if (!nodes) {
      await ElMessageBox.confirm('「最近」里的记录会全部清掉,文件本身不受影响。', '清空最近', {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning',
      })
    }
    await request(ClearRecentDocument, { ids: nodes ? nodes.map((n) => n.id) : null })
    selectedIds.value = []
    void queryClient.invalidateQueries({ queryKey: ['recent'] })
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err, '操作失败'))
  }
}

// ---------- 右键菜单(桌面) ----------

const menu = ref<{ visible: boolean; x: number; y: number; node: NodeListItem | null }>({ visible: false, x: 0, y: 0, node: null })

function onMenu(node: NodeListItem | null, x: number, y: number) {
  if (node) menu.value = { visible: true, x, y, node }
}

const menuItems = computed<MenuEntry[]>(() => {
  const sel = selection.value
  const one = sel.length === 1 ? sel[0]! : null
  if (!sel.length) return []
  const starred = stars.allStarred(sel)
  const entries: MenuEntry[] = []
  if (one) {
    entries.push(
      { key: 'open', label: one.kind === 'FOLDER' ? '打开' : '预览', icon: one.kind === 'FOLDER' ? FolderOpened : View },
      { key: 'locate', label: '打开所在文件夹', icon: FolderOpened },
      { key: 'd1', divider: true },
    )
  }
  entries.push({ key: 'download', label: one && one.kind === 'FILE' ? '下载' : '打包下载', icon: Download })
  if (one) entries.push({ key: 'share', label: '分享', icon: Share })
  entries.push({ key: 'star', label: starred ? '取消收藏' : '收藏', icon: starred ? Star : StarFilled })
  if (isRecent.value) entries.push({ key: 'forget', label: '从最近中移除', icon: Close })
  return entries
})

function onAction(key: string, nodes: NodeListItem[]) {
  const one = nodes.length === 1 ? nodes[0]! : null
  switch (key) {
    case 'open':
      if (one) onOpen(one)
      return
    case 'locate':
      if (one) onLocate(one)
      return
    case 'download':
      return void download(nodes)
    case 'share':
      if (one) openShare(one)
      return
    case 'star':
      return void stars.toggle(nodes)
    case 'forget':
      return void removeRecent(nodes)
  }
}

// ---------- 手机 ----------

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({ visible: false, node: null })

const sheetItems = computed<SheetItem[]>(() => {
  const n = sheet.value.node
  if (!n) return []
  return [
    { key: 'open', label: n.kind === 'FOLDER' ? '打开' : '预览' },
    { key: 'locate', label: '所在文件夹', icon: FolderOpened },
    { key: 'download', label: n.kind === 'FOLDER' ? '打包下载' : '下载', icon: Download },
    { key: 'share', label: '分享', icon: Share },
    { key: 'star', label: n.starred ? '取消收藏' : '收藏', icon: n.starred ? Star : StarFilled },
    ...(isRecent.value ? [{ key: 'forget', label: '从最近中移除', icon: Close }] : []),
  ]
})
</script>

<template>
  <div class="quick-view">
    <div class="quick-head">
      <h3 class="page-title">{{ isRecent ? '最近' : '收藏' }}</h3>
      <el-button v-if="isRecent && items.length" text class="clear-btn" @click="removeRecent(null)">清空</el-button>
    </div>

    <NodeBrowser
      v-if="!isMobile"
      v-model:selected-ids="selectedIds"
      :items="items"
      :loading="isPending"
      :time-label="isRecent ? '打开时间' : '位置'"
      :time-of="isRecent ? openedOf : locationOf"
      :time-link="!isRecent"
      :style="isRecent ? undefined : '--nb-time-w: 300px'"
      @open="onOpen"
      @menu="onMenu"
      @download="(n) => download([n])"
      @share="openShare"
      @time-click="onLocate"
    >
      <template #empty>
        <el-icon :size="44" class="empty-icon"><component :is="isRecent ? View : Star" /></el-icon>
        <span>{{ isRecent ? '还没有打开过文件' : '还没有收藏' }}</span>
        <span v-if="!isRecent" class="empty-hint">在文件或文件夹上右键,选「收藏」</span>
      </template>
    </NodeBrowser>

    <NodeCardList
      v-else
      :items="items"
      mode="list"
      :selection-mode="false"
      :selected-ids="[]"
      :downloading-id="downloadingId"
      :loading="isPending"
      :secondary="isRecent ? (n) => `打开于 ${openedOf(n)}` : locationOf"
      @open="onOpen"
      @menu="(node) => (sheet = { visible: true, node })"
    />

    <ContextMenu v-model:visible="menu.visible" :x="menu.x" :y="menu.y" :items="menuItems" @select="(k) => onAction(k, selection)" />
    <NodeActionSheet
      v-model:visible="sheet.visible"
      :title="sheet.node?.name"
      :items="sheetItems"
      @select="(k) => sheet.node && onAction(k, [sheet.node])"
    />
    <ShareDialog v-model="shareVisible" :node="shareTarget" />
  </div>
</template>

<style scoped>
.quick-view {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.quick-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  margin-bottom: 12px;
}
.page-title {
  margin: 0;
  font-size: 20px;
}
.clear-btn {
  margin-left: auto;
  color: var(--gp-ink-2);
}
.quick-view > .node-browser {
  flex: 1;
}
.empty-icon {
  color: var(--el-border-color-darker);
}
.empty-hint {
  font-size: 13px;
}
</style>
