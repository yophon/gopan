import { describe, expect, it } from 'vitest'

import { recordToShare, shareIdFrom, takeShareRecord } from './shareTarget'

describe('shareTarget', () => {
  it('shareIdFrom 只认非空字符串,数组取第一个', () => {
    expect(shareIdFrom({})).toBeNull()
    expect(shareIdFrom({ share: '' })).toBeNull()
    expect(shareIdFrom({ share: 'abc' })).toBe('abc')
    expect(shareIdFrom({ share: ['x', 'y'] })).toBe('x')
    expect(shareIdFrom({ share: 3 })).toBeNull()
  })

  it('recordToShare 拼标题/正文/链接并去重,blob 包成 File', () => {
    const blob = new Blob(['png'], { type: 'image/png' })
    const out = recordToShare({
      id: '1',
      title: '一篇文章',
      text: '一篇文章 https://a.example/x',
      url: 'https://a.example/x',
      files: [{ name: 'p.png', type: 'image/png', blob }],
    })
    // title 与 url 都包含在 text 里 → 只剩 text
    expect(out.text).toBe('一篇文章 https://a.example/x')
    expect(out.files).toHaveLength(1)
    expect(out.files![0]!.name).toBe('p.png')
    expect(out.files![0]!.type).toBe('image/png')
  })

  it('recordToShare 空记录不带 text / files 字段;文件名缺失用占位', () => {
    expect(recordToShare({ id: '1' })).toEqual({})
    const out = recordToShare({ id: '2', files: [{ name: '', type: '', blob: new Blob(['x'], { type: 'text/plain' }) }] })
    expect(out).not.toHaveProperty('text')
    expect(out.files![0]!.name).toBe('shared')
    expect(out.files![0]!.type).toBe('text/plain')
  })

  it('takeShareRecord 在没有 indexedDB 的环境返回 null', async () => {
    expect(typeof indexedDB).toBe('undefined')
    expect(await takeShareRecord('any')).toBeNull()
  })
})
