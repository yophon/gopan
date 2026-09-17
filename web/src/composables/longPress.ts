import { onScopeDispose, ref } from 'vue'

import { isLongPress } from '@/utils/gesture'

export interface LongPressOptions {
  /** 按住多久算长按,默认 500ms */
  delay?: number
  /** 手指移动超过多少 px 就取消,默认 10px(滚动列表时不能误触发) */
  moveThreshold?: number
  onLongPress: (e: PointerEvent) => void
  /** 没触发长按的"点一下"。注意:不绑原生 click,避免长按后误触发 */
  onTap?: (e: PointerEvent) => void
  /** 默认只对触屏生效;鼠标右键交给 contextmenu */
  touchOnly?: boolean
}

type Phase = 'idle' | 'pending' | 'fired' | 'cancelled'

/**
 * 长按 + 轻点。用 Pointer Events 一份实现覆盖触屏与手写笔:
 * 状态机 idle → pending(按下) → fired(长按触发) | cancelled(移动超阈值)。
 * 长按触发或移动取消后,抬手不再算轻点。
 */
export function useLongPress(opts: LongPressOptions) {
  const { delay = 500, moveThreshold = 10, touchOnly = true } = opts

  const pressed = ref(false)
  let phase: Phase = 'idle'
  let timer: ReturnType<typeof setTimeout> | null = null
  let activeId = -1
  let startX = 0
  let startY = 0
  let startedAt = 0

  function reset() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    phase = 'idle'
    activeId = -1
    pressed.value = false
  }

  function onPointerdown(e: PointerEvent) {
    // 右键/中键不参与:右键留给桌面的 contextmenu
    if (e.button !== 0) return
    if (phase !== 'idle') return
    phase = 'pending'
    activeId = e.pointerId
    startX = e.clientX
    startY = e.clientY
    startedAt = Date.now()
    pressed.value = true
    // 鼠标不排长按定时器,但"点一下"照常生效 —— 否则窄窗口下用鼠标点卡片会毫无反应
    if (touchOnly && e.pointerType === 'mouse') return
    timer = setTimeout(() => {
      timer = null
      phase = 'fired'
      pressed.value = false
      navigator.vibrate?.(10)
      opts.onLongPress(e)
    }, delay)
  }

  function onPointermove(e: PointerEvent) {
    if (phase !== 'pending' || e.pointerId !== activeId) return
    if (isLongPress(e.clientX - startX, e.clientY - startY, Date.now() - startedAt, delay, moveThreshold)) {
      return
    }
    // 超阈值 = 用户在滚动,不是长按
    if (Math.hypot(e.clientX - startX, e.clientY - startY) > moveThreshold) {
      phase = 'cancelled'
      pressed.value = false
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
    }
  }

  function onPointerup(e: PointerEvent) {
    if (e.pointerId !== activeId) return
    const shouldTap = phase === 'pending'
    reset()
    if (shouldTap) opts.onTap?.(e)
  }

  function onPointercancel(e: PointerEvent) {
    if (e.pointerId !== activeId) return
    reset()
  }

  onScopeDispose(reset)

  return {
    /** 长按进行中(用来做按压反馈) */
    pressed,
    handlers: { onPointerdown, onPointermove, onPointerup, onPointercancel },
  }
}
