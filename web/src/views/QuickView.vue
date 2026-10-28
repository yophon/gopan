<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Document, Download, FolderOpened, Headset, Monitor, Share, Star, StarFilled, VideoPlay, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ClearProgressDocument, ClearRecentDocument, RecentFilesDocument, RecentPlaysDocument, StarredNodesDocument } from '@/api/gen/graphql'
import type { QuickNodeFragment } from '@/api/gen/graphql'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import { downloadLabel, downloadToComputer, openWithLocalApp, playWithLocalPlayer, useNodeDownload } from '@/composables/nodeDownload'
import { canPlayExternal } from '@/desktop/play'
import { nativeDownloads } from '@/desktop/transfers'
import { useStars } from '@/composables/quick'
import { progressPercent, progressText } from '@/utils/media'
import { matchesRecentFilter, pagedKind, recentFilters, recentLine } from '@/utils/recent'
import type { RecentFilter } from '@/utils/recent'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

/**
 * 「收藏」与「最近」(doc/24)两页共用:收藏是常去的文件和文件夹,最近是在网页 / App 里打开过的文件。
 * 都不分页(各最多 500)。最近还管着「接着看」:没看完的音视频与 PDF 排在最上面,每行写着看到哪(doc/31 五)。
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

// ---------- 最近:类型筛选、接着看 ----------

const filter = ref<RecentFilter>('all')
watch(isRecent, () => (filter.value = 'all'))
const shown = computed<QuickNodeFragment[]>(() =>
  isRecent.value ? items.value.filter((n) => matchesRecentFilter(n.preview.kind, filter.value)) : items.value,
)

const { data: playsData } = useQuery({
  queryKey: ['recentPlays'],
  queryFn: () => request(RecentPlaysDocument, { limit: 12 }),
  enabled: isRecent,
  staleTime: 0,
})
const plays = computed(() => (isRecent.value ? (playsData.value?.recentPlays ?? []) : []))

const clearProgress = useMutation({
  mutationFn: (nodeId: string) => request(ClearProgressDocument, { nodeId }),
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: ['recentPlays'] })
    void queryClient.invalidateQueries({ queryKey: ['recent'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '操作失败')),
})

function playIcon(kind: string | undefined) {
  if (kind === 'AUDIO') return Headset
  if (kind === 'VIDEO') return VideoPlay
  return Document
}

function resume(nodeId: string) {
  const ids = plays.value.map((p) => p.node.id)
  openPreview(ids, ids.indexOf(nodeId))
}

const selectedIds = ref<string[]>([])
watch(isRecent, () => (selectedIds.value = []))
const selection = computed(() => shown.value.filter((n) => selectedIds.value.includes(n.id)))

// ---------- 打开、定位 ----------

function onOpen(row: NodeListItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
    return
  }
  const files = shown.value.filter((n) => n.kind === 'FILE')
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

/** 「3 小时前 · 看到 23:15 / 1:42:00」 */
function openedOf(node: NodeListItem): string {
  const n = node as QuickNodeFragment
  return recentLine(n.openedAt, n.playProgress, n.preview.kind)
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
      await ElMessageBox.confirm('「最近」里的记录和看到哪都会清掉,文件本身不受影响。', '清空最近', {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning',
      })
    }
    await request(ClearRecentDocument, { ids: nodes ? nodes.map((n) => n.id) : null })
    selectedIds.value = []
    void queryClient.invalidateQueries({ queryKey: ['recent'] })
    void queryClient.invalidateQueries({ queryKey: ['recentPlays'] })
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
      ...(one.kind === 'FILE' && nativeDownloads() ? [{ key: 'openLocal', label: '用本地程序打开', icon: Monitor }] : []),
      ...(one.kind === 'FILE' && nativeDownloads() && canPlayExternal(one.preview?.kind)
        ? [{ key: 'playLocal', label: '用播放器播放', icon: VideoPlay }]
        : []),
      { key: 'locate', label: '打开所在文件夹', icon: FolderOpened },
      { key: 'd1', divider: true },
    )
  }
  entries.push({ key: 'download', label: downloadLabel(sel), icon: Download })
  if (nativeDownloads()) entries.push({ key: 'downloadTo', label: '下载到…', icon: Download })
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
    case 'openLocal':
      if (one) void openWithLocalApp(one)
      return
    case 'playLocal':
      if (one) void playWithLocalPlayer(one)
      return
    case 'locate':
      if (one) onLocate(one)
      return
    case 'download':
      return void download(nodes)
    case 'downloadTo':
      return void downloadToComputer(nodes, true)
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
    { key: 'download', label: downloadLabel([n]), icon: Download },
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

    <section v-if="plays.length" class="resume">
      <h4 class="resume-title">接着看</h4>
      <div class="resume-strip">
        <button v-for="p in plays" :key="p.node.id" type="button" class="resume-card" :title="p.node.name" @click="resume(p.node.id)">
          <span class="resume-thumb">
            <img v-if="p.node.preview.thumbUrl" :src="p.node.preview.thumbUrl" alt="" loading="lazy" />
            <el-icon v-else :size="28"><component :is="playIcon(p.node.preview.kind)" /></el-icon>
          </span>
          <span class="resume-name">{{ p.node.name }}</span>
          <el-progress :percentage="progressPercent(p.progress)" :stroke-width="3" :show-text="false" class="resume-bar" />
          <span class="resume-meta">{{ progressText(p.progress, pagedKind(p.node.preview.kind)) }}</span>
          <span
            class="resume-x"
            role="button"
            title="不再接着看"
            aria-label="不再接着看"
            @click.stop="clearProgress.mutate(p.node.id)"
          >
            <el-icon><Close /></el-icon>
          </span>
        </button>
      </div>
    </section>

    <el-radio-group v-if="isRecent && items.length" v-model="filter" size="small" class="recent-filter">
      <el-radio-button v-for="f in recentFilters" :key="f.value" :value="f.value">{{ f.label }}</el-radio-button>
    </el-radio-group>

    <NodeBrowser
      v-if="!isMobile"
      v-model:selected-ids="selectedIds"
      :items="shown"
      :loading="isPending"
      :time-label="isRecent ? '打开时间' : '位置'"
      :time-of="isRecent ? openedOf : locationOf"
      :time-link="!isRecent"
      style="--nb-time-w: 300px"
      @open="onOpen"
      @menu="onMenu"
      @download="(n) => download([n])"
      @share="openShare"
      @time-click="onLocate"
    >
      <template #empty>
        <el-icon :size="44" class="empty-icon"><component :is="isRecent ? View : Star" /></el-icon>
        <span>{{ isRecent ? (items.length ? '没有这一类的' : '还没有打开过文件') : '还没有收藏' }}</span>
        <span v-if="!isRecent" class="empty-hint">在文件或文件夹上右键,选「收藏」</span>
      </template>
    </NodeBrowser>

    <NodeCardList
      v-else
      :items="shown"
      mode="list"
      :selection-mode="false"
      :selected-ids="[]"
      :downloading-id="downloadingId"
      :loading="isPending"
      :secondary="isRecent ? openedOf : locationOf"
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
.resume {
  margin-bottom: 16px;
}
.resume-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.resume-strip {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 4px;
  scrollbar-width: thin;
}
.resume-card {
  position: relative;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 200px;
  padding: 0 0 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  color: inherit;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
}
.resume-card:hover {
  border-color: var(--el-border-color);
}
.resume-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}
.resume-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.resume-name,
.resume-meta {
  padding: 0 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resume-name {
  font-size: 13px;
}
.resume-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.resume-bar {
  padding: 0 10px;
}
.resume-x {
  position: absolute;
  top: 6px;
  right: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  opacity: 0;
  transition: opacity 0.15s;
}
.resume-card:hover .resume-x,
.resume-card:focus-visible .resume-x {
  opacity: 1;
}
@media (hover: none) {
  .resume-x {
    opacity: 1;
  }
  .resume-strip {
    scrollbar-width: none;
  }
  .resume-strip::-webkit-scrollbar {
    display: none;
  }
}
.recent-filter {
  align-self: flex-start;
  margin-bottom: 12px;
}
.empty-icon {
  color: var(--el-border-color-darker);
}
.empty-hint {
  font-size: 13px;
}
</style>
