<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Document,
  Folder,
  Headset,
  Memo,
  Picture,
  Reading,
  VideoCamera,
} from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { SearchNodesDocument } from '@/api/gen/graphql'
import type { SearchNodesQuery } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { openPreview } from '@/composables/preview'

type Item = SearchNodesQuery['searchNodes']['items'][number]

const route = useRoute()
const router = useRouter()

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

function onRowDblclick(row: Item) {
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
function onLocate(row: Item) {
  void router.push(row.parentId ? `/drive/${row.parentId}` : '/drive')
}

// ---------- 图标 / 缩略图 ----------

const thumbErrors = ref(new Set<string>())

function onThumbError(id: string) {
  const next = new Set(thumbErrors.value)
  next.add(id)
  thumbErrors.value = next
}

function showThumb(row: Item): boolean {
  return (
    row.kind === 'FILE' &&
    (row.preview.kind === 'IMAGE' || row.preview.kind === 'VIDEO') &&
    !!row.preview.thumbUrl &&
    !thumbErrors.value.has(row.id)
  )
}

function fileIcon(row: Item) {
  if (row.kind === 'FOLDER') return Folder
  switch (row.preview.kind) {
    case 'IMAGE':
      return Picture
    case 'VIDEO':
      return VideoCamera
    case 'AUDIO':
      return Headset
    case 'PDF':
      return Reading
    case 'TEXT':
      return Memo
    default:
      return Document
  }
}

function asItem(row: unknown): Item {
  return row as Item
}
</script>

<template>
  <div class="search-view">
    <h3 class="page-title">
      搜索「{{ q }}」
      <span v-if="!loading" class="result-count">{{ total }} 个结果</span>
    </h3>
    <el-table
      v-loading="loading && items.length === 0"
      :data="items"
      row-key="id"
      empty-text="没有匹配的文件"
      @row-dblclick="onRowDblclick"
    >
      <el-table-column label="名称" min-width="320">
        <template #default="{ row }">
          <span class="name-cell">
            <img
              v-if="showThumb(asItem(row))"
              class="name-thumb"
              :src="asItem(row).preview.thumbUrl!"
              alt=""
              loading="lazy"
              @error="onThumbError(asItem(row).id)"
            />
            <el-icon v-else class="name-icon">
              <component :is="fileIcon(asItem(row))" />
            </el-icon>
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
          <el-button link type="primary" @click.stop="onLocate(asItem(row))">
            所在目录
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="nextCursor" class="load-more">
      <el-button :loading="loading" @click="onLoadMore">加载更多</el-button>
    </div>
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
.name-icon {
  color: var(--el-color-primary);
}
.name-thumb {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 4px;
  object-fit: cover;
  background: var(--el-fill-color-light);
}
.load-more {
  display: flex;
  justify-content: center;
  padding: 16px;
}
</style>
