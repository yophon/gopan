<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, Lock, UploadFilled } from '@element-plus/icons-vue'

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
import type { CollectTransport } from '@/uploader/collect'
import { expiryText } from '@/utils/visitor'
import { useBreakpoints } from '@/composables/breakpoints'
import BrandLogo from '@/components/BrandLogo.vue'
import GuestUploadTasks from '@/components/visitor/GuestUploadTasks.vue'
import { useUploadKeepAwake } from '@/composables/uploadKeepAwake'
import '@/styles/visitor.css'

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
  isPending: infoPending,
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

// 传的时候别让手机熄屏、别关页面
useUploadKeepAwake(uploader.busy)

const { isMobile } = useBreakpoints()

/** 头部那一行:截止时间(N 天后截止 · 具体时刻)、单个文件上限 */
const metaParts = computed(() => {
  const i = info.value
  if (!i) return []
  const out: string[] = []
  if (i.expiresAt) {
    const left = expiryText(i.expiresAt, Date.now(), '截止')
    out.push(left ? `${left}(${formatTime(i.expiresAt)})` : `截止 ${formatTime(i.expiresAt)}`)
  }
  if (i.maxFileSize) out.push(`单个文件不超过 ${formatBytes(i.maxFileSize)}`)
  return out
})

watch(info, (i) => {
  if (i && !i.closed) document.title = `${i.title} · gopan 云盘`
})
</script>

<template>
  <div class="visitor">
    <header class="v-top">
      <a href="/app" class="v-brand" title="gopan 云盘"><BrandLogo /></a>
    </header>

    <main class="v-main">
      <div v-if="infoPending" v-loading="true" class="v-state" element-loading-background="transparent" />

      <!-- 不存在 / 出错 / 已结束 -->
      <section v-else-if="notFound || infoError || info?.closed" class="v-state">
        <svg class="v-state-art" viewBox="0 0 34 29" aria-hidden="true">
          <path d="M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" class="folder-back" />
          <path d="M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" class="folder-front" />
        </svg>
        <h1 class="v-state-title">{{ notFound ? '链接不存在' : info?.closed ? '收集已结束' : '打不开这个链接' }}</h1>
        <p class="v-state-text">
          {{
            notFound
              ? '可能是地址抄错了,或者链接已经被删除。'
              : info?.closed
                ? '已经过了截止时间,或者发起的人关掉了这个链接。'
                : errorText(infoErr, '网络不太好,稍后刷新试试。')
          }}
        </p>
      </section>

      <section v-else-if="info" class="gp-folder v-card c-card">
        <span class="gp-folder-tab">
          <el-icon v-if="info.needPassword && !guestToken"><Lock /></el-icon>收集文件
        </span>
        <h1 class="v-title">{{ info.title }}</h1>
        <p v-if="info.note" class="c-note">{{ info.note }}</p>
        <p v-if="metaParts.length" class="v-sub">{{ metaParts.join(' · ') }}</p>

        <!-- 入口:密码 / 名字 -->
        <form v-if="!guestToken" class="v-form" @submit.prevent="open">
          <template v-if="needsForm">
            <el-input v-if="info.needName" v-model="uploaderName" size="large" placeholder="你的名字(对方会看到)" maxlength="50" />
            <el-input v-if="info.needPassword" v-model="password" size="large" type="password" placeholder="密码" show-password />
            <p v-if="openError" class="v-error">{{ openError }}</p>
            <el-button type="primary" size="large" native-type="submit" :loading="opening" class="v-btn">开始上传</el-button>
          </template>
          <div v-else v-loading="true" class="c-opening" element-loading-background="transparent" />
        </form>

        <!-- 上传 -->
        <template v-else>
          <div
            class="c-drop"
            :class="{ dragging }"
            role="button"
            tabindex="0"
            @click="fileInput?.click()"
            @keydown.enter="fileInput?.click()"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="onDrop"
          >
            <el-icon :size="34" class="c-drop-icon"><UploadFilled /></el-icon>
            <p class="c-drop-main">{{ isMobile ? '点这里选择文件' : '点这里选择文件,或者拖进来' }}</p>
            <p class="c-drop-sub">一次可以选多个 · 传完之前别关掉这个页面</p>
            <input ref="fileInput" type="file" multiple hidden @change="onPick" />
          </div>

          <GuestUploadTasks :uploader="uploader" :tasks="pending" />

          <section v-if="delivered.length" class="c-done">
            <h2 class="c-done-title">
              <el-icon><CircleCheckFilled /></el-icon>
              已送达 {{ delivered.length }} 个文件
            </h2>
            <ul>
              <li v-for="(d, i) in delivered" :key="i" class="c-row">
                <span class="c-name" :title="d.name">{{ d.name }}</span>
                <span class="c-size">{{ formatBytes(d.size) }}</span>
              </li>
            </ul>
          </section>
        </template>

        <p class="c-privacy">你传的文件只有发起的人能看到;别人传了什么你也看不到。</p>
      </section>
    </main>

    <footer class="v-foot">
      <a href="/app">用 gopan 云盘收集文件</a>
    </footer>
  </div>
</template>

<style scoped>
.c-card {
  max-width: 560px;
  margin-top: 2vh;
}
.c-note {
  margin: 10px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 15px;
  line-height: 1.7;
  color: var(--gp-ink-2);
}
.c-opening {
  min-height: 44px;
}
.c-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 22px;
  padding: 30px 16px;
  border: 1.5px dashed var(--el-border-color-darker);
  border-radius: 16px;
  background: var(--el-bg-color);
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}
.c-drop:hover,
.c-drop.dragging {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.c-drop-icon {
  color: var(--gp-ink);
}
.c-drop-main {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--gp-ink);
}
.c-drop-sub {
  margin: 0;
  font-size: 13px;
  color: var(--gp-ink-3);
}
.c-done ul {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}
.c-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.c-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  color: var(--gp-ink);
}
.c-size {
  flex: none;
  font-size: 12px;
  color: var(--gp-ink-3);
}
.c-done {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.c-done-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--gp-ink);
}
.c-done-title .el-icon {
  color: var(--el-color-success);
  font-size: 18px;
}
.c-privacy {
  margin: 22px 0 0;
  font-size: 12px;
  color: var(--gp-ink-3);
}
</style>
