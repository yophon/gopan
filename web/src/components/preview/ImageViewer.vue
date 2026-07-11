<script setup lang="ts">
import { computed, ref } from 'vue'

/**
 * 图片查看器:滚轮缩放(以光标为中心)、拖拽平移、双击复位。
 * 纯 CSS transform,不引库;切换图片由父级 :key 整体重建,状态天然归零。
 */

defineProps<{ src: string; alt?: string }>()

const scale = ref(1)
const tx = ref(0)
const ty = ref(0)

const MIN_SCALE = 0.2
const MAX_SCALE = 8

const transform = computed(
  () => `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`,
)
const zoomed = computed(() => scale.value !== 1 || tx.value !== 0 || ty.value !== 0)

function onWheel(e: WheelEvent) {
  e.preventDefault()
  const factor = e.deltaY < 0 ? 1.2 : 1 / 1.2
  const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale.value * factor))
  if (next === scale.value) return
  // 以光标为不动点:先把光标相对图心的偏移按比例放大,再补平移
  const ratio = next / scale.value
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const cx = e.clientX - rect.left - rect.width / 2
  const cy = e.clientY - rect.top - rect.height / 2
  tx.value = cx - (cx - tx.value) * ratio
  ty.value = cy - (cy - ty.value) * ratio
  scale.value = next
}

const dragging = ref(false)
let startX = 0
let startY = 0
let baseTx = 0
let baseTy = 0

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  dragging.value = true
  startX = e.clientX
  startY = e.clientY
  baseTx = tx.value
  baseTy = ty.value
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onPointerMove(e: PointerEvent) {
  if (!dragging.value) return
  tx.value = baseTx + (e.clientX - startX)
  ty.value = baseTy + (e.clientY - startY)
}

function onPointerUp() {
  dragging.value = false
}

function reset() {
  scale.value = 1
  tx.value = 0
  ty.value = 0
}
</script>

<template>
  <div
    class="image-viewer"
    :class="{ dragging, zoomed }"
    @wheel="onWheel"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @dblclick="reset"
  >
    <img class="viewer-img" :style="{ transform }" :src="src" :alt="alt" draggable="false" />
    <span v-if="zoomed" class="zoom-badge">{{ Math.round(scale * 100) }}% · 双击复位</span>
  </div>
</template>

<style scoped>
.image-viewer {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  cursor: grab;
  touch-action: none;
}
.image-viewer.dragging {
  cursor: grabbing;
}
.viewer-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 4px;
  transition: transform 0.06s linear;
  will-change: transform;
  user-select: none;
}
.image-viewer.dragging .viewer-img {
  transition: none;
}
.zoom-badge {
  position: absolute;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.55);
  color: #e5e7eb;
  font-size: 12px;
  pointer-events: none;
}
</style>
