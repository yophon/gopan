import type { AgentMessage } from '@yophon/tau-kernel'

/**
 * 对话 → 面板上一条条显示的东西。纯函数:同一份消息(刚回答完的、从服务端读回来的)画出来一样。
 *
 * 发给模型的话前面带着「你在看什么」(当前文件夹、选中的文件),包在 <gopan-context> 里,显示时去掉。
 */

export type TranscriptItem =
  | { kind: 'user'; key: string; text: string }
  | { kind: 'assistant'; key: string; text: string }
  | { kind: 'tool'; key: string; id: string; name: string; args: Record<string, unknown>; status: 'running' | 'ok' | 'error' | 'denied'; output: string }
  | { kind: 'error'; key: string; text: string }

const CONTEXT_RE = /^<gopan-context>[\s\S]*?<\/gopan-context>\n?/

export function withContext(text: string, context: string): string {
  return context ? `<gopan-context>\n${context}\n</gopan-context>\n${text}` : text
}

export function stripContext(text: string): string {
  return text.replace(CONTEXT_RE, '')
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
      if (text.trim()) out.push({ kind: 'user', key: `u${i}`, text })
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
