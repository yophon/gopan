<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Folder, RefreshLeft } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  PurgeNodesDocument,
  PurgeTrashDocument,
  RestoreNodesDocument,
  TrashDocument,
} from '@/api/gen/graphql'
import type { TrashQuery } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { trashExpiry, trashExpiryText } from '@/utils/trash'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type TrashItem = TrashQuery['trash']['items'][number]

const queryClient = useQueryClient()
const { isMobile, isCompact } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: ['trash'],
  queryFn: () => request(TrashDocument, {}),
})
const items = computed(() => data.value?.trash.items ?? [])

/** 手机卡片按"删除时间"展示,映射成通用节点形状即可复用 NodeCardList */
const cardItems = computed<NodeListItem[]>(() =>
  items.value.map((i) => ({ ...i, updatedAt: i.deletedAt })),
)

/** 卡片第二行:删除于 … · 还剩 N 天 */
function cardSecondary(node: NodeListItem): string {
  return `删除于 ${formatTime(node.updatedAt)} · ${trashExpiryText(node.purgeAt)}`
}

/** 快到期/已过期给个警示色,正常档不上色 */
function expiryClass(purgeAt: string | null | undefined): string {
  return trashExpiry(purgeAt).level === 'normal' ? '' : 'expiry-urgent'
}

const selection = ref<TrashItem[]>([])
const selectedIds = computed(() => selection.value.map((n) => n.id))

function onSelectionChange(rows: TrashItem[]) {
  selection.value = rows
}

function invalidate() {
  selection.value = []
  void queryClient.invalidateQueries({ queryKey: ['trash'] })
  // 还原会把节点放回原目录
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  // 彻删退配额,用量条跟进
  void queryClient.invalidateQueries({ queryKey: ['me'] })
}

const restoreMutation = useMutation({
  mutationFn: (vars: { ids: string[] }) => request(RestoreNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('已还原')
    invalidate()
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

/** el-table 的 slot row 是宽类型 DefaultRow,这里收窄回业务类型 */
function asTrash(row: unknown): TrashItem {
  return row as TrashItem
}

function onRestore() {
  if (selection.value.length === 0) return
  restoreMutation.mutate({ ids: selectedIds.value })
}

async function onPurge() {
  if (selection.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      `彻底删除所选 ${selection.value.length} 项后无法恢复,确定继续?`,
      '彻底删除',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' },
    )
    purgeMutation.mutate({ ids: selectedIds.value })
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

// ---------- 手机的逐项操作 ----------

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({
  visible: false,
  node: null,
})

const sheetItems: SheetItem[] = [
  { key: 'restore', label: '还原', icon: RefreshLeft },
  { key: 'purge', label: '彻底删除', icon: Delete, danger: true },
]

async function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  if (key === 'restore') {
    restoreMutation.mutate({ ids: [node.id] })
    return
  }
  try {
    await ElMessageBox.confirm(`彻底删除「${node.name}」后无法恢复,确定继续?`, '彻底删除', {
      type: 'warning',
      confirmButtonText: '彻底删除',
      cancelButtonText: '取消',
    })
    purgeMutation.mutate({ ids: [node.id] })
  } catch {
    // 取消
  }
}
</script>

<template>
  <div class="trash">
    <h2 class="page-title">回收站</h2>

    <div class="toolbar">
      <template v-if="!isMobile">
        <el-button
          type="primary"
          :icon="RefreshLeft"
          :disabled="selection.length === 0"
          @click="onRestore"
        >
          还原
        </el-button>
        <el-button
          type="danger"
          :icon="Delete"
          :disabled="selection.length === 0"
          @click="onPurge"
        >
          彻底删除
        </el-button>
      </template>
      <el-button type="danger" plain :disabled="items.length === 0" @click="onPurgeTrash">
        清空回收站
      </el-button>
      <span v-if="!isMobile && selection.length > 0" class="selection-hint">
        已选 {{ selection.length }} 项
      </span>
      <span v-else-if="isMobile && items.length > 0" class="selection-hint">
        长按条目可还原或彻底删除
      </span>
    </div>

    <el-table
      v-if="!isMobile"
      v-loading="isFetching"
      :data="items"
      row-key="id"
      empty-text="回收站是空的"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="44" />
      <el-table-column label="名称" min-width="320">
        <template #default="{ row }">
          <span class="name-cell">
            <el-icon class="name-icon">
              <Folder v-if="asTrash(row).kind === 'FOLDER'" />
              <Document v-else />
            </el-icon>
            {{ asTrash(row).name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ asTrash(row).kind === 'FOLDER' ? '—' : formatBytes(asTrash(row).size) }}
        </template>
      </el-table-column>
      <el-table-column label="删除时间" width="180">
        <template #default="{ row }">
          {{ formatTime(asTrash(row).deletedAt) }}
        </template>
      </el-table-column>
      <!-- 1024 以下收起:再加一列这张表就挤了(COMPACT 档就是为这种事留的) -->
      <el-table-column v-if="!isCompact" label="到期" width="140">
        <template #default="{ row }">
          <span :class="expiryClass(asTrash(row).purgeAt)">
            {{ trashExpiryText(asTrash(row).purgeAt) }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <NodeCardList
      v-else
      :items="cardItems"
      mode="list"
      :selection-mode="false"
      :selected-ids="[]"
      :loading="isFetching"
      :secondary="cardSecondary"
      @menu="(node) => (sheet = { visible: true, node })"
    />

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
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 600;
}
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}
.selection-hint {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.name-icon {
  color: var(--el-color-primary);
}
.expiry-urgent {
  color: var(--el-color-danger);
}
</style>
