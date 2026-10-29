import {
  Agent,
  ExtensionRegistry,
  SessionRecorder,
  defaultPlatform,
  restoreSession,
  type ApprovalRequest,
  type ChatTransport,
  type Platform,
  type PlatformFetch,
  type PlatformRequestInit,
  type ToolPolicy,
} from '@yophon/tau-kernel'
import { createHttpMcpExtension } from '@yophon/tau-ext-mcp-http'
import { createAnthropicTransport } from '@yophon/tau-ext-provider-anthropic'
import { createGoogleTransport } from '@yophon/tau-ext-provider-google'
import { createOpenAIResponsesTransport } from '@yophon/tau-ext-provider-openai-responses'

import { FILE_TOOL_METAS, fileTools } from './files'
import { GopanFs, mcpCaller } from './gopan-fs'
import { decide, MCP_SERVER, scopePermissions, toolMetas, type AccessGroup, type ToolMeta } from './policy'
import type { GopanSessionStore } from './session'

/**
 * 助手的装配(doc/33 四):tau 的 Agent + gopan 的 MCP 工具 + 四种模型服务。
 *
 * - 转发:baseUrl = /api/ai/{服务 id},tau 原样拼路径;登录凭证由这里的 fetch 加上,服务端换成真 key。
 * - 直连:baseUrl = 服务地址,key 现取、只放内存;桌面端走外壳的 ai_fetch(没有跨域限制)。
 * - 工具:ext-mcp-http 连同源的 /mcp,带登录凭证、对话 id 与这一轮的批次 id(同一轮的改动合成一批撤回);
 *   读写文件用 tau 自带的 read / write / edit,底下的 GopanFs 走同一条 /mcp(doc/35)。
 * - 问不问:tau 的 ToolPolicy 判,规则来自服务端存的权限;要问时 onApproval 交给面板弹确认卡。
 */

export type ProviderKind = 'OPENAI' | 'ANTHROPIC' | 'GOOGLE' | 'OPENAI_RESPONSES'
export type { ConnectionMode } from './state'
import type { ConnectionMode } from './state'
export type ApprovalAnswer = 'once' | 'session' | 'deny'

export interface ProviderView {
  id: string
  name: string
  kind: ProviderKind
  baseUrl: string
  site: boolean
}

export interface ApprovalView {
  request: ApprovalRequest
  meta: ToolMeta | undefined
  /** 破坏性的不给「这次对话都允许」 */
  canAllowSession: boolean
}

export interface AssistantDeps {
  /** 宿主的平台能力:浏览器里是 defaultPlatform();安卓的 QuickJS 里由 App 桥接(网络走 OkHttp) */
  platform?: Platform
  token(): string | null
  /** 401 后换一张凭证;拿到了返回 true */
  refresh(stale: string | null): Promise<boolean>
  /** 直连要的 key(自己的服务) */
  providerKey(id: string): Promise<string>
  /** 桌面外壳的 fetch(直连时用,没有跨域限制) */
  shellFetch?: PlatformFetch
}

export interface AssistantOptions {
  /** gopan 站点地址(不带结尾的 /):/mcp、/api/ai 都在它下面 */
  origin: string
  provider: ProviderView
  model: string
  mode: ConnectionMode
  conversationId: string
  store: GopanSessionStore
  groups: readonly AccessGroup[]
  systemPrompt: string
  onApproval(view: ApprovalView): Promise<ApprovalAnswer>
}

export interface AssistantHandle {
  agent: Agent
  /** 新一轮回答开始:换一个批次 id */
  beginTurn(): void
  /** 权限改了:下一次工具调用起按新的来 */
  setGroups(groups: readonly AccessGroup[]): void
  /** 本轮可用的工具元信息(确认卡、名字) */
  metas: Map<string, ToolMeta>
}

/** 同源请求的路径(不含查询);不是同源返回 null。不用 URL 类:QuickJS 里没有 */
export function localPath(url: string, origin: string): string | null {
  if (url.startsWith('/')) return url.split('?')[0]!
  if (url !== origin && !url.startsWith(`${origin}/`) && !url.startsWith(`${origin}?`)) return null
  return (url.slice(origin.length) || '/').split('?')[0]!
}

function randomId(platform: Platform): string {
  const b = platform.randomBytes(16)
  b[6] = (b[6]! & 0x0f) | 0x40
  b[8] = (b[8]! & 0x3f) | 0x80
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/** 同源的 /api/ai、/mcp 加登录凭证(401 时换一张再试一次);别的地址按连接方式发 */
function gopanFetch(origin: string, base: PlatformFetch, deps: AssistantDeps, extra: () => Record<string, string>, direct: PlatformFetch): PlatformFetch {
  return async (url, init) => {
    const path = localPath(url, origin)
    if (path === null) return direct(url, init)
    const full = url.startsWith('/') ? origin + url : url
    const send = (token: string | null) => {
      const headers: Record<string, string> = { ...(init?.headers ?? {}) }
      // tau 的 provider 会带上它自己的认证头(转发时是占位的),一律换成登录凭证
      for (const k of Object.keys(headers)) if (/^(authorization|x-api-key|x-goog-api-key)$/i.test(k)) delete headers[k]
      if (token) headers.Authorization = `Bearer ${token}`
      if (path === '/mcp') Object.assign(headers, extra())
      const next: PlatformRequestInit = { ...init, headers }
      return base(full, next)
    }
    const token = deps.token()
    const res = await send(token)
    if (res.status === 401 && (await deps.refresh(token))) return send(deps.token())
    return res
  }
}

function transportFor(o: AssistantOptions, baseUrl: string, apiKey: string, platform: Platform): ChatTransport | undefined {
  const common = { model: o.model, baseUrl, apiKey, provider: o.provider.name }
  switch (o.provider.kind) {
    case 'ANTHROPIC':
      // 浏览器里直连 Anthropic 要显式声明(它才回跨域头)
      return createAnthropicTransport({ ...common, headers: o.mode === 'direct' ? { 'anthropic-dangerous-direct-browser-access': 'true' } : undefined }, platform)
    case 'GOOGLE':
      return createGoogleTransport(common, platform)
    case 'OPENAI_RESPONSES':
      return createOpenAIResponsesTransport(common, platform)
    default:
      return undefined // OpenAI 兼容:内核自带
  }
}

/** 直接问 /mcp 要工具列表(带 _meta 与标注),供权限判断与确认卡用 */
async function listTools(origin: string, fetch: PlatformFetch): Promise<Map<string, ToolMeta>> {
  const res = await fetch(`${origin}/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} }),
  })
  if (res.status === 403) throw new Error(await res.text().catch(() => '助手已停用'))
  if (!res.ok) throw new Error(`连不上 gopan 的工具(${res.status})`)
  const body = JSON.parse(await res.text()) as { result?: { tools?: Parameters<typeof toolMetas>[0] } }
  return toolMetas(body.result?.tools ?? [])
}

export async function createAssistant(o: AssistantOptions, deps: AssistantDeps): Promise<AssistantHandle> {
  const base = deps.platform ?? defaultPlatform()
  let turn = randomId(base)
  const extra = () => ({ 'X-Gopan-Assistant': o.conversationId, 'X-Gopan-Turn': turn })
  const direct = deps.shellFetch ?? base.fetch
  const platform: Platform = { ...base, fetch: gopanFetch(o.origin, base.fetch, deps, extra, direct) }

  const proxied = o.provider.site || o.mode === 'proxy'
  const baseUrl = proxied ? `${o.origin}/api/ai/${o.provider.id}` : o.provider.baseUrl
  // 转发时 key 在服务端,桌面端直连时外壳自己取;tau 的几种 provider 要求有个值,给个占位
  // (转发时发出去前换成登录凭证,外壳不转认证头)
  const apiKey = proxied || deps.shellFetch ? 'gopan' : await deps.providerKey(o.provider.id)

  const metas = await listTools(o.origin, platform.fetch)
  for (const m of FILE_TOOL_METAS) metas.set(m.name, m)
  const files = fileTools(new GopanFs(mcpCaller(o.origin, platform.fetch)))
  let scopes = scopePermissions(o.groups)
  const sessionAllowed = new Set<string>()
  const policy: ToolPolicy = {
    assess(call) {
      const action = decide(metas.get(call.toolName), scopes, sessionAllowed)
      return { risk: action === 'allow' ? 'low' : 'medium', action, reason: action === 'deny' ? '这类操作在「MCP 访问 → 助手」里设成了拒绝' : undefined }
    },
    deniesTool(name) {
      return decide(metas.get(name), scopes, sessionAllowed) === 'deny'
    },
  }

  const mcp = createHttpMcpExtension({
    servers: [{ name: MCP_SERVER, url: `${o.origin}/mcp`, riskFromAnnotations: true }],
    platform,
  })
  const extensions = await ExtensionRegistry.load([mcp.extension])
  await mcp.connect()

  const restored = await restoreSession(o.store)
  const session = await SessionRecorder.open(o.store)
  const agent = new Agent({
    config: { baseUrl, apiKey: proxied ? undefined : apiKey, model: o.model, provider: o.provider.name },
    transport: transportFor(o, baseUrl, apiKey, platform),
    platform,
    systemPrompt: o.systemPrompt,
    tools: files,
    extensions,
    session,
    initialMessages: restored.messages,
    permissionMode: 'supervised',
    policy,
    async onApproval(request) {
      const meta = metas.get(request.toolName)
      const answer = await o.onApproval({ request, meta, canAllowSession: !meta?.destructive })
      if (answer === 'session' && !meta?.destructive) sessionAllowed.add(request.toolName)
      return answer !== 'deny'
    },
  })
  return {
    agent,
    metas,
    beginTurn() {
      turn = randomId(base)
    },
    setGroups(groups) {
      scopes = scopePermissions(groups)
    },
  }
}
