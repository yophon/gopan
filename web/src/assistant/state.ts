import { ref, shallowRef } from 'vue'

/**
 * 助手面板的轻量状态:开没开、用户正在看什么。不引 tau(那一大块在面板里按需加载),
 * 顶栏按钮和文件页只碰这里。
 */

export interface AssistantContext {
  /** 当前文件夹的路径(根目录是 /) */
  path: string
  folderId: string | null
  selection: { id: string; name: string; kind: string }[]
}

export const assistantOpen = ref(false)
export const assistantContext = shallowRef<AssistantContext | null>(null)

export function setAssistantContext(ctx: AssistantContext | null) {
  assistantContext.value = ctx
}

export function toggleAssistant(open?: boolean) {
  assistantOpen.value = open ?? !assistantOpen.value
}

/** 交给模型的「你在看什么」 */
export function describeContext(ctx: AssistantContext | null): string {
  if (!ctx) return ''
  const lines = [`用户正在看:${ctx.path}${ctx.folderId ? `(文件夹 id ${ctx.folderId})` : '(根目录)'}`]
  if (ctx.selection.length > 0) {
    const shown = ctx.selection.slice(0, 50).map((s) => `- ${s.name}(${s.kind === 'folder' ? '文件夹' : '文件'},id ${s.id})`)
    lines.push(`选中了 ${ctx.selection.length} 项:`, ...shown)
    if (ctx.selection.length > 50) lines.push(`……还有 ${ctx.selection.length - 50} 项`)
  }
  return lines.join('\n')
}

const KEY_MODE = 'gopan.assistant.mode'
const KEY_PICK = 'gopan.assistant.pick'

export type ConnectionMode = 'proxy' | 'direct'

/** 连接方式按设备记(doc/33 一):同一个账号,家里电脑直连、公共电脑转发 */
export function loadMode(): ConnectionMode {
  try {
    return localStorage.getItem(KEY_MODE) === 'direct' ? 'direct' : 'proxy'
  } catch {
    return 'proxy'
  }
}

export function saveMode(mode: ConnectionMode) {
  try {
    localStorage.setItem(KEY_MODE, mode)
  } catch {
    // 存不了就只管这一次
  }
}

/** 上次选的服务与模型(这台设备) */
export function loadPick(): { provider: string; model: string } | null {
  try {
    const raw = localStorage.getItem(KEY_PICK)
    const v = raw ? (JSON.parse(raw) as { provider?: unknown; model?: unknown }) : null
    return v && typeof v.provider === 'string' && typeof v.model === 'string' ? { provider: v.provider, model: v.model } : null
  } catch {
    return null
  }
}

export function savePick(provider: string, model: string) {
  try {
    localStorage.setItem(KEY_PICK, JSON.stringify({ provider, model }))
  } catch {
    // 同上
  }
}
