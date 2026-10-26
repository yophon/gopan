<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Close,
  Document,
  Download,
  EditPen,
  Monitor,
  Headset,
  Reading,
  VideoPlay,
  View,
  WarningFilled,
} from '@element-plus/icons-vue'

import { guestRequest, request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  MarkClipReadDocument,
  NodeClipDocument,
  NodeDownloadUrlDocument,
  NodePreviewDocument,
  PlaylistDocument,
  RequestPreviewDocument,
  RequestRemuxDocument,
} from '@/api/gen/graphql'
import type { Variables } from '@/api/transport'
import type { DocumentTypeDecoration } from '@graphql-typed-document-node/core'
import { formatBytes } from '@/utils/format'
import { useBreakpoints } from '@/composables/breakpoints'
import { useSwipe } from '@vueuse/core'
import { closePreview, openPreview, previewNext, previewPrev, previewState } from '@/composables/preview'
import { reportProgress } from '@/composables/media'
import { markOpened } from '@/composables/quick'
import { useAuthStore } from '@/stores/auth'
import { usePlayerStore } from '@/stores/player'
import { formatDuration, mergeSubtitles, pickVideoSource, resumePage } from '@/utils/media'
import { siteOf } from '@/utils/clips'
import { canEditText } from '@/utils/textEdit'
import {
  currentConnection,
  prefetchBudget,
  useNeighborPrefetch,
} from '@/composables/prefetch'

// pdfjs-dist 体积大(~1MB),按需异步加载,只有真正预览 PDF 时才拉
const PdfViewer = defineAsyncComponent(() => import('./PdfViewer.vue'))
// 在线编辑(doc/25):点「编辑」时才加载
const TextEditor = defineAsyncComponent(() => import('./TextEditor.vue'))
import ImageViewer from './ImageViewer.vue'
import ArchiveViewer from './ArchiveViewer.vue'
import VideoPlayer from './VideoPlayer.vue'
import MarkdownFrame from './MarkdownFrame.vue'
import AudioPanel from '@/components/player/AudioPanel.vue'
import { downloadToComputer, openWithLocalApp, playWithLocalPlayer } from '@/composables/nodeDownload'
import { canPlayExternal } from '@/desktop/play'
import { nativeDownloads } from '@/desktop/transfers'

/** 访客模式(分享页):带访客 token 请求;访客是只读白名单,不能触发文档转换 */
const props = defineProps<{ guestToken?: string }>()

function req<TResult, TVariables extends Variables>(
  document: DocumentTypeDecoration<TResult, TVariables>,
  variables?: TVariables,
): Promise<TResult> {
  return props.guestToken
    ? guestRequest(document, props.guestToken, variables)
    : request(document, variables)
}

const currentId = computed<string | null>(() =>
  previewState.visible ? (previewState.ids[previewState.index] ?? null) : null,
)
const hasPrev = computed(() => previewState.visible && previewState.index > 0)
const hasNext = computed(
  () => previewState.visible && previewState.index < previewState.ids.length - 1,
)

// ---------- 单节点预览查询(预签名 15 分钟,打开/切换即查即用,不留缓存) ----------

const { data, isFetching, isError, error, refetch } = useQuery({
  queryKey: ['nodePreview', currentId],
  enabled: computed(() => currentId.value !== null),
  queryFn: () => req(NodePreviewDocument, { id: currentId.value! }),
  staleTime: 0,
  gcTime: 0,
  retry: 1,
  // OFFICE 转换中每 2 秒、视频转封装中每 1.5 秒轮询;模态关闭 → enabled=false 自动停
  refetchInterval: (query) => {
    const p = query.state.data?.node.preview
    if (p?.kind === 'OFFICE' && (p.status === 'PENDING' || p.status === 'RUNNING')) return 2000
    if (p?.kind === 'VIDEO' && (p.remux?.status === 'QUEUED' || p.remux?.status === 'RUNNING')) return 1500
    return false
  },
})

const node = computed(() => data.value?.node ?? null)
const preview = computed(() => node.value?.preview ?? null)

/** 初次拿到元数据前的整体 loading(轮询中的 refetch 不算) */
const initialLoading = computed(() => isFetching.value && !data.value)

// ---------- 影音(doc/22 二):字幕 token、签名过期重取、下一集、全站音乐播放器、阅读进度 ----------

const auth = useAuthStore()
const player = usePlayerStore()

/** /sub 用的 token;NodePreview 刚走过 request(),过期的已经刷新过了 */
const mediaToken = computed(() => props.guestToken ?? auth.accessToken ?? '')

/** 播放中签名过期:重新查一次当前节点,拿新的地址(原文件或转好的 mp4) */
async function refreshContentUrl(): Promise<string | null> {
  const id = currentId.value
  const kind = videoSource.value?.kind
  const res = await refetch()
  if (res.isError || currentId.value !== id) return null
  const p = res.data?.node.preview
  if (kind === 'remux') return p?.remux?.url ?? null
  return p?.contentUrl ?? null
}

// ---------- 浏览器放不了的视频容器:服务器转成 mp4 再放(doc/31 一) ----------

/** 直接放原文件放不了的节点(本次打开期间记着,切回来不再试) */
const directFailed = ref(new Set<string>())
const requestedRemux = new Set<string>()

const remux = computed(() => (preview.value?.kind === 'VIDEO' ? (preview.value.remux ?? null) : null))
const videoSource = computed(() => {
  const p = preview.value
  if (p?.kind !== 'VIDEO') return null
  return pickVideoSource(p.contentUrl, remux.value, !!currentId.value && directFailed.value.has(currentId.value))
})
const videoSubtitles = computed(() =>
  mergeSubtitles(preview.value?.subtitles ?? [], videoSource.value?.kind === 'remux' ? (remux.value?.subtitles ?? []) : []),
)
const remuxPercent = computed(() =>
  remux.value?.status === 'RUNNING' && remux.value.progress != null ? Math.round(remux.value.progress * 100) : null,
)

const requestRemuxMutation = useMutation({
  mutationFn: (vars: { nodeId: string }) => req(RequestRemuxDocument, vars),
  onSuccess: () => void refetch(),
  onError: (err) => ElMessage.error(errorText(err, '没能开始转换')),
})

// 要等转换且还没开始:自动开始一次(失败后的重试要手点)
watch(
  [videoSource, currentId],
  ([src, id]) => {
    if (!id || src?.kind !== 'wait' || remux.value?.status !== 'NONE' || requestedRemux.has(id)) return
    requestedRemux.add(id)
    requestRemuxMutation.mutate({ nodeId: id })
  },
  { immediate: true },
)

/** 原文件放不了:能转封装就转,不能就让播放器自己提示 */
function onVideoUnsupported() {
  const id = currentId.value
  if (!id || !remux.value || videoSource.value?.kind !== 'direct') return
  directFailed.value.add(id)
}

function retryRemux() {
  const id = currentId.value
  if (!id) return
  requestedRemux.add(id)
  requestRemuxMutation.mutate({ nodeId: id })
}

/** 视频的同文件夹连播列表(访客在分享范围内也能查) */
const { data: playlistData } = useQuery({
  queryKey: ['playlist', currentId],
  enabled: computed(() => currentId.value !== null && preview.value?.kind === 'VIDEO'),
  queryFn: () => req(PlaylistDocument, { nodeId: currentId.value! }),
  staleTime: 60_000,
})
const nextVideo = computed(() => {
  const list = playlistData.value?.playlist ?? []
  const i = list.findIndex((n) => n.id === currentId.value)
  return i >= 0 ? (list[i + 1] ?? null) : null
})

function playNextVideo() {
  const next = nextVideo.value
  if (!next) return
  const i = previewState.ids.indexOf(next.id)
  if (i >= 0) {
    previewState.index = i
    return
  }
  // 下一集不在当前列表里(列表只加载了一部分等):换成连播列表
  const ids = (playlistData.value?.playlist ?? []).map((n) => n.id)
  openPreview(ids, ids.indexOf(next.id))
}

/** 登录用户的音频交给全站播放器(关掉预览接着放);访客照旧用自己的 <audio> */
const useGlobalAudio = computed(() => !props.guestToken && player.attached)

watch(
  [data, currentId],
  ([val, id]) => {
    if (!val || !id || val.node.id !== id || !useGlobalAudio.value) return
    const p = val.node.preview
    if (p.kind !== 'AUDIO' || !p.contentUrl) return
    void player.openFromPreview(
      { id: val.node.id, name: val.node.name, parentId: val.node.parentId },
      { url: p.contentUrl, progress: val.node.playProgress },
    )
  },
  { immediate: true },
)

/** PDF 打开时翻到哪页:内容搜索命中页优先,其次上次读到的页(访客不续读) */
const pdfInitialPage = computed(() => {
  const id = currentId.value
  if (!id) return undefined
  const hit = previewState.pages[id]
  if (hit) return hit
  if (props.guestToken) return undefined
  return resumePage(node.value?.playProgress) ?? undefined
})

// 翻页后 5 秒报一次;切走 / 关掉时把没报的补上
let pdfPending: { nodeId: string; page: number; total: number } | null = null
let pdfTimer: ReturnType<typeof setTimeout> | null = null

function flushPdfProgress() {
  if (pdfTimer) clearTimeout(pdfTimer)
  pdfTimer = null
  const p = pdfPending
  pdfPending = null
  if (p) reportProgress(p.nodeId, p.page, p.total, true)
}

function onPdfPage(page: number, total: number) {
  const id = currentId.value
  if (!id || props.guestToken || preview.value?.kind !== 'PDF') return
  pdfPending = { nodeId: id, page, total }
  if (pdfTimer) clearTimeout(pdfTimer)
  pdfTimer = setTimeout(flushPdfProgress, 5000)
}

watch(currentId, () => flushPdfProgress())
onBeforeUnmount(flushPdfProgress)

// ---------- OFFICE:status 为空说明从未触发转换,自动触发一次 ----------

const requestedOffice = new Set<string>()

const requestPreviewMutation = useMutation({
  mutationFn: (vars: { nodeId: string }) => req(RequestPreviewDocument, vars),
  onSuccess: () => {
    // 刷新当前节点,让轮询接管 PENDING/RUNNING
    void refetch()
  },
  onError: (err) => ElMessage.error(errorText(err, '触发文档转换失败')),
})

watch(
  [data, currentId],
  ([val, id]) => {
    if (!val || !id || val.node.id !== id) return
    if (props.guestToken) return // 访客只读,不能触发转换
    const p = val.node.preview
    if (p.kind === 'OFFICE' && !p.status && !requestedOffice.has(id)) {
      requestedOffice.add(id)
      requestPreviewMutation.mutate({ nodeId: id })
    }
  },
  { immediate: true },
)

function retryOffice() {
  const id = currentId.value
  if (!id) return
  requestedOffice.add(id)
  requestPreviewMutation.mutate({ nodeId: id })
}

/**
 * Office 转不出来的两种情况,都引导下载:
 * UNAVAILABLE = 本次部署没开 Office 转换(未部署 Gotenberg);访客 + 无产物 = 有能力但访客无权触发。
 */
const officeUnavailable = computed(() => {
  const p = preview.value
  if (p?.kind !== 'OFFICE') return false
  return p.status === 'UNAVAILABLE' || (!!props.guestToken && !p.status)
})

const officeUnavailableText = computed(() =>
  preview.value?.status === 'UNAVAILABLE'
    ? '本站未开启 Office 在线预览,请下载查看'
    : '该文档还没有生成预览,请下载查看',
)

const officeConverting = computed(() => {
  const p = preview.value
  if (p?.kind !== 'OFFICE' || officeUnavailable.value) return false
  // status 为空 = 刚要触发/触发请求在途,也按转换中展示
  return !p.status || p.status === 'PENDING' || p.status === 'RUNNING'
})

// ---------- Markdown(doc/23 五.2):默认渲染着看,可切回源文件 ----------

const mdSource = ref(false)
watch(currentId, () => (mdSource.value = false))

const showMarkdown = computed(
  () => preview.value?.kind === 'TEXT' && !!preview.value.markdown && !mdSource.value,
)

/** 是不是一篇剪藏:是的话顶上给来源与已读(访客看不到别人的剪藏记录) */
const queryClient = useQueryClient()

// 「最近」(doc/24):自己的文件预览成功一次记一次;访客不记
watch(
  () => node.value?.id,
  (id) => {
    if (id && !props.guestToken) markOpened(queryClient, id)
  },
)
const { data: clipData } = useQuery({
  queryKey: ['nodeClip', currentId],
  enabled: computed(() => !props.guestToken && currentId.value !== null && !!preview.value?.markdown),
  queryFn: () => request(NodeClipDocument, { nodeId: currentId.value! }),
  staleTime: 0,
})
const clip = computed(() => (clipData.value?.nodeClip?.node?.id === currentId.value ? clipData.value.nodeClip : null))

async function toggleClipRead() {
  const c = clip.value
  if (!c) return
  try {
    await request(MarkClipReadDocument, { id: c.id, read: !c.readAt })
    void queryClient.invalidateQueries({ queryKey: ['nodeClip'] })
    void queryClient.invalidateQueries({ queryKey: ['clips'] })
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  }
}

// ---------- 在线编辑(doc/25):文本文件在预览里直接改,有没保存的修改时离开要确认 ----------

const editing = ref(false)
const editorRef = ref<{ confirmLeave: () => Promise<boolean> } | null>(null)
/** 每存一次 +1:渲染着看的 Markdown 跟着重新打开 */
const editVersion = ref(0)
const canEdit = computed(() => !props.guestToken && !!node.value && canEditText(preview.value?.kind, node.value.size))

async function leaveEditing(): Promise<boolean> {
  if (!editing.value) return true
  const ok = (await editorRef.value?.confirmLeave()) ?? true
  if (ok) editing.value = false
  return ok
}

async function guardedClose() {
  if (await leaveEditing()) closePreview()
}

async function guardedPrev() {
  if (await leaveEditing()) previewPrev()
}

async function guardedNext() {
  if (await leaveEditing()) previewNext()
}

// 换了文件 / 关掉了:退出编辑
watch(currentId, () => (editing.value = false))
// 刚新建的文本文件:预览信息到了就直接进编辑
watch(
  [currentId, preview],
  ([id, p]) => {
    if (id && p && previewState.editOnOpen === id) {
      previewState.editOnOpen = null
      if (canEdit.value) editing.value = true
    }
  },
  { immediate: true },
)

function onEditorSaved() {
  editVersion.value++
  void refetch()
  for (const key of ['children', 'nodeDetail', 'fileVersions', 'activities', 'recent', 'starred', 'me']) {
    void queryClient.invalidateQueries({ queryKey: [key] })
  }
}

// ---------- TEXT:拉取文本内容 ----------

const TEXT_MAX_CHARS = 200_000

const textContent = ref('')
const textTruncated = ref(false)
const textLoading = ref(false)
const textError = ref<string | null>(null)

watch(
  [preview, currentId, mdSource],
  async ([p, id]) => {
    // 渲染着看的 Markdown 不用拉原文;切到「源文件」时再拉
    if (!p || !id || p.kind !== 'TEXT' || !p.contentUrl || showMarkdown.value) return
    textLoading.value = true
    textError.value = null
    textContent.value = ''
    textTruncated.value = false
    try {
      const res = await fetch(p.contentUrl)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const raw = await res.text()
      if (currentId.value !== id) return // 已切走,丢弃
      textTruncated.value = raw.length > TEXT_MAX_CHARS
      textContent.value = textTruncated.value ? raw.slice(0, TEXT_MAX_CHARS) : raw
    } catch (err) {
      if (currentId.value !== id) return
      textError.value = err instanceof Error ? err.message : '加载失败'
    } finally {
      if (currentId.value === id) textLoading.value = false
    }
  },
  { immediate: true },
)

// ---------- 下载 ----------

const downloading = ref(false)

async function onDownload() {
  const n = node.value
  if (!n || downloading.value) return
  if (nativeDownloads()) return void downloadToComputer([{ id: n.id, name: n.name, kind: 'FILE', size: n.size }])
  downloading.value = true
  try {
    const res = await req(NodeDownloadUrlDocument, { id: n.id })
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
  } finally {
    downloading.value = false
  }
}

function onViewOriginal() {
  const url = preview.value?.contentUrl
  if (url) window.open(url, '_blank', 'noopener')
}

// ---------- 手机:滑动切换 ----------

const { isMobile } = useBreakpoints()
/** 图片处于放大状态(scale ≠ 1)时,左右滑动要让位给拖动看图 */
const imageMagnified = ref(false)
/** 视频/音频播放中时,滑动让位给播放器快进 */
const videoPlaying = ref(false)

const previewBodyRef = ref<HTMLElement | null>(null)

useSwipe(previewBodyRef, {
  threshold: 60,
  onSwipeEnd(_e, direction) {
    if (!isMobile.value || imageMagnified.value || videoPlaying.value || editing.value) return
    if (preview.value?.kind === 'AUDIO' && useGlobalAudio.value && player.playing) return
    if (direction === 'left' && hasNext.value) void guardedNext()
    else if (direction === 'right' && hasPrev.value) void guardedPrev()
  },
})

// ---------- 邻居预取:当前图就位后,把前后各一张提前拉进浏览器缓存 ----------
// 只在 IMAGE 上做(视频/PDF 抢带宽会让正在看的内容变卡),且必须等当前这张加载完
// —— 预签名只有 15 分钟寿命,提前太久拿到的 URL 等用户翻过去已经过期了。

const { run: runPrefetch, clear: clearPrefetch } = useNeighborPrefetch({
  ids: () => previewState.ids,
  index: () => previewState.index,
  currentKind: () => preview.value?.kind,
  fetchPreview: async (id) => (await req(NodePreviewDocument, { id })).node.preview,
  budget: () => prefetchBudget(currentConnection()),
})

watch([currentId, () => preview.value?.kind, isFetching], ([id, kind, fetching]) => {
  if (!id || fetching || kind !== 'IMAGE') {
    clearPrefetch()
    return
  }
  void runPrefetch()
})

// ---------- 快捷键 ----------

useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (!previewState.visible) return
  const tag = (e.target as HTMLElement | null)?.tagName
  if (e.key === 'Escape') {
    // 编辑器里有确认框开着时,Esc 交给确认框
    if (document.querySelector('.el-message-box')) return
    void guardedClose()
    return
  }
  // video/audio 聚焦时方向键留给播放器做快进
  if (tag === 'VIDEO' || tag === 'AUDIO' || tag === 'INPUT' || tag === 'TEXTAREA') return
  if (editing.value) return
  if (e.key === 'ArrowLeft') void guardedPrev()
  else if (e.key === 'ArrowRight') void guardedNext()
})

</script>

<template>
  <Teleport to="body">
    <Transition name="preview-fade">
      <div
        v-if="previewState.visible"
        class="preview-overlay"
        @click.self="!isMobile && !editing && guardedClose()"
      >
        <!-- 顶部栏 -->
        <header class="preview-header">
          <div class="preview-title" :title="node?.name">
            <span class="preview-name">{{ node?.name ?? '加载中…' }}</span>
            <span v-if="node && node.size !== null && node.size !== undefined" class="preview-size">
              {{ formatBytes(node.size) }}
            </span>
          </div>
          <div class="preview-actions">
            <el-button
              v-if="preview?.kind === 'IMAGE' && preview.contentUrl"
              :icon="View"
              text
              class="header-btn"
              @click="onViewOriginal"
            >
              查看原图
            </el-button>
            <el-button v-if="canEdit && !editing" :icon="EditPen" text class="header-btn" @click="editing = true">编辑</el-button>
            <el-button
              v-if="preview?.kind === 'TEXT' && preview.markdown && !editing"
              :icon="mdSource ? Reading : Document"
              text
              class="header-btn"
              :title="mdSource ? '渲染着看' : '看源文件'"
              @click="mdSource = !mdSource"
            >
              {{ mdSource ? '渲染' : '源文件' }}
            </el-button>
            <el-button
              v-if="nativeDownloads() && node && !isMobile && canPlayExternal(preview?.kind)"
              :icon="VideoPlay"
              text
              class="header-btn"
              title="用电脑上的播放器边下边放"
              @click="playWithLocalPlayer(node)"
            >
              播放器
            </el-button>
            <el-button
              v-else-if="nativeDownloads() && node && !isMobile"
              :icon="Monitor"
              text
              class="header-btn"
              title="用电脑上的程序打开,存盘后自动传回新版本"
              @click="openWithLocalApp(node)"
            >
              本地打开
            </el-button>
            <el-button
              :icon="Download"
              text
              class="header-btn"
              :loading="downloading"
              @click="onDownload"
            >
              下载
            </el-button>
            <el-button :icon="Close" text circle class="header-btn" title="关闭 (Esc)" @click="guardedClose()" />
          </div>
        </header>

        <!-- 左右切换 -->
        <button
          v-if="hasPrev && !isMobile && !editing"
          class="nav-btn nav-prev"
          title="上一个 (←)"
          @click="guardedPrev()"
        >
          <el-icon :size="22"><ArrowLeft /></el-icon>
        </button>
        <button
          v-if="hasNext && !isMobile && !editing"
          class="nav-btn nav-next"
          title="下一个 (→)"
          @click="guardedNext()"
        >
          <el-icon :size="22"><ArrowRight /></el-icon>
        </button>

        <!-- 内容区:key=当前节点,切换即整体重建,播放器/PDF 状态不串 -->
        <main :key="currentId ?? 'none'" ref="previewBodyRef" class="preview-body">
          <div v-if="initialLoading" v-loading="true" class="preview-loading" element-loading-background="transparent" />

          <el-empty
            v-else-if="isError"
            class="preview-placeholder"
            :description="errorText(error, '加载预览信息失败')"
          />

          <!-- 在线编辑 -->
          <div v-else-if="editing && node" class="preview-editor">
            <TextEditor
              ref="editorRef"
              :node-id="node.id"
              :markdown="/\.(md|markdown)$/i.test(node.name)"
              @saved="onEditorSaved"
              @close="editing = false"
            />
          </div>

          <!-- IMAGE:滚轮缩放 / 拖拽平移 / 双击复位 -->
          <ImageViewer
            v-else-if="preview?.kind === 'IMAGE' && (preview.largeUrl || preview.contentUrl)"
            v-model:magnified="imageMagnified"
            :src="(preview.largeUrl ?? preview.contentUrl)!"
            :alt="node?.name"
          />

          <!-- VIDEO:外挂 / 内嵌字幕、续播、下一集、签名过期重取;mkv 等先转成 mp4(doc/31 一) -->
          <VideoPlayer
            v-else-if="(videoSource?.kind === 'direct' || videoSource?.kind === 'remux') && node"
            :key="`${node.id}:${videoSource.kind}`"
            :node-id="node.id"
            :src="videoSource.url"
            :poster="preview?.largeUrl"
            :subtitles="videoSubtitles"
            :token="mediaToken"
            :progress="node.playProgress"
            :track-progress="!props.guestToken"
            :refresh="refreshContentUrl"
            :next-name="nextVideo?.name ?? null"
            @playing="(v) => (videoPlaying = v)"
            @next="playNextVideo"
            @download="onDownload"
            :external="nativeDownloads()"
            @unsupported="onVideoUnsupported"
            @play-external="playWithLocalPlayer(node)"
          />
          <div v-else-if="videoSource?.kind === 'wait'" class="preview-placeholder video-remux">
            <p class="remux-title">
              {{ remux?.status === 'RUNNING' ? '正在转成浏览器能放的格式' : '排队等转换' }}
            </p>
            <el-progress
              class="remux-bar"
              :percentage="remuxPercent ?? 100"
              :indeterminate="remuxPercent === null"
              :show-text="remuxPercent !== null"
              :stroke-width="6"
            />
            <div class="placeholder-actions">
              <el-button v-if="nativeDownloads() && node" size="small" :icon="VideoPlay" @click="playWithLocalPlayer(node)">
                用播放器播放
              </el-button>
              <el-button size="small" :icon="Download" :loading="downloading" @click="onDownload">下载</el-button>
            </div>
          </div>
          <div v-else-if="videoSource?.kind === 'problem'" class="preview-placeholder video-remux">
            <el-icon :size="40" class="failed-icon"><WarningFilled /></el-icon>
            <p>{{ remux?.message || '浏览器放不了这个视频' }}</p>
            <div class="placeholder-actions">
              <el-button v-if="remux?.status === 'FAILED'" size="small" @click="retryRemux">重试</el-button>
              <el-button
                v-if="nativeDownloads() && node"
                size="small"
                type="primary"
                :icon="VideoPlay"
                @click="playWithLocalPlayer(node)"
              >
                用播放器播放
              </el-button>
              <el-button size="small" :type="nativeDownloads() ? 'default' : 'primary'" :icon="Download" :loading="downloading" @click="onDownload">
                下载
              </el-button>
            </div>
          </div>

          <!-- AUDIO:登录用户绑全站播放器 -->
          <AudioPanel
            v-else-if="preview?.kind === 'AUDIO' && preview.contentUrl && useGlobalAudio"
            @download="onDownload"
          />
          <div v-else-if="preview?.kind === 'AUDIO' && preview.contentUrl" class="preview-audio">
            <el-icon :size="88" class="audio-icon"><Headset /></el-icon>
            <div v-if="formatDuration(preview.durationSec)" class="audio-duration">
              时长 {{ formatDuration(preview.durationSec) }}
            </div>
            <audio controls autoplay :src="preview.contentUrl" class="audio-player" />
          </div>

          <!-- PDF 原生 -->
          <PdfViewer
            v-else-if="preview?.kind === 'PDF' && preview.contentUrl"
            :url="preview.contentUrl"
            :initial-page="pdfInitialPage"
            @page="onPdfPage"
          />

          <!-- Markdown:服务端渲染,沙箱 iframe;剪藏的顶上给来源 -->
          <div v-else-if="showMarkdown && node" class="preview-md">
            <div v-if="clip" class="md-clip-bar">
              <span class="clip-site">{{ siteOf(clip) || '剪藏' }}</span>
              <a class="clip-link" :href="clip.url" target="_blank" rel="noopener noreferrer">原网页</a>
              <el-button link size="small" class="clip-read" @click="toggleClipRead">
                {{ clip.readAt ? '标为未读' : '标记已读' }}
              </el-button>
            </div>
            <MarkdownFrame :key="editVersion" :node-id="node.id" :token="mediaToken" class="md-body" />
          </div>

          <!-- TEXT -->
          <div v-else-if="preview?.kind === 'TEXT'" class="preview-text" v-loading="textLoading">
            <el-empty v-if="textError" :description="`文本加载失败:${textError}`" />
            <template v-else>
              <pre class="text-pre">{{ textContent }}</pre>
              <div v-if="textTruncated" class="text-truncated">
                内容过长,仅显示前 {{ TEXT_MAX_CHARS.toLocaleString() }} 字符,完整内容请下载查看
              </div>
            </template>
          </div>

          <!-- OFFICE:访客无产物 / 转换中 / 完成(按 PDF 渲染)/ 失败 -->
          <div v-else-if="officeUnavailable" class="preview-placeholder">
            <el-empty :description="officeUnavailableText">
              <el-button type="primary" :icon="Download" :loading="downloading" @click="onDownload">
                下载文件
              </el-button>
            </el-empty>
          </div>
          <div v-else-if="officeConverting" class="preview-office-pending">
            <el-skeleton :rows="6" animated class="office-skeleton" />
            <p class="office-hint">文档转换中,请稍候…</p>
          </div>
          <PdfViewer
            v-else-if="preview?.kind === 'OFFICE' && preview.status === 'DONE' && preview.contentUrl"
            :url="preview.contentUrl"
            :initial-page="currentId ? previewState.pages[currentId] : undefined"
          />
          <div v-else-if="preview?.kind === 'OFFICE' && preview.status === 'FAILED'" class="preview-placeholder office-failed">
            <el-icon :size="48" class="failed-icon"><WarningFilled /></el-icon>
            <p>文档转换失败</p>
            <div class="placeholder-actions">
              <el-button v-if="!props.guestToken" size="small" @click="retryOffice">重试转换</el-button>
              <el-button size="small" type="primary" :icon="Download" :loading="downloading" @click="onDownload">
                下载原文件
              </el-button>
            </div>
          </div>

          <!-- ARCHIVE:包内浏览、预览、解压 -->
          <ArchiveViewer
            v-else-if="preview?.kind === 'ARCHIVE' && node"
            :node-id="node.id"
            :parent-id="node.parentId"
            :guest-token="props.guestToken"
            @download="onDownload"
          />

          <!-- NONE / 兜底 -->
          <div v-else class="preview-placeholder">
            <el-empty description="该类型暂不支持预览">
              <el-button v-if="nativeDownloads() && node" type="primary" :icon="Monitor" @click="openWithLocalApp(node)">
                用本地程序打开
              </el-button>
              <el-button :type="nativeDownloads() ? 'default' : 'primary'" :icon="Download" :loading="downloading" @click="onDownload">
                下载文件
              </el-button>
            </el-empty>
          </div>
        </main>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 2500;
  display: flex;
  flex-direction: column;
  background: rgba(0, 0, 0, 0.82);
  backdrop-filter: blur(2px);
}
.preview-fade-enter-active,
.preview-fade-leave-active {
  transition: opacity 0.15s ease;
}
.preview-fade-enter-from,
.preview-fade-leave-to {
  opacity: 0;
}

/* ---------- 顶部栏 ---------- */
.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 56px;
  padding: 0 16px 0 20px;
  flex: none;
}
.preview-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.preview-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 48vw;
  color: #f3f4f6;
  font-size: 15px;
  font-weight: 500;
}
.preview-size {
  color: #9ca3af;
  font-size: 12px;
  flex: none;
}
.preview-actions {
  display: flex;
  align-items: center;
  flex: none;
}
.header-btn {
  color: #e5e7eb;
}
.header-btn:hover {
  color: #fff;
}

/* ---------- 左右切换 ---------- */
.nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  color: #f3f4f6;
  cursor: pointer;
  transition: background 0.15s ease;
}
.nav-btn:hover {
  background: rgba(255, 255, 255, 0.24);
}
.nav-prev {
  left: 20px;
}
.nav-next {
  right: 20px;
}

/* ---------- 手机 ---------- */
@media (max-width: 767px) {
  .preview-header {
    height: 48px;
    padding: var(--sat) 8px 0 12px;
    gap: 8px;
  }
  .header-btn :deep(span) {
    display: none; /* 「查看原图 / 下载」文字藏掉,只留图标 */
  }
  .header-btn :deep(.el-icon) {
    margin: 0;
  }
}

/* ---------- 内容区 ---------- */
.preview-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 76px 24px;
}

/* 手机:两侧没有导航按钮了,内边距收窄;底部留安全区 */
@media (max-width: 767px) {
  .preview-body {
    padding: 8px 12px calc(12px + var(--sab));
  }
}
.preview-loading {
  width: 200px;
  height: 200px;
}
.preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 4px;
}
.preview-audio {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 32px 48px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
}
.audio-icon {
  color: #9ca3af;
}
.audio-duration {
  color: #d1d5db;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.audio-player {
  width: min(420px, 70vw);
}
.preview-text {
  width: min(860px, 100%);
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-radius: 8px;
  background: #1f2937;
  overflow: hidden;
}
.preview-md {
  width: min(900px, 100%);
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-bg-color);
}
.md-clip-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
  padding: 6px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.clip-site {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.clip-link {
  flex: none;
  color: var(--el-color-primary);
  text-decoration: none;
}
.clip-read {
  margin-left: auto;
}
.md-body {
  flex: 1;
  min-height: 0;
}
.text-pre {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 16px 20px;
  overflow: auto;
  color: #e5e7eb;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.text-truncated {
  flex: none;
  padding: 8px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #fbbf24;
  font-size: 12px;
}
.preview-office-pending {
  width: min(720px, 100%);
  padding: 32px 40px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
}
.office-hint {
  margin: 16px 0 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.preview-placeholder {
  color: #d1d5db;
}
.preview-placeholder :deep(.el-empty__description p) {
  color: #d1d5db;
}
.office-failed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  font-size: 14px;
}
.video-remux {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  font-size: 14px;
}
.video-remux p {
  margin: 0;
}
.remux-bar {
  width: min(280px, 70vw);
}
.remux-bar :deep(.el-progress__text) {
  color: #d1d5db;
}
.failed-icon {
  color: var(--el-color-warning);
}
.placeholder-actions {
  display: flex;
  gap: 8px;
}
.preview-editor {
  width: 100%;
  height: 100%;
  padding: 12px 64px 16px;
}
@media (max-width: 767px) {
  .preview-editor {
    padding: 0;
  }
}
</style>
