<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, reactive, ref, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Check,
  Close,
  CopyDocument,
  Delete,
  Download,
  EditPen,
  Folder,
  FolderAdd,
  FolderOpened,
  Grid,
  InfoFilled,
  Link,
  List,
  Paperclip,
  Rank,
  Refresh,
  Share,
  Tickets,
  Upload,
  View,
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
  RenameNodeDocument,
  RestoreNodesDocument,
} from '@/api/gen/graphql'
import type { ChildrenQuery } from '@/api/gen/graphql'
import { enqueueFiles } from '@/uploader/manager'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import { useUiStore } from '@/stores/ui'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import { useFetchEnabled } from '@/composables/jobs'
import { useExtract } from '@/composables/extract'
import { isArchiveName } from '@/utils/archive'
import { copyDirectLinks, useLinksEnabled } from '@/composables/links'
import { linkFormatLabels } from '@/utils/links'
import type { LinkFormat } from '@/utils/links'
import { withoutQuery } from '@/utils/notifications'
import { actionToast } from '@/utils/toast'
import { useNodeDownload } from '@/composables/nodeDownload'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import { baseNameRange, DRAG_MIME, nextSort, parseSortPref, stepSelection, type SortKey, type SortPref } from '@/components/nodes/browser'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'

type ChildItem = ChildrenQuery['children']['items'][number]

// 这些弹窗第一次用到时才加载(连同里面的表单、日期选择这些组件),之后一直挂着
const MoveDialog = defineAsyncComponent(() => import('@/components/MoveDialog.vue'))
const ShareDialog = defineAsyncComponent(() => import('@/components/ShareDialog.vue'))
const FileRequestDialog = defineAsyncComponent(() => import('@/components/FileRequestDialog.vue'))
const FetchDialog = defineAsyncComponent(() => import('@/components/FetchDialog.vue'))
const NodeDetailDrawer = defineAsyncComponent(() => import('@/components/NodeDetailDrawer.vue'))

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const ui = useUiStore()
const { isMobile } = useBreakpoints()

/** 当前文件夹 id;null = 根目录 */
const folderId = computed<string | null>(() => {
  const raw = route.params.folderId
  return typeof raw === 'string' && raw !== '' ? raw : null
})

// ---------- 排序(记住上次的选择) ----------

const SORT_KEY = 'gopan_drive_sort'

function loadSort(): SortPref {
  try {
    return parseSortPref(localStorage.getItem(SORT_KEY))
  } catch {
    return parseSortPref(null)
  }
}

const sort = ref<SortPref>(loadSort())

function onSort(key: SortKey) {
  sort.value = nextSort(sort.value, key)
  try {
    localStorage.setItem(SORT_KEY, JSON.stringify(sort.value))
  } catch {
    // 记不住就算了
  }
}

// ---------- 查询:一页 200 条,第一页先出来,剩下的在后台接着拉完 ----------

const childrenQuery = useInfiniteQuery({
  queryKey: ['children', folderId, computed(() => sort.value.key), computed(() => sort.value.desc)],
  queryFn: ({ pageParam }) =>
    request(ChildrenDocument, { parentId: folderId.value, cursor: pageParam, order: sort.value.key, desc: sort.value.desc }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.children.nextCursor ?? undefined,
})

watch(
  () => [childrenQuery.hasNextPage.value, childrenQuery.isFetching.value] as const,
  ([more, fetching]) => {
    if (more && !fetching) void childrenQuery.fetchNextPage()
  },
  { immediate: true },
)

const items = computed<ChildItem[]>(() => childrenQuery.data.value?.pages.flatMap((p) => p.children.items) ?? [])
const initialLoading = computed(() => childrenQuery.isPending.value)

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

const folderName = computed(() => crumbs.value?.at(-1)?.name ?? '我的文件')

// ---------- 选中 ----------
// 桌面:单击 / Ctrl / Shift(NodeBrowser 里算好);手机:多选模式下轻点勾选。两边共用这一份 id 列表。

const selectedIds = ref<string[]>([])
const selection = computed<ChildItem[]>(() => {
  const set = new Set(selectedIds.value)
  return items.value.filter((n) => set.has(n.id))
})
const single = computed<ChildItem | null>(() => (selection.value.length === 1 ? selection.value[0]! : null))
const showSelectionBar = computed(() => isMobile.value && ui.selectionMode)

// 换文件夹、换排序:清掉选中
watch(folderId, () => clearSelection())

// 列表刷新后,已经不在了的选中项(被别处删了、移走了)拿掉
watch(items, (list) => {
  if (!selectedIds.value.length) return
  const alive = new Set(list.map((n) => n.id))
  const kept = selectedIds.value.filter((id) => alive.has(id))
  if (kept.length !== selectedIds.value.length) selectedIds.value = kept
})

function toggleSelect(node: NodeListItem) {
  selectedIds.value = selectedIds.value.includes(node.id)
    ? selectedIds.value.filter((id) => id !== node.id)
    : [...selectedIds.value, node.id]
}

function clearSelection() {
  selectedIds.value = []
  ui.selectionMode = false
}

// ---------- 变更 ----------

function invalidate() {
  // 移动会同时影响旧父与新父,统一按前缀失效最稳妥
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  void queryClient.invalidateQueries({ queryKey: ['breadcrumb'] })
}

const createFolderMutation = useMutation({
  mutationFn: (vars: { parentId: string | null; name: string }) => request(CreateFolderDocument, vars),
  onSuccess: (res) => {
    invalidate()
    selectedIds.value = [res.createFolder.id]
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const renameMutation = useMutation({
  mutationFn: (vars: { id: string; name: string }) => request(RenameNodeDocument, vars),
  onSuccess: () => invalidate(),
  onError: (err) => ElMessage.error(errorText(err)),
})

const moveMutation = useMutation({
  mutationFn: (vars: { ids: string[]; targetParentId: string | null; targetName?: string }) =>
    request(MoveNodesDocument, { ids: vars.ids, targetParentId: vars.targetParentId }),
  onSuccess: (_res, vars) => {
    ElMessage.success(vars.targetName ? `已移到「${vars.targetName}」` : '移动成功')
    clearSelection()
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const copyMutation = useMutation({
  mutationFn: (vars: { ids: string[]; targetParentId: string | null }) => request(CopyNodesDocument, vars),
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
  onSuccess: (_res, vars) => {
    clearSelection()
    invalidate()
    void queryClient.invalidateQueries({ queryKey: ['trash'] })
    // 进的是回收站,能撤:不先弹确认,做完给一个撤销
    actionToast(vars.ids.length > 1 ? `${vars.ids.length} 项已放入回收站` : '已放入回收站', '撤销', () => {
      void request(RestoreNodesDocument, { ids: vars.ids })
        .then(() => {
          invalidate()
          void queryClient.invalidateQueries({ queryKey: ['trash'] })
          selectedIds.value = vars.ids
        })
        .catch((err) => ElMessage.error(errorText(err, '撤销失败,可以去回收站还原')))
    })
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

// ---------- 操作 ----------

/** 打开输入框后只选中主名(不带扩展名),改名时直接敲新名字就行 */
function selectInPrompt(range: [number, number]) {
  void nextTick(() => {
    window.setTimeout(() => {
      const input = document.querySelector<HTMLInputElement>('.el-message-box__input input')
      input?.focus()
      input?.setSelectionRange(range[0], range[1])
    }, 60)
  })
}

async function onCreateFolder() {
  try {
    selectInPrompt([0, '新建文件夹'.length])
    const { value } = await ElMessageBox.prompt('', '新建文件夹', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputValue: '新建文件夹',
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
    selectInPrompt(baseNameRange(node.name, node.kind === 'FOLDER'))
    const { value } = await ElMessageBox.prompt('', '重命名', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: node.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const name = value.trim()
    if (name !== node.name) renameMutation.mutate({ id: node.id, name })
  } catch {
    // 取消
  }
}

const moveDialogVisible = ref(false)
const copyDialogVisible = ref(false)
/** 移动 / 复制对话框处理的那几项 */
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

/** 拖到文件夹 / 面包屑上:直接移动 */
function moveByDrag(ids: string[], targetId: string | null, targetName: string) {
  const moving = ids.filter((id) => id !== targetId)
  if (!moving.length) return
  // 拖回原来所在的文件夹:什么都不做
  if (targetId === folderId.value) return
  moveMutation.mutate({ ids: moving, targetParentId: targetId, targetName })
}

function onDelete(ids: string[]) {
  if (ids.length === 0) return
  deleteMutation.mutate({ ids })
}

// ---------- 上传:按钮(文件 / 文件夹)、整页拖入、拖到某个文件夹上 ----------

const fileInput = ref<HTMLInputElement | null>(null)
const dirInput = ref<HTMLInputElement | null>(null)

function onPickFiles() {
  fileInput.value?.click()
}

function onPickDir() {
  dirInput.value?.click()
}

function onFilesChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // 允许重复选择同一文件
  if (files.length > 0) enqueueFiles(files, folderId.value)
}

/** 选了一个文件夹:按 webkitRelativePath 还原目录结构 */
function onDirChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  const collected = files.map((file) => {
    const parts = (file.webkitRelativePath || file.name).split('/')
    return { dirs: parts.slice(0, -1), file }
  })
  void uploadCollected(collected, folderId.value)
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

type Collected = { dirs: string[]; file: File }

/** 深度优先展开 entry 树 → (相对目录段, 文件) 列表;空目录记进 emptyDirs */
async function walkEntry(entry: FileSystemEntry, dirs: string[], out: Collected[], emptyDirs: string[][]): Promise<void> {
  if (out.length >= MAX_DROP_FILES) return
  if (entry.isFile) {
    out.push({ dirs, file: await entryFile(entry as FileSystemFileEntry) })
    return
  }
  if (entry.isDirectory) {
    const children = await readAllEntries(entry as FileSystemDirectoryEntry)
    const sub = [...dirs, entry.name]
    if (children.length === 0) {
      emptyDirs.push(sub)
      return
    }
    for (const c of children) await walkEntry(c, sub, out, emptyDirs)
  }
}

/** 一个文件夹下的全部子项(翻完所有页):建目录链时找同名文件夹不能只看第一页 */
async function allChildren(parentId: string | null) {
  const out: ChildItem[] = []
  let cursor: string | null = null
  do {
    const res: ChildrenQuery = await request(ChildrenDocument, { parentId, cursor })
    out.push(...res.children.items)
    cursor = res.children.nextCursor ?? null
  } while (cursor)
  return out
}

/** 确保相对目录链存在于 base 下,返回最深层目录 id;同名目录复用 */
async function ensureDirChain(base: string | null, dirs: string[], cache: Map<string, string | null>): Promise<string | null> {
  let parent = base
  let keyPrefix = base ?? 'root'
  for (const name of dirs) {
    keyPrefix += '/' + name
    const cached = cache.get(keyPrefix)
    if (cached !== undefined) {
      parent = cached
      continue
    }
    const hit = (await allChildren(parent)).find((i) => i.kind === 'FOLDER' && i.name === name)
    const id = hit ? hit.id : (await request(CreateFolderDocument, { parentId: parent, name })).createFolder.id
    cache.set(keyPrefix, id)
    parent = id
  }
  return parent
}

/** 按目录分组:先建目录链,再把文件按归属入队 */
async function uploadCollected(collected: Collected[], base: string | null, emptyDirs: string[][] = []) {
  try {
    const cache = new Map<string, string | null>()
    for (const d of emptyDirs) await ensureDirChain(base, d, cache)
    const groups = new Map<string, { dirs: string[]; files: File[] }>()
    for (const c of collected) {
      const key = c.dirs.join('/')
      const g = groups.get(key) ?? { dirs: c.dirs, files: [] }
      g.files.push(c.file)
      groups.set(key, g)
    }
    for (const g of groups.values()) {
      const target = g.dirs.length === 0 ? base : await ensureDirChain(base, g.dirs, cache)
      enqueueFiles(g.files, target)
    }
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '读取文件夹失败'))
  }
}

/** 电脑里拖进来的东西(文件、文件夹混着)传到 target */
async function uploadDataTransfer(dt: DataTransfer, target: string | null) {
  const entries: FileSystemEntry[] = []
  const plainFiles: File[] = []
  for (const item of Array.from(dt.items ?? [])) {
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.()
    if (entry) entries.push(entry)
    else {
      const f = item.getAsFile()
      if (f) plainFiles.push(f)
    }
  }
  if (plainFiles.length > 0) enqueueFiles(plainFiles, target)
  if (entries.length === 0) return
  const collected: Collected[] = []
  const emptyDirs: string[][] = []
  try {
    for (const entry of entries) await walkEntry(entry, [], collected, emptyDirs)
  } catch (err) {
    ElMessage.error(errorText(err, '读取拖入的文件夹失败'))
    return
  }
  if (collected.length >= MAX_DROP_FILES) ElMessage.warning(`单次最多上传 ${MAX_DROP_FILES} 个文件,超出部分已忽略`)
  await uploadCollected(collected, target, emptyDirs)
}

useEventListener(window, 'drop', (e: DragEvent) => {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth.value = 0
  if (e.dataTransfer) void uploadDataTransfer(e.dataTransfer, folderId.value)
})

function onDropFilesOnFolder(dt: DataTransfer, targetId: string) {
  dragDepth.value = 0
  const name = items.value.find((n) => n.id === targetId)?.name
  if (name) ElMessage.info(`传到「${name}」`)
  void uploadDataTransfer(dt, targetId)
}

// ---------- 下载:单个文件预签名直链,文件夹或多项打 zip ----------

const { download: onDownload, downloadingId } = useNodeDownload()

// ---------- 分享 / 收集 / 离线下载 / 解压 / 直链 / 详情 ----------

const shareDialogVisible = ref(false)
const shareTarget = ref<{ id: string; name: string } | null>(null)

function openShare(row: { id: string; name: string }) {
  shareTarget.value = { id: row.id, name: row.name }
  shareDialogVisible.value = true
}

const collectDialogVisible = ref(false)
const collectTarget = ref<{ id: string; name: string } | null>(null)

function openCollect(row: { id: string; name: string }) {
  collectTarget.value = { id: row.id, name: row.name }
  collectDialogVisible.value = true
}

const fetchEnabled = useFetchEnabled()
const fetchDialogVisible = ref(false)

const { extract } = useExtract()
const extractPickVisible = ref(false)
const extractTarget = ref<NodeListItem | null>(null)

function isArchive(row: NodeListItem | null | undefined): boolean {
  return !!row && row.kind === 'FILE' && (row.preview?.kind === 'ARCHIVE' || isArchiveName(row.name))
}

function extractHere(row: NodeListItem) {
  void extract({ nodeId: row.id, parentId: folderId.value })
}

function pickExtract(row: NodeListItem) {
  extractTarget.value = row
  extractPickVisible.value = true
}

function onExtractConfirm(parentId: string | null) {
  const row = extractTarget.value
  if (row) void extract({ nodeId: row.id, parentId })
}

const linksEnabled = useLinksEnabled()
const linkSheet = ref<{ visible: boolean; nodes: NodeListItem[] }>({ visible: false, nodes: [] })
const linkFormatItems: SheetItem[] = (['url', 'markdown', 'html'] as LinkFormat[]).map((f) => ({
  key: f,
  label: linkFormatLabels[f],
}))

function fileTargets(nodes: NodeListItem[]): NodeListItem[] {
  return nodes.filter((n) => n.kind === 'FILE')
}

function openLinkSheet(nodes: NodeListItem[]) {
  const files = fileTargets(nodes)
  if (files.length === 0) {
    ElMessage.warning('直链只能给文件生成')
    return
  }
  linkSheet.value = { visible: true, nodes: files }
}

function onLinkSheetSelect(key: string) {
  void copyDirectLinks(linkSheet.value.nodes, key as LinkFormat)
}

const detail = ref<{ visible: boolean; id: string | null }>({ visible: false, id: null })

function openDetail(node: { id: string }) {
  detail.value = { visible: true, id: node.id }
}

const opened = reactive({ move: false, copy: false, extract: false, share: false, collect: false, fetch: false, detail: false })
watchEffect(() => {
  if (moveDialogVisible.value) opened.move = true
  if (copyDialogVisible.value) opened.copy = true
  if (extractPickVisible.value) opened.extract = true
  if (shareDialogVisible.value) opened.share = true
  if (collectDialogVisible.value) opened.collect = true
  if (fetchDialogVisible.value) opened.fetch = true
  if (detail.value.visible) opened.detail = true
})

// ---------- 打开 ----------

function onOpen(row: NodeListItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
    return
  }
  const files = items.value.filter((n) => n.kind === 'FILE')
  const idx = files.findIndex((n) => n.id === row.id)
  if (idx >= 0) openPreview(files.map((n) => n.id), idx)
}

function goUp() {
  const chain = crumbs.value ?? []
  if (!chain.length) return
  const parent = chain.length > 1 ? chain[chain.length - 2]! : null
  void router.push(parent ? `/drive/${parent.id}` : '/drive')
}

// ---------- ?open=<nodeId>:通知与推送链接直达(doc/22 三.4) ----------
// 文件夹 → 进去;文件 → 在所在文件夹的列表里打开预览。做完从地址里去掉,刷新不再弹。

async function openFromQuery(id: string) {
  try {
    const res = await request(NodeDocument, { id })
    const n = res.node
    if (n.kind === 'FOLDER') {
      await router.replace(`/drive/${n.id}`)
      return
    }
    await router.replace({ path: route.path, query: withoutQuery(route.query, ['open']) })
    const files = (await allChildren(folderId.value)).filter((i) => i.kind === 'FILE')
    const idx = files.findIndex((f) => f.id === n.id)
    if (idx >= 0) openPreview(files.map((f) => f.id), idx)
    else openPreview([n.id], 0)
  } catch (err) {
    ElMessage.error(errorText(err, '文件不存在或已被删除'))
    void router.replace({ path: route.path, query: withoutQuery(route.query, ['open']) })
  }
}

watch(
  () => route.query.open,
  (id) => {
    if (typeof id === 'string' && id) void openFromQuery(id)
  },
  { immediate: true },
)

// ---------- 右键菜单(桌面):条目、空白处 ----------

const menu = ref<{ visible: boolean; x: number; y: number; node: NodeListItem | null }>({ visible: false, x: 0, y: 0, node: null })

function onMenu(node: NodeListItem | null, x: number, y: number) {
  menu.value = { visible: true, x, y, node }
}

const menuItems = computed<MenuEntry[]>(() => {
  if (!menu.value.node) {
    return [
      { key: 'upload', label: '上传文件', icon: Upload },
      { key: 'uploadDir', label: '上传文件夹', icon: FolderOpened },
      { key: 'mkdir', label: '新建文件夹', icon: FolderAdd },
      ...(fetchEnabled.value ? [{ key: 'fetch', label: '离线下载', icon: Link }] : []),
      { key: 'd1', divider: true },
      ...(items.value.length ? [{ key: 'selectAll', label: '全选', icon: Check, hint: 'Ctrl+A' }] : []),
      { key: 'refresh', label: '刷新', icon: Refresh },
    ]
  }
  const sel = selection.value
  const one = single.value
  const files = fileTargets(sel)
  const entries: MenuEntry[] = []
  if (one) entries.push({ key: 'open', label: one.kind === 'FOLDER' ? '打开' : '预览', icon: one.kind === 'FOLDER' ? FolderOpened : View, hint: 'Enter' })
  entries.push({ key: 'download', label: one && one.kind === 'FILE' ? '下载' : '打包下载', icon: Download })
  if (one) entries.push({ key: 'share', label: '分享', icon: Share })
  if (linksEnabled.value && files.length) {
    entries.push({
      key: 'link',
      label: files.length > 1 ? `复制直链(${files.length} 个)` : '复制直链',
      icon: Paperclip,
      children: (['url', 'markdown', 'html'] as LinkFormat[]).map((f) => ({ key: `link:${f}`, label: linkFormatLabels[f] })),
    })
  }
  if (one?.kind === 'FOLDER') entries.push({ key: 'collect', label: '收集文件', icon: Tickets })
  if (one && isArchive(one)) {
    entries.push({ key: 'extract', label: '解压到这里', icon: FolderOpened }, { key: 'extractTo', label: '解压到…', icon: FolderOpened })
  }
  entries.push({ key: 'd1', divider: true })
  if (one) entries.push({ key: 'rename', label: '重命名', icon: EditPen, hint: 'F2' })
  entries.push({ key: 'move', label: '移动到…', icon: Rank }, { key: 'copy', label: '复制到…', icon: CopyDocument })
  if (one) entries.push({ key: 'detail', label: '详情与历史', icon: InfoFilled })
  entries.push({ key: 'd2', divider: true }, { key: 'delete', label: sel.length > 1 ? `删除 ${sel.length} 项` : '删除', icon: Delete, danger: true, hint: 'Del' })
  return entries
})

function onMenuSelect(key: string) {
  if (key.startsWith('link:')) {
    void copyDirectLinks(fileTargets(selection.value), key.slice(5) as LinkFormat)
    return
  }
  const sel = selection.value
  const one = single.value
  switch (key) {
    case 'upload':
      return onPickFiles()
    case 'uploadDir':
      return onPickDir()
    case 'mkdir':
      return void onCreateFolder()
    case 'fetch':
      fetchDialogVisible.value = true
      return
    case 'selectAll':
      selectedIds.value = items.value.map((n) => n.id)
      return
    case 'refresh':
      return invalidate()
    case 'open':
      if (one) onOpen(one)
      return
    case 'download':
      return void onDownload(sel)
    case 'share':
      if (one) openShare(one)
      return
    case 'collect':
      if (one) openCollect(one)
      return
    case 'extract':
      if (one) extractHere(one)
      return
    case 'extractTo':
      if (one) pickExtract(one)
      return
    case 'rename':
      if (one) void onRename(one)
      return
    case 'move':
      return openMove(selectedIds.value)
    case 'copy':
      return openCopy(selectedIds.value)
    case 'detail':
      if (one) openDetail(one)
      return
    case 'delete':
      return onDelete(selectedIds.value)
  }
}

// ---------- 键盘(桌面):Del 删除、F2 重命名、Enter 打开、Ctrl+A 全选、Esc 取消、↑↓ 移动、Backspace 上一级 ----------

function typingTarget(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null
  if (!t) return false
  return t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName)
}

/** 有弹窗、抽屉、预览开着时不接管键盘 */
function overlayOpen(): boolean {
  return !!document.querySelector('.el-overlay:not([style*="display: none"]), .preview-overlay, .el-message-box')
}

useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (isMobile.value || typingTarget(e) || overlayOpen() || menu.value.visible) return
  const mod = e.ctrlKey || e.metaKey
  if (mod && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    selectedIds.value = items.value.map((n) => n.id)
  } else if (e.key === 'Escape' && selectedIds.value.length) {
    clearSelection()
  } else if (e.key === 'Delete' && selectedIds.value.length) {
    e.preventDefault()
    onDelete(selectedIds.value)
  } else if (e.key === 'F2' && single.value) {
    e.preventDefault()
    void onRename(single.value)
  } else if (e.key === 'Enter' && single.value) {
    e.preventDefault()
    onOpen(single.value)
  } else if (e.key === 'Backspace' && !mod) {
    e.preventDefault()
    goUp()
  } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    const id = stepSelection(items.value.map((n) => n.id), selectedIds.value, e.key === 'ArrowDown' ? 1 : -1)
    if (!id) return
    e.preventDefault()
    selectedIds.value = [id]
    void nextTick(() => document.querySelector(`[data-id="${id}"]`)?.scrollIntoView({ block: 'nearest' }))
  }
})

// ---------- 面包屑也能当拖放目标:把东西拖回上级 ----------

const crumbDrop = ref<string | null>(null)

function onCrumbDragOver(id: string | null, e: DragEvent) {
  if (!Array.from(e.dataTransfer?.types ?? []).includes(DRAG_MIME)) return
  if (id === folderId.value) return
  e.preventDefault()
  crumbDrop.value = id ?? 'root'
}

function onCrumbDrop(id: string | null, name: string, e: DragEvent) {
  crumbDrop.value = null
  if (!e.dataTransfer || !Array.from(e.dataTransfer.types).includes(DRAG_MIME)) return
  e.preventDefault()
  try {
    moveByDrag(JSON.parse(e.dataTransfer.getData(DRAG_MIME)) as string[], id, name)
  } catch {
    // 不是我们拖的东西
  }
}

// ---------- 触屏操作菜单 ----------

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({ visible: false, node: null })

function openSheet(node: NodeListItem) {
  sheet.value = { visible: true, node }
}

const sheetItems = computed<SheetItem[]>(() => {
  const node = sheet.value.node
  if (!node) return []
  return [
    { key: 'open', label: node.kind === 'FOLDER' ? '打开' : '预览' },
    { key: 'download', label: node.kind === 'FOLDER' ? '打包下载' : '下载', icon: Download },
    { key: 'share', label: '分享', icon: Share },
    ...(node.kind === 'FILE' && linksEnabled.value ? [{ key: 'link', label: '复制直链', icon: Paperclip }] : []),
    ...(node.kind === 'FOLDER' ? [{ key: 'collect', label: '收集文件', icon: Tickets }] : []),
    ...(isArchive(node)
      ? [
          { key: 'extract', label: '解压到这里', icon: FolderOpened },
          { key: 'extractTo', label: '解压到…', icon: FolderOpened },
        ]
      : []),
    { key: 'rename', label: '重命名', icon: EditPen },
    { key: 'move', label: '移动', icon: Rank },
    { key: 'copy', label: '复制', icon: CopyDocument },
    { key: 'select', label: '选择', icon: Check },
    { key: 'detail', label: '详情与历史', icon: InfoFilled },
    { key: 'delete', label: '删除', icon: Delete, danger: true },
  ]
})

function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  switch (key) {
    case 'open':
      return onOpen(node)
    case 'download':
      return void onDownload([node])
    case 'share':
      return openShare(node)
    case 'link':
      return openLinkSheet([node])
    case 'collect':
      return openCollect(node)
    case 'extract':
      return extractHere(node)
    case 'extractTo':
      return pickExtract(node)
    case 'rename':
      return void onRename(node)
    case 'move':
      return openMove([node.id])
    case 'copy':
      return openCopy([node.id])
    case 'select':
      if (!ui.selectionMode) {
        ui.selectionMode = true
        toggleSelect(node)
      }
      return
    case 'detail':
      return openDetail(node)
    case 'delete':
      return onDelete([node.id])
  }
}
</script>

<template>
  <div class="drive" :class="{ 'has-selection-bar': showSelectionBar }">
    <nav class="crumbs" aria-label="位置">
      <router-link
        to="/drive"
        class="crumb"
        :class="{ current: !folderId, 'drop-target': crumbDrop === 'root' }"
        @dragover="onCrumbDragOver(null, $event)"
        @dragleave="crumbDrop = null"
        @drop="onCrumbDrop(null, '我的文件', $event)"
      >
        我的文件
      </router-link>
      <template v-for="(crumb, i) in crumbs ?? []" :key="crumb.id">
        <span class="crumb-sep">/</span>
        <router-link
          :to="`/drive/${crumb.id}`"
          class="crumb"
          :class="{ current: i === (crumbs?.length ?? 0) - 1, 'drop-target': crumbDrop === crumb.id }"
          @dragover="onCrumbDragOver(crumb.id, $event)"
          @dragleave="crumbDrop = null"
          @drop="onCrumbDrop(crumb.id, crumb.name, $event)"
        >
          {{ crumb.name }}
        </router-link>
      </template>
    </nav>

    <!-- 桌面工具栏:平时是新建类操作;选中了东西就换成对选中项的操作 -->
    <div v-if="!isMobile" class="toolbar">
      <template v-if="selection.length === 0">
        <el-dropdown split-button type="primary" trigger="click" @click="onPickFiles" @command="(c: string) => (c === 'dir' ? onPickDir() : onPickFiles())">
          <el-icon class="el-icon--left"><Upload /></el-icon>上传
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="files" :icon="Upload">上传文件</el-dropdown-item>
              <el-dropdown-item command="dir" :icon="FolderOpened">上传文件夹</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button :icon="FolderAdd" @click="onCreateFolder">新建文件夹</el-button>
        <el-button v-if="fetchEnabled" :icon="Link" @click="fetchDialogVisible = true">离线下载</el-button>
      </template>
      <template v-else>
        <span class="sel-count">
          <el-button link :icon="Close" title="取消选择(Esc)" aria-label="取消选择" @click="clearSelection" />
          已选 {{ selection.length }} 项
        </span>
        <el-button :icon="Download" @click="onDownload(selection)">{{ single && single.kind === 'FILE' ? '下载' : '打包下载' }}</el-button>
        <el-button v-if="single" :icon="Share" @click="openShare(single)">分享</el-button>
        <el-button :icon="Rank" @click="openMove(selectedIds)">移动</el-button>
        <el-button :icon="CopyDocument" @click="openCopy(selectedIds)">复制</el-button>
        <el-button v-if="single" :icon="EditPen" @click="onRename(single)">重命名</el-button>
        <el-button :icon="Delete" class="danger-btn" @click="onDelete(selectedIds)">删除</el-button>
      </template>
      <span class="toolbar-spacer" />
      <el-dropdown trigger="click" @command="(k: SortKey) => onSort(k)">
        <el-button text class="sort-btn">
          {{ { NAME: '按名称', SIZE: '按大小', UPDATED_AT: '按修改时间' }[sort.key] }}
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="NAME">名称{{ sort.key === 'NAME' ? (sort.desc ? ' ↓' : ' ↑') : '' }}</el-dropdown-item>
            <el-dropdown-item command="SIZE">大小{{ sort.key === 'SIZE' ? (sort.desc ? ' ↓' : ' ↑') : '' }}</el-dropdown-item>
            <el-dropdown-item command="UPDATED_AT">修改时间{{ sort.key === 'UPDATED_AT' ? (sort.desc ? ' ↓' : ' ↑') : '' }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button-group class="view-toggle">
        <el-button :type="ui.viewMode === 'list' ? 'primary' : undefined" :icon="List" title="列表" aria-label="列表" @click="ui.viewMode = 'list'" />
        <el-button :type="ui.viewMode === 'grid' ? 'primary' : undefined" :icon="Grid" title="网格" aria-label="网格" @click="ui.viewMode = 'grid'" />
      </el-button-group>
    </div>

    <!-- 手机工具栏:四个入口,重命名/移动等交给长按菜单与多选底栏 -->
    <div v-else class="toolbar mobile">
      <!-- 390px 宽要放下 5 个入口,文字按钮缩成两个字(图标在,意思不变) -->
      <el-button type="primary" :icon="Upload" @click="onPickFiles">上传</el-button>
      <el-button :icon="FolderAdd" title="新建文件夹" @click="onCreateFolder">新建</el-button>
      <el-button v-if="fetchEnabled" :icon="Link" title="离线下载" aria-label="离线下载" @click="fetchDialogVisible = true" />
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

    <NodeBrowser
      v-if="!isMobile"
      v-model:selected-ids="selectedIds"
      :items="items"
      :view="ui.viewMode"
      :loading="initialLoading"
      :sort="sort"
      movable
      @open="onOpen"
      @menu="onMenu"
      @download="(n) => onDownload([n])"
      @share="openShare"
      @sort="onSort"
      @move="(ids, target) => moveByDrag(ids, target, items.find((n) => n.id === target)?.name ?? '')"
      @drop-files="onDropFilesOnFolder"
    >
      <template #empty>
        <el-icon :size="44" class="empty-icon"><Folder /></el-icon>
        <span>{{ folderId ? '这个文件夹是空的' : '还没有文件' }}</span>
        <span class="empty-hint">把文件或文件夹拖进来,或者</span>
        <el-button type="primary" :icon="Upload" @click="onPickFiles">上传文件</el-button>
      </template>
    </NodeBrowser>

    <NodeCardList
      v-else
      :items="items"
      :mode="ui.viewMode"
      :selection-mode="ui.selectionMode"
      :selected-ids="selectedIds"
      :downloading-id="downloadingId"
      :loading="initialLoading"
      @open="onOpen"
      @menu="openSheet"
      @toggle="toggleSelect"
    />

    <!-- 手机多选底栏 -->
    <div v-if="showSelectionBar" class="selection-bar">
      <span class="selection-count">已选 {{ selection.length }}</span>
      <div class="selection-actions">
        <el-button size="small" :icon="EditPen" :disabled="!single" @click="single && onRename(single)">重命名</el-button>
        <el-button size="small" :icon="Rank" :disabled="!selection.length" @click="openMove(selectedIds)">移动</el-button>
        <el-button size="small" :icon="CopyDocument" :disabled="!selection.length" @click="openCopy(selectedIds)">复制</el-button>
        <el-button size="small" :icon="Share" :disabled="!single" @click="single && openShare(single)">分享</el-button>
        <el-button v-if="linksEnabled" size="small" :icon="Paperclip" :disabled="!fileTargets(selection).length" @click="openLinkSheet(selection)">
          直链
        </el-button>
        <el-button size="small" type="danger" :icon="Delete" :disabled="!selection.length" @click="onDelete(selectedIds)">删除</el-button>
        <el-button size="small" @click="clearSelection">取消</el-button>
      </div>
    </div>

    <NodeActionSheet v-model:visible="sheet.visible" :title="sheet.node?.name" :items="sheetItems" @select="onSheetSelect" />

    <NodeActionSheet
      v-model:visible="linkSheet.visible"
      :title="linkSheet.nodes.length > 1 ? `复制 ${linkSheet.nodes.length} 个文件的直链` : '复制直链'"
      :items="linkFormatItems"
      @select="onLinkSheetSelect"
    />

    <ContextMenu v-model:visible="menu.visible" :x="menu.x" :y="menu.y" :items="menuItems" @select="onMenuSelect" />

    <MoveDialog v-if="opened.move" v-model="moveDialogVisible" :exclude-ids="pendingIds" :start-id="folderId" @confirm="onMoveConfirm" />
    <MoveDialog v-if="opened.copy" v-model="copyDialogVisible" mode="copy" :exclude-ids="pendingIds" :start-id="folderId" @confirm="onCopyConfirm" />

    <!-- 隐藏的文件 / 文件夹选择器 -->
    <input ref="fileInput" type="file" multiple class="hidden-input" @change="onFilesChosen" />
    <input ref="dirInput" type="file" webkitdirectory multiple class="hidden-input" @change="onDirChosen" />

    <!-- 整页拖拽遮罩(桌面) -->
    <div v-if="dragging" class="drop-overlay">
      <div class="drop-hint">
        <el-icon :size="40"><Upload /></el-icon>
        <p>松开上传到「{{ folderName }}」<br /><small>拖到某个文件夹上就传进那个文件夹</small></p>
      </div>
    </div>

    <ShareDialog v-if="opened.share" v-model="shareDialogVisible" :node="shareTarget" />
    <FileRequestDialog v-if="opened.collect" v-model="collectDialogVisible" :folder="collectTarget" />
    <FetchDialog v-if="opened.fetch" v-model="fetchDialogVisible" :folder-id="folderId" :folder-name="folderName" />
    <MoveDialog v-if="opened.extract" v-model="extractPickVisible" mode="extract" :exclude-ids="[]" :start-id="folderId" @confirm="onExtractConfirm" />
    <NodeDetailDrawer v-if="opened.detail" v-model="detail.visible" :node-id="detail.id" />
  </div>
</template>

<style scoped>
.drive {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
/* 面包屑当页面标题用:当前目录黑色加粗,上级灰色;上级可以接住拖过来的东西 */
.crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  margin-bottom: 16px;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.4;
}
.crumb {
  padding: 2px 6px;
  margin: 0 -2px;
  border-radius: 8px;
  color: var(--gp-ink-3);
  text-decoration: none;
}
.crumb:hover {
  color: var(--gp-ink);
  background: var(--el-fill-color-light);
}
.crumb.current {
  color: var(--gp-ink);
}
.crumb.current:hover {
  background: none;
}
.crumb.drop-target {
  background: var(--el-color-primary-light-8);
  box-shadow: 0 0 0 2px var(--el-color-primary) inset;
}
.crumb-sep {
  color: var(--el-border-color-darker);
  font-weight: 400;
  margin: 0 2px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  margin-bottom: 10px;
}
.toolbar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.toolbar-spacer {
  flex: 1;
}
/* 列表撑满剩下的高度:下面的空白处也能右键、也能点空白取消选中 */
.drive > .node-browser {
  flex: 1;
}
.sel-count {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 6px;
  font-weight: 600;
}
.danger-btn {
  --el-button-text-color: var(--el-color-danger);
  --el-button-hover-text-color: var(--el-color-danger);
  --el-button-hover-border-color: var(--el-color-danger-light-5);
  --el-button-hover-bg-color: var(--el-color-danger-light-9);
}
.sort-btn {
  color: var(--gp-ink-2);
}
.view-toggle :deep(.el-button) {
  padding: 8px 10px;
}
.empty-icon {
  color: var(--el-border-color-darker);
}
.empty-hint {
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
  align-items: flex-end;
  justify-content: center;
  padding-bottom: 48px;
  pointer-events: none;
}
.drop-hint {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 24px;
  border-radius: 14px;
  background: var(--gp-ink);
  color: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-dark);
  font-size: 15px;
}
.drop-hint p {
  margin: 0;
  line-height: 1.5;
}
.drop-hint small {
  opacity: 0.7;
  font-size: 12px;
}

/* 手机:工具栏可换行,多选时给底部操作栏留出空间 */
@media (max-width: 767px) {
  .crumbs {
    margin-bottom: 14px;
    font-size: 17px;
  }
  .toolbar.mobile {
    flex-wrap: wrap;
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
