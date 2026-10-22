import { describe, expect, it } from 'vitest'

import {
  defaultFolderText,
  feedInputFrom,
  feedKindTag,
  feedModes,
  feedStatus,
  githubRepo,
  intervalText,
  itemStateMeta,
  splitWords,
  validateFeedForm,
} from './feeds'

describe('标签', () => {
  it('条目状态', () => {
    expect(itemStateMeta.NEW.label).toBe('新')
    expect(itemStateMeta.SKIPPED.label).toBe('已跳过')
    expect(itemStateMeta.QUEUED.label).toBe('处理中')
    expect(itemStateMeta.DONE.label).toBe('已完成')
    expect(itemStateMeta.FAILED).toEqual({ label: '失败', type: 'danger' })
  })
  it('类型:GitHub / 播客(RSS + 下载附件)/ RSS', () => {
    expect(feedKindTag({ kind: 'GITHUB', mode: 'DOWNLOAD' })).toBe('GitHub')
    expect(feedKindTag({ kind: 'RSS', mode: 'DOWNLOAD' })).toBe('播客')
    expect(feedKindTag({ kind: 'RSS', mode: 'CLIP' })).toBe('RSS')
  })
  it('GitHub 不能剪藏', () => {
    expect(feedModes('GITHUB')).toEqual(['DOWNLOAD', 'NONE'])
    expect(feedModes('RSS')).toEqual(['DOWNLOAD', 'CLIP', 'NONE'])
  })
  it('状态:文件夹没了优先,其次暂停、失败', () => {
    const base = { enabled: true, folder: { id: 'f' }, failCount: 0, lastError: null }
    expect(feedStatus(base).label).toBe('正常')
    expect(feedStatus({ ...base, folder: null }).label).toBe('文件夹不在了')
    expect(feedStatus({ ...base, enabled: false }).label).toBe('已暂停')
    expect(feedStatus({ ...base, failCount: 3, lastError: 'HTTP 404' })).toEqual({ label: '检查失败 3 次', type: 'danger' })
  })
})

describe('检查间隔', () => {
  it('小时、天、分钟', () => {
    expect(intervalText(60)).toBe('每小时')
    expect(intervalText(360)).toBe('每 6 小时')
    expect(intervalText(1440)).toBe('每天')
    expect(intervalText(2880)).toBe('每 2 天')
    expect(intervalText(90)).toBe('每 90 分钟')
    expect(intervalText(0)).toBe('—')
  })
})

describe('GitHub 仓库', () => {
  it('owner/repo 与 github.com 链接', () => {
    expect(githubRepo('yophon/gopan')).toBe('yophon/gopan')
    expect(githubRepo(' https://github.com/yophon/gopan/releases ')).toBe('yophon/gopan')
    expect(githubRepo('https://github.com/a/b.git')).toBe('a/b')
  })
  it('普通地址、github 上的 atom、不像仓库的都不算', () => {
    expect(githubRepo('https://example.com/feed.xml')).toBeNull()
    expect(githubRepo('https://github.com/yophon/gopan/releases.atom')).toBeNull()
    expect(githubRepo('https://github.com/yophon')).toBeNull()
    expect(githubRepo('not a repo')).toBeNull()
    expect(githubRepo('a/../b')).toBeNull()
  })
})

describe('表单', () => {
  it('默认文件夹', () => {
    expect(defaultFolderText('科技早知道')).toBe('/订阅/科技早知道')
    expect(defaultFolderText(' ')).toBe('/订阅/<标题>')
  })
  it('关键词拆分', () => {
    expect(splitWords('预告, 花絮，预告  直播')).toEqual(['预告', '花絮', '直播'])
    expect(splitWords('')).toEqual([])
  })
  it('校验', () => {
    expect(validateFeedForm({ intervalMinutes: 360, keepLast: 0, initial: 1 })).toBeNull()
    expect(validateFeedForm({ intervalMinutes: 30, keepLast: 0 })).toContain('1 小时')
    expect(validateFeedForm({ intervalMinutes: 20000, keepLast: 0 })).toContain('7 天')
    expect(validateFeedForm({ intervalMinutes: 60, keepLast: -1 })).toContain('1000')
    expect(validateFeedForm({ intervalMinutes: 60, keepLast: 3, initial: 99 })).toContain('0 到 50')
  })
  it('修改是整体替换:其余字段原样带上,只改指定的', () => {
    const feed = {
      title: 'T',
      folder: { id: 'fd' },
      mode: 'DOWNLOAD' as const,
      includeWords: 'a',
      excludeWords: 'b',
      assetPattern: '*.apk',
      prerelease: true,
      keepLast: 3,
      intervalMinutes: 360,
      enabled: true,
    }
    expect(feedInputFrom(feed, { enabled: false })).toEqual({
      title: 'T',
      folderId: 'fd',
      mode: 'DOWNLOAD',
      includeWords: 'a',
      excludeWords: 'b',
      assetPattern: '*.apk',
      prerelease: true,
      keepLast: 3,
      intervalMinutes: 360,
      enabled: false,
    })
    expect(feedInputFrom({ ...feed, folder: null }).folderId).toBeNull()
  })
})
