<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Close,
  Download,
  Headset,
  View,
  WarningFilled,
} from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  NodeDownloadUrlDocument,
  NodePreviewDocument,
  RequestPreviewDocument,
} from '@/api/gen/graphql'
import { formatBytes } from '@/utils/format'
import { closePreview, previewNext, previewPrev, previewState } from '@/composables/preview'

// pdfjs-dist 体积大(~1MB),按需异步加载,只有真正预览 PDF 时才拉
const PdfViewer = defineAsyncComponent(() => import('./PdfViewer.vue'))

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
  queryFn: () => request(NodePreviewDocument, { id: currentId.value! }),
  staleTime: 0,
  gcTime: 0,
  retry: 1,
  // OFFICE 转换中每 2 秒轮询;模态关闭 → enabled=false 自动停
  refetchInterval: (query) => {
    const p = query.state.data?.node.preview
    if (p?.kind === 'OFFICE' && (p.status === 'PENDING' || p.status === 'RUNNING')) return 2000
    return false
  },
})

const node = computed(() => data.value?.node ?? null)
const preview = computed(() => node.value?.preview ?? null)

/** 初次拿到元数据前的整体 loading(轮询中的 refetch 不算) */
const initialLoading = computed(() => isFetching.value && !data.value)

// ---------- OFFICE:status 为空说明从未触发转换,自动触发一次 ----------

const requestedOffice = new Set<string>()

const requestPreviewMutation = useMutation({
  mutationFn: (vars: { nodeId: string }) => request(RequestPreviewDocument, vars),
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

const officeConverting = computed(() => {
  const p = preview.value
  if (p?.kind !== 'OFFICE') return false
  // status 为空 = 刚要触发/触发请求在途,也按转换中展示
  return !p.status || p.status === 'PENDING' || p.status === 'RUNNING'
})

// ---------- TEXT:拉取文本内容 ----------

const TEXT_MAX_CHARS = 200_000

const textContent = ref('')
const textTruncated = ref(false)
const textLoading = ref(false)
const textError = ref<string | null>(null)

watch(
  [preview, currentId],
  async ([p, id]) => {
    if (!p || !id || p.kind !== 'TEXT' || !p.contentUrl) return
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
  downloading.value = true
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
  } finally {
    downloading.value = false
  }
}

function onViewOriginal() {
  const url = preview.value?.contentUrl
  if (url) window.open(url, '_blank', 'noopener')
}

// ---------- 快捷键 ----------

useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (!previewState.visible) return
  const tag = (e.target as HTMLElement | null)?.tagName
  if (e.key === 'Escape') {
    closePreview()
    return
  }
  // video/audio 聚焦时方向键留给播放器做快进
  if (tag === 'VIDEO' || tag === 'AUDIO' || tag === 'INPUT' || tag === 'TEXTAREA') return
  if (e.key === 'ArrowLeft') previewPrev()
  else if (e.key === 'ArrowRight') previewNext()
})

// ---------- 展示辅助 ----------

function formatDuration(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || sec < 0) return ''
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
</script>

<template>
  <Teleport to="body">
    <Transition name="preview-fade">
      <div v-if="previewState.visible" class="preview-overlay" @click.self="closePreview()">
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
            <el-button
              :icon="Download"
              text
              class="header-btn"
              :loading="downloading"
              @click="onDownload"
            >
              下载
            </el-button>
            <el-button :icon="Close" text circle class="header-btn" title="关闭 (Esc)" @click="closePreview()" />
          </div>
        </header>

        <!-- 左右切换 -->
        <button
          v-if="hasPrev"
          class="nav-btn nav-prev"
          title="上一个 (←)"
          @click="previewPrev()"
        >
          <el-icon :size="22"><ArrowLeft /></el-icon>
        </button>
        <button
          v-if="hasNext"
          class="nav-btn nav-next"
          title="下一个 (→)"
          @click="previewNext()"
        >
          <el-icon :size="22"><ArrowRight /></el-icon>
        </button>

        <!-- 内容区:key=当前节点,切换即整体重建,播放器/PDF 状态不串 -->
        <main :key="currentId ?? 'none'" class="preview-body">
          <div v-if="initialLoading" v-loading="true" class="preview-loading" element-loading-background="transparent" />

          <el-empty
            v-else-if="isError"
            class="preview-placeholder"
            :description="errorText(error, '加载预览信息失败')"
          />

          <!-- IMAGE -->
          <img
            v-else-if="preview?.kind === 'IMAGE' && (preview.largeUrl || preview.contentUrl)"
            class="preview-image"
            :src="preview.largeUrl ?? preview.contentUrl ?? undefined"
            :alt="node?.name"
          />

          <!-- VIDEO -->
          <video
            v-else-if="preview?.kind === 'VIDEO' && preview.contentUrl"
            class="preview-video"
            controls
            autoplay
            :poster="preview.largeUrl ?? undefined"
            :src="preview.contentUrl"
          />

          <!-- AUDIO -->
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
          />

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

          <!-- OFFICE:转换中 / 完成(按 PDF 渲染)/ 失败 -->
          <div v-else-if="officeConverting" class="preview-office-pending">
            <el-skeleton :rows="6" animated class="office-skeleton" />
            <p class="office-hint">文档转换中,请稍候…</p>
          </div>
          <PdfViewer
            v-else-if="preview?.kind === 'OFFICE' && preview.status === 'DONE' && preview.contentUrl"
            :url="preview.contentUrl"
          />
          <div v-else-if="preview?.kind === 'OFFICE' && preview.status === 'FAILED'" class="preview-placeholder office-failed">
            <el-icon :size="48" class="failed-icon"><WarningFilled /></el-icon>
            <p>文档转换失败</p>
            <div class="placeholder-actions">
              <el-button size="small" @click="retryOffice">重试转换</el-button>
              <el-button size="small" type="primary" :icon="Download" :loading="downloading" @click="onDownload">
                下载原文件
              </el-button>
            </div>
          </div>

          <!-- NONE / 兜底 -->
          <div v-else class="preview-placeholder">
            <el-empty description="该类型暂不支持预览">
              <el-button type="primary" :icon="Download" :loading="downloading" @click="onDownload">
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

/* ---------- 内容区 ---------- */
.preview-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 76px 24px;
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
.preview-video {
  max-width: 100%;
  max-height: 100%;
  outline: none;
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
.failed-icon {
  color: var(--el-color-warning);
}
.placeholder-actions {
  display: flex;
  gap: 8px;
}
</style>
