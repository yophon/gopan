<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CopyDocument,
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
  CopyNodesDocument,
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

/** 文件夹体积:异步统计,statsStale 时数字可能滞后,展示上弱化并加提示 */
function folderSizeText(row: ChildItem): string {
  if (row.subtreeBytes == null) return '—'
  const text = formatBytes(row.subtreeBytes)
  return row.statsStale ? `约 ${text}` : text
}

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

const copyMutation = useMutation({
  mutationFn: (vars: { ids: string[]; targetParentId: string | null }) =>
    request(CopyNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('复制成功')
    selection.value = []
    invalidate()
    void queryClient.invalidateQueries({ queryKey: ['me'] }) // 复制占配额
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
const copyDialogVisible = ref(false)

function onMove() {
  if (selection.value.length === 0) return
  moveDialogVisible.value = true
}

function onMoveConfirm(targetParentId: string | null) {
  moveMutation.mutate({ ids: selectedIds.value, targetParentId })
}

function onCopy() {
  if (selection.value.length === 0) return
  copyDialogVisible.value = true
}

function onCopyConfirm(targetParentId: string | null) {
  copyMutation.mutate({ ids: selectedIds.value, targetParentId })
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
const MAX_DROP_FILES = 1000

/** readEntries 每批最多返回 100 项,必须循环读空 */
function readAllEntries(dir: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = dir.createReader()
  return new Promise((resolve, reject) => {
    const all: FileSystemEntry[] = []
    const step = () =>
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all)
          return
        }
        all.push(...batch)
        step()
      }, reject)
    step()
  })
}

function entryFile(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

/** 深度优先展开 entry 树 → (相对目录段, 文件) 列表 */
async function walkEntry(
  entry: FileSystemEntry,
  dirs: string[],
  out: { dirs: string[]; file: File }[],
): Promise<void> {
  if (out.length >= MAX_DROP_FILES) return
  if (entry.isFile) {
    out.push({ dirs, file: await entryFile(entry as FileSystemFileEntry) })
    return
  }
  if (entry.isDirectory) {
    const children = await readAllEntries(entry as FileSystemDirectoryEntry)
    const sub = [...dirs, entry.name]
    if (children.length === 0) {
      // 空目录也创建,保持结构
      await ensureDirChain(sub)
      return
    }
    for (const c of children) {
      await walkEntry(c, sub, out)
    }
  }
}

/** 确保相对目录链存在于当前文件夹下,返回最深层目录 id;同名目录复用 */
const dirCache = new Map<string, string | null>()

async function ensureDirChain(dirs: string[]): Promise<string | null> {
  let parent = folderId.value
  let keyPrefix = parent ?? 'root'
  for (const name of dirs) {
    keyPrefix += '/' + name
    const cached = dirCache.get(keyPrefix)
    if (cached !== undefined) {
      parent = cached
      continue
    }
    const res = await request(ChildrenDocument, { parentId: parent })
    const hit = res.children.items.find((i) => i.kind === 'FOLDER' && i.name === name)
    let id: string
    if (hit) {
      id = hit.id
    } else {
      const created = await request(CreateFolderDocument, { parentId: parent, name })
      id = created.createFolder.id
    }
    dirCache.set(keyPrefix, id)
    parent = id
  }
  return parent
}

useEventListener(window, 'drop', async (e: DragEvent) => {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth.value = 0
  const entries: FileSystemEntry[] = []
  const plainFiles: File[] = []
  for (const item of Array.from(e.dataTransfer?.items ?? [])) {
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.()
    if (entry) {
      entries.push(entry)
    } else {
      const f = item.getAsFile()
      if (f) plainFiles.push(f)
    }
  }
  if (plainFiles.length > 0) enqueueFiles(plainFiles, folderId.value)
  if (entries.length === 0) return

  try {
    dirCache.clear()
    const collected: { dirs: string[]; file: File }[] = []
    for (const entry of entries) {
      await walkEntry(entry, [], collected)
    }
    if (collected.length >= MAX_DROP_FILES) {
      ElMessage.warning(`单次最多上传 ${MAX_DROP_FILES} 个文件,超出部分已忽略`)
    }
    // 按目录分组:先建目录链,再把文件按归属入队
    const groups = new Map<string, { dirs: string[]; files: File[] }>()
    for (const c of collected) {
      const key = c.dirs.join('/')
      const g = groups.get(key) ?? { dirs: c.dirs, files: [] }
      g.files.push(c.file)
      groups.set(key, g)
    }
    for (const g of groups.values()) {
      const target = g.dirs.length === 0 ? folderId.value : await ensureDirChain(g.dirs)
      enqueueFiles(g.files, target)
    }
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '读取拖入的文件夹失败'))
  }
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
      <el-button :icon="CopyDocument" :disabled="selection.length === 0" @click="onCopy">
        复制
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
          <span
            v-if="asChild(row).kind === 'FOLDER'"
            class="folder-size"
            :class="{ stale: asChild(row).statsStale }"
            :title="asChild(row).statsStale ? '统计中,数字可能滞后' : `${asChild(row).subtreeCount ?? 0} 个文件`"
          >
            {{ folderSizeText(asChild(row)) }}
          </span>
          <template v-else>{{ formatBytes(asChild(row).size) }}</template>
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

    <MoveDialog
      v-model="copyDialogVisible"
      mode="copy"
      :exclude-ids="selectedIds"
      @confirm="onCopyConfirm"
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
.folder-size {
  color: var(--el-text-color-secondary);
}
.folder-size.stale {
  opacity: 0.6;
}
</style>
