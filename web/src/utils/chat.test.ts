import { describe, expect, it } from 'vitest'

import { mergeChatMessages, type ChatMsg } from './chat'

function msg(id: string, createdAt: string): ChatMsg {
  return { id, body: `msg-${id}`, createdAt, node: null }
}

describe('mergeChatMessages', () => {
  it('合并两批,按 createdAt 升序', () => {
    const existing = [msg('a', '2026-01-01T00:00:00Z'), msg('c', '2026-01-01T00:02:00Z')]
    const incoming = [msg('b', '2026-01-01T00:01:00Z'), msg('d', '2026-01-01T00:03:00Z')]
    const merged = mergeChatMessages(existing, incoming)
    expect(merged.map((m) => m.id)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('重复 id 只保留一个,输入顺序不影响结果', () => {
    const existing = [msg('a', '2026-01-01T00:00:00Z'), msg('b', '2026-01-01T00:01:00Z')]
    const incoming = [msg('b', '2026-01-01T00:01:00Z'), msg('a', '2026-01-01T00:00:00Z')]
    const merged = mergeChatMessages(existing, incoming)
    expect(merged.map((m) => m.id)).toEqual(['a', 'b'])
  })

  it('轮询增量只新增,不重复,顺序保持', () => {
    let list: ChatMsg[] = []
    list = mergeChatMessages(list, [msg('a', '2026-01-01T00:00:00Z')])
    list = mergeChatMessages(list, [msg('a', '2026-01-01T00:00:00Z'), msg('b', '2026-01-01T00:01:00Z')])
    list = mergeChatMessages(list, [msg('c', '2026-01-01T00:02:00Z')])
    expect(list.map((m) => m.id)).toEqual(['a', 'b', 'c'])
  })
})