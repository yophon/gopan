<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query'
import { ArrowLeft, Document, Download, Folder, FolderOpened } from '@element-plus/icons-vue'
import type { Variables } from '@/api/transport'
import type { DocumentTypeDecoration } from '@graphql-typed-document-node/core'

import { guestRequest, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ArchiveEntriesDocument, ArchiveInfoDocument } from '@/api/gen/graphql'
import type { ArchiveEntriesQuery } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/breakpoints'
import { useExtract } from '@/composables/extract'
import { archiveCrumbs, archiveEntryUrl, entryPreviewKind, flagText } from '@/utils/archive'
import { formatBytes, formatTime } from '@/utils/format'
import MoveDialog from '@/components/MoveDialog.vue'
import ImageViewer from './ImageViewer.vue'

const PdfViewer = defineAsyncComponent(() => import('./PdfViewer.vue'))

type Entry = ArchiveEntriesQuery['archiveEntries']['items'][number]

/**
 * 压缩包浏览(doc/21 四.3):不下载就看目录、预览包里的图片 / PDF / 文本、单独下载一个文件;
 * 属主还能整包或勾选解压到网盘。分享访客只读(带 guestToken)。
 */
const props = defineProps<{ nodeId: string; parentId?: string | null; guestToken?: string }>()
const emit = defineEmits<{ download: [] }>()

const auth = useAuthStore()
const { isMobile } = useBreakpoints()
const { extract } = useExtract()
const isOwner = computed(() => !props.guestToken)

function req<TResult, TVariables extends Variables>(
  document: DocumentTypeDecoration<TResult, TVariables>,
  variables?: TVariables,
): Promise<TResult> {
  return props.guestToken ? guestRequest(document, props.guestToken, variables) : request(document, variables)
}

// ---------- 概要:还在读目录时每 2 秒轮询 ----------

const infoQuery = useQuery({
  queryKey: computed(() => ['archiveInfo', props.nodeId]),
  queryFn: () => req(ArchiveInfoDocument, { nodeId: props.nodeId }),
  staleTime: 0,
  gcTime: 0,
  refetchInterval: (q) => (q.state.data?.archiveInfo.status === 'PENDING' ? 2000 : false),
})
const info = computed(() => infoQuery.data.value?.archiveInfo ?? null)
const ready = computed(() => info.value?.status === 'DONE' || info.value?.status === 'TRUNCATED')

// ---------- 当前目录 ----------

const dir = ref('')
const crumbs = computed(() => archiveCrumbs(dir.value))
const entriesQuery = useInfiniteQuery({
  queryKey: computed(() => ['archiveEntries', props.nodeId, dir.value]),
  enabled: ready,
  queryFn: ({ pageParam }) => req(ArchiveEntriesDocument, { nodeId: props.nodeId, path: dir.value, cursor: pageParam }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.archiveEntries.nextCursor ?? null,
  staleTime: 60_000,
})
const items = computed<Entry[]>(() => entriesQuery.data.value?.pages.flatMap((p) => p.archiveEntries.items) ?? [])
const total = computed(() => entriesQuery.data.value?.pages[0]?.archiveEntries.total ?? 0)

const selected = ref<string[]>([])
watch(dir, () => {
  selected.value = []
})

function goto(path: string) {
  dir.value = path
}

function onRow(e: Entry) {
  if (e.isDir) {
    goto(e.path)
    return
  }
  if (e.previewable) void openEntry(e)
  else if (e.downloadable) void downloadEntry(e)
}

function toggle(e: Entry) {
  selected.value = selected.value.includes(e.path)
    ? selected.value.filter((p) => p !== e.path)
    : [...selected.value, e.path]
}

// ---------- 单个文件 ----------

/** 拼地址前拿一个新鲜的 token:登录用户顺手发个请求,过期的会在这一步自动刷新 */
async function token(): Promise<string> {
  if (props.guestToken) return props.guestToken
  await request(ArchiveInfoDocument, { nodeId: props.nodeId })
  return auth.accessToken ?? ''
}

interface Viewing {
  entry: Entry
  kind: 'image' | 'pdf' | 'text'
  url?: string
  text?: string
  loading: boolean
  error?: string
}
const viewing = ref<Viewing | null>(null)
let objectUrl: string | null = null

function releaseObjectUrl() {
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = null
}
onBeforeUnmount(releaseObjectUrl)

async function readError(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string }
    return body.message || `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}

async function openEntry(e: Entry) {
  const kind = entryPreviewKind(e.name) ?? 'text'
  releaseObjectUrl()
  viewing.value = { entry: e, kind, loading: true }
  try {
    const res = await fetch(archiveEntryUrl(props.nodeId, e.idx, await token()))
    if (!res.ok) throw new Error(await readError(res))
    if (viewing.value?.entry.idx !== e.idx) return
    if (kind === 'text') {
      const raw = await res.text()
      viewing.value = { entry: e, kind, text: raw.length > 200_000 ? raw.slice(0, 200_000) : raw, loading: false }
    } else {
      objectUrl = URL.createObjectURL(await res.blob())
      viewing.value = { entry: e, kind, url: objectUrl, loading: false }
    }
  } catch (err) {
    if (viewing.value?.entry.idx === e.idx) {
      viewing.value = { entry: e, kind, loading: false, error: err instanceof Error ? err.message : '加载失败' }
    }
  }
}

function closeEntry() {
  releaseObjectUrl()
  viewing.value = null
}

async function downloadEntry(e: Entry) {
  const a = document.createElement('a')
  a.href = archiveEntryUrl(props.nodeId, e.idx, await token(), true)
  a.download = e.name
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

// ---------- 解压(只有属主) ----------

const extractLabel = computed(() => {
  if (selected.value.length > 0) return `解压选中的 ${selected.value.length} 项`
  return dir.value ? '解压此目录' : '全部解压'
})
const pickTarget = ref(false)
const extracting = ref(false)

async function doExtract(parentId: string | null) {
  extracting.value = true
  const ok = await extract({ nodeId: props.nodeId, base: dir.value, paths: selected.value, parentId })
  extracting.value = false
  if (ok) selected.value = []
}
</script>

<template>
  <div class="archive-viewer">
    <!-- 包里的单个文件 -->
    <div v-if="viewing" class="entry-view">
      <div class="entry-bar">
        <el-button :icon="ArrowLeft" text @click="closeEntry">返回目录</el-button>
        <span class="entry-name" :title="viewing.entry.path">{{ viewing.entry.name }}</span>
        <el-button
          v-if="viewing.entry.downloadable"
          :icon="Download"
          text
          @click="downloadEntry(viewing.entry)"
        >
          下载
        </el-button>
      </div>
      <div class="entry-body" v-loading="viewing.loading" element-loading-background="transparent">
        <el-empty v-if="viewing.error" :description="viewing.error" />
        <ImageViewer v-else-if="viewing.kind === 'image' && viewing.url" :src="viewing.url" :alt="viewing.entry.name" />
        <PdfViewer v-else-if="viewing.kind === 'pdf' && viewing.url" :url="viewing.url" />
        <pre v-else-if="viewing.kind === 'text' && !viewing.loading" class="entry-text">{{ viewing.text }}</pre>
      </div>
    </div>

    <template v-else>
      <div v-if="infoQuery.isError.value" class="state">
        <el-empty :description="errorText(infoQuery.error.value, '读取压缩包失败')" />
      </div>
      <div v-else-if="!info || info.status === 'PENDING'" class="state">
        <div v-loading="true" class="spinner" element-loading-background="transparent" />
        <p>正在读取压缩包目录…</p>
      </div>
      <div v-else-if="!ready" class="state">
        <el-empty :description="info.error || '这个压缩包打不开'">
          <el-button type="primary" :icon="Download" @click="emit('download')">下载压缩包</el-button>
        </el-empty>
      </div>

      <template v-else>
        <div class="toolbar">
          <el-breadcrumb separator="/" class="crumbs">
            <el-breadcrumb-item>
              <a class="crumb" @click="goto('')">{{ info.format.toUpperCase() }}</a>
            </el-breadcrumb-item>
            <el-breadcrumb-item v-for="c in crumbs" :key="c.path">
              <a class="crumb" @click="goto(c.path)">{{ c.name }}</a>
            </el-breadcrumb-item>
          </el-breadcrumb>
          <span class="summary">{{ info.fileCount }} 个文件 · 解压后 {{ formatBytes(info.totalSize) }}</span>
          <span v-if="isOwner" class="actions">
            <el-button type="primary" size="small" :loading="extracting" @click="doExtract(props.parentId ?? null)">
              {{ extractLabel }}
            </el-button>
            <el-button size="small" :icon="FolderOpened" :disabled="extracting" @click="pickTarget = true">
              解压到…
            </el-button>
          </span>
        </div>
        <el-alert
          v-if="info.status === 'TRUNCATED'"
          type="warning"
          :closable="false"
          show-icon
          title="条目太多或压缩包太大,只列出了一部分"
          class="notice"
        />
        <el-alert
          v-if="info.encrypted"
          type="info"
          :closable="false"
          show-icon
          title="有文件加密了:能看到名字,但不能预览和解压"
          class="notice"
        />

        <div class="list" v-loading="entriesQuery.isFetching.value && !entriesQuery.isFetchingNextPage.value">
          <el-alert
            v-if="entriesQuery.isError.value"
            type="error"
            :closable="false"
            :title="errorText(entriesQuery.error.value, '加载失败')"
          />
          <div
            v-for="e in items"
            :key="e.idx"
            class="row"
            :class="{ disabled: !e.isDir && !e.previewable && !e.downloadable }"
            @click="onRow(e)"
          >
            <el-checkbox
              v-if="isOwner"
              :model-value="selected.includes(e.path)"
              class="check"
              :disabled="!!e.flag"
              @click.stop
              @change="toggle(e)"
            />
            <el-icon class="icon" :class="{ folder: e.isDir }"><Folder v-if="e.isDir" /><Document v-else /></el-icon>
            <span class="name" :title="e.path">{{ e.name }}</span>
            <el-tag v-if="e.flag" size="small" type="warning" class="flag">{{ flagText(e.flag) }}</el-tag>
            <span v-if="!e.isDir" class="size">{{ formatBytes(e.size) }}</span>
            <span v-if="!isMobile" class="time">{{ e.modifiedAt ? formatTime(e.modifiedAt) : '' }}</span>
            <el-button
              v-if="!e.isDir && e.downloadable"
              :icon="Download"
              link
              size="small"
              title="下载这个文件"
              @click.stop="downloadEntry(e)"
            />
          </div>
          <el-empty v-if="!entriesQuery.isFetching.value && items.length === 0" description="空目录" />
          <div v-if="entriesQuery.hasNextPage.value" class="more">
            <el-button :loading="entriesQuery.isFetchingNextPage.value" @click="entriesQuery.fetchNextPage()">
              加载更多({{ items.length }} / {{ total }})
            </el-button>
          </div>
        </div>
      </template>
    </template>

    <MoveDialog v-if="isOwner" v-model="pickTarget" mode="extract" :exclude-ids="[]" @confirm="doExtract" />
  </div>
</template>

<style scoped>
.archive-viewer {
  width: min(960px, 100%);
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  overflow: hidden;
}
.state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
}
.spinner {
  width: 60px;
  height: 60px;
}
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.crumbs {
  flex: 1;
  min-width: 0;
}
.crumb {
  cursor: pointer;
}
.summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.actions {
  display: inline-flex;
  gap: 6px;
}
.notice {
  margin: 8px 14px 0;
  width: auto;
}
.list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 6px 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.row:hover {
  background: var(--el-fill-color-light);
}
.row.disabled {
  cursor: default;
  color: var(--el-text-color-secondary);
}
.check {
  height: auto;
}
.icon {
  flex: none;
  color: var(--el-text-color-secondary);
}
.icon.folder {
  color: var(--el-color-warning);
}
.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.flag {
  flex: none;
}
.size,
.time {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.time {
  width: 120px;
  text-align: right;
}
.more {
  display: flex;
  justify-content: center;
  padding: 10px;
}
.entry-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.entry-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.entry-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.entry-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
  background: #1f2937;
}
.entry-text {
  align-self: stretch;
  flex: 1;
  margin: 0;
  padding: 16px 20px;
  overflow: auto;
  color: #e5e7eb;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
