<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { CopyDocument, DataLine, Delete, Document, Folder, Lock, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { MySharesDocument, RevokeShareDocument } from '@/api/gen/graphql'
import type { MySharesQuery } from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import ShareVisitsDialog from '@/components/ShareVisitsDialog.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type ShareItem = MySharesQuery['myShares'][number]

const queryClient = useQueryClient()
const { isMobile, isCompact } = useBreakpoints()

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

// ---------- 访问记录 ----------

const visitsDialog = ref<{ visible: boolean; shareId: string; shareName: string }>({
  visible: false,
  shareId: '',
  shareName: '',
})

function openVisits(row: ShareItem) {
  visitsDialog.value = { visible: true, shareId: row.id, shareName: row.node.name }
}

/** 桌面按钮与手机卡片共用的一句话。计数是近 180 天的,不是历史总量。 */
function visitText(row: ShareItem): string {
  const v = row.visitStats
  if (v.verifyCount + v.downloadCount === 0) return '暂无访问'
  return `访问 ${v.verifyCount} · 下载 ${v.downloadCount}`
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

// ---------- 手机卡片 ----------

/** 卡片用节点形状渲染,靠 id 映射回分享记录拿 token/有效期 */
const byNodeId = computed(() => new Map(items.value.map((s) => [s.node.id, s])))

const cardItems = computed<NodeListItem[]>(() =>
  items.value.map((s) => ({
    id: s.node.id,
    name: s.node.name,
    kind: s.node.kind,
    updatedAt: s.createdAt,
  })),
)

function cardSecondary(node: NodeListItem): string {
  const share = byNodeId.value.get(node.id)
  if (!share) return ''
  return `${formatTime(share.createdAt)} · ${expireText(share)} · ${visitText(share)}`
}

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({
  visible: false,
  node: null,
})

/** 不同分享可能指向同名节点,这里按行内点击的目标节点取分享记录 */
const sheetItems: SheetItem[] = [
  { key: 'open', label: '打开链接', icon: View },
  { key: 'copy', label: '复制链接', icon: CopyDocument },
  { key: 'visits', label: '访问记录', icon: DataLine },
  { key: 'revoke', label: '取消分享', icon: Delete, danger: true },
]

function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  const share = byNodeId.value.get(node.id)
  if (!share) return
  if (key === 'open') {
    window.open(shareUrl(share), '_blank', 'noopener')
    return
  }
  if (key === 'copy') {
    void onCopy(share)
    return
  }
  if (key === 'visits') {
    openVisits(share)
    return
  }
  revokeMutation.mutate({ id: share.id })
}
</script>

<template>
  <div class="shares">
    <h3 class="page-title">我的分享</h3>

    <el-table
      v-if="!isMobile"
      v-loading="isFetching"
      :data="items"
      row-key="id"
      empty-text="还没有创建过分享"
    >
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
      <!-- 1024 以下收起:这张表列已经不少了 -->
      <el-table-column v-if="!isCompact" label="访问" width="170">
        <template #default="{ row }">
          <el-button link type="primary" @click="openVisits(asShare(row))">
            {{ visitText(asShare(row)) }}
          </el-button>
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

    <ShareVisitsDialog
      v-model:visible="visitsDialog.visible"
      :share-id="visitsDialog.shareId"
      :share-name="visitsDialog.shareName"
    />
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
