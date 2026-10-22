<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import {
  CircleCheckFilled,
  Close,
  Link,
  Refresh,
  UploadFilled,
  WarningFilled,
} from '@element-plus/icons-vue'

import { guestRequest, hasErrorCode, plainRequest } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  CollectAbortUploadDocument,
  CollectCompleteUploadDocument,
  CollectInitUploadDocument,
  CollectMyUploadsDocument,
  CollectUploadSessionDocument,
  FileRequestInfoDocument,
  OpenFileRequestDocument,
} from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { createCollectUploader } from '@/uploader/collect'
import type { CollectTask, CollectTransport } from '@/uploader/collect'

/**
 * 收集文件的访客页(/c/:token,doc/20 二.5)。不登录:打开链接 → 密码 / 名字 → 选文件上传。
 * 访客看不到文件夹里已有的内容,只看得到自己这次送达了什么。
 */

const route = useRoute()
const token = computed(() => String(route.params.token ?? ''))

// ---------- 访客凭证:sessionStorage 按链接隔离 ----------

const ctKey = computed(() => `gopan_ct_${token.value}`)
const guestToken = ref<string | null>(sessionStorage.getItem(ctKey.value))

function setGuestToken(t: string | null) {
  guestToken.value = t
  if (t) sessionStorage.setItem(ctKey.value, t)
  else sessionStorage.removeItem(ctKey.value)
}

/** 令牌过期(12 小时)或链接被关闭:回到入口态 */
function onGuestError(err: unknown): boolean {
  if (hasErrorCode(err, 'UNAUTHENTICATED') || hasErrorCode(err, 'COLLECT_CLOSED')) {
    setGuestToken(null)
    void refetchInfo()
    return true
  }
  return false
}

// ---------- 公开信息 ----------

const {
  data: infoData,
  isFetching: infoLoading,
  isError: infoError,
  error: infoErr,
  refetch: refetchInfo,
} = useQuery({
  queryKey: ['fileRequestInfo', token],
  queryFn: () => plainRequest(FileRequestInfoDocument, { token: token.value }),
  retry: false,
})
const info = computed(() => infoData.value?.fileRequestInfo ?? null)
const notFound = computed(() => hasErrorCode(infoErr.value, 'NOT_FOUND'))

// ---------- 打开链接:密码 / 名字 ----------

const nameKey = 'gopan_collect_name'
const password = ref('')
const uploaderName = ref(localStorage.getItem(nameKey) ?? '')
const opening = ref(false)
const openError = ref<string | null>(null)

async function open() {
  if (!info.value) return
  if (info.value.needName && !uploaderName.value.trim()) {
    openError.value = '请填写你的名字'
    return
  }
  if (info.value.needPassword && !password.value) {
    openError.value = '请输入密码'
    return
  }
  opening.value = true
  openError.value = null
  try {
    const res = await plainRequest(OpenFileRequestDocument, {
      token: token.value,
      password: password.value || null,
      uploaderName: uploaderName.value.trim() || null,
    })
    if (uploaderName.value.trim()) localStorage.setItem(nameKey, uploaderName.value.trim())
    setGuestToken(res.openFileRequest.accessToken)
  } catch (err) {
    if (hasErrorCode(err, 'COLLECT_CLOSED')) {
      void refetchInfo()
      return
    }
    openError.value = errorText(err, '打开失败,请稍后再试')
  } finally {
    opening.value = false
  }
}

/** 不要密码也不要名字:直接打开 */
const needsForm = computed(() => !!info.value && (info.value.needPassword || info.value.needName))
watch(
  [info, guestToken],
  ([i, gt]) => {
    if (i && !i.closed && !gt && !needsForm.value && !opening.value) void open()
  },
  { immediate: true },
)

// ---------- 上传 ----------

function call<T>(fn: (tok: string) => Promise<T>): Promise<T> {
  const tok = guestToken.value
  if (!tok) return Promise.reject({ code: 'UNAUTHENTICATED' })
  return fn(tok).catch((err: unknown) => {
    onGuestError(err)
    throw err
  })
}

const transport: CollectTransport = {
  init: (name, size) =>
    call((t) => guestRequest(CollectInitUploadDocument, t, { name, size })).then((r) => r.collectInitUpload),
  session: (id) =>
    call((t) => guestRequest(CollectUploadSessionDocument, t, { id })).then((r) => r.collectUploadSession),
  complete: (sessionId) =>
    call((t) => guestRequest(CollectCompleteUploadDocument, t, { sessionId, etags: [] })).then(
      (r) => r.collectCompleteUpload,
    ),
  abort: (sessionId) =>
    call((t) => guestRequest(CollectAbortUploadDocument, t, { sessionId })).then(() => undefined),
}
const uploader = createCollectUploader(transport, `gopan_collect_${token.value}`)

const pending = computed(() => uploader.tasks.filter((t) => t.status !== 'done'))

const { data: mineData, refetch: refetchMine } = useQuery({
  queryKey: ['collectMyUploads', guestToken],
  enabled: computed(() => !!guestToken.value),
  queryFn: async () => {
    try {
      return await guestRequest(CollectMyUploadsDocument, guestToken.value!)
    } catch (err) {
      if (onGuestError(err)) return null
      throw err
    }
  },
  retry: false,
})
const delivered = computed(() => [...(mineData.value?.collectMyUploads ?? [])].reverse())

// 有文件送达就刷新已送达清单
const doneCount = computed(() => uploader.tasks.filter((t) => t.status === 'done').length)
watch(doneCount, () => void refetchMine())

function addFiles(files: File[]) {
  const max = info.value?.maxFileSize ?? null
  const ok: File[] = []
  for (const f of files) {
    if (f.size === 0) {
      ElMessage.warning(`「${f.name}」是空文件,已跳过`)
      continue
    }
    if (max !== null && f.size > max) {
      ElMessage.warning(`「${f.name}」超过单个文件上限 ${formatBytes(max)},已跳过`)
      continue
    }
    ok.push(f)
  }
  if (ok.length) uploader.add(ok)
}

const fileInput = ref<HTMLInputElement | null>(null)
function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = '' // 同一个文件可以再选
}

const dragging = ref(false)
function onDrop(e: DragEvent) {
  dragging.value = false
  const dt = e.dataTransfer
  if (!dt) return
  const files: File[] = []
  // 拖进来的文件夹没法按文件传:跳过
  const items = Array.from(dt.items ?? [])
  if (items.length) {
    for (const it of items) {
      if (it.kind !== 'file') continue
      if (it.webkitGetAsEntry?.()?.isDirectory) {
        ElMessage.warning('暂不支持拖入文件夹,请选择里面的文件')
        continue
      }
      const f = it.getAsFile()
      if (f) files.push(f)
    }
  } else {
    files.push(...Array.from(dt.files))
  }
  addFiles(files)
}

function percent(t: CollectTask): number {
  return t.size ? Math.floor((t.uploadedBytes / t.size) * 100) : 0
}

function statusText(t: CollectTask): string {
  switch (t.status) {
    case 'queued':
      return '等待中'
    case 'uploading':
      return `${percent(t)}%`
    case 'completing':
      return '校验中'
    case 'failed':
      return t.error ?? '上传失败'
    case 'canceled':
      return '已取消'
    default:
      return ''
  }
}

// ---------- 传的时候别让手机熄屏 / 别关页面 ----------

let wakeLock: { release: () => Promise<void> } | null = null

async function holdWakeLock(on: boolean) {
  const wl = (navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }).wakeLock
  if (!wl) return
  try {
    if (on && !wakeLock) wakeLock = await wl.request('screen')
    if (!on && wakeLock) {
      await wakeLock.release()
      wakeLock = null
    }
  } catch {
    wakeLock = null // 省电模式等会拒绝,不影响上传
  }
}

watch(uploader.busy, (b) => void holdWakeLock(b))

function onVisibility() {
  // 切回前台时系统已经释放了屏幕常亮,重新申请
  if (document.visibilityState === 'visible' && uploader.busy.value) {
    wakeLock = null
    void holdWakeLock(true)
  }
}

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (uploader.busy.value) e.preventDefault()
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  document.removeEventListener('visibilitychange', onVisibility)
  void holdWakeLock(false)
})

const deadlineText = computed(() =>
  info.value?.expiresAt ? `截止 ${formatTime(info.value.expiresAt)}` : '',
)
</script>

<template>
  <div class="collect">
    <header class="collect-header">
      <span class="brand">gopan 云盘</span>
      <span class="brand-sub">收集文件</span>
    </header>

    <main class="collect-main">
      <div v-if="infoLoading && !info" v-loading="true" class="state-box" />

      <el-empty v-else-if="notFound" class="state-box" description="链接不存在或已被删除">
        <template #image><el-icon :size="56" class="dead-icon"><WarningFilled /></el-icon></template>
      </el-empty>

      <el-empty v-else-if="infoError" class="state-box" :description="errorText(infoErr, '加载失败')" />

      <el-empty v-else-if="info?.closed" class="state-box" description="收集已结束,链接已截止或被取消">
        <template #image><el-icon :size="56" class="dead-icon"><WarningFilled /></el-icon></template>
      </el-empty>

      <div v-else-if="info" class="card">
        <h2 class="title">{{ info.title }}</h2>
        <p v-if="info.note" class="note">{{ info.note }}</p>
        <p class="meta">
          <span v-if="deadlineText">{{ deadlineText }}</span>
          <span v-if="info.maxFileSize">单个文件不超过 {{ formatBytes(info.maxFileSize) }}</span>
          <span>上传后你看不到别人传的文件</span>
        </p>

        <!-- 入口:密码 / 名字 -->
        <form v-if="!guestToken" class="entry" @submit.prevent="open">
          <template v-if="needsForm">
            <el-input
              v-if="info.needName"
              v-model="uploaderName"
              placeholder="你的名字(对方会看到)"
              maxlength="50"
              @keyup.enter="open"
            />
            <el-input
              v-if="info.needPassword"
              v-model="password"
              type="password"
              placeholder="密码"
              show-password
              @keyup.enter="open"
            />
            <p v-if="openError" class="entry-error">{{ openError }}</p>
            <el-button type="primary" native-type="submit" :loading="opening" class="entry-btn">
              开始上传
            </el-button>
          </template>
          <p v-else v-loading="true" class="entry-hint">正在打开…</p>
        </form>

        <!-- 上传 -->
        <template v-else>
          <div
            class="drop"
            :class="{ dragging }"
            role="button"
            tabindex="0"
            @click="fileInput?.click()"
            @keydown.enter="fileInput?.click()"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="onDrop"
          >
            <el-icon :size="40" class="drop-icon"><UploadFilled /></el-icon>
            <p class="drop-main">点击选择文件,或拖到这里</p>
            <p class="drop-sub">可以一次选多个;传完之前请不要关闭页面或切到别的应用</p>
            <input ref="fileInput" type="file" multiple hidden @change="onPick" />
          </div>

          <ul v-if="pending.length" class="tasks">
            <li v-for="t in pending" :key="t.id" class="task" :class="t.status">
              <div class="task-row">
                <span class="task-name" :title="t.fileName">{{ t.fileName }}</span>
                <span class="task-size">{{ formatBytes(t.size) }}</span>
                <el-button
                  v-if="t.status === 'failed'"
                  link
                  type="primary"
                  :icon="Refresh"
                  title="重试"
                  @click="uploader.retry(t.id)"
                />
                <el-button
                  v-if="t.status === 'failed' || t.status === 'canceled'"
                  link
                  :icon="Close"
                  title="移除"
                  @click="uploader.remove(t.id)"
                />
                <el-button
                  v-else
                  link
                  :icon="Close"
                  title="取消"
                  @click="uploader.cancel(t.id)"
                />
              </div>
              <el-progress
                v-if="t.status === 'uploading' || t.status === 'completing' || t.status === 'queued'"
                :percentage="percent(t)"
                :show-text="false"
                :stroke-width="4"
              />
              <span class="task-status">{{ statusText(t) }}</span>
            </li>
          </ul>

          <section v-if="delivered.length" class="delivered">
            <h3 class="delivered-title">
              <el-icon class="ok-icon"><CircleCheckFilled /></el-icon>
              已送达 {{ delivered.length }} 个文件
            </h3>
            <ul>
              <li v-for="(d, i) in delivered" :key="i" class="delivered-item">
                <span class="task-name" :title="d.name">{{ d.name }}</span>
                <span class="task-size">{{ formatBytes(d.size) }}</span>
              </li>
            </ul>
          </section>
        </template>
      </div>
    </main>

    <footer class="collect-footer">
      <el-icon><Link /></el-icon>
      由 gopan 云盘提供收集服务
    </footer>
  </div>
</template>

<style scoped>
.collect {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--el-fill-color-lighter);
}
.collect-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 16px 28px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}
.brand {
  font-size: 17px;
  font-weight: 600;
}
.brand-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.collect-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 24px;
}
.state-box {
  margin-top: 10vh;
}
.dead-icon {
  color: var(--el-color-warning);
}
.card {
  width: min(560px, 100%);
  padding: 28px 32px;
  border-radius: 12px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-light);
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.title {
  margin: 0;
  font-size: 20px;
  word-break: break-word;
}
.note {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--el-text-color-regular);
}
.meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.entry {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 4px;
}
.entry-error {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger);
}
.entry-btn {
  width: 100%;
}
.entry-hint {
  margin: 0;
  min-height: 40px;
}
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 28px 16px;
  border: 1.5px dashed var(--el-border-color);
  border-radius: 10px;
  cursor: pointer;
  text-align: center;
  transition: border-color 0.15s, background 0.15s;
}
.drop:hover,
.drop.dragging {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.drop-icon {
  color: var(--el-color-primary);
}
.drop-main {
  margin: 0;
  font-weight: 500;
}
.drop-sub {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tasks,
.delivered ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.task {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.task-row,
.delivered-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.task-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.task-status {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.task.failed .task-status {
  color: var(--el-color-danger);
}
.delivered {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 12px;
}
.delivered-title {
  margin: 0 0 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.ok-icon {
  color: var(--el-color-success);
}
.collect-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 手机:访客大概率用手机打开 */
@media (max-width: 767px) {
  .collect-header {
    padding: 12px 14px;
  }
  .collect-main {
    padding: 16px 12px calc(16px + var(--sab));
  }
  .card {
    padding: 20px 16px;
  }
}
</style>
