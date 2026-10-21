<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { FileRequestUploadsDocument, MarkFileRequestSeenDocument } from '@/api/gen/graphql'
import type { FileRequestUploadsQuery } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { describeUserAgent } from '@/utils/userAgent'
import { useBreakpoints } from '@/composables/breakpoints'

type UploadItem = FileRequestUploadsQuery['fileRequestUploads']['items'][number]

/** 收集链接的收件记录:谁、什么时候、传了什么。打开即标为已读。 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ requestId: string; title: string; newCount: number }>()

const router = useRouter()
const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const { data, isFetching, isError, error, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
  queryKey: computed(() => ['fileRequestUploads', props.requestId]),
  enabled: computed(() => visible.value && !!props.requestId),
  queryFn: ({ pageParam }) =>
    request(FileRequestUploadsDocument, { id: props.requestId, cursor: pageParam ?? null }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.fileRequestUploads.nextCursor ?? null,
})
const items = computed<UploadItem[]>(() => data.value?.pages.flatMap((p) => p.fileRequestUploads.items) ?? [])

// 打开即已读:列表上的"新收到"清零(记录本身在 enabled 变真时自动重新拉)
watch(visible, async (v) => {
  if (!v || !props.requestId) return
  if (props.newCount > 0) {
    try {
      await request(MarkFileRequestSeenDocument, { id: props.requestId })
      void queryClient.invalidateQueries({ queryKey: ['myFileRequests'] })
    } catch {
      // 标已读失败不影响查看
    }
  }
})

function uploader(row: UploadItem): string {
  return row.uploaderName || '未署名'
}

function locate(row: UploadItem) {
  if (!row.node) return
  visible.value = false
  void router.push(row.node.parentId ? `/drive/${row.node.parentId}` : '/drive')
}

function asItem(row: unknown): UploadItem {
  return row as UploadItem
}
</script>

<template>
  <el-dialog v-model="visible" :title="`收件记录 · ${props.title}`" width="760px">
    <el-alert v-if="isError" type="error" :closable="false" :title="errorText(error, '加载失败')" />

    <el-table
      v-else-if="!isMobile"
      v-loading="isFetching && !isFetchingNextPage"
      :data="items"
      row-key="id"
      empty-text="还没有人传文件"
      max-height="56vh"
    >
      <el-table-column label="文件" min-width="220">
        <template #default="{ row }">
          <el-button v-if="asItem(row).node" link type="primary" class="file-link" @click="locate(asItem(row))">
            {{ asItem(row).fileName }}
          </el-button>
          <span v-else class="gone" title="文件已删除">{{ asItem(row).fileName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatBytes(asItem(row).size) }}</template>
      </el-table-column>
      <el-table-column label="上传者" width="120">
        <template #default="{ row }">
          <span :title="'名字由上传者自填'">{{ uploader(asItem(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ formatTime(asItem(row).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="来源" width="170">
        <template #default="{ row }">
          <span class="src" :title="asItem(row).userAgent ?? ''">
            {{ asItem(row).ip ?? '—' }}<br />{{ describeUserAgent(asItem(row).userAgent) }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <ul v-else v-loading="isFetching && !isFetchingNextPage" class="mobile-list">
      <li v-if="!items.length && !isFetching" class="empty">还没有人传文件</li>
      <li v-for="row in items" :key="row.id" class="mobile-item" @click="locate(row)">
        <span class="m-name" :class="{ gone: !row.node }">{{ row.fileName }}</span>
        <span class="m-meta">
          {{ uploader(row) }} · {{ formatBytes(row.size) }} · {{ formatTime(row.createdAt) }}
        </span>
      </li>
    </ul>

    <div v-if="hasNextPage" class="more">
      <el-button :loading="isFetchingNextPage" @click="fetchNextPage()">加载更多</el-button>
    </div>
    <p class="note">名字由上传者自填;文件删除后记录仍保留</p>
  </el-dialog>
</template>

<style scoped>
.file-link {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gone {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}
.src {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.mobile-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 60vh;
  overflow-y: auto;
}
.mobile-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.m-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.m-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.empty {
  padding: 24px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.more {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}
.note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
