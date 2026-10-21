<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useEventListener, useResizeObserver } from '@vueuse/core'
import { Close, DArrowLeft, DArrowRight, Headset, VideoPause, VideoPlay } from '@element-plus/icons-vue'

import { usePlayerStore } from '@/stores/player'
import { openPreview, previewState } from '@/composables/preview'
import { formatDuration } from '@/utils/media'
import { playModeLabel } from '@/utils/player'

/**
 * 底部迷你播放条(doc/22 二.4)。全站唯一的 <audio> 挂在这里,AppShell 只挂一次,
 * 切换页面不中断;关掉预览后靠它继续控制。
 */

const player = usePlayerStore()
const audioEl = ref<HTMLAudioElement | null>(null)
const barEl = ref<HTMLElement | null>(null)

onMounted(() => {
  if (audioEl.value) player.attach(audioEl.value)
})
onBeforeUnmount(() => {
  player.detach()
  setBarHeight(0)
})

// 页面关掉 / 切后台前补报一次进度
useEventListener(window, 'pagehide', () => player.flush())
useEventListener(document, 'visibilitychange', () => {
  if (document.hidden) player.flush()
})

/** 预览正开着这一首时,播放条被预览盖住,不显示 */
const visible = computed(() => !!player.current && !(previewState.visible && previewState.ids[previewState.index] === player.current?.id))

// 上传抽屉等右下角浮层要让出播放条的高度
function setBarHeight(h: number) {
  document.documentElement.style.setProperty('--player-bar-h', `${Math.round(h)}px`)
}
useResizeObserver(barEl, (entries) => setBarHeight(entries[0]?.contentRect.height ?? 0))
watch(visible, (v) => {
  if (!v) setBarHeight(0)
})

// ---------- 进度条 ----------

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

const timeText = computed(() => {
  const d = formatDuration(player.duration)
  return d ? `${formatDuration(sliderValue.value)} / ${d}` : formatDuration(sliderValue.value)
})

const MODE_SHORT = { order: '顺序', one: '单曲', shuffle: '随机' } as const

function openCurrent() {
  const t = player.current
  if (t) openPreview([t.id], 0)
}
</script>

<template>
  <audio ref="audioEl" preload="metadata" class="hidden-audio" />
  <div v-if="visible" ref="barEl" class="mini-player">
    <button class="mp-info" type="button" title="打开播放页" @click="openCurrent">
      <el-icon class="mp-icon" :size="20"><Headset /></el-icon>
      <span class="mp-text">
        <span class="mp-title">{{ player.current?.name }}</span>
        <span class="mp-sub" :class="{ error: !!player.error }">
          {{ player.error || (player.loading ? '加载中…' : timeText) }}
        </span>
      </span>
    </button>

    <div class="mp-controls">
      <el-button text circle :icon="DArrowLeft" title="上一首" aria-label="上一首" @click="player.prev()" />
      <el-button
        type="primary"
        circle
        :icon="player.playing ? VideoPause : VideoPlay"
        :title="player.playing ? '暂停' : '播放'"
        :aria-label="player.playing ? '暂停' : '播放'"
        @click="player.toggle()"
      />
      <el-button text circle :icon="DArrowRight" title="下一首" aria-label="下一首" @click="player.next()" />
    </div>

    <el-slider
      class="mp-progress"
      :model-value="sliderValue"
      :min="0"
      :max="Math.max(player.duration, 1)"
      :step="1"
      :show-tooltip="false"
      :disabled="!player.duration"
      size="small"
      @input="onSliderInput"
      @change="onSliderChange"
    />

    <div class="mp-tail">
      <el-button
        text
        size="small"
        class="mp-mode"
        :title="`${playModeLabel[player.mode]}(点一下切换)`"
        @click="player.cycleMode()"
      >
        {{ MODE_SHORT[player.mode] }}
      </el-button>
      <el-button text circle size="small" :icon="Close" title="关闭播放器" aria-label="关闭播放器" @click="player.close()" />
    </div>
  </div>
</template>

<style scoped>
.hidden-audio {
  display: none;
}
.mini-player {
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 6px 16px;
  border-top: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.mp-info {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 240px;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: inherit;
}
.mp-icon {
  flex: none;
  color: var(--el-color-primary);
}
.mp-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.mp-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.mp-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-sub.error {
  color: var(--el-color-danger);
}
.mp-controls {
  display: flex;
  align-items: center;
  flex: none;
}
.mp-controls :deep(.el-button + .el-button) {
  margin-left: 4px;
}
.mp-progress {
  flex: 1;
  min-width: 80px;
  padding: 0 6px;
}
.mp-tail {
  display: flex;
  align-items: center;
  flex: none;
}
.mp-mode {
  min-width: 40px;
}

/* 手机:两行,上面曲名与按钮,下面进度条 */
@media (max-width: 767px) {
  .mini-player {
    flex-wrap: wrap;
    gap: 2px 8px;
    padding: 6px 12px calc(4px + var(--sab));
  }
  .mp-info {
    flex: 1;
    width: auto;
  }
  .mp-progress {
    order: 3;
    flex-basis: 100%;
  }
}
</style>
