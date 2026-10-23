<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, EditPen, Folder, Lock, MoreFilled, Tickets, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { MyFileRequestsDocument, RevokeFileRequestDocument } from '@/api/gen/graphql'
import type { FileRequestFieldsFragment } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { useBreakpoints } from '@/composables/breakpoints'
import FileRequestDialog from '@/components/FileRequestDialog.vue'
import FileRequestUploadsDialog from '@/components/FileRequestUploadsDialog.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import type { SheetItem } from '@/components/nodes/types'

/** 「分享」页的「收集」标签:我开的收集链接、收件统计与操作(doc/20)。 */

type Item = FileRequestFieldsFragment

/** openId:通知点进来(/shares?tab=requests&id=)时直接打开这条的收件记录;打开后 emit consumed 让页面清掉 id */
const props = defineProps<{ openId?: string | null }>()
const emit = defineEmits<{ consumed: [] }>()

const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: ['myFileRequests'],
  queryFn: () => request(MyFileRequestsDocument),
})
const items = computed(() => data.value?.myFileRequests ?? [])

const revokeMutation = useMutation({
  mutationFn: (id: string) => request(RevokeFileRequestDocument, { id }),
  onSuccess: () => {
    ElMessage.success('收集已结束,链接已失效')
    void queryClient.invalidateQueries({ queryKey: ['myFileRequests'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '操作失败')),
})

async function onRevoke(row: Item) {
  try {
    await ElMessageBox.confirm('结束后链接立即失效,正在传的也会失败。已收到的文件不受影响。', `结束「${row.title}」?`, {
      confirmButtonText: '结束收集',
      cancelButtonText: '再想想',
      type: 'warning',
    })
  } catch {
    return
  }
  revokeMutation.mutate(row.id)
}

function linkUrl(row: Item): string {
  return `${location.origin}/c/${row.token}`
}

async function onCopy(row: Item) {
  try {
    await navigator.clipboard.writeText(linkUrl(row))
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.error('复制失败,请手动复制')
  }
}

function isExpired(row: Item): boolean {
  return !!row.expiresAt && new Date(row.expiresAt).getTime() < Date.now()
}

function expireText(row: Item): string {
  if (!row.expiresAt) return '不限时间'
  return (isExpired(row) ? '已截止 ' : '截止 ') + formatTime(row.expiresAt)
}

function receivedText(row: Item): string {
  if (row.fileCount === 0) return '还没收到'
  return `${row.fileCount} 个 · ${formatBytes(row.totalBytes)} · ${row.uploaderCount} 人`
}

function limitText(row: Item): string {
  const parts: string[] = []
  if (row.maxFiles) parts.push(`≤${row.maxFiles} 个`)
  if (row.maxTotalBytes) parts.push(`共 ≤${formatBytes(row.maxTotalBytes)}`)
  if (row.maxFileSize) parts.push(`单个 ≤${formatBytes(row.maxFileSize)}`)
  return parts.join(' · ')
}

// ---------- 对话框 ----------

const editDialog = ref<{ visible: boolean; request: Item | null }>({ visible: false, request: null })
const uploadsDialog = ref<{ visible: boolean; id: string; title: string; newCount: number }>({
  visible: false,
  id: '',
  title: '',
  newCount: 0,
})

function openUploads(row: Item) {
  uploadsDialog.value = { visible: true, id: row.id, title: row.title, newCount: row.newCount }
}

watch(
  [() => props.openId, items, isFetching],
  ([id, list, fetching]) => {
    if (!id) return
    const row = list.find((r) => r.id === id)
    if (row) openUploads(row)
    else if (fetching) return // 等列表回来再找
    else ElMessage.warning('这条收集链接已经不在了')
    emit('consumed')
  },
  { immediate: true },
)

// ---------- 手机 ----------

const sheet = ref<{ visible: boolean; row: Item | null }>({ visible: false, row: null })
const sheetItems: SheetItem[] = [
  { key: 'uploads', label: '收件记录', icon: Tickets },
  { key: 'open', label: '打开链接', icon: View },
  { key: 'copy', label: '复制链接', icon: CopyDocument },
  { key: 'edit', label: '修改', icon: EditPen },
  { key: 'revoke', label: '结束收集', icon: Delete, danger: true },
]

function onSheetSelect(key: string) {
  const row = sheet.value.row
  if (!row) return
  if (key === 'uploads') openUploads(row)
  else if (key === 'open') window.open(linkUrl(row), '_blank', 'noopener')
  else if (key === 'copy') void onCopy(row)
  else if (key === 'edit') editDialog.value = { visible: true, request: row }
  else void onRevoke(row)
}

function asItem(row: unknown): Item {
  return row as Item
}
</script>

<template>
  <div class="fr-panel">
    <p v-if="!items.length && data" class="intro">
      在文件夹上右键(手机上长按)选「收集文件」,把链接发给别人:对方不用注册就能往里传,看不到里面已有的内容。
    </p>

    <el-table
      v-if="!isMobile"
      v-loading="isFetching && !data"
      :data="items"
      row-key="id"
      empty-text="还没有收集链接"
    >
      <el-table-column label="标题" min-width="240">
        <template #default="{ row }">
          <div class="title-cell">
            <span class="title-line">
              <a class="fr-link" :href="linkUrl(asItem(row))" target="_blank" rel="noopener">{{ asItem(row).title }}</a>
              <el-icon v-if="asItem(row).hasPassword" class="lock-icon" title="有密码保护"><Lock /></el-icon>
            </span>
            <span class="sub">
              <el-icon><Folder /></el-icon>{{ asItem(row).folder.name }}
              <template v-if="asItem(row).groupByUploader"> · 按人分文件夹</template>
              <template v-else-if="asItem(row).requireName"> · 要求留名</template>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="已收到" min-width="180">
        <template #default="{ row }">
          <div class="received">
            <el-button link type="primary" @click="openUploads(asItem(row))">{{ receivedText(asItem(row)) }}</el-button>
            <el-tag v-if="asItem(row).newCount" type="danger" size="small" effect="dark" round>
              新 {{ asItem(row).newCount }}
            </el-tag>
          </div>
          <div v-if="limitText(asItem(row))" class="limit">{{ limitText(asItem(row)) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="截止" width="190">
        <template #default="{ row }">
          <span :class="{ expired: isExpired(asItem(row)) }">{{ expireText(asItem(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="CopyDocument" @click="onCopy(asItem(row))">复制链接</el-button>
          <el-button link :icon="EditPen" @click="editDialog = { visible: true, request: asItem(row) }">修改</el-button>
          <el-button link type="danger" @click="onRevoke(asItem(row))">结束</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ul v-else v-loading="isFetching && !data" class="cards">
      <li v-if="!items.length && !(isFetching && !data)" class="empty">还没有收集链接</li>
      <li v-for="row in items" :key="row.id" class="card" @click="openUploads(row)">
        <div class="card-main">
          <span class="title-line">
            {{ row.title }}
            <el-icon v-if="row.hasPassword" class="lock-icon"><Lock /></el-icon>
            <el-tag v-if="row.newCount" type="danger" size="small" effect="dark" round>新 {{ row.newCount }}</el-tag>
          </span>
          <span class="sub">{{ row.folder.name }} · {{ receivedText(row) }}</span>
          <span class="sub" :class="{ expired: isExpired(row) }">{{ expireText(row) }}</span>
        </div>
        <el-button link :icon="MoreFilled" @click.stop="sheet = { visible: true, row }" />
      </li>
    </ul>

    <NodeActionSheet
      v-model:visible="sheet.visible"
      :title="sheet.row?.title"
      :items="sheetItems"
      @select="onSheetSelect"
    />
    <FileRequestDialog v-model="editDialog.visible" :request="editDialog.request" />
    <FileRequestUploadsDialog
      v-model="uploadsDialog.visible"
      :request-id="uploadsDialog.id"
      :title="uploadsDialog.title"
      :new-count="uploadsDialog.newCount"
    />
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.title-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.title-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.fr-link {
  color: var(--el-text-color-primary);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fr-link:hover {
  color: var(--el-color-primary);
}
.sub {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.lock-icon {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.expired {
  color: var(--el-color-danger);
}
.received {
  display: flex;
  align-items: center;
  gap: 6px;
}
.limit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cards {
  list-style: none;
  margin: 0;
  padding: 0;
}
.card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.empty {
  padding: 32px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}
</style>
