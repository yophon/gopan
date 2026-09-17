<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { FolderOpened } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { SearchNodesDocument } from '@/api/gen/graphql'
import type { SearchNodesQuery } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import NodeIcon from '@/components/nodes/NodeIcon.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type Item = SearchNodesQuery['searchNodes']['items'][number]

const route = useRoute()
const router = useRouter()
const { isMobile } = useBreakpoints()

const q = computed(() => (typeof route.query.q === 'string' ? route.query.q.trim() : ''))

// 手动累积分页:换关键词清空重来,nextCursor 驱动「加载更多」
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

watch(q, () => void fetchPage(null), { immediate: true })

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

function asItem(row: unknown): Item {
  return row as Item
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
      <span v-if="!loading" class="result-count">{{ total }} 个结果</span>
    </h3>

    <el-table
      v-if="!isMobile"
      v-loading="loading && items.length === 0"
      :data="items"
      row-key="id"
      empty-text="没有匹配的文件"
      @row-dblclick="(row: unknown) => onOpen(asItem(row))"
    >
      <el-table-column label="名称" min-width="320">
        <template #default="{ row }">
          <span class="name-cell">
            <NodeIcon :node="asItem(row)" :size="28" />
            {{ asItem(row).name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ asItem(row).kind === 'FOLDER' ? '—' : formatBytes(asItem(row).size) }}
        </template>
      </el-table-column>
      <el-table-column label="修改时间" width="180">
        <template #default="{ row }">{{ formatTime(asItem(row).updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="onLocate(asItem(row))">所在目录</el-button>
        </template>
      </el-table-column>
    </el-table>

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
}
.result-count {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.load-more {
  display: flex;
  justify-content: center;
  padding: 16px;
}
</style>
