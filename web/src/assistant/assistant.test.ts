import { describe, expect, it } from 'vitest'
import type { AgentMessage } from '@yophon/tau-kernel'

import { localPath } from './agent'
import { describeCall, toolLabel } from './describe'
import { decide, mcpToolName, scopePermissions, toolMetas } from './policy'
import { ConflictError, GopanSessionStore } from './session'
import { describeContext } from './state'
import { buildTranscript, friendlyError, stripContext, titleFrom, withContext } from './transcript'

const metas = toolMetas([
  { name: 'list_files', annotations: { readOnlyHint: true }, _meta: { 'gopan/scopes': ['files:read'] } },
  { name: 'move_nodes', annotations: { destructiveHint: false }, _meta: { 'gopan/scopes': ['files:write'] } },
  { name: 'trash_nodes', annotations: { destructiveHint: true }, _meta: { 'gopan/scopes': ['files:delete'] } },
  { name: 'batch_nodes', annotations: { destructiveHint: true }, _meta: { 'gopan/scopes': ['files:write', 'files:delete'] } },
  { name: 'odd' },
])
const groups = (over: Record<string, 'allow' | 'ask' | 'deny'> = {}) =>
  scopePermissions([
    { key: 'files:read', scopes: ['files:read', 'files:download'], permission: over['files:read'] ?? 'allow' },
    { key: 'files:write', scopes: ['files:write'], permission: over['files:write'] ?? 'ask' },
    { key: 'files:delete', scopes: ['files:delete'], permission: over['files:delete'] ?? 'ask' },
  ])

describe('助手的权限', () => {
  it('按权限组判,一个工具属于几组取最严的', () => {
    const none = new Set<string>()
    expect(decide(metas.get(mcpToolName('list_files')), groups(), none)).toBe('allow')
    expect(decide(metas.get(mcpToolName('move_nodes')), groups(), none)).toBe('ask')
    expect(decide(metas.get(mcpToolName('move_nodes')), groups({ 'files:write': 'allow' }), none)).toBe('allow')
    expect(decide(metas.get(mcpToolName('batch_nodes')), groups({ 'files:write': 'allow', 'files:delete': 'deny' }), none)).toBe('deny')
    expect(decide(metas.get(mcpToolName('batch_nodes')), groups({ 'files:write': 'allow', 'files:delete': 'allow' }), none)).toBe('allow')
  })

  it('不认识的、没标 scope 的工具一律问', () => {
    expect(decide(undefined, groups(), new Set())).toBe('ask')
    expect(decide(metas.get(mcpToolName('odd')), groups(), new Set())).toBe('ask')
  })

  it('「这次对话都允许」只对不破坏东西的生效', () => {
    const allowed = new Set([mcpToolName('move_nodes'), mcpToolName('trash_nodes')])
    expect(decide(metas.get(mcpToolName('move_nodes')), groups(), allowed)).toBe('allow')
    expect(decide(metas.get(mcpToolName('trash_nodes')), groups(), allowed)).toBe('ask')
    // 拒绝的不因为点过「都允许」放开
    expect(decide(metas.get(mcpToolName('move_nodes')), groups({ 'files:write': 'deny' }), allowed)).toBe('deny')
  })

  it('工具名和 tau 的 MCP 命名一致', () => {
    expect(mcpToolName('list_files')).toBe('mcp__gopan__list_files')
    expect(metas.get('mcp__gopan__trash_nodes')?.destructive).toBe(true)
  })
})

describe('确认卡的说法', () => {
  it('写成人话', () => {
    expect(describeCall('move_nodes', { node_ids: ['a', 'b'], target_path: '/报销' })).toMatchObject({ title: '移动 2 项到「/报销」', ids: ['a', 'b'] })
    expect(describeCall('trash_nodes', { node_ids: ['a'] }).title).toBe('把 1 项放进回收站')
    expect(describeCall('batch_nodes', { operation: 'copy', node_ids: ['a'], dry_run: true }).title).toBe('1 项复制到根目录(先试一遍,不真改)')
    expect(describeCall('create_share', { node_id: 'x', allow_upload: true, password: 'p' }).title).toBe('建分享链接(带密码、允许对方上传)')
    expect(describeCall('chat_send', { text: '你好' })).toMatchObject({ title: '发到传输助手', detail: '你好' })
    expect(describeCall('rename_node', { path: '/a.txt', name: 'b.txt' })).toMatchObject({ title: '改名为「b.txt」', paths: ['/a.txt'] })
  })

  it('只读的工具有短说法,不认识的给原名', () => {
    expect(toolLabel('search_files', { query: '发票' })).toBe('搜索「发票」')
    expect(toolLabel('move_nodes', { node_ids: ['a'] })).toBe('移动 1 项到根目录')
    expect(toolLabel('mystery', { a: 1 })).toBe('调用 mystery')
  })
})

describe('对话 → 面板', () => {
  const now = Date.now()
  const usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } }
  const assistant = (content: unknown[], extra: Record<string, unknown> = {}) =>
    ({ role: 'assistant', content, api: 'x', provider: 'x', model: 'm', usage, stopReason: 'toolUse', timestamp: now, ...extra }) as unknown as AgentMessage

  it('去掉上下文、配对工具结果、标出被拒绝的', () => {
    const msgs: AgentMessage[] = [
      { role: 'user', content: withContext('把这几个移走', '用户正在看:/'), timestamp: now },
      assistant([
        { type: 'text', text: '好的' },
        { type: 'toolCall', id: 't1', name: 'mcp__gopan__move_nodes', arguments: { node_ids: ['a'] } },
        { type: 'toolCall', id: 't2', name: 'mcp__gopan__trash_nodes', arguments: { node_ids: ['b'] } },
      ]),
      { role: 'toolResult', toolCallId: 't1', toolName: 'mcp__gopan__move_nodes', content: [{ type: 'text', text: 'ok' }], isError: false, timestamp: now },
      { role: 'toolResult', toolCallId: 't2', toolName: 'mcp__gopan__trash_nodes', content: [{ type: 'text', text: 'Denied by policy: approval declined' }], isError: true, timestamp: now },
      assistant([], { stopReason: 'error', errorMessage: '额度用完了' }),
    ]
    const items = buildTranscript(msgs)
    expect(items.map((i) => i.kind)).toEqual(['user', 'assistant', 'tool', 'tool', 'error'])
    expect(items[0]).toMatchObject({ text: '把这几个移走' })
    expect(items[2]).toMatchObject({ name: 'move_nodes', status: 'ok' })
    expect(items[3]).toMatchObject({ name: 'trash_nodes', status: 'denied' })
  })

  it('出错的话只留给人看的那句', () => {
    expect(friendlyError('HTTP 502: {"code":"AI_UPSTREAM","message":"模型服务的地址不允许访问(内网地址)"}')).toBe('模型服务的地址不允许访问(内网地址)')
    expect(friendlyError('HTTP 429: {"code":"AI_QUOTA","message":"x"}')).toBe('今天的站点额度用完了,可以换自己的 key')
    expect(friendlyError('HTTP 401: {"error":{"message":"Invalid API key"}}')).toBe('Invalid API key')
    expect(friendlyError('HTTP 500: oops')).toBe('HTTP 500: oops')
  })

  it('上下文与标题', () => {
    expect(stripContext(withContext('hi', 'ctx'))).toBe('hi')
    expect(withContext('hi', '')).toBe('hi')
    expect(titleFrom('\n  上个月的发票  \n第二行')).toBe('上个月的发票')
    expect(describeContext({ path: '/旅行', folderId: 'f1', selection: [{ id: 'a', name: '1.jpg', kind: 'file' }] })).toContain('选中了 1 项')
    expect(describeContext(null)).toBe('')
  })
})

describe('对话记录写到服务端', () => {
  const meta = { id: 'c1', cwd: '/', timestamp: '2026-10-29T00:00:00Z' }
  const bytes = (n: number) => crypto.getRandomValues(new Uint8Array(n))
  const entry = (id: string, parentId: string | null) => ({ type: 'message', id, parentId, timestamp: meta.timestamp, message: { role: 'user', content: id, timestamp: 0 } }) as never

  it('按序号排队追加,读回来的不再写', async () => {
    const calls: [number, number][] = []
    const store = new GopanSessionStore(meta, bytes, { async append(from, entries) { calls.push([from, entries.length]); return from + entries.length } }, 1)
    await store.load([JSON.stringify(entry('e0', null)), 'not json'])
    await store.appendEntry(entry('e1', 'e0'))
    await store.appendEntry(entry('e2', 'e1'))
    await store.flush()
    expect(calls.reduce((n, [, c]) => n + c, 0)).toBe(2)
    expect(calls[0]![0]).toBe(1)
    expect((await store.getEntries()).length).toBe(3)
    expect(store.pending).toBe(0)
  })

  it('冲突后不再写,网络错误留着下次再试', async () => {
    let fail: 'conflict' | 'net' | null = 'net'
    const store = new GopanSessionStore(meta, bytes, {
      async append(from, entries) {
        if (fail === 'net') throw new Error('offline')
        if (fail === 'conflict') throw new ConflictError()
        return from + entries.length
      },
    }, 0)
    await store.appendEntry(entry('e1', null))
    await store.flush()
    expect(store.pending).toBe(1)
    fail = null
    await store.flush()
    expect(store.pending).toBe(0)
    fail = 'conflict'
    await store.appendEntry(entry('e2', 'e1'))
    await store.flush()
    expect(store.conflict).toBe(true)
    expect(store.pending).toBe(1)
  })
})

describe('同源判断(不用 URL 类,QuickJS 里没有)', () => {
  it('只认这个站点下的路径', () => {
    const o = 'https://drive.example.com'
    expect(localPath(o + '/mcp', o)).toBe('/mcp')
    expect(localPath(o + '/api/ai/p1/chat/completions?x=1', o)).toBe('/api/ai/p1/chat/completions')
    expect(localPath('/query', o)).toBe('/query')
    expect(localPath(o, o)).toBe('/')
    expect(localPath('https://drive.example.com.evil.com/mcp', o)).toBeNull()
    expect(localPath('https://api.deepseek.com/v1/chat/completions', o)).toBeNull()
  })
})
