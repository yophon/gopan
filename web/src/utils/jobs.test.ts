import { describe, expect, it } from 'vitest'

import { isActiveJob, jobPercent, jobStatusText, jobWorthShowing, parseUrls } from './jobs'
import type { JobLike } from './jobs'

const base: JobLike = {
  kind: 'FETCH_URL',
  status: 'RUNNING',
  bytesDone: 0,
  bytesTotal: null,
  filesDone: 0,
  filesTotal: null,
  filesSkipped: 0,
}

describe('parseUrls', () => {
  it('一行一个、去重、只要 http(s)', () => {
    expect(
      parseUrls('https://a.com/x.zip\nhttp://b.com/y\nftp://c.com/z\nhttps://a.com/x.zip\n\n  not a url'),
    ).toEqual(['https://a.com/x.zip', 'http://b.com/y'])
  })
  it('从"标题 链接"里挑出链接,去掉两边的标点', () => {
    expect(parseUrls('看看这个「https://example.com/报告.pdf」,')).toEqual(['https://example.com/报告.pdf'])
    expect(parseUrls('【会议纪要】https://example.com/m.docx 点开看')).toEqual(['https://example.com/m.docx'])
    expect(parseUrls('看看这个 <https://example.com/a.pdf>。')).toEqual(['https://example.com/a.pdf'])
    expect(parseUrls('报告 https://example.com/b.pdf，')).toEqual(['https://example.com/b.pdf'])
    expect(parseUrls('（https://example.com/c.pdf）')).toEqual(['https://example.com/c.pdf'])
  })
})

describe('jobPercent', () => {
  it('有总量按字节算,没有返回 null', () => {
    expect(jobPercent({ ...base, bytesDone: 50, bytesTotal: 200 })).toBe(25)
    expect(jobPercent({ ...base, bytesDone: 50 })).toBeNull()
    expect(jobPercent({ ...base, status: 'DONE' })).toBe(100)
  })
  it('解压按文件个数算', () => {
    expect(jobPercent({ ...base, kind: 'EXTRACT_ARCHIVE', filesDone: 3, filesTotal: 4 })).toBe(75)
  })
})

describe('jobStatusText', () => {
  it('各状态', () => {
    expect(jobStatusText({ ...base, status: 'QUEUED' })).toBe('排队中')
    expect(jobStatusText({ ...base, status: 'QUEUED', errorMessage: '连接源站超时' })).toBe('等待重试:连接源站超时')
    expect(jobStatusText({ ...base, bytesDone: 1024, bytesTotal: 2048 }, 512)).toBe('1.0 KB / 2.0 KB · 512 B/s')
    expect(jobStatusText({ ...base, status: 'DONE', bytesDone: 2048 })).toBe('已完成 · 2.0 KB')
    expect(jobStatusText({ ...base, status: 'DONE', errorCode: 'GOT_HTML' })).toContain('网页')
    expect(jobStatusText({ ...base, status: 'FAILED', errorMessage: '源站返回 HTTP 404' })).toBe('源站返回 HTTP 404')
    expect(jobStatusText({ ...base, status: 'CANCELED' })).toBe('已取消')
    expect(
      jobStatusText({ ...base, kind: 'EXTRACT_ARCHIVE', status: 'DONE', filesDone: 9, filesSkipped: 1 }),
    ).toBe('已解压 9 个文件,跳过 1 个')
    expect(jobStatusText({ ...base, kind: 'EXTRACT_ARCHIVE', filesDone: 2, filesTotal: 5 })).toBe('解压中 2 / 5 个文件')
  })
  it('isActiveJob', () => {
    expect(isActiveJob({ status: 'QUEUED' })).toBe(true)
    expect(isActiveJob({ status: 'DONE' })).toBe(false)
  })
})

describe('jobWorthShowing', () => {
  const now = Date.parse('2026-10-21T12:00:00Z')
  it('进行中的、刚结束的、一天内失败的', () => {
    expect(jobWorthShowing({ status: 'RUNNING' }, now)).toBe(true)
    expect(jobWorthShowing({ status: 'DONE', finishedAt: '2026-10-21T11:55:00Z' }, now)).toBe(true)
    expect(jobWorthShowing({ status: 'DONE', finishedAt: '2026-10-21T11:00:00Z' }, now)).toBe(false)
    expect(jobWorthShowing({ status: 'FAILED', finishedAt: '2026-10-21T01:00:00Z' }, now)).toBe(true)
    expect(jobWorthShowing({ status: 'FAILED', finishedAt: '2026-10-19T01:00:00Z' }, now)).toBe(false)
    expect(jobWorthShowing({ status: 'CANCELED', finishedAt: null }, now)).toBe(false)
  })
})
