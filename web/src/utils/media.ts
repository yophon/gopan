/** 影音的展示口径与小规则(doc/22 二)。纯函数,单测直接引。 */

/** 秒 → mm:ss,一小时以上 h:mm:ss;非法值返回空串 */
export function formatDuration(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || !Number.isFinite(sec) || sec < 0) return ''
  const total = Math.floor(sec)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

export interface ProgressLike {
  position: number
  duration: number
  finished: boolean
}

/** 「看到 23:15 / 1:42:00」或「第 12 / 300 页」;isPdf 由调用方按预览类型给 */
export function progressText(p: ProgressLike, isPdf: boolean): string {
  if (p.finished) return '已看完'
  if (isPdf) return `第 ${Math.round(p.position)} / ${Math.round(p.duration)} 页`
  const dur = formatDuration(p.duration)
  return dur ? `看到 ${formatDuration(p.position)} / ${dur}` : `看到 ${formatDuration(p.position)}`
}

/** 进度百分比(0–100),给进度条用 */
export function progressPercent(p: ProgressLike): number {
  if (p.finished) return 100
  if (!(p.duration > 0)) return 0
  return Math.min(100, Math.max(0, Math.round((p.position / p.duration) * 100)))
}

/** 超过 10 秒才自动跳过去(太靠前的不值得提示) */
export const RESUME_MIN_SEC = 10

/** 音视频要不要续播:没看完且超过 10 秒,返回要跳到的秒数,否则 null */
export function resumePosition(p: ProgressLike | null | undefined): number | null {
  if (!p || p.finished) return null
  if (!(p.position > RESUME_MIN_SEC)) return null
  // 已经到头的记录(时长变了等)不跳,免得一打开就结束
  if (p.duration > 0 && p.position >= p.duration - 1) return null
  return p.position
}

/** PDF 续读的页码;没记过、看完了、还在第一页返回 null */
export function resumePage(p: ProgressLike | null | undefined): number | null {
  if (!p || p.finished) return null
  const page = Math.floor(p.position)
  return page >= 2 ? page : null
}

/** 与服务端同一口径(doc/22 决策 7):5 分钟以下的音视频、3 页以下的 PDF 不记,省掉白报 */
export const PROGRESS_MIN_MEDIA_SEC = 300
export const PROGRESS_MIN_PDF_PAGES = 3

export function worthSavingProgress(isPdf: boolean, duration: number): boolean {
  if (!Number.isFinite(duration)) return false
  return isPdf ? duration >= PROGRESS_MIN_PDF_PAGES : duration >= PROGRESS_MIN_MEDIA_SEC
}

// ---------- 字幕 ----------

export interface SubtitleLike {
  nodeId: string
  lang: string
  default: boolean
}

/** 默认选中哪一条:服务端标了 default 的优先,其次第一条中文,再次第一条;没有字幕为 -1 */
export function defaultSubtitleIndex(tracks: SubtitleLike[]): number {
  if (tracks.length === 0) return -1
  const flagged = tracks.findIndex((t) => t.default)
  if (flagged >= 0) return flagged
  const zh = tracks.findIndex((t) => t.lang.toLowerCase().startsWith('zh'))
  return zh >= 0 ? zh : 0
}

/** 字幕地址:/sub/{nodeId}?token=…(<track> 没法带 Authorization 头,同 /arc) */
export function subtitleUrl(nodeId: string, token: string): string {
  return `/sub/${encodeURIComponent(nodeId)}?${new URLSearchParams({ token }).toString()}`
}

// ---------- 播放出错 ----------

/** 预签名地址 15 分钟有效;拿到超过 10 分钟就当它可能过期了 */
export const URL_STALE_MS = 10 * 60 * 1000

export type MediaErrorAction = 'ignore' | 'refetch' | 'unsupported' | 'failed'

/**
 * <video> / <audio> 报错后怎么办。MediaError.code:1 中止、2 网络、3 解码、4 格式不支持。
 * 网络错误、或放过一阵 / 地址拿了很久之后才出的错,多半是签名过期(403),重新取地址接着放一次;
 * 刚打开就报 3 / 4 是浏览器真放不了(mkv HEVC、avi 等)。重新取过还不行就不再试。
 */
export function mediaErrorAction(opts: {
  code: number | null | undefined
  retried: boolean
  hadPlayed: boolean
  urlAgeMs: number
}): MediaErrorAction {
  const { code, retried, hadPlayed, urlAgeMs } = opts
  if (code === 1) return 'ignore'
  if (retried) return code === 2 ? 'failed' : 'unsupported'
  if (code === 2) return 'refetch'
  if (hadPlayed || urlAgeMs >= URL_STALE_MS) return 'refetch'
  return 'unsupported'
}

// ---------- 浏览器放不了的视频容器:服务器转成 mp4 再放(doc/31 一) ----------

export type RemuxStatusLike = 'NONE' | 'QUEUED' | 'RUNNING' | 'DONE' | 'UNSUPPORTED' | 'FAILED' | 'UNAVAILABLE'

export interface RemuxLike {
  status: RemuxStatusLike
  url?: string | null
  direct: boolean
}

export type VideoSource =
  | { kind: 'direct'; url: string }
  | { kind: 'remux'; url: string }
  | { kind: 'wait' } // 排队 / 正在转 / 还没开始
  | { kind: 'problem' } // 编码放不了、转失败、太大

/**
 * 视频该放什么:不是这类容器(remux 为空)放原文件;转好了放 mp4;
 * 服务器说原文件多半能放(direct)且还没放失败过,先放原文件;其余等转换或给出原因。
 */
export function pickVideoSource(contentUrl: string | null | undefined, remux: RemuxLike | null | undefined, directFailed: boolean): VideoSource | null {
  if (!remux) return contentUrl ? { kind: 'direct', url: contentUrl } : null
  if (remux.status === 'DONE' && remux.url) return { kind: 'remux', url: remux.url }
  if (remux.direct && !directFailed && contentUrl) return { kind: 'direct', url: contentUrl }
  if (remux.status === 'UNSUPPORTED' || remux.status === 'FAILED' || remux.status === 'UNAVAILABLE') return { kind: 'problem' }
  return { kind: 'wait' }
}

export interface PlayerSubtitle extends SubtitleLike {
  label: string
  /** 内嵌字幕直接给地址;外挂字幕按 nodeId 拼 /sub */
  url?: string
}

/** 外挂字幕在前(自己放的,默认选中以它为准),文件里内嵌的跟在后面;两边都有时内嵌的标一下 */
export function mergeSubtitles(
  external: { nodeId: string; label: string; lang: string; default: boolean }[],
  embedded: { label: string; lang: string; url: string; default: boolean }[],
): PlayerSubtitle[] {
  const extDefault = external.some((t) => t.default)
  return [
    ...external.map((t) => ({ nodeId: t.nodeId, label: t.label, lang: t.lang, default: t.default })),
    ...embedded.map((t, i) => ({
      nodeId: `embedded:${i}`,
      label: external.length > 0 ? `${t.label}(内嵌)` : t.label,
      lang: t.lang,
      default: !extDefault && t.default,
      url: t.url,
    })),
  ]
}
