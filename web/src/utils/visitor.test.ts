import { describe, expect, it } from 'vitest'

import { expiryText, mostlyMedia, shareSummary } from './visitor'

const now = new Date('2026-10-23T10:00:00').getTime()
const at = (h: number) => new Date(now + h * 3_600_000).toISOString()

describe('expiryText', () => {
  it('没有期限、已过期都不说', () => {
    expect(expiryText(null, now)).toBe('')
    expect(expiryText(at(-1), now)).toBe('')
  })
  it('当天说「今天」,之后按天向上取整', () => {
    expect(expiryText(at(5), now)).toBe('今天过期')
    expect(expiryText(at(20), now)).toBe('1 天后过期')
    expect(expiryText(at(24 * 7), now)).toBe('7 天后过期')
  })
  it('收集页说「截止」', () => {
    expect(expiryText(at(48), now, '截止')).toBe('2 天后截止')
  })
})

describe('shareSummary', () => {
  it('文件夹:文件数 · 体积 · 期限', () => {
    expect(shareSummary({ kind: 'FOLDER', fileCount: 24, size: 3 * 1024 * 1024, expiresAt: at(60) }, now)).toBe(
      '24 个文件 · 3.0 MB · 3 天后过期',
    )
  })
  it('单个文件只有大小;有密码时字段为空就什么都不说', () => {
    expect(shareSummary({ kind: 'FILE', fileCount: 1, size: 2048 }, now)).toBe('2.0 KB')
    expect(shareSummary({ kind: 'FOLDER', fileCount: null, size: null }, now)).toBe('')
  })
})

describe('mostlyMedia', () => {
  const img = { kind: 'FILE', preview: { kind: 'IMAGE' } }
  const doc = { kind: 'FILE', preview: { kind: 'PDF' } }
  const dir = { kind: 'FOLDER', preview: null }
  it('六成以上是图片视频才用网格,文件夹不算', () => {
    expect(mostlyMedia([img, img, img, doc, dir, dir])).toBe(true)
    expect(mostlyMedia([img, img, doc, doc])).toBe(false)
  })
  it('文件太少不切', () => {
    expect(mostlyMedia([img, img, img])).toBe(false)
  })
})
