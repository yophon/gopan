import { describe, expect, it } from 'vitest'

import {
  defaultSubtitleIndex,
  formatDuration,
  mediaErrorAction,
  mergeSubtitles,
  pickVideoSource,
  progressPercent,
  progressText,
  resumePage,
  resumePosition,
  subtitleUrl,
  URL_STALE_MS,
  worthSavingProgress,
} from './media'

describe('formatDuration', () => {
  it('一小时以内 mm:ss', () => {
    expect(formatDuration(0)).toBe('00:00')
    expect(formatDuration(5)).toBe('00:05')
    expect(formatDuration(1395.7)).toBe('23:15')
    expect(formatDuration(3599)).toBe('59:59')
  })
  it('一小时以上 h:mm:ss', () => {
    expect(formatDuration(3600)).toBe('1:00:00')
    expect(formatDuration(6120)).toBe('1:42:00')
    expect(formatDuration(36005)).toBe('10:00:05')
  })
  it('非法值给空串', () => {
    expect(formatDuration(null)).toBe('')
    expect(formatDuration(undefined)).toBe('')
    expect(formatDuration(-1)).toBe('')
    expect(formatDuration(Number.NaN)).toBe('')
    expect(formatDuration(Number.POSITIVE_INFINITY)).toBe('')
  })
})

describe('progressText / progressPercent', () => {
  it('音视频:看到 xx / 总长', () => {
    expect(progressText({ position: 1395, duration: 6120, finished: false }, false)).toBe('看到 23:15 / 1:42:00')
  })
  it('PDF:第几页', () => {
    expect(progressText({ position: 12, duration: 300, finished: false }, true)).toBe('第 12 / 300 页')
  })
  it('看完了', () => {
    expect(progressText({ position: 6100, duration: 6120, finished: true }, false)).toBe('已看完')
    expect(progressPercent({ position: 1, duration: 6120, finished: true })).toBe(100)
  })
  it('百分比钳在 0–100,时长未知为 0', () => {
    expect(progressPercent({ position: 30, duration: 120, finished: false })).toBe(25)
    expect(progressPercent({ position: 300, duration: 120, finished: false })).toBe(100)
    expect(progressPercent({ position: 30, duration: 0, finished: false })).toBe(0)
  })
})

describe('resumePosition / resumePage', () => {
  it('超过 10 秒且没看完才续播', () => {
    expect(resumePosition({ position: 1395, duration: 6120, finished: false })).toBe(1395)
    expect(resumePosition({ position: 10, duration: 6120, finished: false })).toBeNull()
    expect(resumePosition({ position: 1395, duration: 6120, finished: true })).toBeNull()
    expect(resumePosition(null)).toBeNull()
  })
  it('记录已经到头的不跳', () => {
    expect(resumePosition({ position: 6120, duration: 6120, finished: false })).toBeNull()
  })
  it('PDF 从第 2 页起才续读', () => {
    expect(resumePage({ position: 12, duration: 300, finished: false })).toBe(12)
    expect(resumePage({ position: 1, duration: 300, finished: false })).toBeNull()
    expect(resumePage({ position: 300, duration: 300, finished: true })).toBeNull()
    expect(resumePage(undefined)).toBeNull()
  })
})

describe('worthSavingProgress', () => {
  it('5 分钟以下的音视频、3 页以下的 PDF 不报', () => {
    expect(worthSavingProgress(false, 299)).toBe(false)
    expect(worthSavingProgress(false, 300)).toBe(true)
    expect(worthSavingProgress(true, 2)).toBe(false)
    expect(worthSavingProgress(true, 3)).toBe(true)
    expect(worthSavingProgress(false, Number.NaN)).toBe(false)
  })
})

describe('defaultSubtitleIndex', () => {
  const t = (lang: string, def = false) => ({ nodeId: lang, lang, default: def })
  it('没有字幕为 -1', () => {
    expect(defaultSubtitleIndex([])).toBe(-1)
  })
  it('服务端标了 default 的优先', () => {
    expect(defaultSubtitleIndex([t('zh-Hans'), t('en', true)])).toBe(1)
  })
  it('没标 default:第一条中文,其次第一条', () => {
    expect(defaultSubtitleIndex([t('en'), t('ja'), t('zh-Hant'), t('zh-Hans')])).toBe(2)
    expect(defaultSubtitleIndex([t('en'), t('')])).toBe(0)
  })
})

describe('subtitleUrl', () => {
  it('token 走查询串并转义', () => {
    expect(subtitleUrl('n1', 'a+b/c')).toBe('/sub/n1?token=a%2Bb%2Fc')
  })
})

describe('mediaErrorAction', () => {
  const base = { retried: false, hadPlayed: false, urlAgeMs: 0 }
  it('中止不处理', () => {
    expect(mediaErrorAction({ ...base, code: 1 })).toBe('ignore')
  })
  it('网络错误先重新取地址', () => {
    expect(mediaErrorAction({ ...base, code: 2 })).toBe('refetch')
  })
  it('刚打开就解码 / 格式错误:浏览器放不了', () => {
    expect(mediaErrorAction({ ...base, code: 3 })).toBe('unsupported')
    expect(mediaErrorAction({ ...base, code: 4 })).toBe('unsupported')
  })
  it('放过一阵或地址旧了才报 4:当签名过期,重新取', () => {
    expect(mediaErrorAction({ ...base, code: 4, hadPlayed: true })).toBe('refetch')
    expect(mediaErrorAction({ ...base, code: 4, urlAgeMs: URL_STALE_MS })).toBe('refetch')
  })
  it('重新取过还失败就不再试', () => {
    expect(mediaErrorAction({ ...base, code: 2, retried: true })).toBe('failed')
    expect(mediaErrorAction({ ...base, code: 4, retried: true, hadPlayed: true })).toBe('unsupported')
  })
})

describe('pickVideoSource / mergeSubtitles(doc/31 一)', () => {
  it('不是要转的容器就放原文件', () => {
    expect(pickVideoSource('/o', null, false)).toEqual({ kind: 'direct', url: '/o' })
    expect(pickVideoSource(null, null, false)).toBeNull()
  })
  it('转好了放 mp4;能直接放的先放原文件,放失败了才等转换', () => {
    expect(pickVideoSource('/o', { status: 'DONE', url: '/r', direct: true }, false)).toEqual({ kind: 'remux', url: '/r' })
    expect(pickVideoSource('/o', { status: 'NONE', direct: true }, false)).toEqual({ kind: 'direct', url: '/o' })
    expect(pickVideoSource('/o', { status: 'NONE', direct: true }, true)).toEqual({ kind: 'wait' })
    expect(pickVideoSource('/o', { status: 'RUNNING', direct: false }, false)).toEqual({ kind: 'wait' })
    expect(pickVideoSource('/o', { status: 'UNSUPPORTED', direct: false }, false)).toEqual({ kind: 'problem' })
    expect(pickVideoSource('/o', { status: 'FAILED', direct: true }, true)).toEqual({ kind: 'problem' })
  })
  it('外挂字幕在前且默认以它为准', () => {
    const ext = [{ nodeId: 'n1', label: '简体中文', lang: 'zh-Hans', default: true }]
    const emb = [{ label: '简体中文', lang: 'zh-Hans', url: '/remux/a/sub0.vtt', default: true }]
    const merged = mergeSubtitles(ext, emb)
    expect(merged.map((t) => [t.label, t.default, t.url])).toEqual([
      ['简体中文', true, undefined],
      ['简体中文(内嵌)', false, '/remux/a/sub0.vtt'],
    ])
    expect(mergeSubtitles([], emb)[0]).toMatchObject({ label: '简体中文', default: true, nodeId: 'embedded:0' })
  })
})
