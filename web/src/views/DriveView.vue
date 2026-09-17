<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Check,
  CopyDocument,
  Delete,
  Download,
  EditPen,
  FolderAdd,
  Grid,
  List,
  Rank,
  Share,
  Upload,
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
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import MoveDialog from '@/components/MoveDialog.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import NodeTable from '@/components/nodes/NodeTable.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type ChildItem = ChildrenQuery['children']['items'][number]

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const auth = useAuthStore()
const ui = useUiStore()
const { isMobile } = useBreakpoints()

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
const items = computed<ChildItem[]>(() => data.value?.children.items ?? [])

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
// 桌面表格与手机卡片共用这一份选中状态:手机上由 ui.selectionMode 决定
// 轻点是"打开"还是"勾选"。

const selection = ref<NodeListItem[]>([])
const selectedIds = computed(() => selection.value.map((n) => n.id))
const showSelectionBar = computed(() => isMobile.value && ui.selectionMode)

function toggleSelect(node: NodeListItem) {
  const hit = selection.value.find((n) => n.id === node.id)
  if (hit) {
    selection.value = selection.value.filter((n) => n.id !== node.id)
  } else {
    selection.value = [...selection.value, node]
  }
}

function clearSelection() {
  selection.value = []
  ui.selectionMode = false
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
    clearSelection()
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const copyMutation = useMutation({
  mutationFn: (vars: { ids: string[]; targetParentId: string | null }) =>
    request(CopyNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('复制成功')
    clearSelection()
    invalidate()
    void queryClient.invalidateQueries({ queryKey: ['me'] }) // 复制占配额
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const deleteMutation = useMutation({
  mutationFn: (vars: { ids: string[] }) => request(DeleteNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('已放入回收站')
    clearSelection()
    invalidate()
    void queryClient.invalidateQueries({ queryKey: ['trash'] })
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

// ---------- 操作 ----------

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

async function onRename(node: NodeListItem) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: node.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    renameMutation.mutate({ id: node.id, name: value.trim() })
  } catch {
    // 取消
  }
}

const moveDialogVisible = ref(false)
const copyDialogVisible = ref(false)
/** 移动/复制对话框的目标:桌面取多选,手机长按菜单取单个 */
const pendingIds = ref<string[]>([])

function openMove(ids: string[]) {
  if (ids.length === 0) return
  pendingIds.value = [...ids]
  moveDialogVisible.value = true
}

function openCopy(ids: string[]) {
  if (ids.length === 0) return
  pendingIds.value = [...ids]
  copyDialogVisible.value = true
}

function onMoveConfirm(targetParentId: string | null) {
  moveMutation.mutate({ ids: pendingIds.value, targetParentId })
}

function onCopyConfirm(targetParentId: string | null) {
  copyMutation.mutate({ ids: pendingIds.value, targetParentId })
}

async function onDelete(ids: string[]) {
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确定将${ids.length > 1 ? `所选的 ${ids.length} 个` : ''}项目放入回收站?`,
      '删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // 取消
  }
  deleteMutation.mutate({ ids })
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

// 整页拖拽:dragenter/leave 用计数器抵消子元素冒泡(桌面专属,触屏不会触发)
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

async function onDownload(row: NodeListItem) {
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

// ---------- 打开 ----------
// 桌面:双击表格行;手机:轻点卡片。语义一致,只是触发方式不同。

function onOpen(row: NodeListItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
    return
  }
  // 多选状态下不触发预览,避免误操作
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

// ---------- 触屏操作菜单 ----------

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({
  visible: false,
  node: null,
})

function openSheet(node: NodeListItem) {
  sheet.value = { visible: true, node }
}

/** 长按菜单比桌面右键菜单全:桌面漏掉的重命名/移动/复制在这里补齐 */
const sheetItems = computed<SheetItem[]>(() => {
  const node = sheet.value.node
  if (!node) return []
  return [
    { key: 'open', label: node.kind === 'FOLDER' ? '打开' : '预览' },
    { key: 'download', label: node.kind === 'FOLDER' ? '打包下载' : '下载', icon: Download },
    { key: 'share', label: '分享', icon: Share },
    { key: 'rename', label: '重命名', icon: EditPen },
    { key: 'move', label: '移动', icon: Rank },
    { key: 'copy', label: '复制', icon: CopyDocument },
    { key: 'select', label: '选择', icon: Check },
    { key: 'delete', label: '删除', icon: Delete, danger: true },
  ]
})

function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  switch (key) {
    case 'open':
      onOpen(node)
      break
    case 'download':
      void onDownload(node)
      break
    case 'share':
      openShare(node)
      break
    case 'rename':
      void onRename(node)
      break
    case 'move':
      openMove([node.id])
      break
    case 'copy':
      openCopy([node.id])
      break
    case 'select':
      if (!ui.selectionMode) {
        ui.selectionMode = true
        toggleSelect(node)
      }
      break
    case 'delete':
      void onDelete([node.id])
      break
  }
}

// ---------- 右键菜单(桌面) ----------

const contextMenu = ref<{ visible: boolean; x: number; y: number; row: NodeListItem | null }>({
  visible: false,
  x: 0,
  y: 0,
  row: null,
})

function onRowContextmenu(row: NodeListItem, x: number, y: number) {
  contextMenu.value = { visible: true, x, y, row }
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
</script>

<template>
  <div class="drive" :class="{ 'has-selection-bar': showSelectionBar }">
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

    <!-- 桌面工具栏:保持原样 -->
    <div v-if="!isMobile" class="toolbar">
      <el-button type="primary" :icon="Upload" @click="onPickFiles">上传文件</el-button>
      <el-button :icon="FolderAdd" @click="onCreateFolder">新建文件夹</el-button>
      <el-button :icon="EditPen" :disabled="selection.length !== 1" @click="selection[0] && onRename(selection[0])">
        重命名
      </el-button>
      <el-button :icon="Rank" :disabled="selection.length === 0" @click="openMove(selectedIds)">
        移动
      </el-button>
      <el-button :icon="CopyDocument" :disabled="selection.length === 0" @click="openCopy(selectedIds)">
        复制
      </el-button>
      <el-button
        :icon="Share"
        :disabled="selection.length !== 1"
        @click="selection[0] && openShare(selection[0])"
      >
        分享
      </el-button>
      <el-popconfirm
        title="确定将所选项目放入回收站?"
        confirm-button-text="删除"
        cancel-button-text="取消"
        @confirm="onDelete(selectedIds)"
      >
        <template #reference>
          <el-button type="danger" :icon="Delete" :disabled="selection.length === 0">
            删除
          </el-button>
        </template>
      </el-popconfirm>
      <span v-if="selection.length > 0" class="selection-hint">已选 {{ selection.length }} 项</span>
    </div>

    <!-- 手机工具栏:四个入口,重命名/移动等交给长按菜单与多选底栏 -->
    <div v-else class="toolbar mobile">
      <el-button type="primary" :icon="Upload" @click="onPickFiles">上传文件</el-button>
      <el-button :icon="FolderAdd" @click="onCreateFolder">新建文件夹</el-button>
      <el-button
        :icon="ui.viewMode === 'grid' ? List : Grid"
        :title="ui.viewMode === 'grid' ? '切换为列表' : '切换为网格'"
        :aria-label="ui.viewMode === 'grid' ? '切换为列表' : '切换为网格'"
        @click="ui.toggleViewMode()"
      />
      <el-button
        :icon="Check"
        :type="ui.selectionMode ? 'primary' : undefined"
        title="多选"
        aria-label="多选"
        @click="ui.selectionMode = !ui.selectionMode; !ui.selectionMode && clearSelection()"
      />
    </div>

    <NodeTable
      v-if="!isMobile"
      :items="items"
      :loading="isFetching"
      :selected-ids="selectedIds"
      :downloading-id="downloadingId"
      @select="(rows) => (selection = rows)"
      @open="onOpen"
      @menu="onRowContextmenu"
      @download="onDownload"
    />

    <NodeCardList
      v-else
      :items="items"
      :mode="ui.viewMode"
      :selection-mode="ui.selectionMode"
      :selected-ids="selectedIds"
      :downloading-id="downloadingId"
      :loading="isFetching"
      @open="onOpen"
      @menu="openSheet"
      @toggle="toggleSelect"
    />

    <!-- 手机多选底栏 -->
    <div v-if="showSelectionBar" class="selection-bar">
      <span class="selection-count">已选 {{ selection.length }}</span>
      <div class="selection-actions">
        <el-button size="small" :icon="EditPen" :disabled="selection.length !== 1" @click="selection[0] && onRename(selection[0])">
          重命名
        </el-button>
        <el-button size="small" :icon="Rank" :disabled="!selection.length" @click="openMove(selectedIds)">
          移动
        </el-button>
        <el-button size="small" :icon="CopyDocument" :disabled="!selection.length" @click="openCopy(selectedIds)">
          复制
        </el-button>
        <el-button size="small" :icon="Share" :disabled="selection.length !== 1" @click="selection[0] && openShare(selection[0])">
          分享
        </el-button>
        <el-button size="small" type="danger" :icon="Delete" :disabled="!selection.length" @click="onDelete(selectedIds)">
          删除
        </el-button>
        <el-button size="small" @click="clearSelection">取消</el-button>
      </div>
    </div>

    <NodeActionSheet
      v-model:visible="sheet.visible"
      :title="sheet.node?.name"
      :items="sheetItems"
      @select="onSheetSelect"
    />

    <MoveDialog
      v-model="moveDialogVisible"
      :exclude-ids="pendingIds"
      @confirm="onMoveConfirm"
    />

    <MoveDialog
      v-model="copyDialogVisible"
      mode="copy"
      :exclude-ids="pendingIds"
      @confirm="onCopyConfirm"
    />

    <!-- 隐藏文件选择器(多选) -->
    <input ref="fileInput" type="file" multiple class="hidden-input" @change="onFilesChosen" />

    <!-- 整页拖拽遮罩(桌面) -->
    <div v-if="dragging" class="drop-overlay">
      <div class="drop-hint">
        <el-icon :size="40"><Upload /></el-icon>
        <p>松开鼠标,上传到当前文件夹</p>
      </div>
    </div>

    <!-- 右键菜单:下载/打包下载 + 分享(桌面) -->
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

/* 手机:工具栏可换行,多选时给底部操作栏留出空间 */
@media (max-width: 767px) {
  .toolbar.mobile {
    flex-wrap: wrap;
    gap: 8px;
  }
  .toolbar.mobile :deep(.el-button + .el-button) {
    margin-left: 0;
  }
  .drive.has-selection-bar {
    padding-bottom: 64px;
  }
}

.selection-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3100;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px calc(8px + var(--sab));
  border-top: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.selection-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.selection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.selection-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
