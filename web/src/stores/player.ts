import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

import { request } from '@/api/client'
import { NodePreviewDocument, PlaylistDocument } from '@/api/gen/graphql'
import { reportProgress } from '@/composables/media'
import { mediaErrorAction, resumePosition } from '@/utils/media'
import type { ProgressLike } from '@/utils/media'
import { nextIndex, nextPlayMode, parsePlayMode, prevIndex, PREV_RESTART_SEC, shuffleOrder } from '@/utils/player'
import type { PlayMode } from '@/utils/player'

export interface PlayerTrack {
  id: string
  name: string
  parentId?: string | null
}

/** 打开预览时已经拿到的地址与进度,第一首不用再查一次 */
export interface TrackPreset {
  url: string
  progress?: ProgressLike | null
}

const MODE_KEY = 'gopan_player_mode'
const SAVE_EVERY_MS = 15_000
/** 连着几首都放不了就别再自动往下跳了 */
const MAX_SKIP_STREAK = 3

function loadMode(): PlayMode {
  try {
    return parsePlayMode(localStorage.getItem(MODE_KEY))
  } catch {
    return 'order'
  }
}

/** 文件名去掉扩展名,给锁屏 / 媒体键面板当标题 */
function trackTitle(name: string): string {
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(0, i) : name
}

/**
 * 全站唯一的音乐播放器(doc/22 二.4)。<audio> 由 AppShell 里的 MiniPlayer 持有并 attach 进来,
 * 切换页面不中断;预览里的音频面板与底部迷你播放条都绑这一个 store。
 * 每首开播前才去取地址(预签名 15 分钟),出错时重新取一次接着放。
 * 分享访客页不挂 MiniPlayer,attached 为 false,预览里照旧用自己的 <audio>。
 */
export const usePlayerStore = defineStore('player', () => {
  const queue = ref<PlayerTrack[]>([])
  const index = ref(-1)
  const mode = ref<PlayMode>(loadMode())
  /** 随机播放的顺序 */
  const order = ref<number[]>([])
  const playing = ref(false)
  const loading = ref(false)
  const currentTime = ref(0)
  const duration = ref(0)
  const error = ref<string | null>(null)
  /** 刚续播跳过去的位置,给「从 xx 继续 · 从头播放」用;点掉或开始下一首清掉 */
  const resumedFrom = ref<number | null>(null)
  const attached = ref(false)

  const current = computed<PlayerTrack | null>(() => queue.value[index.value] ?? null)

  let el: HTMLAudioElement | null = null
  let loadSeq = 0
  let urlAt = 0
  let retried = false
  let hadPlayed = false
  let wantPlay = false
  let pendingSeek: number | null = null
  let saveTimer: ReturnType<typeof setInterval> | null = null
  let skipStreak = 0

  watch(mode, (m) => {
    try {
      localStorage.setItem(MODE_KEY, m)
    } catch {
      // 记不住就算了
    }
  })

  // ---------- 进度 ----------

  function saveNow() {
    const t = current.value
    if (!t || !el || !el.src) return
    const d = Number.isFinite(el.duration) ? el.duration : duration.value
    // 换地址重载期间位置会先归零,别把 0 报上去
    if (!(d > 0) || !hadPlayed || pendingSeek !== null) return
    reportProgress(t.id, el.currentTime, d)
  }

  function startSaveTimer() {
    stopSaveTimer()
    saveTimer = setInterval(saveNow, SAVE_EVERY_MS)
  }

  function stopSaveTimer() {
    if (saveTimer) clearInterval(saveTimer)
    saveTimer = null
  }

  // ---------- 加载 ----------

  async function fetchUrl(id: string): Promise<{ url: string | null; progress: ProgressLike | null }> {
    const res = await request(NodePreviewDocument, { id })
    return { url: res.node.preview.contentUrl ?? null, progress: res.node.playProgress ?? null }
  }

  async function playEl() {
    if (!el) return
    wantPlay = true
    try {
      await el.play()
    } catch {
      // 自动播放被拦(没有用户手势)等:停在暂停态,点一下播放即可。
      // 换歌打断上一次 play() 也会走到这里,那时新的已经在放了,别误改状态
      if (el?.paused) playing.value = false
    }
  }

  async function loadTrack(i: number, preset?: TrackPreset) {
    if (!el) return
    const track = queue.value[i]
    if (!track) return
    saveNow()
    // 取新地址期间旧的那首先停下,免得它的进度算到新这首头上
    el.pause()
    const seq = ++loadSeq
    index.value = i
    error.value = null
    loading.value = true
    currentTime.value = 0
    duration.value = 0
    resumedFrom.value = null
    retried = false
    hadPlayed = false
    pendingSeek = null
    updateMetadata()
    try {
      const got = preset ? { url: preset.url, progress: preset.progress ?? null } : await fetchUrl(track.id)
      if (seq !== loadSeq || !el) return
      if (!got.url) throw new Error('no url')
      urlAt = Date.now()
      const resume = resumePosition(got.progress)
      if (resume !== null) {
        pendingSeek = resume
        resumedFrom.value = resume
      }
      el.src = got.url
      await playEl()
    } catch {
      if (seq !== loadSeq) return
      loading.value = false
      error.value = '拿不到播放地址'
    }
  }

  /** 签名过期等:重新取地址,回到原位置接着放 */
  async function reload() {
    const track = current.value
    if (!el || !track) return
    const seq = loadSeq
    const at = el.currentTime || currentTime.value
    const resumePlay = wantPlay
    loading.value = true
    try {
      const got = await fetchUrl(track.id)
      if (seq !== loadSeq || !el || !got.url) throw new Error('no url')
      urlAt = Date.now()
      pendingSeek = at > 0 ? at : null
      el.src = got.url
      if (resumePlay) await playEl()
    } catch {
      if (seq !== loadSeq) return
      loading.value = false
      error.value = '播放中断了,网络或地址失效'
    }
  }

  // ---------- 队列 ----------

  function playQueue(tracks: PlayerTrack[], start: number, preset?: TrackPreset) {
    if (tracks.length === 0) return
    queue.value = tracks
    const i = Math.min(Math.max(start, 0), tracks.length - 1)
    order.value = mode.value === 'shuffle' ? shuffleOrder(tracks.length, i) : []
    skipStreak = 0
    void loadTrack(i, preset)
  }

  /**
   * 预览里打开一个音频:正在放的就是它则不打断;否则先放起来,再把同文件夹的音频排进队列。
   */
  async function openFromPreview(track: PlayerTrack, preset?: TrackPreset) {
    if (!el) return
    if (current.value?.id === track.id) {
      if (!playing.value && !error.value) void playEl()
      return
    }
    playQueue([track], 0, preset)
    try {
      const res = await request(PlaylistDocument, { nodeId: track.id })
      if (current.value?.id !== track.id) return
      const list = res.playlist.map((n) => ({ id: n.id, name: n.name, parentId: n.parentId }))
      const i = list.findIndex((n) => n.id === track.id)
      if (i < 0) return
      queue.value = list
      index.value = i
      order.value = mode.value === 'shuffle' ? shuffleOrder(list.length, i) : []
    } catch {
      // 拿不到文件夹列表就只放这一首
    }
  }

  function next(auto = false) {
    if (!el || queue.value.length === 0) return
    const m = nextIndex({ len: queue.value.length, index: index.value, mode: mode.value, order: order.value }, auto)
    if (!m) {
      // 顺序播放放完最后一首:停在这一首的末尾
      playing.value = false
      wantPlay = false
      return
    }
    if (m.order) order.value = m.order
    if (m.index === index.value) {
      el.currentTime = 0
      void playEl()
      return
    }
    void loadTrack(m.index)
  }

  function prev() {
    if (!el || queue.value.length === 0) return
    if (el.currentTime > PREV_RESTART_SEC) {
      el.currentTime = 0
      return
    }
    const m = prevIndex({ len: queue.value.length, index: index.value, mode: mode.value, order: order.value })
    if (!m || m.index === index.value) {
      el.currentTime = 0
      return
    }
    void loadTrack(m.index)
  }

  function playAt(i: number) {
    if (i === index.value) return
    if (mode.value === 'shuffle') order.value = shuffleOrder(queue.value.length, i)
    void loadTrack(i)
  }

  function play() {
    if (!el || !current.value) return
    if (error.value || !el.src) {
      error.value = null
      if (el.src) void reload()
      else void loadTrack(index.value)
      return
    }
    void playEl()
  }

  function pause() {
    wantPlay = false
    el?.pause()
  }

  function toggle() {
    if (playing.value) pause()
    else play()
  }

  function seek(t: number) {
    if (!el || !Number.isFinite(t)) return
    el.currentTime = Math.max(0, Math.min(t, Number.isFinite(el.duration) ? el.duration : t))
    currentTime.value = el.currentTime
  }

  function restart() {
    resumedFrom.value = null
    seek(0)
  }

  function setMode(m: PlayMode) {
    mode.value = m
    order.value = m === 'shuffle' && index.value >= 0 ? shuffleOrder(queue.value.length, index.value) : []
  }

  function cycleMode() {
    setMode(nextPlayMode(mode.value))
  }

  function close() {
    saveNow()
    stopSaveTimer()
    loadSeq++
    wantPlay = false
    if (el) {
      el.pause()
      el.removeAttribute('src')
      el.load()
    }
    queue.value = []
    index.value = -1
    order.value = []
    playing.value = false
    loading.value = false
    error.value = null
    resumedFrom.value = null
    currentTime.value = 0
    duration.value = 0
    updateMetadata()
  }

  // ---------- <audio> 事件 ----------

  function onError() {
    if (!el || !current.value || !el.src) return
    const action = mediaErrorAction({
      code: el.error?.code,
      retried,
      hadPlayed,
      urlAgeMs: Date.now() - urlAt,
    })
    if (action === 'ignore') return
    if (action === 'refetch') {
      retried = true
      void reload()
      return
    }
    loading.value = false
    playing.value = false
    error.value = action === 'unsupported' ? '浏览器放不了这个格式,下载或用安卓 App 听' : '播放中断了,网络或地址失效'
    // 放不了的格式自动跳下一首(单曲循环也往下跳,别原地重试),连着几首都不行就停
    if (action === 'unsupported' && queue.value.length > 1 && skipStreak < MAX_SKIP_STREAK) {
      skipStreak++
      const seq = loadSeq
      setTimeout(() => {
        if (seq !== loadSeq) return
        const m = nextIndex(
          {
            len: queue.value.length,
            index: index.value,
            mode: mode.value === 'one' ? 'order' : mode.value,
            order: order.value,
          },
          true,
        )
        if (!m) return
        if (m.order) order.value = m.order
        void loadTrack(m.index)
      }, 1500)
    }
  }

  const handlers: Record<string, () => void> = {
    loadedmetadata: () => {
      if (!el) return
      if (Number.isFinite(el.duration)) duration.value = el.duration
      if (pendingSeek !== null) {
        // 记录的位置超出了实际时长(文件换过等)就不跳
        if (!Number.isFinite(el.duration) || pendingSeek < el.duration - 1) el.currentTime = pendingSeek
        else resumedFrom.value = null
        pendingSeek = null
      }
    },
    durationchange: () => {
      if (el && Number.isFinite(el.duration)) duration.value = el.duration
      updatePositionState()
    },
    timeupdate: () => {
      if (!el) return
      currentTime.value = el.currentTime
      if (el.currentTime > 0 && !el.paused && !loading.value) hadPlayed = true
    },
    play: () => {
      playing.value = true
      startSaveTimer()
      setPlaybackState('playing')
    },
    playing: () => {
      loading.value = false
      error.value = null
      retried = false
      skipStreak = 0
    },
    waiting: () => {
      loading.value = true
    },
    canplay: () => {
      loading.value = false
    },
    pause: () => {
      playing.value = false
      stopSaveTimer()
      saveNow()
      setPlaybackState('paused')
    },
    seeked: () => updatePositionState(),
    ended: () => {
      playing.value = false
      stopSaveTimer()
      saveNow()
      next(true)
    },
    error: onError,
  }

  function attach(audio: HTMLAudioElement) {
    if (el === audio) return
    if (el) detach()
    el = audio
    for (const [ev, fn] of Object.entries(handlers)) audio.addEventListener(ev, fn)
    attached.value = true
    setupMediaSession()
  }

  function detach() {
    if (!el) return
    close()
    for (const [ev, fn] of Object.entries(handlers)) el.removeEventListener(ev, fn)
    el = null
    attached.value = false
  }

  /** 页面关掉 / 切到后台前补报一次 */
  function flush() {
    saveNow()
  }

  // ---------- 锁屏与媒体键(Media Session) ----------

  function mediaSession(): MediaSession | null {
    return typeof navigator !== 'undefined' && 'mediaSession' in navigator ? navigator.mediaSession : null
  }

  function setupMediaSession() {
    const ms = mediaSession()
    if (!ms) return
    const set = (action: MediaSessionAction, fn: MediaSessionActionHandler) => {
      try {
        ms.setActionHandler(action, fn)
      } catch {
        // 老浏览器不认某些动作
      }
    }
    set('play', () => play())
    set('pause', () => pause())
    set('previoustrack', () => prev())
    set('nexttrack', () => next(false))
    set('seekto', (d) => {
      if (d.seekTime !== undefined && d.seekTime !== null) seek(d.seekTime)
    })
    set('seekbackward', (d) => seek((el?.currentTime ?? 0) - (d.seekOffset ?? 10)))
    set('seekforward', (d) => seek((el?.currentTime ?? 0) + (d.seekOffset ?? 10)))
  }

  function updateMetadata() {
    const ms = mediaSession()
    if (!ms) return
    const t = current.value
    try {
      ms.metadata =
        t && typeof MediaMetadata !== 'undefined'
          ? new MediaMetadata({ title: trackTitle(t.name), artist: 'gopan', album: '' })
          : null
    } catch {
      // 忽略
    }
  }

  function setPlaybackState(state: MediaSessionPlaybackState) {
    const ms = mediaSession()
    if (ms) ms.playbackState = state
  }

  function updatePositionState() {
    const ms = mediaSession()
    if (!ms || !el || typeof ms.setPositionState !== 'function') return
    const d = el.duration
    if (!Number.isFinite(d) || d <= 0) return
    try {
      ms.setPositionState({ duration: d, position: Math.min(el.currentTime, d), playbackRate: el.playbackRate || 1 })
    } catch {
      // position 超出等,忽略
    }
  }

  return {
    queue,
    index,
    mode,
    playing,
    loading,
    currentTime,
    duration,
    error,
    resumedFrom,
    attached,
    current,
    attach,
    detach,
    flush,
    openFromPreview,
    playQueue,
    playAt,
    next,
    prev,
    play,
    pause,
    toggle,
    seek,
    restart,
    setMode,
    cycleMode,
    close,
  }
})
