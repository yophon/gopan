import { describe, expect, it } from 'vitest'

import {
  collapseText,
  dayLabel,
  escapeHtml,
  groupByDay,
  groupMedia,
  isCollapsible,
  isPendingId,
  linkify,
  mergeChatMessages,
  pastedFileName,
  pendingToChatMsg,
  removeChatMessage,
  sharePrefill,
  sourceLabel,
  textByteLength,
  textFileName,
  uploadPercentage,
  type ChatMsg,
} from './chat'

function msg(id: string, createdAt: string): ChatMsg {
  return { id, body: `msg-${id}`, createdAt, source: 'web', node: null }
}

function media(id: string, createdAt: string, kind: 'IMAGE' | 'VIDEO' | 'PDF' = 'IMAGE', thumb = true): ChatMsg {
  return {
    id,
    body: '',
    createdAt,
    source: 'web',
    node: {
      id: `n-${id}`,
      parentId: null,
      name: `${id}.bin`,
      kind: 'FILE',
      size: 1,
      preview: { kind, thumbUrl: thumb ? `/t/${id}` : null },
    },
  }
}

describe('sourceLabel', () => {
  it('web 不标,mcp / webdav 各有标签', () => {
    expect(sourceLabel('web')).toBe('')
    expect(sourceLabel('mcp')).toBe('Agent')
    expect(sourceLabel('webdav')).toBe('WebDAV')
    expect(sourceLabel('')).toBe('')
  })
})

describe('groupMedia', () => {
  const t = (s: number) => `2026-01-01T00:${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}Z`

  it('2 分钟内连续的图片合成一组,文本保持单条', () => {
    const items = [msg('t1', t(0)), media('a', t(10)), media('b', t(20)), media('c', t(130)), msg('t2', t(140))]
    const out = groupMedia(items)
    expect(out.map((x) => x.kind)).toEqual(['single', 'media', 'single'])
    expect(out[1]).toMatchObject({ kind: 'media', id: 'a' })
    expect((out[1] as { messages: ChatMsg[] }).messages.map((m) => m.id)).toEqual(['a', 'b', 'c'])
  })

  it('间隔超过 2 分钟就断开', () => {
    const out = groupMedia([media('a', t(0)), media('b', t(121)), media('c', t(130))])
    expect(out.map((x) => x.kind)).toEqual(['single', 'media'])
    expect((out[1] as { messages: ChatMsg[] }).messages.map((m) => m.id)).toEqual(['b', 'c'])
  })

  it('中间夹一条文本或普通文件就断开', () => {
    const out = groupMedia([media('a', t(0)), msg('x', t(1)), media('b', t(2)), media('c', t(3))])
    expect(out.map((x) => x.kind)).toEqual(['single', 'single', 'media'])
    const out2 = groupMedia([media('a', t(0)), media('p', t(1), 'PDF'), media('b', t(2))])
    expect(out2.map((x) => x.kind)).toEqual(['single', 'single', 'single'])
  })

  it('满 9 个另起一组', () => {
    const items = Array.from({ length: 11 }, (_, i) => media(`m${i}`, t(i)))
    const out = groupMedia(items)
    expect(out.map((x) => x.kind)).toEqual(['media', 'media'])
    expect((out[0] as { messages: ChatMsg[] }).messages).toHaveLength(9)
    expect((out[1] as { messages: ChatMsg[] }).messages).toHaveLength(2)
  })

  it('单张不合并;没有缩略图的图片、pending 消息不参与', () => {
    const out = groupMedia([media('a', t(0)), msg('x', t(1)), media('b', t(2), 'IMAGE', false), media('c', t(3))])
    expect(out.map((x) => x.kind)).toEqual(['single', 'single', 'single', 'single'])
    const pendingLike: ChatMsg = { ...media('p', t(4)), id: 'pending-1' }
    const out2 = groupMedia([media('a', t(3)), pendingLike, media('c', t(5))])
    expect(out2.map((x) => x.kind)).toEqual(['single', 'single', 'single'])
  })
})

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

  it('加载更早的一页插到前面', () => {
    const latest = [msg('x', '2026-01-02T00:00:00Z')]
    const older = [msg('o1', '2026-01-01T00:00:00Z'), msg('o2', '2026-01-01T00:01:00Z')]
    expect(mergeChatMessages(latest, older).map((m) => m.id)).toEqual(['o1', 'o2', 'x'])
  })
})

describe('removeChatMessage / pending', () => {
  it('删除存在的 id 返回新数组,不存在原样返回', () => {
    const list = [msg('a', '1'), msg('b', '2')]
    const out = removeChatMessage(list, 'a')
    expect(out.map((m) => m.id)).toEqual(['b'])
    expect(removeChatMessage(list, 'zzz')).toBe(list)
  })

  it('pending 消息转渲染项,id 可识别', () => {
    const p = pendingToChatMsg({ id: 'pending-1', body: 'hi', createdAt: '2026-01-01T00:00:00Z', status: 'sending' })
    expect(p.node).toBeNull()
    expect(isPendingId(p.id)).toBe(true)
    expect(isPendingId('018f-real')).toBe(false)
  })
})

describe('日期分隔', () => {
  const now = new Date(2026, 9, 13, 15, 0, 0) // 2026-10-13 本地时间

  it('今天 / 昨天 / 同年 / 跨年', () => {
    expect(dayLabel(new Date(2026, 9, 13, 1).toISOString(), now)).toBe('今天')
    expect(dayLabel(new Date(2026, 9, 12, 23, 59).toISOString(), now)).toBe('昨天')
    expect(dayLabel(new Date(2026, 0, 5).toISOString(), now)).toBe('1月5日')
    expect(dayLabel(new Date(2025, 11, 31).toISOString(), now)).toBe('2025-12-31')
    expect(dayLabel('not-a-date', now)).toBe('')
  })

  it('groupByDay 按本地日分组并保持顺序', () => {
    const items = [
      msg('a', new Date(2026, 9, 12, 9).toISOString()),
      msg('b', new Date(2026, 9, 12, 18).toISOString()),
      msg('c', new Date(2026, 9, 13, 8).toISOString()),
    ]
    const groups = groupByDay(items, now)
    expect(groups.map((g) => g.label)).toEqual(['昨天', '今天'])
    expect(groups[0]!.items.map((m) => m.id)).toEqual(['a', 'b'])
    expect(groups[1]!.items.map((m) => m.id)).toEqual(['c'])
    expect(groupByDay([], now)).toEqual([])
  })
})

describe('linkify / 折叠', () => {
  it('escapeHtml 转义五个字符', () => {
    expect(escapeHtml(`<a href="x">&'</a>`)).toBe('&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;')
  })

  it('URL 变链接,其余文本转义,句末标点不进链接', () => {
    const html = linkify('看这个 https://example.com/a?b=1&c=2。 <b>不渲染</b>')
    expect(html).toContain(
      '<a href="https://example.com/a?b=1&amp;c=2" target="_blank" rel="noopener noreferrer">https://example.com/a?b=1&amp;c=2</a>',
    )
    expect(html).toContain('。 &lt;b&gt;不渲染&lt;/b&gt;')
    expect(html).not.toContain('<b>')
  })

  it('没有 URL 的文本只做转义', () => {
    expect(linkify('a < b')).toBe('a &lt; b')
  })

  it('超过 8 行才折叠,折叠取前 8 行', () => {
    const eight = Array.from({ length: 8 }, (_, i) => `l${i}`).join('\n')
    const nine = `${eight}\nl8`
    expect(isCollapsible(eight)).toBe(false)
    expect(isCollapsible(nine)).toBe(true)
    expect(collapseText(nine)).toBe(eight)
    expect(collapseText(eight)).toBe(eight)
  })
})

describe('文件命名 / 文本长度', () => {
  const now = new Date(2026, 9, 13, 14, 30)

  it('粘贴的无名图片按时间命名,有名文件保留原名', () => {
    const png = { name: 'image.png', type: 'image/png' } as File
    expect(pastedFileName(png, now)).toBe('截图 2026-10-13 14-30.png')
    const jpeg = { name: '', type: 'image/jpeg' } as File
    expect(pastedFileName(jpeg, now)).toBe('截图 2026-10-13 14-30.jpg')
    const named = { name: '合同.pdf', type: 'application/pdf' } as File
    expect(pastedFileName(named, now)).toBe('合同.pdf')
  })

  it('文本转存文件名', () => {
    expect(textFileName(now)).toBe('文本 2026-10-13 14-30.txt')
  })

  it('字节长度按 UTF-8 计', () => {
    expect(textByteLength('abc')).toBe(3)
    expect(textByteLength('你好')).toBe(6)
  })
})

describe('sharePrefill', () => {
  it('title/text/url 换行拼接,数组取第一个,空值跳过', () => {
    expect(sharePrefill({ title: 'T', text: ['body'], url: 'https://x.y' })).toBe('T\nbody\nhttps://x.y')
    expect(sharePrefill({ text: '  only  ' })).toBe('only')
    expect(sharePrefill({})).toBe('')
  })

  it('text 已含 url 时不重复', () => {
    expect(sharePrefill({ text: '看 https://x.y', url: 'https://x.y' })).toBe('看 https://x.y')
  })
})

describe('uploadPercentage', () => {
  it('hashing 看 hashedBytes,其余看 uploadedBytes,done 恒 100', () => {
    expect(uploadPercentage({ status: 'hashing', size: 200, hashedBytes: 50, uploadedBytes: 0 })).toBe(25)
    expect(uploadPercentage({ status: 'uploading', size: 200, hashedBytes: 200, uploadedBytes: 100 })).toBe(50)
    expect(uploadPercentage({ status: 'done', size: 200, hashedBytes: 0, uploadedBytes: 0 })).toBe(100)
    expect(uploadPercentage({ status: 'queued', size: 0, hashedBytes: 0, uploadedBytes: 0 })).toBe(0)
  })
})
