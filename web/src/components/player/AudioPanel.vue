<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { DArrowLeft, DArrowRight, Download, Headset, VideoPause, VideoPlay } from '@element-plus/icons-vue'

import { usePlayerStore } from '@/stores/player'
import { formatDuration } from '@/utils/media'
import { playModeLabel } from '@/utils/player'

/** 预览里的音频面板:不自带 <audio>,控件全绑全站播放器(doc/22 二.4) */

const emit = defineEmits<{ download: [] }>()
const player = usePlayerStore()

const dragging = ref(false)
const dragValue = ref(0)
const sliderValue = computed(() => (dragging.value ? dragValue.value : player.currentTime))

function onSliderInput(v: number | number[]) {
  dragging.value = true
  dragValue.value = Array.isArray(v) ? v[0] : v
}

function onSliderChange(v: number | number[]) {
  dragging.value = false
  player.seek(Array.isArray(v) ? v[0] : v)
}

// ---------- 歌词(doc/31 二):当前行滚到中间;手动滚过之后 4 秒内不抢 ----------

const lyricsEl = ref<HTMLElement | null>(null)
let userScrollAt = 0

function onLyricsScrollIntent() {
  userScrollAt = Date.now()
}

function centerLyric(smooth: boolean) {
  const box = lyricsEl.value
  const line = box?.querySelector<HTMLElement>('.lyric.on')
  if (!box || !line || Date.now() - userScrollAt < 4000) return
  box.scrollTo({ top: line.offsetTop - box.clientHeight / 2 + line.clientHeight / 2, behavior: smooth ? 'smooth' : 'auto' })
}

watch(
  () => player.lyricIndex,
  async () => {
    await nextTick()
    centerLyric(true)
  },
)
watch(
  () => player.lyrics,
  async () => {
    userScrollAt = 0
    await nextTick()
    if (lyricsEl.value) lyricsEl.value.scrollTop = 0
    centerLyric(false)
  },
)

function seekToLine(t: number) {
  if (!player.lyrics?.synced || t < 0) return
  userScrollAt = 0
  player.seek(t)
}

const showQueue = ref(false)
const queueEl = ref<HTMLElement | null>(null)
watch(showQueue, async (v) => {
  if (!v) return
  await nextTick()
  queueEl.value?.querySelector('.q-item.current')?.scrollIntoView({ block: 'center' })
})
</script>

<template>
  <div class="audio-panel">
    <div
      v-if="player.lyrics && !showQueue"
      ref="lyricsEl"
      class="ap-lyrics"
      :class="{ plain: !player.lyrics.synced }"
      @wheel.passive="onLyricsScrollIntent"
      @touchmove.passive="onLyricsScrollIntent"
    >
      <p
        v-for="(l, i) in player.lyrics.lines"
        :key="i"
        class="lyric"
        :class="{ on: i === player.lyricIndex, blank: !l.text }"
        @click="seekToLine(l.t)"
      >
        {{ l.text || '\u00a0' }}
      </p>
    </div>
    <el-icon v-else :size="72" class="ap-icon"><Headset /></el-icon>
    <div class="ap-title" :title="player.current?.name">{{ player.current?.name ?? '—' }}</div>
    <div class="ap-sub">
      <template v-if="player.queue.length > 1">第 {{ player.index + 1 }} / {{ player.queue.length }} 首 · </template>
      {{ playModeLabel[player.mode] }}
    </div>

    <div v-if="player.resumedFrom !== null" class="ap-resume">
      从 {{ formatDuration(player.resumedFrom) }} 继续 ·
      <button type="button" class="link-btn" @click="player.restart()">从头播放</button>
    </div>

    <div v-if="player.error" class="ap-error">
      <span>{{ player.error }}</span>
      <el-button size="small" type="primary" :icon="Download" @click="emit('download')">下载</el-button>
    </div>

    <div class="ap-progress">
      <span class="ap-time">{{ formatDuration(sliderValue) || '00:00' }}</span>
      <el-slider
        class="ap-slider"
        :model-value="sliderValue"
        :min="0"
        :max="Math.max(player.duration, 1)"
        :step="1"
        :show-tooltip="false"
        :disabled="!player.duration"
        @input="onSliderInput"
        @change="onSliderChange"
      />
      <span class="ap-time">{{ formatDuration(player.duration) || '--:--' }}</span>
    </div>

    <div class="ap-controls">
      <el-button text class="ap-mode" @click="player.cycleMode()">{{ playModeLabel[player.mode] }}</el-button>
      <el-button circle size="large" :icon="DArrowLeft" title="上一首" aria-label="上一首" @click="player.prev()" />
      <el-button
        type="primary"
        circle
        size="large"
        class="ap-play"
        :loading="player.loading && !player.error"
        :icon="player.playing ? VideoPause : VideoPlay"
        :title="player.playing ? '暂停' : '播放'"
        :aria-label="player.playing ? '暂停' : '播放'"
        @click="player.toggle()"
      />
      <el-button circle size="large" :icon="DArrowRight" title="下一首" aria-label="下一首" @click="player.next()" />
      <el-button text class="ap-mode" :disabled="player.queue.length < 2" @click="showQueue = !showQueue">
        {{ showQueue ? '收起列表' : '播放列表' }}
      </el-button>
    </div>

    <ol v-if="showQueue" ref="queueEl" class="ap-queue">
      <li
        v-for="(t, i) in player.queue"
        :key="t.id"
        class="q-item"
        :class="{ current: i === player.index }"
        @click="player.playAt(i)"
      >
        <span class="q-no">{{ i + 1 }}</span>
        <span class="q-name">{{ t.name }}</span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.audio-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: min(520px, 100%);
  max-height: 100%;
  padding: 28px 32px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  color: #e5e7eb;
  overflow: hidden;
}
.ap-icon {
  color: #9ca3af;
}
.ap-lyrics {
  width: 100%;
  height: min(240px, 34vh);
  padding: 96px 0;
  overflow-y: auto;
  scrollbar-width: none;
  text-align: center;
  mask-image: linear-gradient(transparent, #000 22%, #000 78%, transparent);
}
.ap-lyrics::-webkit-scrollbar {
  display: none;
}
.lyric {
  margin: 0;
  padding: 5px 8px;
  font-size: 14px;
  line-height: 1.5;
  color: #9ca3af;
  cursor: pointer;
  transition:
    color 0.2s,
    transform 0.2s;
}
.ap-lyrics.plain {
  padding: 8px 0;
  mask-image: none;
}
.ap-lyrics.plain .lyric {
  cursor: default;
}
.lyric.blank {
  padding: 2px 0;
}
.lyric.on {
  color: #f9fafb;
  font-weight: 500;
  transform: scale(1.06);
}
.ap-title {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 500;
}
.ap-sub {
  font-size: 12px;
  color: #9ca3af;
}
.ap-resume {
  font-size: 13px;
  color: #d1d5db;
}
.link-btn {
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary-light-3);
  cursor: pointer;
  font-size: 13px;
}
.ap-error {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #fbbf24;
  text-align: center;
}
.ap-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.ap-slider {
  flex: 1;
}
.ap-time {
  flex: none;
  min-width: 44px;
  font-size: 12px;
  color: #d1d5db;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
.ap-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ap-controls :deep(.el-button + .el-button) {
  margin-left: 0;
}
.ap-mode {
  min-width: 72px;
  color: #d1d5db;
}
.ap-queue {
  width: 100%;
  max-height: 32vh;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}
.q-item {
  display: flex;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}
.q-item:hover {
  background: rgba(255, 255, 255, 0.08);
}
.q-item.current {
  color: var(--el-color-primary-light-3);
}
.q-no {
  flex: none;
  width: 28px;
  color: #9ca3af;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.q-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 767px) {
  .audio-panel {
    padding: 20px 16px;
  }
  .ap-mode {
    min-width: 0;
    padding: 0 4px;
  }
}
</style>
