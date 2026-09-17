/**
 * 手势判定的纯函数。放在这里是为了能在 node 环境的 vitest 里直接测,
 * DOM 包装在 composables/longPress.ts、预览里的滑动/缩放见 preview 组件。
 */

/** 长按:按住时间够长且位移没超过阈值 */
export function isLongPress(
  dx: number,
  dy: number,
  heldMs: number,
  delay = 500,
  moveThreshold = 10,
): boolean {
  if (heldMs < delay) return false
  return Math.hypot(dx, dy) <= moveThreshold
}

/** 滑动方向:横轴优先(手指斜着划时不要误判成上下);阈值内算"没滑动" */
export function swipeAxis(
  dx: number,
  dy: number,
  threshold = 60,
): 'left' | 'right' | 'up' | 'down' | null {
  if (Math.abs(dx) >= Math.abs(dy)) {
    if (dx <= -threshold) return 'left'
    if (dx >= threshold) return 'right'
    return null
  }
  if (dy <= -threshold) return 'up'
  if (dy >= threshold) return 'down'
  return null
}

/** 双指缩放:baseDist → dist 的变化乘到 baseScale 上,并钳到 [min, max] */
export function pinchScale(
  baseDist: number,
  dist: number,
  baseScale: number,
  min = 0.2,
  max = 8,
): number {
  if (baseDist <= 0) return baseScale
  return Math.min(max, Math.max(min, baseScale * (dist / baseDist)))
}

export interface Point {
  x: number
  y: number
}

/**
 * 双指缩放时保持锚点(两指中点)不动的平移量,与 ImageViewer 的 wheel 缩放同一套公式:
 * 屏幕点 p 对应的内容坐标 c = (p - t) / s;要让 c 缩放后仍停在 p,则 t' = p - c * s'。
 */
export function pinchTranslate(
  anchor: Point,
  baseTx: number,
  baseTy: number,
  baseScale: number,
  nextScale: number,
): { tx: number; ty: number } {
  if (baseScale <= 0) return { tx: baseTx, ty: baseTy }
  const cx = (anchor.x - baseTx) / baseScale
  const cy = (anchor.y - baseTy) / baseScale
  return { tx: anchor.x - cx * nextScale, ty: anchor.y - cy * nextScale }
}

/** 两个触点的中点与间距,给 pinch 用 */
export function twoPointerMetrics(a: Point, b: Point): { center: Point; distance: number } {
  return {
    center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
    distance: Math.hypot(a.x - b.x, a.y - b.y),
  }
}
