import { ref, watch } from 'vue'
import type { QueryClient } from '@tanstack/vue-query'

import { singleFlightRefresh } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { keysFor, parseEvents, type Change } from '@/utils/realtime'

/**
 * 自动刷新(doc/32 二):连着 GET /api/events(Server-Sent Events),别处(手机、别的标签页、WebDAV、
 * 分享访客)改了网盘,这里只刷新受影响的列表。
 *
 * - 用 fetch 读流而不是 EventSource:要带 Authorization 头,凭据不进地址。
 * - 页面在后台时断开,回到前台再连(vue-query 回到前台本来就会重拉一遍)。
 * - 服务端每 5 分钟断一次让客户端带新凭据重连;出错退避重连,连不上时各页面原有的刷新照旧。
 */

/** 推送连接是否连着:传输助手据此把轮询放慢 */
export const realtimeConnected = ref(false)

const MAX_BACKOFF = 60_000

export function startRealtime(queryClient: QueryClient): () => void {
  const auth = useAuthStore()
  let ctrl: AbortController | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  let backoff = 2000
  let stopped = false

  const pending = new Map<string, unknown[]>()
  let flushTimer: ReturnType<typeof setTimeout> | null = null
  function apply(c: Change) {
    const keys = keysFor(c)
    if (keys === null) {
      void queryClient.invalidateQueries()
      return
    }
    for (const k of keys) pending.set(JSON.stringify(k), k)
    // 一批改动常连着来几条(多选移动、传一串文件),攒 200ms 再刷
    flushTimer ??= setTimeout(() => {
      flushTimer = null
      for (const k of pending.values()) void queryClient.invalidateQueries({ queryKey: k })
      pending.clear()
    }, 200)
  }

  function disconnect() {
    if (timer) clearTimeout(timer)
    timer = null
    ctrl?.abort()
    ctrl = null
    realtimeConnected.value = false
  }

  function later(ms: number) {
    if (stopped || timer) return
    timer = setTimeout(() => {
      timer = null
      void connect()
    }, ms)
  }

  async function connect() {
    if (stopped || ctrl || document.visibilityState === 'hidden') return
    const token = auth.accessToken
    if (!token) return
    const mine = new AbortController()
    ctrl = mine
    try {
      const res = await fetch('/api/events', {
        headers: { Authorization: `Bearer ${token}` },
        signal: mine.signal,
        cache: 'no-store',
      })
      if (res.status === 401) {
        ctrl = null
        if (await singleFlightRefresh(token)) later(0)
        return
      }
      if (!res.ok || !res.body) throw new Error(`events ${res.status}`)
      realtimeConnected.value = true
      backoff = 2000
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
      let buf = ''
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        const parsed = parseEvents(buf + value)
        buf = parsed.rest
        parsed.events.forEach(apply)
      }
      // 服务端到点断开:马上重连
      ctrl = null
      realtimeConnected.value = false
      later(0)
    } catch {
      if (mine.signal.aborted) return
      ctrl = null
      realtimeConnected.value = false
      later(backoff)
      backoff = Math.min(backoff * 2, MAX_BACKOFF)
    }
  }

  function onVisibility() {
    if (document.visibilityState === 'hidden') disconnect()
    else void connect()
  }
  document.addEventListener('visibilitychange', onVisibility)
  const unwatch = watch(
    () => auth.accessToken,
    (t) => {
      if (!t) disconnect()
      else void connect()
    },
    { immediate: true },
  )

  return () => {
    stopped = true
    unwatch()
    document.removeEventListener('visibilitychange', onVisibility)
    disconnect()
    if (flushTimer) clearTimeout(flushTimer)
  }
}
