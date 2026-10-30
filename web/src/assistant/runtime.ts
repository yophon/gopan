import { restoreSession, type AgentEvent, type Platform } from '@yophon/tau-kernel'

import { createAssistant, type ApprovalAnswer, type AssistantHandle, type ConnectionMode, type ProviderView } from './agent'
import { describeCall, toolLabel } from './describe'
import type { AccessGroup, Permission } from './policy'
import { SYSTEM_PROMPT } from './prompt'
import { ConflictError, GopanSessionStore } from './session'
import { buildTranscript, changedContext, friendlyError, shortToolName, titleFrom, userContent, type TranscriptItem } from './transcript'

/**
 * 不依赖浏览器的助手运行时(doc/33 八之 2):安卓 App 在 QuickJS 里跑它,界面是 Compose 原生写的。
 * 和网页的 controller.ts 做的是同一件事 —— 一个对话一个 tau Agent,对话记录写在 gopan 上 ——
 * 区别是状态不进 Vue,而是一条条事件交给宿主(emit),确认卡也交给宿主弹(approve)。
 *
 * 宿主的网络请求自己加登录凭证(401 时换一张再试),所以这里同源请求不带凭证。
 */

export interface ViewItem {
  kind: TranscriptItem['kind']
  key: string
  /** 用户 / 助手 / 出错的文字;工具调用是一行人话(「移动 2 项到「/报销」」) */
  text: string
  /** 工具调用:running / ok / error / denied */
  status?: string
  /** 用户发的图(data URL);宿主不要图(inlineImages 为 false)时只给张数 imageCount */
  images?: string[]
  imageCount?: number
}

export interface ConversationView {
  id: string
  title: string
  providerId: string | null
  model: string
  entries: number
  updatedAt: string
}

export type RuntimeEvent =
  | { type: 'items'; items: ViewItem[] }
  | { type: 'live'; text: string }
  | { type: 'conversation'; conversation: ConversationView }
  | { type: 'error'; message: string; conflict?: boolean }

export interface ApprovalRequestView {
  title: string
  /** 要动的东西(名字或路径),最多 20 个 */
  names: string[]
  more: number
  detail?: string
  canAllowSession: boolean
}

export interface RuntimeHost {
  origin: string
  platform: Platform
  /**
   * 事件里带不带用户发的图:App 在本进程里跑,带上好显示;服务器上跑的事件要推给各个设备,
   * 每次都是整份对话,只给张数(设备打开对话时从记录里读图)。默认 true
   */
  inlineImages?: boolean
  emit(event: RuntimeEvent): void
  approve(view: ApprovalRequestView): Promise<ApprovalAnswer>
  /** 这一轮里用户点了「停止」就 resolve(true);轮次结束(turnEnd)时 resolve(false) */
  waitStop(): Promise<boolean>
  turnEnd(): void
}

export interface SendArgs {
  text: string
  /** 随这句话发的图(data URL,客户端已缩好,doc/35) */
  images?: string[]
  context: string
  provider: ProviderView
  model: string
  mode: ConnectionMode
}

const CONV_FIELDS = 'id title providerId model entries createdAt updatedAt'

interface ConvRow extends ConversationView {
  createdAt: string
}

export function viewItems(items: readonly TranscriptItem[], inlineImages = true): ViewItem[] {
  return items.map((it) => {
    if (it.kind === 'tool') return { kind: it.kind, key: it.key, text: toolLabel(it.name, it.args), status: it.status }
    if (it.kind === 'user' && it.images?.length) {
      return inlineImages ? { kind: it.kind, key: it.key, text: it.text, images: it.images } : { kind: it.kind, key: it.key, text: it.text, imageCount: it.images.length }
    }
    return { kind: it.kind, key: it.key, text: it.text }
  })
}

export class AssistantRuntime {
  private readonly host: RuntimeHost
  private conversation: ConvRow | null = null
  private store: GopanSessionStore | null = null
  private handle: AssistantHandle | null = null
  private built = ''
  private items: ViewItem[] = []

  constructor(host: RuntimeHost) {
    this.host = host
  }

  private async gql<T>(query: string, variables: Record<string, unknown> = {}): Promise<T> {
    const res = await this.host.platform.fetch(`${this.host.origin}/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, variables }),
    })
    const body = JSON.parse(await res.text()) as { data?: T; errors?: { message: string; extensions?: { code?: string } }[] }
    const e = body.errors?.[0]
    if (e) {
      const err = new Error(e.message) as Error & { code?: string }
      err.code = e.extensions?.code
      throw err
    }
    if (!body.data) throw new Error(`gopan 没有回应(${res.status})`)
    return body.data
  }

  private newStore(conv: ConvRow): GopanSessionStore {
    return new GopanSessionStore(
      { id: conv.id, cwd: '/', timestamp: conv.createdAt },
      (n) => this.host.platform.randomBytes(n),
      {
        append: async (fromSeq, entries) => {
          try {
            const r = await this.gql<{ appendAiEntries: ConvRow }>(
              `mutation($c: ID!, $f: Int!, $e: [String!]!) { appendAiEntries(conversationId: $c, fromSeq: $f, entries: $e) { ${CONV_FIELDS} } }`,
              { c: conv.id, f: fromSeq, e: entries },
            )
            this.conversation = r.appendAiEntries
            return r.appendAiEntries.entries
          } catch (err) {
            if ((err as { code?: string }).code === 'CONFLICT') throw new ConflictError()
            throw err
          }
        },
      },
      conv.entries,
    )
  }

  private emitItems(items: ViewItem[]) {
    this.items = items
    this.host.emit({ type: 'items', items })
  }

  /** 新对话:清空;第一句话发出去时再建 */
  reset() {
    this.conversation = null
    this.store = null
    this.handle = null
    this.built = ''
    this.emitItems([])
  }

  /** 打开一个旧对话:读回记录,画出来 */
  async open(id: string) {
    this.reset()
    const { aiConversation } = await this.gql<{ aiConversation: ConvRow }>(`query($id: ID!) { aiConversation(id: $id) { ${CONV_FIELDS} } }`, { id })
    const { aiEntries } = await this.gql<{ aiEntries: string[] }>(`query($id: ID!) { aiEntries(conversationId: $id, fromSeq: 0) }`, { id })
    const store = this.newStore({ ...aiConversation, entries: aiEntries.length })
    await store.load(aiEntries)
    this.conversation = aiConversation
    this.store = store
    this.host.emit({ type: 'conversation', conversation: aiConversation })
    this.emitItems(this.view(buildTranscript((await restoreSession(store)).messages)))
  }

  private async ensure(args: SendArgs): Promise<AssistantHandle> {
    const key = `${args.provider.id}|${args.model}|${args.mode}`
    if (this.handle && this.built === key) return this.handle
    if (!this.conversation) {
      const r = await this.gql<{ createAiConversation: ConvRow }>(
        `mutation($t: String!, $p: ID, $m: String) { createAiConversation(title: $t, providerId: $p, model: $m) { ${CONV_FIELDS} } }`,
        { t: titleFrom(args.text), p: args.provider.id, m: args.model },
      )
      this.conversation = r.createAiConversation
      this.store = this.newStore(r.createAiConversation)
      this.host.emit({ type: 'conversation', conversation: r.createAiConversation })
    }
    if (!this.store) throw new Error('对话没有打开')
    const { assistantAccess } = await this.gql<{ assistantAccess: { disabled: boolean; groups: { key: string; scopes: string[]; permission: string }[] } }>(
      '{ assistantAccess { disabled groups { key scopes permission } } }',
    )
    if (assistantAccess.disabled) throw new Error('助手已停用:在「Agent 接入 → 助手」里打开')
    const groups: AccessGroup[] = assistantAccess.groups.map((g) => ({ key: g.key, scopes: g.scopes, permission: g.permission.toLowerCase() as Permission }))
    this.handle = await createAssistant(
      {
        origin: this.host.origin,
        provider: args.provider,
        model: args.model,
        mode: args.provider.site ? 'proxy' : args.mode,
        conversationId: this.conversation.id,
        store: this.store,
        groups,
        systemPrompt: SYSTEM_PROMPT,
        // 长期记忆存在服务器上(doc/38)
        memory: {
          read: async (file) => (await this.gql<{ aiMemory: string }>('query($n: String) { aiMemory(name: $n) }', { n: file })).aiMemory,
          write: async (file, content) => {
            await this.gql('mutation($n: String, $c: String!) { setAiMemory(name: $n, content: $c) }', { n: file, c: content })
          },
          list: async () => [],
        },
        onApproval: async (view) => {
          const d = describeCall(view.meta?.tool ?? shortToolName(view.request.toolName), view.request.args)
          const names = await Promise.all(
            d.ids.slice(0, 20).map((id) =>
              this.gql<{ node: { name: string } }>('query($id: ID!) { node(id: $id) { name } }', { id }).then((r) => r.node.name, () => '(找不到)'),
            ),
          )
          return this.host.approve({
            title: d.title,
            names: [...names, ...d.paths],
            more: Math.max(0, d.ids.length - 20),
            detail: d.detail,
            canAllowSession: view.canAllowSession,
          })
        },
      },
      {
        platform: this.host.platform,
        token: () => null,
        refresh: async () => false,
        providerKey: async (id) => (await this.gql<{ aiProviderKey: string }>('query($id: ID!) { aiProviderKey(id: $id) }', { id })).aiProviderKey,
      },
    )
    this.built = key
    return this.handle
  }

  private view(items: readonly TranscriptItem[]): ViewItem[] {
    return viewItems(items, this.host.inlineImages !== false)
  }

  async send(args: SendArgs) {
    const input = args.text.trim()
    const images = args.images ?? []
    if (!input && !images.length) return
    this.emitItems([...this.items, ...this.view([{ kind: 'user', key: `pending-${this.items.length}`, text: input, ...(images.length ? { images } : {}) }])])
    let stopped = false
    const stopWatch = this.host.waitStop().then((stop) => {
      if (!stop) return
      stopped = true
      this.handle?.agent.abort('用户停止')
    })
    try {
      const h = await this.ensure(args)
      if (stopped) return
      h.beginTurn()
      let live = ''
      for await (const ev of h.agent.prompt(userContent(input, changedContext(args.context, h.agent.messages), images)) as AsyncGenerator<AgentEvent>) {
        if (ev.type === 'text_delta') {
          live += ev.delta
          this.host.emit({ type: 'live', text: live })
        } else if (ev.type === 'assistant_message' || ev.type === 'tool_start' || ev.type === 'tool_result' || ev.type === 'user_message') {
          // 这段话已经进了消息列表:正在打字的那份清掉(也告诉宿主,不然会画两遍)
          if (live) this.host.emit({ type: 'live', text: '' })
          live = ''
          this.emitItems(this.view(buildTranscript(h.agent.messages)))
        }
      }
    } catch (err) {
      this.host.emit({ type: 'error', message: friendly(err) })
    } finally {
      if (this.handle) this.emitItems(this.view(buildTranscript(this.handle.agent.messages)))
      this.host.emit({ type: 'live', text: '' })
      this.host.turnEnd()
      await stopWatch
      await this.store?.flush()
      if (this.store?.conflict) this.host.emit({ type: 'error', message: '这个对话在别处有了新内容,请从「对话记录」重新打开', conflict: true })
      else if (this.store?.lastError) this.host.emit({ type: 'error', message: '对话记录没存上,网络好了会再试' })
      if (this.conversation) this.host.emit({ type: 'conversation', conversation: this.conversation })
    }
  }
}

function friendly(err: unknown): string {
  return friendlyError(err instanceof Error ? err.message : String(err))
}
