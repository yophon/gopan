<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DataBoard,
  Delete,
  Document,
  Download,
  EditPen,
  Folder,
  FolderAdd,
  Grid,
  Headset,
  Memo,
  Picture,
  Rank,
  Reading,
  Share,
  Upload,
  VideoCamera,
} from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ChildrenDocument,
  CreateFolderDocument,
  DeleteNodesDocument,
  MoveNodesDocument,
  NodeDocument,
  NodeDownloadUrlDocument,
  RenameNodeDocument,
} from '@/api/gen/graphql'
import type { ChildrenQuery } from '@/api/gen/graphql'
import { enqueueFiles } from '@/uploader/manager'
import { formatBytes, formatTime } from '@/utils/format'
import { openPreview } from '@/composables/preview'
import { useAuthStore } from '@/stores/auth'
import MoveDialog from '@/components/MoveDialog.vue'
import ShareDialog from '@/components/ShareDialog.vue'

type ChildItem = ChildrenQuery['children']['items'][number]

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

/** 当前文件夹 id;null = 根目录 */
const folderId = computed<string | null>(() => {
  const raw = route.params.folderId
  return typeof raw === 'string' && raw !== '' ? raw : null
})

// ---------- 查询 ----------

const { data, isFetching } = useQuery({
  queryKey: ['children', folderId],
  queryFn: () => request(ChildrenDocument, { parentId: folderId.value }),
})
const items = computed(() => data.value?.children.items ?? [])

interface Crumb {
  id: string
  name: string
}

/** 面包屑:从当前文件夹沿 parentId 回溯到根 */
const { data: crumbs } = useQuery({
  queryKey: ['breadcrumb', folderId],
  queryFn: async (): Promise<Crumb[]> => {
    const chain: Crumb[] = []
    let cur = folderId.value
    while (cur) {
      const res = await request(NodeDocument, { id: cur })
      chain.unshift({ id: res.node.id, name: res.node.name })
      cur = res.node.parentId ?? null
    }
    return chain
  },
})

// ---------- 多选 ----------

const selection = ref<ChildItem[]>([])
const selectedIds = computed(() => selection.value.map((n) => n.id))

function onSelectionChange(rows: ChildItem[]) {
  selection.value = rows
}

// ---------- 变更 ----------

function invalidate() {
  // 移动会同时影响旧父与新父,统一按前缀失效最稳妥
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  void queryClient.invalidateQueries({ queryKey: ['breadcrumb'] })
}

const createFolderMutation = useMutation({
  mutationFn: (vars: { parentId: string | null; name: string }) =>
    request(CreateFolderDocument, vars),
  onSuccess: () => {
    ElMessage.success('文件夹已创建')
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const renameMutation = useMutation({
  mutationFn: (vars: { id: string; name: string }) => request(RenameNodeDocument, vars),
  onSuccess: () => {
    ElMessage.success('重命名成功')
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const moveMutation = useMutation({
  mutationFn: (vars: { ids: string[]; targetParentId: string | null }) =>
    request(MoveNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('移动成功')
    selection.value = []
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const deleteMutation = useMutation({
  mutationFn: (vars: { ids: string[] }) => request(DeleteNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('已放入回收站')
    selection.value = []
    invalidate()
    void queryClient.invalidateQueries({ queryKey: ['trash'] })
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

// ---------- 工具栏操作 ----------

async function onCreateFolder() {
  try {
    const { value } = await ElMessageBox.prompt('请输入文件夹名称', '新建文件夹', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    createFolderMutation.mutate({ parentId: folderId.value, name: value.trim() })
  } catch {
    // 取消
  }
}

async function onRename() {
  const target = selection.value[0]
  if (!target || selection.value.length !== 1) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: target.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    renameMutation.mutate({ id: target.id, name: value.trim() })
  } catch {
    // 取消
  }
}

const moveDialogVisible = ref(false)

function onMove() {
  if (selection.value.length === 0) return
  moveDialogVisible.value = true
}

function onMoveConfirm(targetParentId: string | null) {
  moveMutation.mutate({ ids: selectedIds.value, targetParentId })
}

function onDelete() {
  if (selection.value.length === 0) return
  deleteMutation.mutate({ ids: selectedIds.value })
}

// ---------- 上传 ----------

const fileInput = ref<HTMLInputElement | null>(null)

function onPickFiles() {
  fileInput.value?.click()
}

function onFilesChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // 允许重复选择同一文件
  if (files.length > 0) enqueueFiles(files, folderId.value)
}

// 整页拖拽:dragenter/leave 用计数器抵消子元素冒泡
const dragDepth = ref(0)
const dragging = computed(() => dragDepth.value > 0)

function hasFiles(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes('Files')
}

useEventListener(window, 'dragenter', (e: DragEvent) => {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth.value++
})
useEventListener(window, 'dragover', (e: DragEvent) => {
  if (!hasFiles(e)) return
  e.preventDefault()
})
useEventListener(window, 'dragleave', (e: DragEvent) => {
  if (!hasFiles(e)) return
  dragDepth.value = Math.max(0, dragDepth.value - 1)
})
useEventListener(window, 'drop', (e: DragEvent) => {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth.value = 0
  // 只收文件;拖入的文件夹用 webkitGetAsEntry 识别后跳过(文件夹上传是 v2)
  const files: File[] = []
  for (const item of Array.from(e.dataTransfer?.items ?? [])) {
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.()
    if (entry?.isDirectory) continue
    const f = item.getAsFile()
    if (f) files.push(f)
  }
  if (files.length > 0) enqueueFiles(files, folderId.value)
})

// ---------- 下载 ----------

const auth = useAuthStore()
const downloadingId = ref<string | null>(null)

function clickA(href: string, download?: string) {
  const a = document.createElement('a')
  a.href = href
  if (download) a.download = download
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

async function onDownload(row: ChildItem) {
  if (row.kind === 'FOLDER') {
    // 文件夹走服务端流式 zip;token 15 分钟有效,点击即用
    clickA(`/pack?nodes=${row.id}&token=${encodeURIComponent(auth.accessToken ?? '')}`)
    return
  }
  downloadingId.value = row.id
  try {
    // downloadUrl 是 15 分钟预签名,按需查询、即查即用
    const res = await request(NodeDownloadUrlDocument, { id: row.id })
    const url = res.node.downloadUrl
    if (!url) {
      ElMessage.error('无法获取下载链接')
      return
    }
    clickA(url, row.name)
  } catch (err) {
    ElMessage.error(errorText(err, '获取下载链接失败'))
  } finally {
    downloadingId.value = null
  }
}

// ---------- 分享 ----------

const shareDialogVisible = ref(false)
const shareTarget = ref<{ id: string; name: string } | null>(null)

function openShare(row: { id: string; name: string }) {
  shareTarget.value = { id: row.id, name: row.name }
  shareDialogVisible.value = true
}

function onShareSelected() {
  const target = selection.value[0]
  if (target && selection.value.length === 1) openShare(target)
}

// ---------- 右键菜单 ----------

const contextMenu = ref<{ visible: boolean; x: number; y: number; row: ChildItem | null }>({
  visible: false,
  x: 0,
  y: 0,
  row: null,
})

function onRowContextmenu(row: ChildItem, _col: unknown, e: MouseEvent) {
  e.preventDefault()
  contextMenu.value = { visible: true, x: e.clientX, y: e.clientY, row }
}

function closeContextMenu() {
  contextMenu.value.visible = false
}

useEventListener(window, 'click', closeContextMenu)
useEventListener(window, 'contextmenu', (e: MouseEvent) => {
  // 点在表格行上的 contextmenu 由 onRowContextmenu 接管;其他位置关掉菜单
  if (!(e.target as HTMLElement | null)?.closest('.el-table__row')) closeContextMenu()
})

function onContextDownload() {
  const row = contextMenu.value.row
  closeContextMenu()
  if (row) void onDownload(row)
}

function onContextShare() {
  const row = contextMenu.value.row
  closeContextMenu()
  if (row) openShare(row)
}

// ---------- 导航 ----------

/** el-table 的 slot row 是宽类型 DefaultRow,这里收窄回业务类型 */
function asChild(row: unknown): ChildItem {
  return row as ChildItem
}

function onRowDblclick(row: ChildItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
    return
  }
  // 多选状态下双击不触发预览,避免误操作
  if (selection.value.length > 1) return
  const files = items.value.filter((n) => n.kind === 'FILE')
  const idx = files.findIndex((n) => n.id === row.id)
  if (idx >= 0) {
    openPreview(
      files.map((n) => n.id),
      idx,
    )
  }
}

// ---------- 图标 / 缩略图 ----------

/** 缩略图 URL 是乐观签发的,派生物可能还没生成;onerror 记下 id,回落到图标 */
const thumbErrors = ref(new Set<string>())

function onThumbError(id: string) {
  const next = new Set(thumbErrors.value)
  next.add(id)
  thumbErrors.value = next
}

function showThumb(row: ChildItem): boolean {
  return (
    row.kind === 'FILE' &&
    (row.preview.kind === 'IMAGE' || row.preview.kind === 'VIDEO') &&
    !!row.preview.thumbUrl &&
    !thumbErrors.value.has(row.id)
  )
}

function fileIcon(row: ChildItem) {
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
    case 'OFFICE': {
      // OFFICE 内部再按扩展名细分:表格 / 演示 / 文档
      const ext = row.name.split('.').pop()?.toLowerCase() ?? ''
      if (['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return Grid
      if (['ppt', 'pptx', 'odp'].includes(ext)) return DataBoard
      return Document
    }
    default:
      return Document
  }
}
</script>

<template>
  <div class="drive">
    <el-breadcrumb separator="/" class="breadcrumb">
      <el-breadcrumb-item :to="{ path: '/drive' }">我的文件</el-breadcrumb-item>
      <el-breadcrumb-item
        v-for="crumb in crumbs ?? []"
        :key="crumb.id"
        :to="{ path: `/drive/${crumb.id}` }"
      >
        {{ crumb.name }}
      </el-breadcrumb-item>
    </el-breadcrumb>

    <div class="toolbar">
      <el-button type="primary" :icon="Upload" @click="onPickFiles">
        上传文件
      </el-button>
      <el-button :icon="FolderAdd" @click="onCreateFolder">
        新建文件夹
      </el-button>
      <el-button
        :icon="EditPen"
        :disabled="selection.length !== 1"
        @click="onRename"
      >
        重命名
      </el-button>
      <el-button :icon="Rank" :disabled="selection.length === 0" @click="onMove">
        移动
      </el-button>
      <el-button :icon="Share" :disabled="selection.length !== 1" @click="onShareSelected">
        分享
      </el-button>
      <el-popconfirm
        title="确定将所选项目放入回收站?"
        confirm-button-text="删除"
        cancel-button-text="取消"
        @confirm="onDelete"
      >
        <template #reference>
          <el-button type="danger" :icon="Delete" :disabled="selection.length === 0">
            删除
          </el-button>
        </template>
      </el-popconfirm>
      <span v-if="selection.length > 0" class="selection-hint">
        已选 {{ selection.length }} 项
      </span>
    </div>

    <el-table
      v-loading="isFetching"
      :data="items"
      row-key="id"
      empty-text="这里空空如也"
      @selection-change="onSelectionChange"
      @row-dblclick="onRowDblclick"
      @row-contextmenu="(row: any, col: any, e: MouseEvent) => onRowContextmenu(asChild(row), col, e)"
    >
      <el-table-column type="selection" width="44" />
      <el-table-column label="名称" min-width="320">
        <template #default="{ row }">
          <span class="name-cell" :class="{ folder: asChild(row).kind === 'FOLDER' }">
            <img
              v-if="showThumb(asChild(row))"
              class="name-thumb"
              :src="asChild(row).preview.thumbUrl!"
              alt=""
              loading="lazy"
              @error="onThumbError(asChild(row).id)"
            />
            <el-icon v-else class="name-icon">
              <component :is="fileIcon(asChild(row))" />
            </el-icon>
            {{ asChild(row).name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ asChild(row).kind === 'FOLDER' ? '—' : formatBytes(asChild(row).size) }}
        </template>
      </el-table-column>
      <el-table-column label="修改时间" width="180">
        <template #default="{ row }">
          {{ formatTime(asChild(row).updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :icon="Download"
            :loading="downloadingId === asChild(row).id"
            :title="asChild(row).kind === 'FOLDER' ? '打包下载' : '下载'"
            @click.stop="onDownload(asChild(row))"
          />
        </template>
      </el-table-column>
    </el-table>

    <MoveDialog
      v-model="moveDialogVisible"
      :exclude-ids="selectedIds"
      @confirm="onMoveConfirm"
    />

    <!-- 隐藏文件选择器(多选) -->
    <input ref="fileInput" type="file" multiple class="hidden-input" @change="onFilesChosen" />

    <!-- 整页拖拽遮罩 -->
    <div v-if="dragging" class="drop-overlay">
      <div class="drop-hint">
        <el-icon :size="40"><Upload /></el-icon>
        <p>松开鼠标,上传到当前文件夹</p>
      </div>
    </div>

    <!-- 右键菜单:下载/打包下载 + 分享 -->
    <ul
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
    >
      <li class="context-menu-item" @click="onContextDownload">
        <el-icon><Download /></el-icon>
        {{ contextMenu.row?.kind === 'FOLDER' ? '打包下载' : '下载' }}
      </li>
      <li class="context-menu-item" @click="onContextShare">
        <el-icon><Share /></el-icon>
        分享
      </li>
    </ul>

    <ShareDialog v-model="shareDialogVisible" :node="shareTarget" />
  </div>
</template>

<style scoped>
.breadcrumb {
  margin-bottom: 16px;
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
.name-cell.folder {
  cursor: pointer;
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
.hidden-input {
  display: none;
}
.drop-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--el-color-primary) 12%, transparent);
  border: 2px dashed var(--el-color-primary);
  pointer-events: none;
}
.drop-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 28px 40px;
  border-radius: 12px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow);
  color: var(--el-color-primary);
  font-size: 15px;
}
.context-menu {
  position: fixed;
  z-index: 3001;
  min-width: 120px;
  margin: 0;
  padding: 4px;
  list-style: none;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
}
.context-menu-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  color: var(--el-text-color-primary);
}
.context-menu-item:hover {
  background: var(--el-fill-color-light);
}
</style>
