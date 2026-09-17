<script setup lang="ts">
import { Download } from '@element-plus/icons-vue'

import { formatBytes, formatTime } from '@/utils/format'
import NodeIcon from './NodeIcon.vue'
import { folderSizeText } from './nodeDisplay'
import type { NodeListItem } from './types'

/**
 * 桌面表格。行为与重构前一致:勾选列多选、双击进目录/预览、右键出菜单、行尾下载。
 * 手机不用它(见 NodeCardList) —— 744px 的最小列宽在手机上必然横向滚动。
 */
defineProps<{
  items: NodeListItem[]
  loading?: boolean
  selectedIds: string[]
  downloadingId?: string | null
}>()

const emit = defineEmits<{
  (e: 'select', rows: NodeListItem[]): void
  (e: 'open', row: NodeListItem): void
  (e: 'menu', row: NodeListItem, x: number, y: number): void
  (e: 'download', row: NodeListItem): void
}>()

/** el-table 的 row slot 是宽类型,这里收窄回业务类型 */
function asNode(row: unknown): NodeListItem {
  return row as NodeListItem
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="items"
    row-key="id"
    empty-text="这里空空如也"
    @selection-change="(rows: unknown[]) => emit('select', rows.map(asNode))"
    @row-dblclick="(row: unknown) => emit('open', asNode(row))"
    @row-contextmenu="(row: unknown, _col: unknown, e: MouseEvent) => emit('menu', asNode(row), e.clientX, e.clientY)"
  >
    <el-table-column type="selection" width="44" />
    <el-table-column label="名称" min-width="320">
      <template #default="{ row }">
        <span class="name-cell" :class="{ folder: asNode(row).kind === 'FOLDER' }">
          <NodeIcon :node="asNode(row)" :size="28" />
          {{ asNode(row).name }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="大小" width="120">
      <template #default="{ row }">
        <span
          v-if="asNode(row).kind === 'FOLDER'"
          class="folder-size"
          :class="{ stale: asNode(row).statsStale }"
          :title="
            asNode(row).statsStale
              ? '统计中,数字可能滞后'
              : `${asNode(row).subtreeCount ?? 0} 个文件`
          "
        >
          {{ folderSizeText(asNode(row)) }}
        </span>
        <template v-else>{{ formatBytes(asNode(row).size) }}</template>
      </template>
    </el-table-column>
    <el-table-column label="修改时间" width="180">
      <template #default="{ row }">
        {{ formatTime(asNode(row).updatedAt) }}
      </template>
    </el-table-column>
    <el-table-column label="操作" width="80" align="center">
      <template #default="{ row }">
        <el-button
          link
          type="primary"
          :icon="Download"
          :loading="downloadingId === asNode(row).id"
          :title="asNode(row).kind === 'FOLDER' ? '打包下载' : '下载'"
          @click.stop="emit('download', asNode(row))"
        />
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.name-cell.folder {
  cursor: pointer;
}
.folder-size {
  color: var(--el-text-color-secondary);
}
.folder-size.stale {
  opacity: 0.6;
}
</style>
