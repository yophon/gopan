import { describe, expect, it } from 'vitest'

import { archiveCrumbs, archiveEntryUrl, entryPreviewKind, flagText, isArchiveName } from './archive'

describe('archive utils', () => {
  it('面包屑', () => {
    expect(archiveCrumbs('')).toEqual([])
    expect(archiveCrumbs('a/b')).toEqual([
      { name: 'a', path: 'a' },
      { name: 'b', path: 'a/b' },
    ])
  })
  it('条目地址带 token,下载加 download=1', () => {
    expect(archiveEntryUrl('n1', 3, 'tok')).toBe('/arc/n1/3?token=tok')
    expect(archiveEntryUrl('n1', 3, 'a+b', true)).toBe('/arc/n1/3?token=a%2Bb&download=1')
  })
  it('压缩包名字与服务端同口径', () => {
    for (const n of ['a.zip', 'B.7Z', 'c.rar', 'd.tar.gz', 'e.tgz', 'f.tar.xz', 'g.part1.rar', 'h.part01.rar']) {
      expect(isArchiveName(n)).toBe(true)
    }
    for (const n of ['a.docx', 'b.gz', 'c.part2.rar', 'd.7z.001', 'zip']) {
      expect(isArchiveName(n)).toBe(false)
    }
  })
  it('标记与预览类型', () => {
    expect(flagText('encrypted')).toBe('已加密')
    expect(flagText(null)).toBe('')
    expect(entryPreviewKind('a.PNG')).toBe('image')
    expect(entryPreviewKind('b.pdf')).toBe('pdf')
    expect(entryPreviewKind('c.md')).toBe('text')
  })
})
