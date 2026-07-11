<script setup lang="ts">
import { computed } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { CopyDocument, Document, Folder, Lock } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { MySharesDocument, RevokeShareDocument } from '@/api/gen/graphql'
import type { MySharesQuery } from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'

type ShareItem = MySharesQuery['myShares'][number]

const queryClient = useQueryClient()

const { data, isFetching } = useQuery({
  queryKey: ['myShares'],
  queryFn: () => request(MySharesDocument),
})
const items = computed(() => data.value?.myShares ?? [])

const revokeMutation = useMutation({
  mutationFn: (vars: { id: string }) => request(RevokeShareDocument, vars),
  onSuccess: () => {
    ElMessage.success('分享已取消')
    void queryClient.invalidateQueries({ queryKey: ['myShares'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '取消分享失败')),
})

function asShare(row: unknown): ShareItem {
  return row as ShareItem
}

function shareUrl(row: ShareItem): string {
  return `${location.origin}/s/${row.token}`
}

async function onCopy(row: ShareItem) {
  try {
    await navigator.clipboard.writeText(shareUrl(row))
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.error('复制失败,请手动复制')
  }
}

function isExpired(row: ShareItem): boolean {
  return !!row.expiresAt && new Date(row.expiresAt).getTime() < Date.now()
}

function expireText(row: ShareItem): string {
  if (!row.expiresAt) return '永久有效'
  return (isExpired(row) ? '已过期 ' : '至 ') + formatTime(row.expiresAt)
}
</script>

<template>
  <div class="shares">
    <h3 class="page-title">我的分享</h3>
    <el-table v-loading="isFetching" :data="items" row-key="id" empty-text="还没有创建过分享">
      <el-table-column label="内容" min-width="280">
        <template #default="{ row }">
          <span class="name-cell">
            <el-icon class="name-icon">
              <component :is="asShare(row).node.kind === 'FOLDER' ? Folder : Document" />
            </el-icon>
            <a class="share-link" :href="shareUrl(asShare(row))" target="_blank" rel="noopener">
              {{ asShare(row).node.name }}
            </a>
            <el-icon v-if="asShare(row).hasPassword" class="lock-icon" title="有密码保护">
              <Lock />
            </el-icon>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(asShare(row).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="有效期" width="200">
        <template #default="{ row }">
          <span :class="{ expired: isExpired(asShare(row)) }">{{ expireText(asShare(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="CopyDocument" @click="onCopy(asShare(row))">
            复制链接
          </el-button>
          <el-popconfirm
            title="取消后链接立即失效,确定?"
            confirm-button-text="取消分享"
            cancel-button-text="再想想"
            @confirm="revokeMutation.mutate({ id: asShare(row).id })"
          >
            <template #reference>
              <el-button link type="danger">取消分享</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.name-icon {
  color: var(--el-color-primary);
}
.share-link {
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.share-link:hover {
  color: var(--el-color-primary);
}
.lock-icon {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.expired {
  color: var(--el-color-danger);
}
</style>
