<script setup lang="ts">
import NodeCard from './NodeCard.vue'
import type { NodeListItem } from './types'

/** 手机上的节点列表:list 一行一条,grid 两列以上缩略图墙 */
defineProps<{
  items: NodeListItem[]
  mode: 'list' | 'grid'
  selectionMode: boolean
  selectedIds: string[]
  downloadingId?: string | null
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'open', node: NodeListItem): void
  (e: 'menu', node: NodeListItem): void
  (e: 'toggle', node: NodeListItem): void
}>()
</script>

<template>
  <div v-loading="loading" class="node-card-list" :class="`mode-${mode}`">
    <NodeCard
      v-for="node in items"
      :key="node.id"
      :node="node"
      :mode="mode"
      :selection-mode="selectionMode"
      :selected="selectedIds.includes(node.id)"
      :downloading="downloadingId === node.id"
      @open="emit('open', $event)"
      @menu="emit('menu', $event)"
      @toggle="emit('toggle', $event)"
    />
    <el-empty v-if="!items.length && !loading" description="这里空空如也" class="empty" />
  </div>
</template>

<style scoped>
.node-card-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.node-card-list.mode-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(108px, 1fr));
  gap: 10px;
}
.empty {
  grid-column: 1 / -1;
}
</style>
