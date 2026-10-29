<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, reactive, ref, watch, watchEffect } from 'vue'
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
  Document,
  Download,
  EditPen,
  Folder,
  FolderAdd,
  FolderOpened,
  Grid,
  InfoFilled,
  Link,
  List,
  Monitor,
  VideoPlay,
  Paperclip,
  Rank,
  Refresh,
  Share,
  Star,
  StarFilled,
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
  CreateTextFileDocument,
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
import { downloadLabel, downloadToComputer, openWithLocalApp, playWithLocalPlayer, useNodeDownload } from '@/composables/nodeDownload'
import { canPlayExternal } from '@/desktop/play'
import { nativeDownloads } from '@/desktop/transfers'
import { collectDropped, nativeUploads, topLevelFiles, uploadViaDesktop, type Handoff } from '@/desktop/handoff'
import { useUploadsStore } from '@/stores/uploads'
import { useStars } from '@/composables/quick'
import { NEW_TEXT_NAME } from '@/utils/textEdit'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import { baseNameRange, DRAG_MIME, nextSort, parseSortPref, stepSelection, type SortKey, type SortPref } from '@/components/nodes/browser'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'
import { MAX_DROP_FILES, walkEntry, type Collected } from '@/uploader/dropTree'
import { setAssistantContext } from '@/assistant/state'

type ChildItem = ChildrenQuery['children']['items'][number]

// 这些弹窗第一次用到时才加载(连同里面的表单、日期选择这些组件),之后一直挂着
const MoveDialog = defineAsyncComponent(() => import('@/components/MoveDialog.vue'))
const ShareDialog = defineAsyncComponent(() => import('@/components/ShareDialog.vue'))
const FileRequestDialog = defineAsyncComponent(() => import('@/components/FileRequestDialog.vue'))
const FetchDialog = defineAsyncComponent(() => import('@/components/FetchDialog.vue'))
const NodeDetailDrawer = defineAsyncComponent(() => import('@/components/NodeDetailDrawer.vue'))
const BatchRenameDialog = defineAsyncComponent(() => import('@/components/BatchRenameDialog.vue'))

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

// 告诉助手用户正在看哪儿、选了什么(doc/33 一):「这几个」「这里」指的就是它们
watchEffect(() => {
  setAssistantContext({
    path: `/${(crumbs.value ?? []).map((c) => c.name).join('/')}`,
    folderId: folderId.value,
    selection: selection.value.map((n) => ({ id: n.id, name: n.name, kind: n.kind === 'FOLDER' ? 'folder' : 'file' })),
  })
})
onBeforeUnmount(() => setAssistantContext(null))

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

/** 批量重命名(doc/27):多选时用;打开时记下选中项(按列表顺序,序号照这个编) */
const batchRenameVisible = ref(false)
const batchRenameItems = ref<{ id: string; name: string; isFolder: boolean }[]>([])

function openBatchRename() {
  if (selection.value.length < 2) return
  batchRenameItems.value = selection.value.map((n) => ({ id: n.id, name: n.name, isFolder: n.kind === 'FOLDER' }))
  batchRenameVisible.value = true
}

function onBatchRenamed() {
  clearSelection()
  invalidate()
}

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

/** 新建文本文件(doc/25):起好名字就建出来,直接打开编辑 */
async function onCreateTextFile() {
  try {
    selectInPrompt(baseNameRange(NEW_TEXT_NAME, false))
    const { value } = await ElMessageBox.prompt('', '新建文本文件', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputValue: NEW_TEXT_NAME,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const res = await request(CreateTextFileDocument, { parentId: folderId.value, name: value.trim() })
    invalidate()
    selectedIds.value = [res.createTextFile.id]
    openPreview([res.createTextFile.id], 0, {}, { edit: true })
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err, '创建失败'))
  }
}

const newSheetVisible = ref(false)
const newSheetItems: SheetItem[] = [
  { key: 'folder', label: '文件夹', icon: FolderAdd },
  { key: 'text', label: '文本文件', icon: Document },
]

function onNewSheetSelect(key: string) {
  if (key === 'folder') void onCreateFolder()
  else if (key === 'text') void onCreateTextFile()
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

const stars = useStars()

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
  if (files.length > 0) void enqueueInto(files, folderId.value)
}

/**
 * 传进 target 的文件里有和已有文件同名的:问一次「替换」还是「保留两者」(doc/24)。
 * 返回要替换的文件名(保留两者 / 没有同名时为空);关掉对话框 = 这次不传了,返回 null。
 */
async function askReplace(files: { name: string }[], target: string | null): Promise<Set<string> | null> {
  let existing: { name: string; kind: string }[]
  try {
    existing = target === folderId.value ? items.value : await allChildren(target)
  } catch {
    return new Set()
  }
  const taken = new Set(existing.filter((n) => n.kind === 'FILE').map((n) => n.name))
  const dup = new Set(files.filter((f) => taken.has(f.name)).map((f) => f.name))
  if (!dup.size) return dup
  const first = [...dup][0]!
  try {
    await ElMessageBox.confirm(
      `${dup.size > 1 ? `「${first}」等 ${dup.size} 个文件` : `「${first}」`}这里已经有了。替换后,原来的内容留在历史版本里,随时能恢复。`,
      '有同名文件',
      { confirmButtonText: '替换', cancelButtonText: '保留两者', distinguishCancelAndClose: true },
    )
    return dup
  } catch (action) {
    return action === 'cancel' ? new Set() : null
  }
}

const uploadsStore = useUploadsStore()

/**
 * 桌面端:交给外壳的传输引擎上传(doc/29),缩到托盘照常跑、退出再打开接着传;同名的先问替换。
 * [rootDepth] 见 uploadViaDesktop(选文件夹时)。
 */
async function handToDesktop(objects: Handoff[], target: string | null, rootDepth?: number) {
  const replace = rootDepth ? new Set<string>() : await askReplace(topLevelFiles(objects), target)
  if (!replace) return
  try {
    const n = await uploadViaDesktop(objects, { parentId: target, replace: [...replace], rootDepth })
    if (n > 0) uploadsStore.collapsed = false
  } catch (err) {
    ElMessage.error(typeof err === 'string' ? err : errorText(err, '上传失败'))
  }
}

/** 文件传进 target:先处理同名,同名的带上「替换」,其余照常 */
async function enqueueInto(files: File[], target: string | null) {
  if (nativeUploads()) return handToDesktop(files, target)
  const replace = await askReplace(files, target)
  if (!replace) return
  const rest = files.filter((f) => !replace.has(f.name))
  const rep = files.filter((f) => replace.has(f.name))
  if (rest.length) enqueueFiles(rest, target)
  if (rep.length) enqueueFiles(rep, target, { replace: true })
}

/** 选了一个文件夹:按 webkitRelativePath 还原目录结构 */
function onDirChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  if (nativeUploads()) {
    const first = files[0]!
    void handToDesktop([first], folderId.value, (first.webkitRelativePath || first.name).split('/').length)
    return
  }
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
      // 直接放在目标文件夹里的文件问同名;子文件夹里的照常自动改名
      if (g.dirs.length === 0) await enqueueInto(g.files, base)
      else enqueueFiles(g.files, await ensureDirChain(base, g.dirs, cache))
    }
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '读取文件夹失败'))
  }
}

/** 电脑里拖进来的东西(文件、文件夹混着)传到 target */
async function uploadDataTransfer(dt: DataTransfer, target: string | null) {
  // 桌面端:趁 drop 事件还没结束把拖进来的取出来(文件夹要 FileSystemHandle 才有路径),交给外壳
  if (nativeUploads()) return collectDropped(dt).then((objects) => handToDesktop(objects, target))
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
  if (plainFiles.length > 0) await enqueueInto(plainFiles, target)
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
const shareTarget = ref<{ id: string; name: string; kind: string } | null>(null)

function openShare(row: { id: string; name: string; kind: string }) {
  shareTarget.value = { id: row.id, name: row.name, kind: row.kind }
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

const opened = reactive({ move: false, copy: false, extract: false, share: false, collect: false, fetch: false, detail: false, batchRename: false })
watchEffect(() => {
  if (batchRenameVisible.value) opened.batchRename = true
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
      { key: 'mktext', label: '新建文本文件', icon: Document },
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
  if (one?.kind === 'FILE' && nativeDownloads()) entries.push({ key: 'openLocal', label: '用本地程序打开', icon: Monitor })
  if (one?.kind === 'FILE' && nativeDownloads() && canPlayExternal(one.preview?.kind))
    entries.push({ key: 'playLocal', label: '用播放器播放', icon: VideoPlay })
  entries.push({ key: 'download', label: downloadLabel(sel), icon: Download })
  if (nativeDownloads()) entries.push({ key: 'downloadTo', label: '下载到…', icon: Download })
  if (one) entries.push({ key: 'share', label: '分享', icon: Share })
  const starred = stars.allStarred(sel)
  entries.push({ key: 'star', label: starred ? '取消收藏' : '收藏', icon: starred ? Star : StarFilled })
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
  else if (sel.length > 1) entries.push({ key: 'batchRename', label: '批量重命名', icon: EditPen, hint: 'F2' })
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
    case 'mktext':
      return void onCreateTextFile()
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
    case 'openLocal':
      if (one) void openWithLocalApp(one)
      return
    case 'playLocal':
      if (one) void playWithLocalPlayer(one)
      return
    case 'download':
      return void onDownload(sel)
    case 'downloadTo':
      return void downloadToComputer(sel, true)
    case 'share':
      if (one) openShare(one)
      return
    case 'star':
      return void stars.toggle(sel)
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
    case 'batchRename':
      return openBatchRename()
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
  } else if (e.key === 'F2' && selection.value.length > 1) {
    e.preventDefault()
    openBatchRename()
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
    { key: 'download', label: downloadLabel([node]), icon: Download },
    { key: 'share', label: '分享', icon: Share },
    { key: 'star', label: node.starred ? '取消收藏' : '收藏', icon: node.starred ? Star : StarFilled },
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
    case 'star':
      return void stars.toggle([node])
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
        <el-dropdown split-button trigger="click" @click="onCreateFolder" @command="(c: string) => (c === 'text' ? onCreateTextFile() : onCreateFolder())">
          <el-icon class="el-icon--left"><FolderAdd /></el-icon>新建文件夹
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="folder" :icon="FolderAdd">新建文件夹</el-dropdown-item>
              <el-dropdown-item command="text" :icon="Document">新建文本文件</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-if="fetchEnabled" :icon="Link" @click="fetchDialogVisible = true">离线下载</el-button>
      </template>
      <template v-else>
        <span class="sel-count">
          <el-button link :icon="Close" title="取消选择(Esc)" aria-label="取消选择" @click="clearSelection" />
          已选 {{ selection.length }} 项
        </span>
        <el-button :icon="Download" @click="onDownload(selection)">{{ single && single.kind === 'FILE' ? '下载' : '打包下载' }}</el-button>
        <el-button v-if="single" :icon="Share" @click="openShare(single)">分享</el-button>
        <el-button :icon="stars.allStarred(selection) ? Star : StarFilled" @click="stars.toggle(selection)">
          {{ stars.allStarred(selection) ? '取消收藏' : '收藏' }}
        </el-button>
        <el-button :icon="Rank" @click="openMove(selectedIds)">移动</el-button>
        <el-button :icon="CopyDocument" @click="openCopy(selectedIds)">复制</el-button>
        <el-button v-if="single" :icon="EditPen" @click="onRename(single)">重命名</el-button>
        <el-button v-else-if="selection.length > 1" :icon="EditPen" @click="openBatchRename">批量重命名</el-button>
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
      <el-button :icon="FolderAdd" title="新建" @click="newSheetVisible = true">新建</el-button>
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
        <el-button size="small" :icon="EditPen" :disabled="!selection.length" @click="single ? onRename(single) : openBatchRename()">重命名</el-button>
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
    <NodeActionSheet v-model:visible="newSheetVisible" title="新建" :items="newSheetItems" @select="onNewSheetSelect" />

    <NodeActionSheet
      v-model:visible="linkSheet.visible"
      :title="linkSheet.nodes.length > 1 ? `复制 ${linkSheet.nodes.length} 个文件的直链` : '复制直链'"
      :items="linkFormatItems"
      @select="onLinkSheetSelect"
    />

    <ContextMenu v-model:visible="menu.visible" :x="menu.x" :y="menu.y" :items="menuItems" @select="onMenuSelect" />

    <BatchRenameDialog
      v-if="opened.batchRename"
      v-model="batchRenameVisible"
      :items="batchRenameItems"
      :siblings="items.map((n) => n.name)"
      @done="onBatchRenamed"
    />
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
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  margin-bottom: 10px;
}
/* 窄的时候整组换行,不把「上传 | ▾」这种分体按钮从中间拆开 */
.toolbar > * {
  flex-shrink: 0;
}
.toolbar :deep(.el-button-group) {
  display: inline-flex;
  flex-wrap: nowrap;
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
