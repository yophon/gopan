<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { CircleCheckFilled, Scissor, WarningFilled } from '@element-plus/icons-vue'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ClipUrlDocument } from '@/api/gen/graphql'
import type { ClipFieldsFragment, JobFieldsFragment } from '@/api/gen/graphql'
import { waitForJob } from '@/composables/clip'
import { CLIP_READY, MAX_CLIP_HTML_BYTES, clipPageHtml, clipTargetUrl, htmlBytes } from '@/utils/clips'
import { formatTime } from '@/utils/format'
import { jobPercent, jobStatusText } from '@/utils/jobs'

/**
 * 书签栏小工具弹出的小窗(doc/23 五.3):显示要剪的地址,点「剪藏」。
 * 勾了「连同页面内容」:向原页面(window.opener)说一声准备好了,等它把页面 HTML 发过来(最多 5 秒),
 * 只收原页面发来的、地址对得上的那条。成功后 2 秒自己关掉。
 */

const route = useRoute()
const url = computed(() => clipTargetUrl(route.query.url))
const hasOpener = typeof window !== 'undefined' && !!window.opener

type Phase = 'idle' | 'waiting' | 'sending' | 'running' | 'done' | 'existing' | 'failed'
const phase = ref<Phase>('idle')
const withPage = ref(false)
const job = ref<JobFieldsFragment | null>(null)
const existing = ref<ClipFieldsFragment | null>(null)
const message = ref('')
const closing = ref(false)

let alive = true
onBeforeUnmount(() => (alive = false))

const PAGE_WAIT_MS = 5000

/** 向原页面要页面内容;没有原页面、超时都返回 null */
function requestPage(target: string): Promise<string | null> {
  return new Promise((resolve) => {
    const opener = window.opener as Window | null
    if (!opener) {
      resolve(null)
      return
    }
    const timer = setTimeout(() => {
      cleanup()
      resolve(null)
    }, PAGE_WAIT_MS)
    function onMessage(e: MessageEvent) {
      if (e.source !== opener) return
      const html = clipPageHtml(e.data, target)
      if (html === null) return
      cleanup()
      resolve(html)
    }
    function cleanup() {
      clearTimeout(timer)
      window.removeEventListener('message', onMessage)
    }
    window.addEventListener('message', onMessage)
    // 这条消息不带任何内容,发给谁都无妨;原页面那边只认 gopan 的源
    opener.postMessage({ type: CLIP_READY }, '*')
  })
}

function fail(text: string) {
  phase.value = 'failed'
  message.value = text
}

async function start(force = false) {
  const target = url.value
  if (!target) return
  job.value = null
  existing.value = null
  message.value = ''
  let html: string | null = null
  if (withPage.value) {
    phase.value = 'waiting'
    html = await requestPage(target)
    if (html === null) {
      fail('没收到页面内容(原页面可能已经关了或拦住了)。不勾「连同页面内容」再试一次。')
      return
    }
    if (htmlBytes(html) > MAX_CLIP_HTML_BYTES) {
      fail('页面内容超过 5 MB,服务器收不下。不勾「连同页面内容」再试一次。')
      return
    }
  }
  phase.value = 'sending'
  try {
    const res = (await request(ClipUrlDocument, { url: target, html, force })).clipUrl
    if (res.existing) {
      existing.value = res.existing
      phase.value = 'existing'
      return
    }
    if (!res.job) {
      fail('剪藏失败')
      return
    }
    job.value = res.job
    phase.value = 'running'
    const done = await waitForJob(res.job.id, (j) => (job.value = j), () => alive)
    if (!done) return
    if (done.status === 'DONE') {
      phase.value = 'done'
      autoClose()
    } else {
      fail(done.errorMessage || (done.status === 'CANCELED' ? '任务被取消了' : '剪藏失败'))
    }
  } catch (err) {
    fail(getErrorMessage(err) ?? errorText(err, '剪藏失败'))
  }
}

function autoClose() {
  if (!hasOpener) return
  closing.value = true
  setTimeout(() => {
    if (alive && closing.value) window.close()
  }, 2000)
}

function openLink(n: { id: string; parentId?: string | null } | null | undefined): string {
  if (!n) return '/clips'
  return `${n.parentId ? `/drive/${n.parentId}` : '/drive'}?open=${encodeURIComponent(n.id)}`
}

const busy = computed(() => phase.value === 'waiting' || phase.value === 'sending' || phase.value === 'running')
</script>

<template>
  <div class="popup">
    <h1 class="title">
      <el-icon><Scissor /></el-icon>
      剪藏到 gopan
    </h1>

    <template v-if="!url">
      <p class="error">地址不对:书签栏小工具要在 http / https 的网页上点。</p>
    </template>

    <template v-else>
      <p class="url" :title="url">{{ url }}</p>

      <el-checkbox v-model="withPage" :disabled="busy || !hasOpener">连同页面内容(要登录才能看的页面用)</el-checkbox>
      <p v-if="!hasOpener" class="muted">要从书签栏小工具打开,才能带上页面内容。</p>
      <p v-else class="muted">勾上后把你现在看到的页面一起发过来,服务器不再自己去抓。</p>

      <div v-if="phase === 'idle'" class="actions">
        <el-button type="primary" :icon="Scissor" @click="start()">剪藏</el-button>
      </div>

      <div v-else-if="busy" class="state">
        <el-progress
          :percentage="job ? (jobPercent(job) ?? 30) : 30"
          :indeterminate="!job || jobPercent(job) === null"
          :stroke-width="6"
          :show-text="false"
        />
        <span class="muted">
          {{ phase === 'waiting' ? '正在取页面内容…' : phase === 'sending' ? '提交中…' : job ? jobStatusText(job) : '' }}
        </span>
      </div>

      <div v-else-if="phase === 'done'" class="state ok">
        <p class="state-line">
          <el-icon><CircleCheckFilled /></el-icon>
          已存好{{ job?.title ? `:${job.title}` : '' }}
        </p>
        <p v-if="job?.errorMessage" class="muted">{{ job.errorMessage }}</p>
        <div class="actions">
          <a class="open-link" :href="openLink(job?.result)" target="_blank" rel="noopener" @click="closing = false">
            在 gopan 里打开
          </a>
          <span v-if="closing" class="muted">2 秒后自动关闭</span>
        </div>
      </div>

      <div v-else-if="phase === 'existing' && existing" class="state">
        <p class="state-line">这个网页在 {{ formatTime(existing.createdAt) }} 剪过了:{{ existing.title }}</p>
        <div class="actions">
          <a class="open-link" :href="openLink(existing.node)" target="_blank" rel="noopener">打开那篇</a>
          <el-button size="small" @click="start(true)">重新剪藏</el-button>
        </div>
      </div>

      <div v-else-if="phase === 'failed'" class="state">
        <p class="state-line error">
          <el-icon><WarningFilled /></el-icon>
          {{ message }}
        </p>
        <div class="actions">
          <el-button size="small" type="primary" @click="start()">再试一次</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.popup {
  box-sizing: border-box;
  max-width: 480px;
  min-height: 100%;
  margin: 0 auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 14px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
}
.title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.url {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.muted {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.state {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.state-line {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  line-height: 1.5;
  word-break: break-word;
}
.state-line .el-icon {
  flex: none;
  margin-top: 3px;
}
.ok .state-line {
  color: var(--el-color-success);
}
.error {
  margin: 0;
  color: var(--el-color-danger);
}
.open-link {
  color: var(--el-color-primary);
  text-decoration: none;
}
</style>
