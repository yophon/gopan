<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Delete } from '@element-plus/icons-vue'

import type { SpaceFileFieldsFragment } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { useSpaceCleanup } from '@/composables/space'
import { formatBytes, formatTime } from '@/utils/format'
import { notificationRoute } from '@/utils/notifications'
import { categoryText } from '@/utils/space'
import NodeIcon from '@/components/nodes/NodeIcon.vue'

/**
 * 空间页里「大文件 / 很久没动 / 空文件夹」共用的列表:勾选 → 移进回收站。
 * 桌面是表格(勾选列),手机是带复选框的卡片;点名字去它所在的文件夹。
 */
const props = defineProps<{
  items: SpaceFileFieldsFragment[]
  loading: boolean
  emptyText: string
  /** 文件夹列表不显示大小与类型 */
  folders?: boolean
}>()

const router = useRouter()
const { isMobile, isCompact } = useBreakpoints()
const { trash } = useSpaceCleanup()

const selected = ref<string[]>([])
const table = ref<{ clearSelection: () => void } | null>(null)
// 列表换了(删完刷新、换了筛选)就清掉勾选,免得删到看不见的
watch(
  () => props.items,
  () => {
    selected.value = []
    table.value?.clearSelection()
  },
)

const selectedBytes = computed(() =>
  props.items.filter((f) => selected.value.includes(f.node.id)).reduce((s, f) => s + (f.node.size ?? 0), 0),
)
const allChecked = computed(() => props.items.length > 0 && selected.value.length === props.items.length)

function toggle(id: string) {
  selected.value = selected.value.includes(id) ? selected.value.filter((x) => x !== id) : [...selected.value, id]
}

function toggleAll() {
  selected.value = allChecked.value ? [] : props.items.map((f) => f.node.id)
}

function asFile(row: unknown): SpaceFileFieldsFragment {
  return row as SpaceFileFieldsFragment
}

function open(f: SpaceFileFieldsFragment) {
  const to = notificationRoute({ kind: 'NODE', id: f.node.id, parentId: f.node.parentId })
  if (to) void router.push(to)
}

async function onDelete() {
  const ok = await trash([...selected.value], props.folders ? '个文件夹' : '个文件', props.folders ? undefined : selectedBytes.value)
  if (ok) selected.value = []
}
</script>

<template>
  <div class="sfl">
    <div class="sfl-bar">
      <el-checkbox
        v-if="isMobile"
        :model-value="allChecked"
        :indeterminate="selected.length > 0 && !allChecked"
        :disabled="!items.length"
        @change="toggleAll"
      >
        全选
      </el-checkbox>
      <span class="muted">
        <template v-if="selected.length">已选 {{ selected.length }} 项<template v-if="!folders"> · {{ formatBytes(selectedBytes) }}</template></template>
        <template v-else>共 {{ items.length }} 项</template>
      </span>
      <el-button type="danger" plain size="small" :icon="Delete" :disabled="!selected.length" @click="onDelete">
        移进回收站
      </el-button>
    </div>

    <el-table
      v-if="!isMobile"
      ref="table"
      v-loading="loading"
      :data="items"
      row-key="node.id"
      :empty-text="emptyText"
      max-height="560"
      @selection-change="(rows: SpaceFileFieldsFragment[]) => (selected = rows.map((r) => r.node.id))"
    >
      <el-table-column type="selection" width="44" />
      <el-table-column label="名称" min-width="220">
        <template #default="{ row }">
          <span class="name-cell">
            <NodeIcon :node="asFile(row).node" :size="22" />
            <button type="button" class="name" :title="`打开所在位置:${asFile(row).node.name}`" @click="open(asFile(row))">
              {{ asFile(row).node.name }}
            </button>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="位置" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ asFile(row).path }}</template>
      </el-table-column>
      <el-table-column v-if="!folders" label="大小" width="100" align="right">
        <template #default="{ row }">{{ formatBytes(asFile(row).node.size) }}</template>
      </el-table-column>
      <el-table-column v-if="!folders && !isCompact" label="类型" width="100">
        <template #default="{ row }">{{ categoryText(asFile(row).category) }}</template>
      </el-table-column>
      <el-table-column label="修改时间" width="150">
        <template #default="{ row }">{{ formatTime(asFile(row).node.updatedAt) }}</template>
      </el-table-column>
    </el-table>

    <ul v-else v-loading="loading" class="cards">
      <li v-if="!items.length && !loading" class="empty">{{ emptyText }}</li>
      <li v-for="f in items" :key="f.node.id" class="card">
        <el-checkbox :model-value="selected.includes(f.node.id)" @change="toggle(f.node.id)" />
        <NodeIcon :node="f.node" :size="32" />
        <div class="card-main" @click="open(f)">
          <span class="card-name">{{ f.node.name }}</span>
          <span class="card-sub">
            <template v-if="!folders">{{ formatBytes(f.node.size) }} · </template>{{ f.path }}
          </span>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.sfl-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.muted {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}
.name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.name:hover {
  color: var(--el-color-primary);
  text-decoration: underline;
}
.cards {
  margin: 0;
  padding: 0;
  list-style: none;
}
.card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 2px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.card-name,
.card-sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.empty {
  padding: 32px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}
</style>
