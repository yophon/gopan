<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Download, RefreshRight, VideoPlay, WarningFilled } from '@element-plus/icons-vue'

import { reportProgress } from '@/composables/media'
import { defaultSubtitleIndex, formatDuration, mediaErrorAction, resumePosition, subtitleUrl } from '@/utils/media'
import type { ProgressLike } from '@/utils/media'

/**
 * 预览里的视频(doc/22 二):浏览器原生控件,只补外挂字幕选择、续播提示、下一集倒计时,
 * 以及签名过期(播放中 403)时重新取地址接着放;浏览器放不了的格式给出提示而不是黑框。
 */
interface Subtitle {
  nodeId: string
  label: string
  lang: string
  default: boolean
  /** 转封装时从文件里取出的内嵌字幕直接给地址(doc/31 一);外挂字幕按 nodeId 拼 /sub */
  url?: string
}

const props = defineProps<{
  nodeId: string
  src: string
  poster?: string | null
  subtitles: Subtitle[]
  /** /sub 用的 token:登录用户的 access token 或访客 token */
  token: string
  /** 打开时的播放进度(只在挂载时读一次) */
  progress?: ProgressLike | null
  /** 访客不记、不续播 */
  trackProgress: boolean
  /** 重新取播放地址;取不到返回 null */
  refresh: () => Promise<string | null>
  /** 下一集的名字;没有为 null */
  nextName?: string | null
  /** 桌面端:放不了时能交给电脑上的播放器(doc/31 三) */
  external?: boolean
}>()

const emit = defineEmits<{
  playing: [boolean]
  next: []
  download: []
  /** 浏览器放不了这个文件:有转封装可走时由外面接手 */
  unsupported: []
  playExternal: []
}>()

const videoEl = ref<HTMLVideoElement | null>(null)
const currentSrc = ref(props.src)
const initialProgress = props.trackProgress ? (props.progress ?? null) : null

let urlAt = Date.now()
let retried = false
let hadPlayed = false
let firstMeta = true
let pending: { at: number; play: boolean } | null = null

// ---------- 字幕 ----------

const subIndex = ref(defaultSubtitleIndex(props.subtitles))
const trackSrcs = computed(() => props.subtitles.map((t) => t.url ?? subtitleUrl(t.nodeId, props.token)))

function applySubtitle() {
  const tracks = videoEl.value?.textTracks
  if (!tracks) return
  for (let i = 0; i < tracks.length; i++) {
    tracks[i].mode = i === subIndex.value ? 'showing' : 'disabled'
  }
}

function onSubtitleChange(e: Event) {
  subIndex.value = Number((e.target as HTMLSelectElement).value)
  applySubtitle()
}

// ---------- 续播 ----------

const resumedFrom = ref<number | null>(null)
let resumeHintTimer: ReturnType<typeof setTimeout> | null = null

function restart() {
  if (videoEl.value) videoEl.value.currentTime = 0
  resumedFrom.value = null
}

function onLoadedMetadata() {
  const v = videoEl.value
  if (!v) return
  applySubtitle()
  if (pending) {
    v.currentTime = pending.at
    if (pending.play) void v.play().catch(() => {})
    pending = null
    return
  }
  if (firstMeta) {
    firstMeta = false
    const at = resumePosition(initialProgress)
    // 记录的位置超出了实际时长(文件换过等)就不跳
    if (at !== null && (!Number.isFinite(v.duration) || at < v.duration - 1)) {
      v.currentTime = at
      resumedFrom.value = at
      resumeHintTimer = setTimeout(() => (resumedFrom.value = null), 8000)
    }
  }
}

// ---------- 进度上报 ----------

let saveTimer: ReturnType<typeof setInterval> | null = null

function save() {
  const v = videoEl.value
  // 换地址重载期间位置会先归零,别把 0 报上去
  if (!props.trackProgress || !v || !hadPlayed || pending) return
  reportProgress(props.nodeId, v.currentTime, v.duration)
}

function onPlay() {
  emit('playing', true)
  if (saveTimer) clearInterval(saveTimer)
  if (props.trackProgress) saveTimer = setInterval(save, 15_000)
}

function onPause() {
  emit('playing', false)
  if (saveTimer) clearInterval(saveTimer)
  saveTimer = null
  save()
}

function onTimeUpdate() {
  const v = videoEl.value
  if (v && v.currentTime > 0 && !v.paused) hadPlayed = true
}

function onPlaying() {
  retried = false
  failure.value = null
}

// ---------- 出错 ----------

const failure = ref<'unsupported' | 'failed' | null>(null)
const reloading = ref(false)

async function reload() {
  const v = videoEl.value
  pending = { at: v?.currentTime ?? 0, play: !!v && !v.paused }
  // 出错时浏览器已经停了,按「出错前在放」处理:用户本来就在看
  if (v && hadPlayed) pending.play = true
  reloading.value = true
  try {
    const url = await props.refresh()
    if (!url) throw new Error('no url')
    urlAt = Date.now()
    currentSrc.value = url
    failure.value = null
  } catch {
    pending = null
    failure.value = 'failed'
  } finally {
    reloading.value = false
  }
}

function onError() {
  const v = videoEl.value
  if (!v) return
  const action = mediaErrorAction({ code: v.error?.code, retried, hadPlayed, urlAgeMs: Date.now() - urlAt })
  if (action === 'ignore') return
  if (action === 'refetch') {
    retried = true
    void reload()
    return
  }
  emit('playing', false)
  failure.value = action
  if (action === 'unsupported') emit('unsupported')
}

function retry() {
  retried = false
  void reload()
}

// ---------- 下一集 ----------

const countdown = ref<number | null>(null)
let countdownTimer: ReturnType<typeof setInterval> | null = null

function stopCountdown() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = null
  countdown.value = null
}

function onEnded() {
  emit('playing', false)
  save()
  if (!props.nextName) return
  countdown.value = 5
  countdownTimer = setInterval(() => {
    if (countdown.value === null) return
    countdown.value--
    if (countdown.value <= 0) {
      stopCountdown()
      emit('next')
    }
  }, 1000)
}

function goNextNow() {
  stopCountdown()
  emit('next')
}

onBeforeUnmount(() => {
  save()
  if (saveTimer) clearInterval(saveTimer)
  if (resumeHintTimer) clearTimeout(resumeHintTimer)
  stopCountdown()
})
</script>

<template>
  <div class="video-wrap">
    <video
      v-show="!failure"
      ref="videoEl"
      class="preview-video"
      controls
      autoplay
      playsinline
      :poster="poster ?? undefined"
      :src="currentSrc"
      @loadedmetadata="onLoadedMetadata"
      @play="onPlay"
      @playing="onPlaying"
      @pause="onPause"
      @timeupdate="onTimeUpdate"
      @ended="onEnded"
      @error="onError"
    >
      <track
        v-for="(t, i) in subtitles"
        :key="t.nodeId"
        kind="subtitles"
        :src="trackSrcs[i]"
        :srclang="t.lang || 'und'"
        :label="t.label"
        :default="i === subIndex"
      />
    </video>

    <!-- 放不了:给出原因与出路,不留黑框 -->
    <div v-if="failure" class="video-failure">
      <el-icon :size="40" class="failure-icon"><WarningFilled /></el-icon>
      <p v-if="failure === 'unsupported'">
        {{ external ? '浏览器放不了这个格式,用电脑上的播放器看' : '浏览器放不了这个格式,下载或用安卓 App 看' }}
      </p>
      <p v-else>播放中断了,网络或地址失效</p>
      <div class="failure-actions">
        <el-button v-if="failure === 'failed'" size="small" :icon="RefreshRight" :loading="reloading" @click="retry">
          重试
        </el-button>
        <el-button v-if="external" size="small" type="primary" :icon="VideoPlay" @click="emit('playExternal')">
          用播放器播放
        </el-button>
        <el-button size="small" :type="external ? 'default' : 'primary'" :icon="Download" @click="emit('download')">下载</el-button>
      </div>
    </div>

    <div v-if="resumedFrom !== null && !failure" class="video-toast">
      从 {{ formatDuration(resumedFrom) }} 继续 ·
      <button type="button" class="link-btn" @click="restart">从头播放</button>
    </div>

    <div v-if="countdown !== null" class="next-card">
      <button type="button" class="next-name" @click="goNextNow">
        下一集:{{ nextName }}({{ countdown }})
      </button>
      ·
      <button type="button" class="link-btn" @click="stopCountdown">取消</button>
    </div>

    <div v-if="subtitles.length > 0 && !failure" class="video-toolbar">
      <label class="sub-label">
        字幕
        <select class="sub-select" :value="subIndex" @change="onSubtitleChange">
          <option v-for="(t, i) in subtitles" :key="t.nodeId" :value="i">{{ t.label }}</option>
          <option :value="-1">关</option>
        </select>
      </label>
    </div>
  </div>
</template>

<style scoped>
.video-wrap {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.preview-video {
  flex: 0 1 auto;
  min-height: 0;
  max-width: 100%;
  max-height: 100%;
  outline: none;
}
.video-failure {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 28px 32px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  color: #e5e7eb;
  font-size: 14px;
  text-align: center;
}
.video-failure p {
  margin: 0;
}
.failure-icon {
  color: var(--el-color-warning);
}
.failure-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.video-toast,
.next-card {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  max-width: calc(100% - 24px);
  padding: 8px 14px;
  border-radius: 8px;
  background: rgba(17, 24, 39, 0.88);
  color: #f3f4f6;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.video-toast {
  top: 12px;
}
.next-card {
  bottom: 64px;
}
.next-name {
  max-width: 60vw;
  padding: 0;
  border: none;
  background: none;
  color: inherit;
  font-size: 13px;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
}
.link-btn {
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary-light-3);
  font-size: 13px;
  cursor: pointer;
}
.video-toolbar {
  flex: none;
  display: flex;
  justify-content: flex-end;
  width: 100%;
}
.sub-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #d1d5db;
  font-size: 13px;
}
.sub-select {
  padding: 3px 6px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 4px;
  background: rgba(17, 24, 39, 0.9);
  color: #f3f4f6;
  font-size: 13px;
}
</style>
