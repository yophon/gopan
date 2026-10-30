import type { AgentMessage, ImageContent, TextContent } from '@yophon/tau-kernel'

/**
 * 对话 → 面板上一条条显示的东西。纯函数:同一份消息(刚回答完的、从服务端读回来的)画出来一样。
 *
 * 发给模型的话前面带着「你在看什么」(当前文件夹、选中的文件),包在 <gopan-context> 里,显示时去掉;
 * 只在它变了的时候带。
 */

export type TranscriptItem =
  /** images:随这句话发的图(data URL) */
  | { kind: 'user'; key: string; text: string; images?: string[] }
  | { kind: 'assistant'; key: string; text: string }
  | { kind: 'tool'; key: string; id: string; name: string; args: Record<string, unknown>; status: 'running' | 'ok' | 'error' | 'denied'; output: string; label?: string }
  | { kind: 'error'; key: string; text: string }

const CONTEXT_RE = /^<gopan-context>\n?([\s\S]*?)\n?<\/gopan-context>\n?/

export function withContext(text: string, context: string): string {
  return context ? `<gopan-context>\n${context}\n</gopan-context>\n${text}` : text
}

export function stripContext(text: string): string {
  return text.replace(CONTEXT_RE, '')
}

/** 模型眼下知道的「你在看什么」:对话里最近一次带上的那份(压缩掉了就没有) */
export function lastContext(messages: readonly AgentMessage[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]!
    if (m.role !== 'user') continue
    const hit = CONTEXT_RE.exec(textOf(m.content))
    if (hit) return hit[1]!
  }
  return ''
}

/** 和上一次带上的一样就不再带:每句都带同一段,模型会照着这个格式自己续写出一轮「用户的话」 */
export function changedContext(context: string, messages: readonly AgentMessage[]): string {
  return context === lastContext(messages) ? '' : context
}

/** 随消息发的图:data URL ↔ tau 的图片块(doc/35) */
export function imageBlock(dataUrl: string): ImageContent | null {
  const m = /^data:(image\/[a-z+.-]+);base64,(.+)$/s.exec(dataUrl)
  return m ? { type: 'image', mimeType: m[1]!, data: m[2]! } : null
}

/** 发给模型的这句话:文字(带「你在看什么」)加上图 */
export function userContent(text: string, context: string, images: readonly string[] = []): string | (TextContent | ImageContent)[] {
  const body = withContext(text, context)
  const blocks = images.map(imageBlock).filter((b): b is ImageContent => b !== null)
  return blocks.length ? [{ type: 'text', text: body }, ...blocks] : body
}

function imagesOf(content: string | readonly { type: string; data?: string; mimeType?: string }[]): string[] {
  if (typeof content === 'string') return []
  return content.filter((c) => c.type === 'image' && c.data).map((c) => `data:${c.mimeType ?? 'image/jpeg'};base64,${c.data}`)
}

function textOf(content: string | readonly { type: string; text?: string }[]): string {
  if (typeof content === 'string') return content
  return content.filter((c) => c.type === 'text').map((c) => c.text ?? '').join('')
}

/** MCP 工具名 → gopan 工具名(mcp__gopan__move_nodes → move_nodes) */
export function shortToolName(name: string): string {
  const m = /^mcp__gopan__(.+)$/.exec(name)
  return m ? m[1]! : name
}

export function buildTranscript(messages: readonly AgentMessage[]): TranscriptItem[] {
  const out: TranscriptItem[] = []
  const tools = new Map<string, Extract<TranscriptItem, { kind: 'tool' }>>()
  messages.forEach((m, i) => {
    if (m.role === 'user') {
      const text = stripContext(textOf(m.content))
      const images = imagesOf(m.content)
      if (text.trim() || images.length) out.push({ kind: 'user', key: `u${i}`, text, ...(images.length ? { images } : {}) })
    } else if (m.role === 'assistant') {
      const text = m.content.filter((c) => c.type === 'text').map((c) => (c as { text: string }).text).join('')
      if (text.trim()) out.push({ kind: 'assistant', key: `a${i}`, text })
      for (const c of m.content) {
        if (c.type !== 'toolCall') continue
        const item = { kind: 'tool' as const, key: `t${c.id}`, id: c.id, name: shortToolName(c.name), args: c.arguments as Record<string, unknown>, status: 'running' as const, output: '' }
        tools.set(c.id, item)
        out.push(item)
      }
      if (m.errorMessage && m.stopReason !== 'aborted') out.push({ kind: 'error', key: `e${i}`, text: friendlyError(m.errorMessage) })
    } else if (m.role === 'toolResult') {
      const item = tools.get(m.toolCallId)
      if (!item) return
      const output = textOf(m.content)
      item.output = output
      item.status = m.isError ? (/^Denied by policy|^用户拒绝|denied/i.test(output) ? 'denied' : 'error') : 'ok'
    }
  })
  return out
}

/**
 * 出错时给人看的话:模型服务 / 转发回的「HTTP 502: {"code":…,"message":"…"}」只留里面那句;额度用完换成提示。
 */
export function friendlyError(raw: string): string {
  let msg = raw
  const m = /^HTTP (\d+):\s*(\{[\s\S]*\})\s*$/.exec(raw.trim())
  if (m) {
    try {
      const body = JSON.parse(m[2]!) as { code?: string; message?: string; error?: { message?: string } }
      const inner = body.message ?? body.error?.message
      if (body.code === 'AI_QUOTA') return '今天的站点额度用完了,可以换自己的 key'
      if (inner) msg = inner
    } catch {
      // 不是 JSON:原样
    }
  }
  if (/AI_QUOTA/.test(msg)) return '今天的站点额度用完了,可以换自己的 key'
  return msg
}

/** 第一句话当标题 */
export function titleFrom(text: string): string {
  const line = text.split('\n').find((l) => l.trim()) ?? ''
  return line.trim().slice(0, 40)
}
