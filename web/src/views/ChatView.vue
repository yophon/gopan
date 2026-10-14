<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { refDebounced, useDocumentVisibility, useEventListener } from '@vueuse/core'
import {
  ArrowDown,
  Close,
  DataBoard,
  Delete,
  Document,
  DocumentCopy,
  Download,
  Folder,
  Grid,
  Headset,
  Memo,
  Paperclip,
  Picture,
  Promotion,
  Reading,
  RefreshRight,
  Search,
  Upload,
  VideoCamera,
} from '@element-plus/icons-vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ChatFolderInfoDocument,
  ChatMessagesDocument,
  DeleteChatMessageDocument,
  EnsureChatFolderDocument,
  NodeDownloadUrlDocument,
  SendChatMessageDocument,
} from '@/api/gen/graphql'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import { useUploadsStore } from '@/stores/uploads'
import type { UploadTask } from '@/stores/uploads'
import {
  cancelTask,
  enqueueFiles,
  removeFinishedTask,
  retryTask,
  setUploadDoneListener,
} from '@/uploader/manager'
import {
  MAX_TEXT_BYTES,
  PENDING_PREFIX,
  collapseText,
  groupByDay,
  groupMedia,
  isCollapsible,
  isMediaMsg,
  isPendingId,
  linkify,
  mergeChatMessages,
  pastedFileName,
  pendingToChatMsg,
  removeChatMessage,
  sharePrefill,
  sourceLabel,
  textByteLength,
  textFileName,
  uploadPercentage,
  type ChatMsg,
  type PendingMsg,
} from '@/utils/chat'
import { formatBytes } from '@/utils/format'

// ---------- 会话指针(模块级,upload 完成回调与组件共享) ----------

/** 传输助手文件夹 id;上传回调据此判断是否刷新会话 */
let chatFolderId: string | null = null
/** 同一指针的响应式副本,模板里筛上传卡片用 */
const chatFolderRef = ref<string | null>(null)

function setChatFolder(id: string | null) {
  chatFolderId = id
  chatFolderRef.value = id
}

const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()
const route = useRoute()
const router = useRouter()
const uploads = useUploadsStore()

function invalidateChat() {
  void queryClient.invalidateQueries({ queryKey: ['chatMessages'] })
}

// ---------- chatFolder 指针(只读查询,不触发创建) ----------
// 用户此前传过文件 → 指针落库,读回来才能筛出发往该文件夹的上传任务、以及完成后刷新会话

const { data: folderData } = useQuery({
  queryKey: ['chatFolder'],
  queryFn: () => request(ChatFolderInfoDocument).then((r) => r.chatFolder),
})
watch(folderData, (f) => {
  if (f) setChatFolder(f.id)
})

// ---------- 消息列表(轮询 + 本地去重合并) ----------

const PAGE_LATEST = 200
const PAGE_OLDER = 100
const POLL_MS = 2000

const messages = ref<ChatMsg[]>([])
const pending = ref<PendingMsg[]>([])

// 页面不可见时停掉轮询(vue-query 收到 false 即暂停),回到前台立刻拉一次
const visibility = useDocumentVisibility()
const pollInterval = computed(() => (visibility.value === 'visible' ? POLL_MS : false))

const latestQuery = useQuery({
  queryKey: ['chatMessages'],
  // 每次全量拉最新 200 条,与本地去重合并;删除靠本地移除 + 下一轮全量覆盖兜底
  queryFn: () =>
    request(ChatMessagesDocument, { limit: PAGE_LATEST }).then((r) => r.chatMessages),
  refetchInterval: pollInterval,
  refetchOnWindowFocus: true,
  retry: 1,
  retryDelay: 1000,
})

watch(visibility, (v) => {
  if (v === 'visible') void latestQuery.refetch()
})

/** 轮询连续失败两次以上视为离线;恢复后自动回到已连接 */
const offline = computed(() => latestQuery.isError.value || latestQuery.failureCount.value >= 2)

const hasMore = ref(true)
const loadingOlder = ref(false)
const initialLoaded = ref(false)
/** 用户不在底部时,新到的消息计数(浮标) */
const unread = ref(0)
/** 删除后短暂抑制:轮询可能先于删除落地返回旧数据,这些 id 不再合并回来 */
const tombstones = new Set<string>()

watch(latestQuery.data, (incoming) => {
  if (!incoming) return
  const fresh = incoming.filter((m) => !tombstones.has(m.id))
  const known = new Set(messages.value.map((m) => m.id))
  const added = fresh.filter((m) => !known.has(m.id)).length
  // 全量页:服务端已删的(别的端删的)要从本地拿掉,但只在"最新页覆盖范围"内判断
  const latestIds = new Set(fresh.map((m) => m.id))
  const oldestIncoming = fresh[0]?.createdAt ?? ''
  const kept = messages.value.filter((m) => m.createdAt < oldestIncoming || latestIds.has(m.id))
  messages.value = mergeChatMessages(kept, fresh)

  if (!initialLoaded.value) {
    initialLoaded.value = true
    hasMore.value = incoming.length >= PAGE_LATEST
    void scrollToBottom(true)
    return
  }
  if (added > 0) {
    if (nearBottom()) void scrollToBottom(true)
    else unread.value += added
  }
})

// ---------- 搜索 ----------

const search = ref('')
const searchTerm = refDebounced(search, 300)
const searchActive = computed(() => searchTerm.value.trim().length > 0)

const searchQuery = useQuery({
  queryKey: computed(() => ['chatMessages', 'search', searchTerm.value.trim()]),
  queryFn: () =>
    request(ChatMessagesDocument, { limit: PAGE_LATEST, query: searchTerm.value.trim() }).then(
      (r) => r.chatMessages,
    ),
  enabled: searchActive,
})

// ---------- 渲染列表 ----------

const pendingById = computed(() => new Map(pending.value.map((p) => [p.id, p])))

const displayed = computed<ChatMsg[]>(() => {
  if (searchActive.value) return searchQuery.data.value ?? []
  return [...messages.value, ...pending.value.map(pendingToChatMsg)]
})

/** 按天分组后,每天内部再把连发的图片/视频合成九宫格项 */
const groups = computed(() =>
  groupByDay(displayed.value).map((g) => ({ key: g.key, label: g.label, items: groupMedia(g.items) })),
)

/** 正在发到传输助手文件夹的上传任务(完成/取消的不显示,完成后服务端消息会出现) */
const uploadingTasks = computed(() =>
  chatFolderRef.value
    ? uploads.tasks.filter(
        (t) => t.parentId === chatFolderRef.value && t.status !== 'done' && t.status !== 'canceled',
      )
    : [],
)

/** 展开的长文本消息 id */
const expanded = ref(new Set<string>())

function toggleExpand(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function bodyHtml(m: ChatMsg): string {
  const text = expanded.value.has(m.id) ? m.body : collapseText(m.body)
  return linkify(text)
}

// ---------- 滚动 ----------

const listEl = ref<HTMLElement | null>(null)
const NEAR_BOTTOM_PX = 80

function nearBottom(): boolean {
  const el = listEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
}

async function scrollToBottom(force = false) {
  if (!force && !nearBottom()) return
  await nextTick()
  const el = listEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
  unread.value = 0
}

function onListScroll() {
  const el = listEl.value
  if (!el) return
  if (nearBottom()) unread.value = 0
  if (el.scrollTop < 40 && !searchActive.value) void loadOlder()
}

async function loadOlder() {
  if (loadingOlder.value || !hasMore.value || !initialLoaded.value) return
  const oldest = messages.value[0]
  const el = listEl.value
  if (!oldest || !el) return
  loadingOlder.value = true
  const prevHeight = el.scrollHeight
  const prevTop = el.scrollTop
  try {
    const res = await request(ChatMessagesDocument, { limit: PAGE_OLDER, before: oldest.id })
    const page = res.chatMessages
    if (page.length < PAGE_OLDER) hasMore.value = false
    messages.value = mergeChatMessages(messages.value, page)
    await nextTick()
    // 顶部插入内容后把视口钉在原来那条消息上
    el.scrollTop = el.scrollHeight - prevHeight + prevTop
  } catch (err) {
    ElMessage.error(errorText(err, '加载更早的消息失败'))
  } finally {
    loadingOlder.value = false
  }
}

// ---------- 手机键盘 ----------
// iOS 上键盘弹起时布局视口不会变小,固定 100% 高度的聊天页会把输入框盖在键盘下面。
// 用 visualViewport 的真实高度顶替页面高度,键盘一弹一收都跟着变。
const viewportHeight = ref<number | null>(null)

function syncViewport() {
  viewportHeight.value = window.visualViewport?.height ?? null
}

onMounted(() => {
  if (!window.visualViewport) return
  syncViewport()
  window.visualViewport.addEventListener('resize', syncViewport)
  window.visualViewport.addEventListener('scroll', syncViewport)
})

onBeforeUnmount(() => {
  window.visualViewport?.removeEventListener('resize', syncViewport)
  window.visualViewport?.removeEventListener('scroll', syncViewport)
})

const pageHeightStyle = computed(() =>
  isMobile.value && viewportHeight.value ? { height: `${viewportHeight.value}px` } : {},
)

// ---------- 发送文本(乐观上屏) ----------

const draft = ref('')

const sendMutation = useMutation({
  mutationFn: (body: string) => request(SendChatMessageDocument, { body }),
})

function sendText(body: string, pendingId?: string) {
  const id = pendingId ?? `${PENDING_PREFIX}${crypto.randomUUID()}`
  const item: PendingMsg = { id, body, createdAt: new Date().toISOString(), status: 'sending' }
  pending.value = [...pending.value.filter((p) => p.id !== id), item]
  void scrollToBottom(true)
  sendMutation.mutate(body, {
    onSuccess: (res) => {
      pending.value = pending.value.filter((p) => p.id !== id)
      messages.value = mergeChatMessages(messages.value, [res.sendChatMessage])
      void scrollToBottom(true)
      invalidateChat()
    },
    onError: (err) => {
      pending.value = pending.value.map((p) => (p.id === id ? { ...p, status: 'failed' } : p))
      ElMessage.error(errorText(err, '消息发送失败'))
    },
  })
}

async function send() {
  const body = draft.value.trim()
  if (!body) return
  draft.value = ''
  if (textByteLength(body) > MAX_TEXT_BYTES) {
    // 超过服务端文本上限:转成 .txt 文件发,而不是报错丢掉
    const file = new File([body], textFileName(), { type: 'text/plain' })
    await sendFiles([file])
    return
  }
  sendText(body)
}

function retryPending(p: PendingMsg) {
  sendText(p.body, p.id)
}

function dropPending(id: string) {
  pending.value = pending.value.filter((p) => p.id !== id)
}

function onComposerKeydown(evt: Event | KeyboardEvent) {
  // 桌面:Enter 发送、Shift+Enter 换行;手机:Enter 只换行,靠按钮发送。
  // 输入法组合中的 Enter(选词)不能当发送。
  if (!(evt instanceof KeyboardEvent)) return
  if (evt.key !== 'Enter' || isMobile.value || evt.shiftKey || evt.isComposing) return
  evt.preventDefault()
  void send()
}

// ---------- 系统分享(Web Share Target,GET)带进来的文本 ----------

onMounted(() => {
  const prefill = sharePrefill(route.query as Record<string, unknown>)
  if (!prefill) return
  draft.value = prefill
  void router.replace({ path: route.path })
})

// ---------- 附件:上传到传输助手文件夹 → 服务端自动落消息 ----------

setUploadDoneListener((task) => {
  if (chatFolderId !== null && task.parentId === chatFolderId) invalidateChat()
})

function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.onchange = () => {
    const files = Array.from(input.files ?? [])
    if (!files.length) return
    void sendFiles(files)
  }
  input.click()
}

async function sendFiles(files: File[]) {
  if (files.length === 0) return
  try {
    // 幂等创建传输助手文件夹(指针已在库,改名/移动后仍复用)
    const res = await request(EnsureChatFolderDocument)
    setChatFolder(res.ensureChatFolder.id)
    enqueueFiles(files, res.ensureChatFolder.id)
    uploads.collapsed = true // 进度在聊天里就能看,不弹全局抽屉
    void scrollToBottom(true)
  } catch (err) {
    ElMessage.error(errorText(err, '无法创建传输助手文件夹'))
  }
}

// 粘贴:截图 / 文件 → 直接发;纯文本照常进输入框
useEventListener(document, 'paste', (e: ClipboardEvent) => {
  const files = Array.from(e.clipboardData?.files ?? [])
  if (files.length === 0) return
  e.preventDefault()
  const named = files.map((f) => new File([f], pastedFileName(f), { type: f.type }))
  void sendFiles(named)
})

// 拖拽:限定在聊天页内,不抢网盘页的全局处理
const dragDepth = ref(0)
const dragging = computed(() => dragDepth.value > 0)

function hasFiles(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes('Files')
}

function onDragEnter(e: DragEvent) {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth.value++
}

function onDragOver(e: DragEvent) {
  if (!hasFiles(e)) return
  e.preventDefault()
}

function onDragLeave(e: DragEvent) {
  if (!hasFiles(e)) return
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function onDrop(e: DragEvent) {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth.value = 0
  const files = Array.from(e.dataTransfer?.files ?? [])
  void sendFiles(files)
}

// ---------- 上传卡片 ----------

function uploadStatusText(task: UploadTask): string {
  switch (task.status) {
    case 'queued':
      return '排队中'
    case 'hashing':
      return `计算校验值 ${uploadPercentage(task)}%`
    case 'initiating':
      return '准备上传'
    case 'uploading':
      return `${uploadPercentage(task)}% · ${formatBytes(task.speedBps)}/s`
    case 'completing':
      return '校验并保存中'
    case 'paused':
      return '已暂停'
    case 'failed':
      return task.error ?? '上传失败'
    default:
      return ''
  }
}

// ---------- 文件卡片 / 预览 / 下载 ----------

const isMedia = isMediaMsg

function fileIcon(m: ChatMsg) {
  const n = m.node
  const k = n?.preview?.kind
  const name = n?.name ?? ''
  switch (k) {
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
      const ext = name.split('.').pop()?.toLowerCase() ?? ''
      if (['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return Grid
      if (['ppt', 'pptx', 'odp'].includes(ext)) return DataBoard
      return Document
    }
    default: {
      const ext = name.split('.').pop()?.toLowerCase() ?? ''
      if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return Folder
      return Document
    }
  }
}

/** 点文件卡片:拿当前列表里全部文件节点组预览列表,index 对齐 */
function openChatFile(m: ChatMsg) {
  const n = m.node
  if (!n) return
  const files = displayed.value.filter((x) => x.node).map((x) => x.node!.id)
  const idx = files.indexOf(n.id)
  if (idx >= 0) openPreview(files, idx)
}

async function onDownload(m: ChatMsg) {
  const n = m.node
  if (!n) return
  try {
    const res = await request(NodeDownloadUrlDocument, { id: n.id })
    const url = res.node.downloadUrl
    if (!url) {
      ElMessage.error('无法获取下载链接')
      return
    }
    const a = document.createElement('a')
    a.href = url
    a.download = n.name
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (err) {
    ElMessage.error(errorText(err, '获取下载链接失败'))
  }
}

// ---------- 复制 / 删除 ----------

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败,请手动选择文本')
  }
}

const latestText = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]!
    if (!m.node && m.body) return m.body
  }
  return ''
})

function copyLatest() {
  if (latestText.value) void copyText(latestText.value)
}

const deleteMutation = useMutation({
  mutationFn: (id: string) => request(DeleteChatMessageDocument, { id }),
})

async function confirmDelete(m: ChatMsg) {
  try {
    await ElMessageBox.confirm(
      m.node ? '只删除这条消息,文件仍保留在网盘里。' : '删除这条消息?',
      '删除消息',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deleteMutation.mutate(m.id, {
    onSuccess: () => {
      tombstones.add(m.id)
      messages.value = removeChatMessage(messages.value, m.id)
      invalidateChat()
    },
    onError: (err) => ElMessage.error(errorText(err, '删除失败')),
  })
}

// 手机长按 / 桌面右键:弹操作面板
const actionTarget = ref<ChatMsg | null>(null)
let pressTimer: number | null = null

function openActions(m: ChatMsg) {
  if (isPendingId(m.id)) return
  actionTarget.value = m
}

function onTouchStart(m: ChatMsg) {
  clearPress()
  pressTimer = window.setTimeout(() => {
    pressTimer = null
    openActions(m)
  }, 500)
}

function clearPress() {
  if (pressTimer !== null) {
    window.clearTimeout(pressTimer)
    pressTimer = null
  }
}

function actionCopy() {
  const m = actionTarget.value
  actionTarget.value = null
  if (m) void copyText(m.node ? m.node.name : m.body)
}

function actionDownload() {
  const m = actionTarget.value
  actionTarget.value = null
  if (m) void onDownload(m)
}

function actionDelete() {
  const m = actionTarget.value
  actionTarget.value = null
  if (m) void confirmDelete(m)
}

function timeOf(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div
    class="chat-page"
    :style="pageHeightStyle"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <header class="chat-header">
      <div class="title">
        <h2>传输助手</h2>
        <span v-if="!isMobile">在电脑和手机之间传输消息与文件</span>
      </div>
      <div class="tools">
        <el-input
          v-model="search"
          class="search"
          placeholder="搜索消息或文件名"
          clearable
          :prefix-icon="Search"
        />
        <el-button
          text
          :icon="DocumentCopy"
          title="复制最新一条文本"
          :disabled="!latestText"
          @click="copyLatest"
        />
        <el-tag v-if="offline" type="warning" effect="plain">离线,重连中</el-tag>
        <el-tag v-else type="success" effect="plain">已连接</el-tag>
      </div>
    </header>

    <main ref="listEl" class="message-list" @scroll.passive="onListScroll">
      <div v-if="loadingOlder" class="hint">加载更早的消息…</div>
      <div v-else-if="!searchActive && initialLoaded && !hasMore && messages.length > 0" class="hint">
        没有更早的消息了
      </div>

      <div v-if="searchActive && searchQuery.isPending.value" class="empty">搜索中…</div>
      <div v-else-if="searchActive && displayed.length === 0" class="empty">没有匹配的消息</div>
      <div v-else-if="!searchActive && initialLoaded && displayed.length === 0 && uploadingTasks.length === 0" class="empty">
        发一条消息,或把文件拖进来、粘贴截图
      </div>

      <template v-for="group in groups" :key="group.key">
        <div class="day-sep"><span>{{ group.label }}</span></div>
        <template v-for="item in group.items" :key="item.kind === 'media' ? item.id : item.message.id">
        <!-- 连发的图片 / 视频:九宫格 -->
        <div v-if="item.kind === 'media'" class="message-row">
          <div class="media-grid" :class="item.messages.length === 2 ? 'cols-2' : 'cols-3'">
            <div
              v-for="message in item.messages"
              :key="message.id"
              class="media-tile"
              :title="message.node!.name"
              @click="openChatFile(message)"
              @contextmenu.prevent="openActions(message)"
              @touchstart.passive="onTouchStart(message)"
              @touchend="clearPress"
              @touchmove.passive="clearPress"
              @touchcancel="clearPress"
            >
              <img :src="message.node!.preview.thumbUrl!" :alt="message.node!.name" loading="lazy" />
              <el-icon v-if="message.node!.preview.kind === 'VIDEO'" class="tile-badge"><VideoCamera /></el-icon>
              <span v-if="!isMobile" class="tile-actions">
                <el-icon title="下载" @click.stop="onDownload(message)"><Download /></el-icon>
                <el-icon title="删除" @click.stop="confirmDelete(message)"><Delete /></el-icon>
              </span>
            </div>
          </div>
          <div class="row-foot">
            <time>{{ timeOf(item.messages[item.messages.length - 1]!.createdAt) }}</time>
            <span class="count-text">{{ item.messages.length }} 个文件</span>
            <span v-if="sourceLabel(item.messages[0]!.source)" class="source-tag">{{ sourceLabel(item.messages[0]!.source) }}</span>
          </div>
        </div>
        <div
          v-else
          class="message-row"
          :class="{ pending: isPendingId(item.message.id), failed: pendingById.get(item.message.id)?.status === 'failed' }"
          @contextmenu.prevent="openActions(item.message)"
          @touchstart.passive="onTouchStart(item.message)"
          @touchend="clearPress"
          @touchmove.passive="clearPress"
          @touchcancel="clearPress"
        >
          <template v-for="message in [item.message]" :key="message.id">
          <!-- 文件消息 -->
          <template v-if="message.node">
            <div v-if="isMedia(message)" class="media-card" @click="openChatFile(message)">
              <img
                class="media-thumb"
                :src="message.node.preview.thumbUrl!"
                :alt="message.node.name"
                loading="lazy"
              />
              <div class="media-meta">
                <span class="file-name">{{ message.node.name }}</span>
                <span class="file-size">{{ formatBytes(message.node.size) }}</span>
                <el-icon class="dl" title="下载" @click.stop="onDownload(message)"><Download /></el-icon>
              </div>
            </div>
            <div v-else class="file-card" @click="openChatFile(message)">
              <el-icon class="file-icon"><component :is="fileIcon(message)" /></el-icon>
              <div class="file-meta">
                <div class="file-name">{{ message.node.name }}</div>
                <div class="file-size">{{ formatBytes(message.node.size) }}</div>
              </div>
              <el-icon class="dl" title="下载" @click.stop="onDownload(message)"><Download /></el-icon>
            </div>
          </template>

          <!-- 文本消息(含乐观上屏的 pending) -->
          <template v-else>
            <div class="bubble">
              <!-- linkify 先整体 HTML 转义再包链接,v-html 安全 -->
              <div class="bubble-text" v-html="bodyHtml(message)" />
              <button
                v-if="isCollapsible(message.body)"
                class="expand"
                type="button"
                @click="toggleExpand(message.id)"
              >
                {{ expanded.has(message.id) ? '收起' : '展开全文' }}
              </button>
            </div>
          </template>

          <!-- 行尾:时间 / 发送状态 / 桌面悬停操作 -->
          <div class="row-foot">
            <template v-if="pendingById.get(message.id)?.status === 'failed'">
              <span class="fail-text">发送失败</span>
              <el-button text size="small" :icon="RefreshRight" @click="retryPending(pendingById.get(message.id)!)">重试</el-button>
              <el-button text size="small" :icon="Close" @click="dropPending(message.id)">移除</el-button>
            </template>
            <span v-else-if="isPendingId(message.id)" class="sending-text">发送中…</span>
            <template v-else>
              <time>{{ timeOf(message.createdAt) }}</time>
              <span v-if="sourceLabel(message.source)" class="source-tag">{{ sourceLabel(message.source) }}</span>
              <span v-if="!isMobile" class="hover-actions">
                <el-icon title="复制" @click="copyText(message.node ? message.node.name : message.body)"><DocumentCopy /></el-icon>
                <el-icon title="删除" @click="confirmDelete(message)"><Delete /></el-icon>
              </span>
            </template>
          </div>
          </template>
        </div>
        </template>
      </template>

      <!-- 正在上传的文件:占位卡片,完成后由服务端消息接替 -->
      <div v-for="task in uploadingTasks" :key="task.id" class="message-row">
        <div class="file-card uploading" :class="{ failed: task.status === 'failed' }">
          <el-icon class="file-icon"><Upload /></el-icon>
          <div class="file-meta">
            <div class="file-name">{{ task.fileName }}</div>
            <el-progress
              :percentage="uploadPercentage(task)"
              :show-text="false"
              :stroke-width="4"
              :status="task.status === 'failed' ? 'exception' : undefined"
            />
            <div class="file-size">{{ formatBytes(task.size) }} · {{ uploadStatusText(task) }}</div>
          </div>
          <div class="task-actions">
            <el-icon v-if="task.status === 'failed'" title="重试" @click="retryTask(task.id)"><RefreshRight /></el-icon>
            <el-icon v-if="task.status === 'failed'" title="移除" @click="removeFinishedTask(task.id)"><Close /></el-icon>
            <el-icon v-else title="取消" @click="cancelTask(task.id)"><Close /></el-icon>
          </div>
        </div>
      </div>
    </main>

    <el-button v-if="unread > 0" class="new-msg-fab" type="primary" round size="small" @click="scrollToBottom(true)">
      {{ unread }} 条新消息
      <el-icon class="el-icon--right"><ArrowDown /></el-icon>
    </el-button>

    <footer class="composer">
      <el-button text :icon="Paperclip" title="发送文件" @click="pickFile" />
      <el-input
        v-model="draft"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 6 }"
        resize="none"
        :placeholder="isMobile ? '输入消息' : '输入消息,Enter 发送,Shift+Enter 换行'"
        @keydown="onComposerKeydown"
      />
      <el-button
        type="primary"
        :icon="Promotion"
        :disabled="!draft.trim() || sendMutation.isPending.value"
        @click="send"
      >
        发送
      </el-button>
    </footer>

    <!-- 拖拽遮罩(限聊天页) -->
    <div v-if="dragging" class="drop-overlay">
      <div class="drop-hint">
        <el-icon :size="40"><Upload /></el-icon>
        <p>松开发送</p>
      </div>
    </div>

    <!-- 长按 / 右键操作面板 -->
    <el-dialog
      :model-value="actionTarget !== null"
      :show-close="false"
      :width="isMobile ? '92%' : '320px'"
      align-center
      class="action-sheet"
      @close="actionTarget = null"
    >
      <div class="sheet-title">{{ actionTarget?.node ? actionTarget.node.name : '消息' }}</div>
      <el-button class="sheet-btn" :icon="DocumentCopy" @click="actionCopy">
        {{ actionTarget?.node ? '复制文件名' : '复制文本' }}
      </el-button>
      <el-button v-if="actionTarget?.node" class="sheet-btn" :icon="Download" @click="actionDownload">下载</el-button>
      <el-button class="sheet-btn" type="danger" plain :icon="Delete" @click="actionDelete">删除消息</el-button>
      <el-button class="sheet-btn" text @click="actionTarget = null">取消</el-button>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-page { position: relative; height: 100%; display: flex; flex-direction: column; max-width: 900px; margin: 0 auto; }
.chat-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; border-bottom: 1px solid var(--el-border-color-light); padding: 4px 0 12px; }
h2 { margin: 0 0 4px; font-size: 20px; }
.chat-header .title span { color: var(--el-text-color-secondary); font-size: 13px; }
.tools { display: flex; align-items: center; gap: 8px; min-width: 0; }
.search { width: 220px; }
.message-list { flex: 1; overflow: auto; padding: 12px 4px; -webkit-overflow-scrolling: touch; }
.hint { text-align: center; color: var(--el-text-color-placeholder); font-size: 12px; padding: 4px 0 8px; }
.empty { text-align: center; color: var(--el-text-color-secondary); margin-top: 24vh; }
.day-sep { display: flex; justify-content: center; margin: 14px 0 6px; }
.day-sep span { font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color); border-radius: 10px; padding: 2px 10px; }
.message-row { display: flex; flex-direction: column; align-items: flex-start; margin: 10px 0; -webkit-touch-callout: none; }
.message-row.pending .bubble { opacity: 0.6; }
.message-row.failed .bubble { opacity: 1; border: 1px solid var(--el-color-danger-light-5); }
.bubble { max-width: min(70%, 560px); background: var(--el-fill-color-light); border-radius: 12px; padding: 10px 13px; line-height: 1.5; }
.bubble-text { white-space: pre-wrap; word-break: break-word; }
.bubble-text :deep(a) { color: var(--el-color-primary); text-decoration: underline; word-break: break-all; }
.expand { margin-top: 6px; padding: 0; border: 0; background: none; color: var(--el-color-primary); font-size: 13px; cursor: pointer; }
.file-card { display: flex; align-items: center; gap: 10px; max-width: min(84%, 480px); width: 100%; background: var(--el-fill-color-light); border-radius: 12px; padding: 8px 12px; cursor: pointer; }
.file-card:hover { background: var(--el-fill-color-darker); }
.file-card.uploading { cursor: default; }
.file-card.uploading:hover { background: var(--el-fill-color-light); }
.file-card.failed { border: 1px solid var(--el-color-danger-light-5); }
.file-icon { width: 40px; height: 40px; font-size: 28px; color: var(--el-text-color-secondary); flex-shrink: 0; }
.file-meta { min-width: 0; flex: 1; }
.file-name { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.file-size { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.dl, .task-actions .el-icon { color: var(--el-text-color-secondary); cursor: pointer; flex-shrink: 0; }
.dl:hover, .task-actions .el-icon:hover { color: var(--el-color-primary); }
.task-actions { display: flex; gap: 8px; }
.media-card { max-width: min(84%, 480px); background: var(--el-fill-color-light); border-radius: 12px; padding: 6px; cursor: pointer; }
.media-thumb { display: block; max-width: 240px; max-height: 240px; width: auto; height: auto; border-radius: 8px; }
.media-meta { display: flex; align-items: center; gap: 8px; padding: 6px 4px 2px; max-width: 240px; }
.media-meta .file-name { flex: 1; font-size: 12px; font-weight: 400; }
.media-meta .file-size { margin: 0; white-space: nowrap; }
.media-grid { display: grid; gap: 4px; max-width: min(84%, 360px); width: 100%; }
.media-grid.cols-2 { grid-template-columns: repeat(2, 1fr); }
.media-grid.cols-3 { grid-template-columns: repeat(3, 1fr); }
.media-tile { position: relative; aspect-ratio: 1; border-radius: 8px; overflow: hidden; background: var(--el-fill-color-light); cursor: pointer; }
.media-tile img { display: block; width: 100%; height: 100%; object-fit: cover; }
.tile-badge { position: absolute; left: 6px; bottom: 6px; color: #fff; font-size: 16px; filter: drop-shadow(0 0 2px rgba(0, 0, 0, 0.8)); }
.tile-actions { position: absolute; right: 4px; top: 4px; display: inline-flex; gap: 4px; padding: 2px 4px; border-radius: 6px; background: rgba(0, 0, 0, 0.55); opacity: 0; transition: opacity 0.15s; }
.tile-actions .el-icon { color: #fff; font-size: 14px; cursor: pointer; }
.media-tile:hover .tile-actions { opacity: 1; }
.count-text, .source-tag { font-size: 11px; color: var(--el-text-color-secondary); }
.source-tag { padding: 0 5px; border-radius: 4px; background: var(--el-fill-color); }
.row-foot { display: flex; align-items: center; gap: 6px; margin-top: 4px; min-height: 18px; }
time, .sending-text { font-size: 11px; color: var(--el-text-color-secondary); }
.fail-text { font-size: 11px; color: var(--el-color-danger); }
.hover-actions { display: inline-flex; gap: 8px; opacity: 0; transition: opacity 0.15s; }
.hover-actions .el-icon { font-size: 14px; color: var(--el-text-color-secondary); cursor: pointer; }
.hover-actions .el-icon:hover { color: var(--el-color-primary); }
.message-row:hover .hover-actions { opacity: 1; }
.new-msg-fab { position: absolute; left: 50%; transform: translateX(-50%); bottom: 72px; z-index: 5; box-shadow: var(--el-box-shadow-light); }
.composer { display: flex; gap: 8px; align-items: flex-end; padding-top: 10px; border-top: 1px solid var(--el-border-color-light); }
.composer :deep(.el-textarea__inner) { line-height: 1.5; }
.drop-overlay { position: absolute; inset: 0; z-index: 20; display: flex; align-items: center; justify-content: center; background: color-mix(in srgb, var(--el-color-primary) 12%, transparent); border: 2px dashed var(--el-color-primary); border-radius: 8px; pointer-events: none; }
.drop-hint { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 24px 36px; border-radius: 12px; background: var(--el-bg-color); box-shadow: var(--el-box-shadow); color: var(--el-color-primary); font-size: 15px; }
.drop-hint p { margin: 0; }
.sheet-title { font-size: 13px; color: var(--el-text-color-secondary); margin-bottom: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sheet-btn { display: flex; width: 100%; margin: 0 0 8px !important; justify-content: flex-start; }
@media (max-width: 767px) {
  .chat-header { flex-wrap: wrap; }
  .tools { flex: 1; }
  .search { flex: 1; width: auto; min-width: 0; }
  .composer :deep(.el-button) { padding: 8px; }
  .bubble { max-width: 85%; }
  .media-thumb { max-width: 200px; max-height: 200px; }
}
</style>
