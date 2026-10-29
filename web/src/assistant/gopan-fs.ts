import { decodeBase64, type FileInfo, type FileSystem, type PlatformFetch } from '@yophon/tau-kernel'

/**
 * 把网盘当成 tau 的 FileSystem(doc/35 四):tau 的 read / write / edit 底下就是它。
 *
 * 全部经同一条 /mcp(带助手的对话 id 与这一轮的批次 id)调 gopan 的底层工具,所以权限、目录范围、
 * 动态里记「助手」、整批撤回都和别的工具一样在服务端做。只用 fetch,三种宿主(浏览器、App 的
 * QuickJS、服务器的 qjs)都能跑。
 */

/** gopan 工具报的错:code 是服务端的错误码(EDIT_CONFLICT、NOT_FOUND……) */
export class GopanToolError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

export interface McpContent {
  type: string
  text?: string
  data?: string
  mimeType?: string
}

export interface McpResult {
  content: McpContent[]
  structured: Record<string, unknown>
}

export type McpCall = (tool: string, args: Record<string, unknown>) => Promise<McpResult>

/** 直接对 /mcp 发 tools/call(服务端是无状态的 JSON 响应,不用先 initialize) */
export function mcpCaller(origin: string, fetch: PlatformFetch): McpCall {
  let id = 0
  return async (tool, args) => {
    const res = await fetch(`${origin}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
      body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method: 'tools/call', params: { name: tool, arguments: args } }),
    })
    const text = await res.text()
    if (!res.ok) throw new GopanToolError(String(res.status), text.trim() || `gopan 的工具出错了(${res.status})`)
    const body = JSON.parse(text) as {
      result?: { content?: McpContent[]; structuredContent?: Record<string, unknown>; isError?: boolean }
      error?: { message?: string }
    }
    if (body.error) throw new GopanToolError('RPC', body.error.message ?? 'gopan 的工具出错了')
    const content = body.result?.content ?? []
    if (body.result?.isError) {
      // 服务端的业务错误写成「CODE: 说明」
      const msg = content.map((c) => c.text ?? '').join('\n').trim()
      const m = /^([A-Z_]+): ([\s\S]*)$/.exec(msg)
      throw m ? new GopanToolError(m[1]!, m[2]!) : new GopanToolError('ERROR', msg || 'gopan 的工具出错了')
    }
    return { content, structured: body.result?.structuredContent ?? {} }
  }
}

/** resolve_path / get_file_info 给的节点 */
export interface GopanNode {
  id: string
  name: string
  kind: 'file' | 'folder'
  size?: number
  mime?: string
  sha256?: string
  updated_at?: string
}

const OFFICE = /\.(docx?|xlsx?|pptx?|od[tsp]|rtf)$/i

export function isVisual(n: GopanNode): boolean {
  return n.kind === 'file' && /^(image|video)\//.test(n.mime ?? '')
}

export function isDocument(n: GopanNode): boolean {
  return n.kind === 'file' && (n.mime === 'application/pdf' || OFFICE.test(n.name))
}

/** 能读成文字的上限,和在线编辑一样 */
const MAX_TEXT = 2 << 20

/** 网盘路径:相对路径按工作目录拼,去掉 . 与 .. */
export function resolvePath(cwd: string, path: string): string {
  const raw = path.startsWith('/') ? path : `${cwd.replace(/\/+$/, '')}/${path}`
  const out: string[] = []
  for (const part of raw.split('/')) {
    if (part === '' || part === '.') continue
    if (part === '..') out.pop()
    else out.push(part)
  }
  return `/${out.join('/')}`
}

export class GopanFs implements FileSystem {
  /** 读过的文本文件的版本:写回时带上,中途被别处改过就报冲突 */
  private readonly versions = new Map<string, string>()
  /** 文档抽出的文字(read 包一层先读一遍判断是不是扫描件,接着 tau 的 read 再要时不用再取) */
  private readonly documents = new Map<string, string>()
  /** 路径 → 节点 id:读写工具的结果里附上,模型在回答里引用文件要用(doc/35 六) */
  private readonly ids = new Map<string, string>()

  idOf(path: string): string | undefined {
    return this.ids.get(this.abs(path))
  }

  constructor(
    private readonly call: McpCall,
    readonly cwd = '/',
  ) {}

  private abs(path: string): string {
    return resolvePath(this.cwd, path)
  }

  /** 按路径查节点(带类型、大小、sha256);根目录(或目录范围的根)是一个没有 id 的文件夹 */
  async node(path: string): Promise<GopanNode> {
    const p = this.abs(path)
    if (p === '/') return { id: '', name: '/', kind: 'folder' }
    const r = await this.call('get_file_info', { path: p })
    const n = r.structured as unknown as GopanNode
    if (!n?.id) throw new GopanToolError('NOT_FOUND', `${p} 不存在`)
    this.ids.set(p, n.id)
    return n
  }

  async stat(path: string): Promise<FileInfo> {
    const n = await this.node(path)
    return { path: this.abs(path), name: n.name, kind: n.kind === 'folder' ? 'directory' : 'file', size: n.size ?? 0, mtimeMs: n.updated_at ? Date.parse(n.updated_at) : 0 }
  }

  async listDir(path: string): Promise<FileInfo[]> {
    const dir = this.abs(path)
    const r = await this.call('list_files', { path: dir })
    const items = (r.structured.items ?? []) as GopanNode[]
    return items.map((n) => ({
      path: resolvePath(dir, n.name),
      name: n.name,
      kind: n.kind === 'folder' ? 'directory' : 'file',
      size: n.size ?? 0,
      mtimeMs: n.updated_at ? Date.parse(n.updated_at) : 0,
    }))
  }

  /** 文档(PDF / Office)抽出的文字,连读到底;空串 = 扫描件 */
  async documentText(path: string): Promise<string> {
    const p = this.abs(path)
    const cached = this.documents.get(p)
    if (cached !== undefined) return cached
    let text = ''
    for (let offset = 0; ; ) {
      const r = await this.call('read_document_text', { path: p, offset, max_chars: 50000 })
      const s = r.structured as { text?: string; next_offset?: number; eof?: boolean }
      text += s.text ?? ''
      if (s.eof !== false || text.length >= MAX_TEXT || s.next_offset === undefined || s.next_offset <= offset) break
      offset = s.next_offset
    }
    this.documents.set(p, text)
    return text
  }

  async readTextFile(path: string): Promise<string> {
    const p = this.abs(path)
    const n = await this.node(p)
    if (n.kind === 'folder') throw new GopanToolError('IS_FOLDER', `${p} 是文件夹,看里面有什么用 list_files`)
    if (isDocument(n)) return this.documentText(p)
    if (isVisual(n)) throw new GopanToolError('NOT_TEXT', `${p} 是${n.mime?.startsWith('video/') ? '视频' : '图片'},用 read 直接看`)
    let text = ''
    let sha = ''
    for (let offset = 0; ; ) {
      let r: McpResult
      try {
        r = await this.call('read_text_file', { path: p, offset, max_bytes: 65536 })
      } catch (err) {
        if (err instanceof GopanToolError && offset === 0) throw new GopanToolError(err.code, `${p} 不是文本文件(${n.mime ?? '未知类型'}),读不了`)
        throw err
      }
      const s = r.structured as { text?: string; next_offset?: number; eof?: boolean; sha256?: string }
      text += s.text ?? ''
      sha = s.sha256 ?? sha
      if (s.eof !== false || s.next_offset === undefined || s.next_offset <= offset) break
      if (text.length > MAX_TEXT) throw new GopanToolError('TOO_LARGE', `${p} 超过 2 MB,读不完`)
      offset = s.next_offset
    }
    if (sha) this.versions.set(p, sha)
    return text
  }

  /** 图片 / 视频封面 / 文档第一页,JPEG 的 base64(view_image) */
  async image(path: string): Promise<{ data: string; mimeType: string; note: string }> {
    const r = await this.call('view_image', { path: this.abs(path) })
    const img = r.content.find((c) => c.type === 'image' && c.data)
    if (!img) throw new GopanToolError('NOT_IMAGE', `${this.abs(path)} 没有图可看`)
    return { data: img.data!, mimeType: img.mimeType ?? 'image/jpeg', note: r.content.find((c) => c.type === 'text')?.text ?? '' }
  }

  async readBinaryFile(path: string): Promise<Uint8Array> {
    return decodeBase64((await this.image(path)).data)
  }

  async writeTextFile(path: string, content: string): Promise<void> {
    const p = this.abs(path)
    const write = () => this.call('write_text_file', { path: p, content, ...(this.versions.has(p) ? { base_sha256: this.versions.get(p) } : {}) })
    let r: McpResult
    try {
      r = await write()
    } catch (err) {
      if (!(err instanceof GopanToolError)) throw err
      if (err.code === 'NOT_FOUND') {
        // 和 tau 的 write 说的一样:缺的上级文件夹建出来
        const dir = p.slice(0, p.lastIndexOf('/')) || '/'
        if (dir === '/') throw err
        await this.call('create_directories', { path: dir })
        r = await write()
      } else if (err.code === 'EDIT_CONFLICT') {
        throw new GopanToolError(err.code, `${p} 在你读之后被改过了,重新 read 一遍再改`)
      } else if (err.code === 'READ_FIRST') {
        throw new GopanToolError(err.code, `${p} 已经有内容,先 read 一遍再覆盖`)
      } else {
        throw err
      }
    }
    const { sha256: sha, id } = r.structured as { sha256?: string; id?: string }
    if (sha) this.versions.set(p, sha)
    if (id) this.ids.set(p, id)
    this.documents.delete(p)
  }

  async appendFile(): Promise<void> {
    throw new GopanToolError('UNSUPPORTED', '网盘里不能追加写')
  }

  async remove(): Promise<void> {
    throw new GopanToolError('UNSUPPORTED', '删除用 trash_nodes(放进回收站)')
  }
}
