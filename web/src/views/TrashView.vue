<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Delete, RefreshLeft } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { PurgeNodesDocument, PurgeTrashDocument, RestoreNodesDocument, TrashDocument } from '@/api/gen/graphql'
import type { TrashQuery } from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'
import { trashExpiry, trashExpiryText } from '@/utils/trash'
import { actionToast } from '@/utils/toast'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type TrashItem = TrashQuery['trash']['items'][number]

const queryClient = useQueryClient()
const router = useRouter()
const { isMobile } = useBreakpoints()

// 一页一页拉完(以前只拉第一页,回收站里东西多了后面的看不到)
const trashQuery = useInfiniteQuery({
  queryKey: ['trash'],
  queryFn: ({ pageParam }) => request(TrashDocument, { cursor: pageParam }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.trash.nextCursor ?? undefined,
})
watch(
  () => [trashQuery.hasNextPage.value, trashQuery.isFetching.value] as const,
  ([more, fetching]) => {
    if (more && !fetching) void trashQuery.fetchNextPage()
  },
  { immediate: true },
)
const items = computed<TrashItem[]>(() => trashQuery.data.value?.pages.flatMap((p) => p.trash.items) ?? [])

/** 列表里按"删除时间"展示,映射成通用节点形状 */
const listItems = computed<NodeListItem[]>(() => items.value.map((i) => ({ ...i, updatedAt: i.deletedAt })))

/** 删除于 … · 还剩 N 天 */
function deletedText(node: NodeListItem): string {
  return `${formatTime(node.updatedAt)} · ${trashExpiryText(node.purgeAt)}`
}

const selectedIds = ref<string[]>([])
const selection = computed(() => {
  const set = new Set(selectedIds.value)
  return items.value.filter((n) => set.has(n.id))
})
const urgent = computed(() => items.value.filter((n) => trashExpiry(n.purgeAt).level !== 'normal').length)

function invalidate() {
  selectedIds.value = []
  void queryClient.invalidateQueries({ queryKey: ['trash'] })
  // 还原会把节点放回原目录
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  // 彻删退配额,用量条跟进
  void queryClient.invalidateQueries({ queryKey: ['me'] })
}

const restoreMutation = useMutation({
  mutationFn: (vars: { ids: string[] }) => request(RestoreNodesDocument, vars),
  onSuccess: (res) => {
    invalidate()
    // 还原回去的都在同一个文件夹时,给一个「去看看」
    const parents = new Set(res.restoreNodes.map((n) => n.parentId ?? null))
    const text = res.restoreNodes.length > 1 ? `已还原 ${res.restoreNodes.length} 项` : '已还原'
    if (parents.size === 1) {
      const parent = [...parents][0]
      actionToast(text, '去看看', () => void router.push(parent ? `/drive/${parent}` : '/drive'))
    } else {
      ElMessage.success(text)
    }
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const purgeMutation = useMutation({
  mutationFn: (vars: { ids: string[] }) => request(PurgeNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('已彻底删除')
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const purgeTrashMutation = useMutation({
  mutationFn: () => request(PurgeTrashDocument, {}),
  onSuccess: () => {
    ElMessage.success('回收站已清空')
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

function restore(ids: string[]) {
  if (ids.length) restoreMutation.mutate({ ids })
}

async function purge(nodes: TrashItem[]) {
  if (!nodes.length) return
  try {
    await ElMessageBox.confirm(
      nodes.length > 1 ? `彻底删除这 ${nodes.length} 项后无法恢复,确定继续?` : `彻底删除「${nodes[0]!.name}」后无法恢复,确定继续?`,
      '彻底删除',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' },
    )
    purgeMutation.mutate({ ids: nodes.map((n) => n.id) })
  } catch {
    // 取消
  }
}

async function onPurgeTrash() {
  try {
    await ElMessageBox.confirm('清空回收站后所有内容无法恢复,确定继续?', '清空回收站', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消',
    })
    purgeTrashMutation.mutate()
  } catch {
    // 取消
  }
}

// ---------- 桌面右键菜单与键盘 ----------

const menu = ref({ visible: false, x: 0, y: 0 })

function onMenu(node: NodeListItem | null, x: number, y: number) {
  if (node) menu.value = { visible: true, x, y }
}

const menuItems = computed<MenuEntry[]>(() => [
  { key: 'restore', label: selection.value.length > 1 ? `还原 ${selection.value.length} 项` : '还原', icon: RefreshLeft },
  { key: 'd', divider: true },
  { key: 'purge', label: '彻底删除', icon: Delete, danger: true, hint: 'Del' },
])

function onMenuSelect(key: string) {
  if (key === 'restore') restore(selectedIds.value)
  else if (key === 'purge') void purge(selection.value)
}

useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  const t = e.target as HTMLElement | null
  if (isMobile.value || menu.value.visible || (t && ['INPUT', 'TEXTAREA'].includes(t.tagName))) return
  if (document.querySelector('.el-overlay:not([style*="display: none"]), .el-message-box')) return
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    selectedIds.value = items.value.map((n) => n.id)
  } else if (e.key === 'Escape') selectedIds.value = []
  else if (e.key === 'Delete' && selection.value.length) void purge(selection.value)
})

// ---------- 手机的逐项操作 ----------

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({ visible: false, node: null })

const sheetItems: SheetItem[] = [
  { key: 'restore', label: '还原', icon: RefreshLeft },
  { key: 'purge', label: '彻底删除', icon: Delete, danger: true },
]

function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  if (key === 'restore') restore([node.id])
  else void purge(items.value.filter((n) => n.id === node.id))
}
</script>

<template>
  <div class="trash">
    <h2 class="page-title">回收站</h2>

    <div class="toolbar">
      <template v-if="!isMobile && selection.length">
        <span class="sel-count">
          <el-button link :icon="Close" title="取消选择(Esc)" aria-label="取消选择" @click="selectedIds = []" />
          已选 {{ selection.length }} 项
        </span>
        <el-button type="primary" :icon="RefreshLeft" @click="restore(selectedIds)">还原</el-button>
        <el-button :icon="Delete" class="danger-btn" @click="purge(selection)">彻底删除</el-button>
      </template>
      <span v-else-if="urgent" class="hint urgent">{{ urgent }} 项一天内会被自动清掉</span>
      <span class="spacer" />
      <el-button type="danger" plain :disabled="items.length === 0" @click="onPurgeTrash">清空回收站</el-button>
    </div>

    <NodeBrowser
      v-if="!isMobile"
      v-model:selected-ids="selectedIds"
      class="trash-list"
      :items="listItems"
      :loading="trashQuery.isPending.value"
      :row-actions="false"
      :openable="false"
      time-label="删除于"
      :time-of="deletedText"
      empty-text="回收站是空的"
      style="--nb-time-w: 230px"
      @menu="onMenu"
    />

    <NodeCardList
      v-else
      :items="listItems"
      mode="list"
      :selection-mode="false"
      :selected-ids="[]"
      :loading="trashQuery.isPending.value"
      :secondary="(n) => `删除于 ${deletedText(n)}`"
      @menu="(node) => (sheet = { visible: true, node })"
    />

    <ContextMenu v-model:visible="menu.visible" :x="menu.x" :y="menu.y" :items="menuItems" @select="onMenuSelect" />
    <NodeActionSheet v-model:visible="sheet.visible" :title="sheet.node?.name" :items="sheetItems" @select="onSheetSelect" />
  </div>
</template>

<style scoped>
.trash {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.trash-list {
  flex: 1;
}
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
  font-weight: 700;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  margin-bottom: 10px;
}
.toolbar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.spacer {
  flex: 1;
}
.sel-count {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 6px;
  font-weight: 600;
}
.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.hint.urgent {
  color: var(--el-color-danger);
}
.danger-btn {
  --el-button-text-color: var(--el-color-danger);
  --el-button-hover-text-color: var(--el-color-danger);
  --el-button-hover-border-color: var(--el-color-danger-light-5);
  --el-button-hover-bg-color: var(--el-color-danger-light-9);
}
</style>
