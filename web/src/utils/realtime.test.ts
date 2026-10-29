import { describe, expect, it } from 'vitest'

import { keysFor, parseEvents } from './realtime'

describe('keysFor', () => {
  it('文件夹改动:只刷那几个文件夹的列表,外加名字会出现的地方', () => {
    const keys = keysFor({ t: 'files', f: ['a', ''] })!
    expect(keys).toContainEqual(['children', 'a'])
    expect(keys).toContainEqual(['children', null])
    expect(keys).toContainEqual(['recent'])
    expect(keys).not.toContainEqual(['trash'])
  })

  it('太多了没列、或回收站变了', () => {
    const keys = keysFor({ t: 'files', all: true, trash: true })!
    expect(keys).toContainEqual(['children'])
    expect(keys).toContainEqual(['trash'])
  })

  it('传输助手、最近、重连', () => {
    expect(keysFor({ t: 'chat' })).toEqual([['chatMessages']])
    expect(keysFor({ t: 'recent' })).toEqual([['recent'], ['recentPlays']])
    expect(keysFor({ t: 'resync' })).toBeNull()
    expect(keysFor({ t: 'later' })).toEqual([])
  })
})

describe('parseEvents', () => {
  it('切出完整的事件,半截的留到下一块;注释行(心跳)跳过', () => {
    const a = parseEvents('retry: 3000\n: ok\n\ndata: {"t":"chat"}\n\ndata: {"t":"fi')
    expect(a.events).toEqual([{ t: 'chat' }])
    const b = parseEvents(a.rest + 'les","f":[""]}\n\n: ping\n\n')
    expect(b.events).toEqual([{ t: 'files', f: [''] }])
    expect(b.rest).toBe('')
  })

  it('CRLF 与坏行', () => {
    expect(parseEvents('data: {"t":"recent"}\r\n\r\ndata: oops\n\n').events).toEqual([{ t: 'recent' }])
  })
})

describe('服务器上跑的助手(doc/34)', () => {
  it('这一轮的状态变了才刷新对话列表,过程中的事件不刷', () => {
    expect(keysFor({ t: 'assistant', c: 'c1', e: { type: 'run', run: { id: 'r', status: 'waiting' } } })).toEqual([['aiConversations']])
    expect(keysFor({ t: 'assistant', c: 'c1', e: { type: 'live', text: '…' } })).toEqual([])
  })
})
