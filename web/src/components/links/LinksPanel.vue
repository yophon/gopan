<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, MoreFilled, Picture, VideoPause, VideoPlay, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { MyLinksDocument, PauseLinkDocument, RevokeLinkDocument } from '@/api/gen/graphql'
import type { DirectLinkFieldsFragment } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { uploadImage } from '@/composables/imgUpload'
import { ClipboardError, showManualCopy, writeClipboard } from '@/composables/links'
import { formatBytes, formatRelative } from '@/utils/format'
import { imageUploadError } from '@/utils/imgUpload'
import { absoluteLinkUrl, formatLink, linkFormatLabels, linkStatusMeta } from '@/utils/links'
import type { LinkFormat } from '@/utils/links'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import type { SheetItem } from '@/components/nodes/types'
import ImageKeysPanel from './ImageKeysPanel.vue'
import LinkDetailDrawer from './LinkDetailDrawer.vue'
import LinkPrefsPanel from './LinkPrefsPanel.vue'
import LinkThumb from './LinkThumb.vue'

type Item = DirectLinkFieldsFragment

/**
 * 「分享」页的「直链」标签(doc/22 四.4):我的直链、上传接口(图床密钥与配置模板)、默认规则。
 * 在这个标签里粘贴截图或拖入图片 = 传进图床文件夹并复制 Markdown。
 * openId / panel 来自地址(通知、推送链接直达),用完 emit consumed 让页面清掉。
 */
const props = defineProps<{ openId?: string | null; panel?: string | null }>()
const emit = defineEmits<{ consumed: [] }>()

const queryClient = useQueryClient()
const { isMobile, isCompact } = useBreakpoints()
const origin = location.origin

const section = ref<'list' | 'keys' | 'prefs'>('list')

// ---------- 列表 ----------

const query = useInfiniteQuery({
  queryKey: ['myLinks'],
  queryFn: ({ pageParam }) => request(MyLinksDocument, { cursor: pageParam }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.myLinks.nextCursor ?? null,
})
const items = computed<Item[]>(() => query.data.value?.pages.flatMap((p) => p.myLinks.items) ?? [])
const total = computed(() => query.data.value?.pages[0]?.myLinks.total ?? 0)

function invalidate() {
  void queryClient.invalidateQueries({ queryKey: ['myLinks'] })
}

function asItem(row: unknown): Item {
  return row as Item
}

async function copy(row: Item, fmt: LinkFormat) {
  try {
    await writeClipboard(Promise.resolve(formatLink(row, fmt, origin)))
    ElMessage.success(`已复制(${linkFormatLabels[fmt]})`)
  } catch (err) {
    if (err instanceof ClipboardError) showManualCopy(err.text)
  }
}

function usageText(u: { hits: number; bytes: number }): string {
  return u.hits ? `${u.hits} 次 · ${formatBytes(u.bytes)}` : '—'
}

// ---------- 详情抽屉 ----------

const detail = ref<{ visible: boolean; id: string | null }>({ visible: false, id: null })

function openDetail(id: string) {
  detail.value = { visible: true, id }
}

watch(
  () => [props.openId, props.panel] as const,
  ([id, panel]) => {
    if (!id && !panel) return
    if (panel === 'keys') section.value = 'keys'
    else if (panel === 'prefs') section.value = 'prefs'
    if (id) {
      section.value = 'list'
      openDetail(id)
    }
    emit('consumed')
  },
  { immediate: true },
)

// ---------- 手机:操作菜单 ----------

const sheet = ref<{ visible: boolean; row: Item | null }>({ visible: false, row: null })
const sheetItems = computed<SheetItem[]>(() => {
  const row = sheet.value.row
  if (!row) return []
  return [
    { key: 'detail', label: '详情与统计', icon: View },
    { key: 'url', label: '复制链接', icon: CopyDocument },
    { key: 'markdown', label: '复制 Markdown' },
    { key: 'html', label: '复制 HTML' },
    { key: 'bbcode', label: '复制 BBCode' },
    {
      key: 'pause',
      label: row.status === 'PAUSED' ? '恢复' : '暂停',
      icon: row.status === 'PAUSED' ? VideoPlay : VideoPause,
      disabled: row.status === 'EXPIRED',
    },
    { key: 'revoke', label: '吊销', icon: Delete, danger: true },
  ]
})

async function togglePause(row: Item) {
  try {
    await request(PauseLinkDocument, { id: row.id, paused: row.status !== 'PAUSED' })
    ElMessage.success(row.status === 'PAUSED' ? '已恢复' : '已暂停')
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  }
}

async function revoke(row: Item) {
  try {
    await ElMessageBox.confirm('吊销后这个地址立即失效(404),引用它的网页会裂图。文件本身不受影响。', `吊销「${row.name}」的直链?`, {
      confirmButtonText: '吊销',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await request(RevokeLinkDocument, { id: row.id })
    ElMessage.success('直链已吊销')
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '吊销失败'))
  }
}

function onSheetSelect(key: string) {
  const row = sheet.value.row
  if (!row) return
  if (key === 'detail') openDetail(row.id)
  else if (key === 'pause') void togglePause(row)
  else if (key === 'revoke') void revoke(row)
  else void copy(row, key as LinkFormat)
}

// ---------- 粘贴 / 拖入上传到图床 ----------

interface UploadItem {
  key: number
  name: string
  progress: number
  status: 'uploading' | 'done' | 'error'
  message?: string
  url?: string
}

const uploadsList = ref<UploadItem[]>([])
let uploadSeq = 0
const fileInput = ref<HTMLInputElement | null>(null)

function startUploads(files: File[]) {
  const images = files.filter((f) => f.type.startsWith('image/') || /\.(jpe?g|png|gif|webp|avif|bmp|svg)$/i.test(f.name))
  if (images.length === 0) {
    if (files.length) ElMessage.warning('只能传图片(jpg、png、gif、webp、avif、bmp、svg)')
    return
  }
  const jobs = images.map((file) => {
    const item: UploadItem = { key: ++uploadSeq, name: file.name || '剪贴板图片', progress: 0, status: 'uploading' }
    uploadsList.value = [item, ...uploadsList.value].slice(0, 20)
    const entry = () => uploadsList.value.find((u) => u.key === item.key)
    const problem = imageUploadError(file)
    if (problem) {
      Object.assign(entry() ?? item, { status: 'error', message: problem })
      return Promise.reject(new Error(problem))
    }
    return uploadImage(file, (r) => {
      const e = entry()
      if (e) e.progress = Math.round(r * 100)
    }).then(
      (res) => {
        Object.assign(entry() ?? item, { status: 'done', progress: 100, url: res.url, name: res.name })
        return res.markdown
      },
      (err: unknown) => {
        const message = err instanceof Error ? err.message : '上传失败'
        Object.assign(entry() ?? item, { status: 'error', message })
        throw err
      },
    )
  })
  // 全部传完后把成功的 Markdown 一次复制(一行一张);剪贴板要在这一拍里交出去
  const markdown = Promise.allSettled(jobs).then((results) => {
    const ok = results.filter((r): r is PromiseFulfilledResult<string> => r.status === 'fulfilled').map((r) => r.value)
    invalidate()
    if (ok.length === 0) throw new Error('none')
    return ok.join('\n')
  })
  writeClipboard(markdown).then(
    (text) => ElMessage.success(text.includes('\n') ? '已上传,Markdown 已复制(一行一张)' : '已上传,Markdown 已复制'),
    (err: unknown) => {
      if (err instanceof ClipboardError) showManualCopy(err.text, '已上传,复制 Markdown 失败,请手动复制')
      else ElMessage.error('没有图片传上去')
    },
  )
}

function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  startUploads(files)
}

useEventListener(document, 'paste', (e: ClipboardEvent) => {
  if (section.value !== 'list') return
  const target = e.target as HTMLElement | null
  // 往输入框里粘贴文字的不管
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return
  const files = Array.from(e.clipboardData?.files ?? []).filter((f) => f.type.startsWith('image/'))
  if (files.length === 0) return
  e.preventDefault()
  startUploads(files)
})

const dragDepth = ref(0)
function hasFiles(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes('Files')
}
function onDragEnter(e: DragEvent) {
  if (!hasFiles(e) || section.value !== 'list') return
  e.preventDefault()
  dragDepth.value++
}
function onDragOver(e: DragEvent) {
  if (hasFiles(e) && section.value === 'list') e.preventDefault()
}
function onDragLeave(e: DragEvent) {
  if (!hasFiles(e)) return
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}
function onDrop(e: DragEvent) {
  if (!hasFiles(e) || section.value !== 'list') return
  e.preventDefault()
  dragDepth.value = 0
  startUploads(Array.from(e.dataTransfer?.files ?? []))
}

async function copyUploaded(u: UploadItem) {
  if (!u.url) return
  try {
    await writeClipboard(Promise.resolve(absoluteLinkUrl(u.url, origin)))
    ElMessage.success('链接已复制')
  } catch (err) {
    if (err instanceof ClipboardError) showManualCopy(err.text)
  }
}
</script>

<template>
  <div
    class="links-panel"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <el-segmented
      v-model="section"
      :options="[
        { label: '我的直链', value: 'list' },
        { label: '上传接口', value: 'keys' },
        { label: '默认规则', value: 'prefs' },
      ]"
      class="section-tabs"
    />

    <template v-if="section === 'list'">
      <div class="drop-zone" :class="{ active: dragDepth > 0 }">
        <el-icon :size="22"><Picture /></el-icon>
        <span class="drop-text">
          {{ isMobile ? '选一张图片' : '在这里粘贴截图(Ctrl+V)或拖入图片' }},传进图床文件夹并复制 Markdown
        </span>
        <el-button size="small" @click="fileInput?.click()">选择图片</el-button>
        <input ref="fileInput" type="file" accept="image/*" multiple class="hidden-input" @change="onPickFiles" />
      </div>

      <ul v-if="uploadsList.length" class="uploads">
        <li v-for="u in uploadsList" :key="u.key" class="upload-item">
          <span class="up-name" :title="u.name">{{ u.name }}</span>
          <el-progress
            v-if="u.status === 'uploading'"
            :percentage="u.progress"
            :stroke-width="4"
            :show-text="false"
            class="up-bar"
          />
          <span v-else-if="u.status === 'error'" class="up-error">{{ u.message }}</span>
          <el-button v-else link type="primary" size="small" @click="copyUploaded(u)">复制链接</el-button>
        </li>
        <li class="up-clear">
          <el-button link size="small" @click="uploadsList = uploadsList.filter((u) => u.status === 'uploading')">
            清除记录
          </el-button>
        </li>
      </ul>

      <p class="intro">
        在文件上右键(手机上长按)选「复制直链」生成。直链是公开地址,任何拿到的人都能访问;每次访问都记账,超了每天的流量上限会自动暂停。
        <template v-if="total"> 共 {{ total }} 条。</template>
      </p>

      <el-table
        v-if="!isMobile"
        v-loading="query.isFetching.value && !query.data.value"
        :data="items"
        row-key="id"
        empty-text="还没有直链"
        class="links-table"
        @row-click="(row: Item) => openDetail(row.id)"
      >
        <el-table-column label="文件" min-width="240">
          <template #default="{ row }">
            <span class="file-cell">
              <LinkThumb :node-id="asItem(row).nodeId" :name="asItem(row).name" :mime="asItem(row).mime" />
              <span class="file-name" :title="asItem(row).name">{{ asItem(row).name }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!linkStatusMeta(asItem(row).status, asItem(row).pauseReason).detail"
              :content="linkStatusMeta(asItem(row).status, asItem(row).pauseReason).detail"
            >
              <el-tag size="small" :type="linkStatusMeta(asItem(row).status, asItem(row).pauseReason).type">
                {{ linkStatusMeta(asItem(row).status, asItem(row).pauseReason).label }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="今天" width="150">
          <template #default="{ row }">{{ usageText(asItem(row).today) }}</template>
        </el-table-column>
        <el-table-column label="30 天" width="160">
          <template #default="{ row }">{{ usageText(asItem(row).last30Days) }}</template>
        </el-table-column>
        <el-table-column v-if="!isCompact" label="最后访问" width="110">
          <template #default="{ row }">{{ asItem(row).lastHitAt ? formatRelative(asItem(row).lastHitAt) : '从未' }}</template>
        </el-table-column>
        <el-table-column label="复制" width="150" align="center">
          <template #default="{ row }">
            <span @click.stop>
              <el-button link type="primary" @click="copy(asItem(row), 'url')">链接</el-button>
              <el-button link type="primary" @click="copy(asItem(row), 'markdown')">Markdown</el-button>
            </span>
          </template>
        </el-table-column>
      </el-table>

      <ul v-else v-loading="query.isFetching.value && !query.data.value" class="cards">
        <li v-if="!items.length && !query.isFetching.value" class="empty">还没有直链</li>
        <li v-for="row in items" :key="row.id" class="card" @click="openDetail(row.id)">
          <LinkThumb :node-id="row.nodeId" :name="row.name" :mime="row.mime" :size="40" />
          <div class="card-main">
            <span class="card-name">{{ row.name }}</span>
            <span class="card-sub">
              <el-tag size="small" :type="linkStatusMeta(row.status, row.pauseReason).type">
                {{ linkStatusMeta(row.status, row.pauseReason).label }}
              </el-tag>
              今天 {{ usageText(row.today) }} · 30 天 {{ usageText(row.last30Days) }}
            </span>
          </div>
          <el-button link :icon="MoreFilled" aria-label="操作" @click.stop="sheet = { visible: true, row }" />
        </li>
      </ul>

      <div v-if="query.hasNextPage.value" class="more">
        <el-button link :loading="query.isFetchingNextPage.value" @click="query.fetchNextPage()">加载更多</el-button>
      </div>

      <div v-if="dragDepth > 0" class="drop-overlay">
        <div class="drop-hint">松开鼠标,传进图床文件夹</div>
      </div>
    </template>

    <ImageKeysPanel v-else-if="section === 'keys'" />
    <LinkPrefsPanel v-else />

    <NodeActionSheet
      v-model:visible="sheet.visible"
      :title="sheet.row?.name"
      :items="sheetItems"
      @select="onSheetSelect"
    />
    <LinkDetailDrawer v-model="detail.visible" :link-id="detail.id" />
  </div>
</template>

<style scoped>
.links-panel {
  position: relative;
  min-height: 300px;
}
.section-tabs {
  margin-bottom: 12px;
}
.intro {
  margin: 12px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.drop-zone {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.drop-zone.active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.drop-text {
  flex: 1;
  min-width: 0;
}
.hidden-input {
  display: none;
}
.uploads {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}
.upload-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
  font-size: 13px;
}
.up-name {
  flex: 0 1 280px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.up-bar {
  flex: 1;
  max-width: 240px;
}
.up-error {
  color: var(--el-color-danger);
  font-size: 12px;
}
.up-clear {
  padding-top: 2px;
}
.links-table :deep(.el-table__row) {
  cursor: pointer;
}
.file-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}
.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cards {
  margin: 0;
  padding: 0;
  list-style: none;
}
.card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.empty {
  padding: 32px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.more {
  padding: 8px 0;
  text-align: center;
}
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--el-color-primary);
  border-radius: 8px;
  background: color-mix(in srgb, var(--el-color-primary) 10%, transparent);
  pointer-events: none;
}
.drop-hint {
  padding: 16px 24px;
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--el-color-primary);
  box-shadow: var(--el-box-shadow-light);
}

@media (max-width: 767px) {
  .drop-zone {
    flex-wrap: wrap;
  }
}
</style>
