import { describe, expect, it } from 'vitest'

import { describeCall, toolLabel } from './describe'
import { fileTools } from './files'
import { GopanFs, GopanToolError, mcpCaller, resolvePath, type McpCall, type McpResult } from './gopan-fs'
import { refsIn, splitReply, withRefChips } from './refs'
import { buildTranscript, userContent } from './transcript'
import { viewItems } from './runtime'

const ID_A = '0190f1a2-0000-7000-8000-00000000000a'
const ID_B = '0190f1a2-0000-7000-8000-00000000000b'

/** 假的 gopan:按路径存文件,记下调过的工具 */
function fakeDrive(files: Record<string, { mime: string; text?: string; doc?: string; sha?: string }>) {
  const calls: { tool: string; args: Record<string, unknown> }[] = []
  const ok = (structured: Record<string, unknown>, content: McpResult['content'] = []): McpResult => ({ content, structured })
  const call: McpCall = async (tool, args) => {
    calls.push({ tool, args })
    const path = String(args.path ?? '')
    const f = files[path]
    switch (tool) {
      case 'get_file_info':
        if (path === '/笔记') return ok({ id: 'dir', name: '笔记', kind: 'folder' })
        if (!f) throw new GopanToolError('NOT_FOUND', '不存在')
        return ok({ id: path, name: path.split('/').pop(), kind: 'file', mime: f.mime, size: (f.text ?? '').length, sha256: f.sha })
      case 'read_text_file':
        return ok({ text: f!.text, offset: 0, next_offset: f!.text!.length, eof: true, sha256: f!.sha })
      case 'read_document_text':
        return ok({ text: f!.doc ?? '', offset: 0, next_offset: 0, eof: true })
      case 'view_image':
        return ok({}, [{ type: 'text', text: 'x.jpg (image)' }, { type: 'image', data: 'AAAA', mimeType: 'image/jpeg' }])
      case 'write_text_file':
        if (f?.sha && args.base_sha256 !== f.sha) throw new GopanToolError(args.base_sha256 ? 'EDIT_CONFLICT' : 'READ_FIRST', 'x')
        files[path] = { mime: 'text/plain', text: String(args.content), sha: `v${calls.length}` }
        return ok({ id: path, name: 'x', kind: 'file', sha256: files[path]!.sha })
      default:
        throw new Error(`没想到会调 ${tool}`)
    }
  }
  return { call, calls, files }
}

function tool(fs: GopanFs, name: string) {
  return fileTools(fs).find((t) => t.name === name)!
}

describe('网盘当文件系统(GopanFs)', () => {
  it('路径:相对的按工作目录拼,. 与 .. 化掉', () => {
    expect(resolvePath('/', 'a/b.md')).toBe('/a/b.md')
    expect(resolvePath('/笔记', '../旅行/./x.jpg')).toBe('/旅行/x.jpg')
    expect(resolvePath('/a', '/b//c/')).toBe('/b/c')
  })

  it('模型拿到的只有 read / write / edit', () => {
    const fs = new GopanFs(fakeDrive({}).call)
    expect(fileTools(fs).map((t) => t.name).sort()).toEqual(['edit', 'read', 'write'])
  })

  it('read:文本按行带行号;读过的版本在写回时带上', async () => {
    const d = fakeDrive({ '/a.md': { mime: 'text/markdown', text: '第一行\n第二行', sha: 's1' } })
    const fs = new GopanFs(d.call)
    const r = await tool(fs, 'read').execute({ path: '/a.md' })
    expect(r.content[0]).toMatchObject({ type: 'text' })
    expect((r.content[0] as { text: string }).text).toContain('1\t第一行')
    const e = await tool(fs, 'edit').execute({ path: '/a.md', edits: [{ oldText: '第二行', newText: '第 2 行' }] })
    expect(e.isError).toBeFalsy()
    expect(d.files['/a.md']!.text).toBe('第一行\n第 2 行')
    expect(d.calls.find((c) => c.tool === 'write_text_file')!.args.base_sha256).toBe('s1')
    // 结果末尾附上文件 id,模型引用文件时用
    expect((r.content[0] as { text: string }).text.endsWith('\n[gopan id: /a.md]')).toBe(true)
    expect((e.content[0] as { text: string }).text).toContain('[gopan id: /a.md]')
  })

  it('read:任何格式的图片、视频给图;扫描件给第一页;有字的文档给文字', async () => {
    const d = fakeDrive({
      '/p.heic': { mime: 'image/heic' },
      '/v.mp4': { mime: 'video/mp4' },
      '/scan.pdf': { mime: 'application/pdf', doc: '  ' },
      '/r.docx': { mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', doc: '报告正文' },
    })
    const read = tool(new GopanFs(d.call), 'read')
    const heic = await read.execute({ path: '/p.heic' })
    expect(heic.content.map((c) => c.type)).toEqual(['text', 'image'])
    const video = await read.execute({ path: '/v.mp4' })
    expect((video.content[0] as { text: string }).text).toContain('video cover')
    const scan = await read.execute({ path: '/scan.pdf' })
    expect((scan.content[0] as { text: string }).text).toContain('scanned')
    expect(scan.content[1]).toMatchObject({ type: 'image', data: 'AAAA' })
    const doc = await read.execute({ path: '/r.docx' })
    expect((doc.content[0] as { text: string }).text).toContain('报告正文')
  })

  it('read:文件夹、不存在的报错', async () => {
    const read = tool(new GopanFs(fakeDrive({}).call), 'read')
    expect((await read.execute({ path: '/笔记' })).isError).toBe(true)
    expect((await read.execute({ path: '/没有.md' })).isError).toBe(true)
  })

  it('write:新建不带版本;没读过的已有文件被拒;中途被改过报冲突', async () => {
    const d = fakeDrive({ '/old.md': { mime: 'text/markdown', text: 'x', sha: 's0' } })
    const fs = new GopanFs(d.call)
    const w = tool(fs, 'write')
    expect((await w.execute({ path: '/new.md', content: '你好' })).isError).toBeFalsy()
    expect(d.calls.at(-1)!.args).not.toHaveProperty('base_sha256')
    const blind = await w.execute({ path: '/old.md', content: 'y' })
    expect(blind.isError).toBe(true)
    expect((blind.content[0] as { text: string }).text).toContain('先 read')
    await tool(fs, 'read').execute({ path: '/old.md' })
    d.files['/old.md']!.sha = 'changed-elsewhere'
    const late = await w.execute({ path: '/old.md', content: 'y' })
    expect((late.content[0] as { text: string }).text).toContain('被改过')
  })

  it('mcpCaller:业务错误拆出错误码', async () => {
    const fetch = async () =>
      new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { isError: true, content: [{ type: 'text', text: 'EDIT_CONFLICT: 文件在别处被改过' }] } }))
    const call = mcpCaller('https://x', fetch as never)
    await expect(call('write_text_file', {})).rejects.toMatchObject({ code: 'EDIT_CONFLICT', message: '文件在别处被改过' })
  })
})

describe('读写工具的说法', () => {
  it('确认卡与面板上的一行', () => {
    expect(describeCall('write', { path: '/笔记/会议.md', content: 'abc' })).toMatchObject({ title: expect.stringContaining('会议.md'), paths: ['/笔记/会议.md'], detail: 'abc' })
    const e = describeCall('edit', { path: '/a.md', edits: [{ oldText: '旧', newText: '新' }, { oldText: '甲', newText: '乙' }] })
    expect(e.title).toBe('修改「a.md」(2 处)')
    expect(e.detail).toContain('把「旧」改成「新」')
    expect(toolLabel('read', { path: '/旅行/IMG_1.HEIC' })).toBe('看了「IMG_1.HEIC」')
    expect(toolLabel('read', { path: '/a.md' })).toBe('读了「a.md」')
  })
})

describe('随消息发的图', () => {
  const jpg = 'data:image/jpeg;base64,/9j/AAA='
  it('发给模型的是文字加图片块;记录里读回来还是图', () => {
    const content = userContent('这是什么', '', [jpg, 'not-an-image'])
    expect(content).toEqual([{ type: 'text', text: '这是什么' }, { type: 'image', mimeType: 'image/jpeg', data: '/9j/AAA=' }])
    expect(userContent('只有字', '')).toBe('只有字')
    const items = buildTranscript([{ role: 'user', content: content as never, timestamp: 0 }])
    expect(items[0]).toMatchObject({ kind: 'user', text: '这是什么', images: [jpg] })
  })

  it('推给各个设备的事件里只给张数', () => {
    const items = [{ kind: 'user' as const, key: 'u0', text: '看看', images: [jpg, jpg] }]
    expect(viewItems(items)[0]).toMatchObject({ images: [jpg, jpg] })
    expect(viewItems(items, false)[0]).toEqual({ kind: 'user', key: 'u0', text: '看看', imageCount: 2 })
  })
})

describe('回答里引用的文件', () => {
  it('找出引用;只列文件的段落变卡片,句子里的留在 Markdown', () => {
    const text = `找到了 [报销](gopan:${ID_A}) 文件夹,里面有:\n\n- [发票1.jpg](gopan:${ID_B})\n- [发票2.jpg](gopan:${ID_A.toUpperCase()})\n\n要移动吗?`
    expect(refsIn(text).map((r) => r.id)).toEqual([ID_A, ID_B, ID_A])
    const blocks = splitReply(text)
    expect(blocks.map((b) => b.kind)).toEqual(['md', 'cards', 'md'])
    expect(blocks[1]).toEqual({ kind: 'cards', refs: [{ name: '发票1.jpg', id: ID_B }, { name: '发票2.jpg', id: ID_A }] })
  })

  it('手里没有 id 时可以写路径;路径原样(去掉结尾的 /)', () => {
    expect(refsIn('改好了 [todo.md](gopan:/笔记/todo.md) 和 [笔记](gopan:/笔记/)')).toEqual([
      { name: 'todo.md', id: '/笔记/todo.md' },
      { name: '笔记', id: '/笔记' },
    ])
  })

  it('代码块里的空行不切段;别的协议不当引用', () => {
    expect(splitReply('```\na\n\nb\n```')).toEqual([{ kind: 'md', text: '```\na\n\nb\n```' }])
    expect(refsIn('[x](https://e.com) [y](gopan:not-a-uuid)')).toEqual([])
  })

  it('标签替换在 Markdown 转义之后', () => {
    const html = withRefChips(`看 **[a<b>](gopan:${ID_A})**`, (md) => `<p>${md.replace(/\*\*(.+)\*\*/, '<b>$1</b>')}</p>`, (r) => `<a data-gid="${r.id}">${r.name.replace(/</g, '&lt;').replace(/>/g, '&gt;')}</a>`)
    expect(html).toBe(`<p>看 <b><a data-gid="${ID_A}">a&lt;b&gt;</a></b></p>`)
  })
})
