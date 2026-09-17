<script setup lang="ts">
import { computed, ref } from 'vue'

import { pinchScale, pinchTranslate, twoPointerMetrics, type Point } from '@/utils/gesture'

/**
 * 图片查看器:滚轮缩放(以光标为中心)、单指/鼠标拖拽平移、双指 pinch 缩放、
 * 双击复位。纯 CSS transform,不引库;切换图片由父级 :key 整体重建,状态天然归零。
 *
 * `zoomed` 通过 v-model 回传给父级:预览的左右滑动在放大时必须让位给拖动。
 */
defineProps<{ src: string; alt?: string }>()

// 只回传"是否放大"(scale !== 1):父级用它决定左右滑动要不要让位给图片拖动。
// 纯平移(tx/ty)不算,否则在 100% 下拖一下图就再也划不动了。
const magnifiedModel = defineModel<boolean>('magnified', { default: false })

const scale = ref(1)
const tx = ref(0)
const ty = ref(0)

const MIN_SCALE = 0.2
const MAX_SCALE = 8

const transform = computed(
  () => `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`,
)
const zoomed = computed(() => scale.value !== 1 || tx.value !== 0 || ty.value !== 0)
const emitMagnified = () => (magnifiedModel.value = scale.value !== 1)

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
  emitMagnified()
}

// ---------- 指针:1 指拖动,2 指缩放 ----------

const dragging = ref(false)
const pointers = new Map<number, Point>()

let startX = 0
let startY = 0
let baseTx = 0
let baseTy = 0

// pinch 基准(两指按下瞬间确定,整段手势内不变)
let pinchBaseDist = 0
let pinchBaseScale = 1
let pinchBaseTx = 0
let pinchBaseTy = 0
let pinchCenter: Point = { x: 0, y: 0 }

/** 相对元素中心的坐标:transform 以中心为原点,所以锚点也要减掉中心 */
function toLocal(el: HTMLElement, x: number, y: number): Point {
  const rect = el.getBoundingClientRect()
  return { x: x - rect.left - rect.width / 2, y: y - rect.top - rect.height / 2 }
}

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  const el = e.currentTarget as HTMLElement
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  el.setPointerCapture(e.pointerId)

  if (pointers.size === 1) {
    dragging.value = true
    startX = e.clientX
    startY = e.clientY
    baseTx = tx.value
    baseTy = ty.value
    return
  }

  if (pointers.size === 2) {
    // 进入 pinch:冻结基准,拖动让位
    dragging.value = false
    const [a, b] = [...pointers.values()]
    const m = twoPointerMetrics(a, b)
    pinchBaseDist = m.distance
    pinchBaseScale = scale.value
    pinchBaseTx = tx.value
    pinchBaseTy = ty.value
    pinchCenter = toLocal(el, m.center.x, m.center.y)
  }
}

function onPointerMove(e: PointerEvent) {
  if (!pointers.has(e.pointerId)) return
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  const el = e.currentTarget as HTMLElement

  if (pointers.size >= 2) {
    const [a, b] = [...pointers.values()]
    const m = twoPointerMetrics(a, b)
    const next = pinchScale(pinchBaseDist, m.distance, pinchBaseScale, MIN_SCALE, MAX_SCALE)
    // 以起点中点为不动点缩放,再叠加中点自身的位移 —— 两指既能缩放也能平移
    const moved = pinchTranslate(pinchCenter, pinchBaseTx, pinchBaseTy, pinchBaseScale, next)
    const centerNow = toLocal(el, m.center.x, m.center.y)
    tx.value = moved.tx + (centerNow.x - pinchCenter.x)
    ty.value = moved.ty + (centerNow.y - pinchCenter.y)
    scale.value = next
    emitMagnified()
    return
  }

  if (!dragging.value) return
  // 100% 时图片本来就放得下,拖动没有意义,还打架手机上的"滑动切换" —— 直接不响应
  if (scale.value === 1) return
  tx.value = baseTx + (e.clientX - startX)
  ty.value = baseTy + (e.clientY - startY)
  emitMagnified()
}

let lastTapAt = 0
let lastTapX = 0
let lastTapY = 0

/** 触屏没有可靠的 dblclick,自己按"两次轻点间隔 <300ms 且位置接近"判定 */
function maybeDoubleTap(e: PointerEvent) {
  if (e.pointerType === 'mouse') return // 鼠标交给原生 dblclick
  const now = Date.now()
  const doubled =
    now - lastTapAt < 300 && Math.hypot(e.clientX - lastTapX, e.clientY - lastTapY) < 20
  lastTapAt = doubled ? 0 : now
  lastTapX = e.clientX
  lastTapY = e.clientY
  if (doubled) reset()
}

function onPointerUp(e: PointerEvent) {
  if (!pointers.has(e.pointerId)) return
  const wasSingle = pointers.size === 1
  pointers.delete(e.pointerId)
  if (wasSingle) {
    const moved = Math.hypot(e.clientX - startX, e.clientY - startY)
    if (dragging.value && moved < 10) maybeDoubleTap(e)
  }
  dragging.value = false
  if (pointers.size === 1) {
    // 从 pinch 回到单指:重设拖动基准,否则会瞬移
    const [only] = [...pointers.values()]
    startX = only.x
    startY = only.y
    baseTx = tx.value
    baseTy = ty.value
    dragging.value = true
  }
}

function reset() {
  scale.value = 1
  tx.value = 0
  ty.value = 0
  emitMagnified()
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
  /* 触屏手势全由我们自己处理(单指拖动/双指缩放),不能让浏览器抢去滚动或缩放 */
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
