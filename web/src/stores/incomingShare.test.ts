import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useIncomingShareStore } from './incomingShare'

function file(name: string): File {
  return new File(['x'], name, { type: 'text/plain' })
}

describe('incomingShare store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('空分享不入队', () => {
    const s = useIncomingShareStore()
    s.push({})
    s.push({ text: '   ', files: [] })
    expect(s.pending).toBeNull()
  })

  it('文本以换行拼接,文件追加', () => {
    const s = useIncomingShareStore()
    s.push({ text: '第一段' })
    s.push({ text: '第二段', files: [file('a.txt')] })
    s.push({ files: [file('b.txt')] })
    expect(s.pending).toEqual({ text: '第一段\n第二段', files: [expect.any(File), expect.any(File)] })
    expect(s.pending!.files!.map((f) => f.name)).toEqual(['a.txt', 'b.txt'])
  })

  it('只有文件时不带 text 字段', () => {
    const s = useIncomingShareStore()
    s.push({ files: [file('a.txt')] })
    expect(s.pending).not.toHaveProperty('text')
  })

  it('take 取走并清空,再次 take 为 null', () => {
    const s = useIncomingShareStore()
    s.push({ text: 'hi' })
    expect(s.take()).toEqual({ text: 'hi' })
    expect(s.pending).toBeNull()
    expect(s.take()).toBeNull()
  })
})
