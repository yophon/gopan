/** 自动刷新(doc/32 二)的纯函数部分:推送事件 → 要刷新的查询。单测直接引。 */

export interface Change {
  t: 'files' | 'chat' | 'recent' | 'resync' | string
  /** files:变了的文件夹,'' = 根目录 */
  f?: string[]
  /** files:太多了没列 */
  all?: boolean
  /** files:回收站也变了 */
  trash?: boolean
}

/** 一条改动要刷新哪些查询;null = 全部(重连后可能漏了) */
export function keysFor(c: Change): unknown[][] | null {
  switch (c.t) {
    case 'files': {
      const keys: unknown[][] = c.all || !c.f ? [['children']] : c.f.map((id) => ['children', id || null])
      keys.push(['breadcrumb'], ['starred'], ['recent'], ['activities'], ['me'], ['folderPicker'])
      if (c.trash) keys.push(['trash'])
      return keys
    }
    case 'chat':
      return [['chatMessages']]
    case 'recent':
      return [['recent'], ['recentPlays']]
    case 'resync':
      return null
    default:
      return []
  }
}

/** 从 SSE 文本流里切出完整的 data 行 */
export function parseEvents(buf: string): { events: Change[]; rest: string } {
  const events: Change[] = []
  const blocks = buf.replace(/\r\n/g, '\n').split('\n\n')
  const rest = blocks.pop() ?? ''
  for (const block of blocks) {
    const data = block
      .split('\n')
      .filter((l) => l.startsWith('data:'))
      .map((l) => l.slice(5).trimStart())
      .join('\n')
    if (!data) continue
    try {
      events.push(JSON.parse(data) as Change)
    } catch {
      // 坏行跳过
    }
  }
  return { events, rest }
}
