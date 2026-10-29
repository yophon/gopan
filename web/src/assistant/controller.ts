import { computed, reactive, shallowRef } from 'vue'
import { defaultPlatform, type AgentEvent } from '@yophon/tau-kernel'

import { getErrorCode, request, singleFlightRefresh } from '@/api/client'
import {
  AiEntriesDocument,
  AiProviderKeyDocument,
  AppendAiEntriesDocument,
  AssistantAccessDocument,
  CreateAiConversationDocument,
  type AiConversationFieldsFragment,
} from '@/api/gen/graphql'
import { shellAiFetch } from '@/desktop/aifetch'
import { useAuthStore } from '@/stores/auth'
import { createAssistant, type ApprovalAnswer, type ApprovalView, type AssistantHandle, type ProviderView } from './agent'
import { describeCall, type CallDescription } from './describe'
import { SYSTEM_PROMPT } from './prompt'
import type { AccessGroup, Permission } from './policy'
import { ConflictError, GopanSessionStore } from './session'
import { assistantContext, describeContext, type ConnectionMode } from './state'
import { buildTranscript, friendlyError, shortToolName, titleFrom, withContext, type TranscriptItem } from './transcript'

/**
 * 助手面板的控制器(doc/33 四):一个对话一个 tau Agent;对话记录写在 gopan 上。
 * 换了服务 / 模型 / 连接方式就按同一份记录重建 Agent,接着聊。
 */


export interface PendingApproval {
  view: ApprovalView
  description: CallDescription
  resolve(answer: ApprovalAnswer): void
}

export interface ChatSetup {
  provider: ProviderView
  model: string
  mode: ConnectionMode
}

function toGroups(raw: { key: string; scopes: string[]; permission: string }[]): AccessGroup[] {
  return raw.map((g) => ({ key: g.key, scopes: g.scopes, permission: g.permission.toLowerCase() as Permission }))
}

export const chat = reactive({
  conversation: null as AiConversationFieldsFragment | null,
  items: [] as TranscriptItem[],
  live: '',
  running: false,
  error: '',
  conflict: false,
  approval: null as PendingApproval | null,
  loading: false,
})

const handle = shallowRef<AssistantHandle | null>(null)
let store: GopanSessionStore | null = null
let built = ''

export const canSend = computed(() => !chat.running && !chat.loading && !chat.conflict)

function platformBytes() {
  return defaultPlatform().randomBytes
}

function newStore(conv: AiConversationFieldsFragment): GopanSessionStore {
  return new GopanSessionStore(
    { id: conv.id, cwd: '/', timestamp: conv.createdAt },
    platformBytes(),
    {
      async append(fromSeq, entries) {
        try {
          const r = await request(AppendAiEntriesDocument, { conversationId: conv.id, fromSeq, entries })
          if (chat.conversation?.id === conv.id) chat.conversation = r.appendAiEntries
          return r.appendAiEntries.entries
        } catch (err) {
          if (getErrorCode(err) === 'CONFLICT') {
            chat.conflict = true
            throw new ConflictError()
          }
          throw err
        }
      },
    },
    conv.entries,
  )
}

function refresh() {
  chat.items = handle.value ? buildTranscript(handle.value.agent.messages) : chat.items
}

/** 新对话:清空面板,第一句话发出去时再建 */
export function newChat() {
  if (chat.running) return
  handle.value = null
  store = null
  built = ''
  Object.assign(chat, { conversation: null, items: [], live: '', error: '', conflict: false, approval: null })
}

/** 打开一个旧对话:读回记录,画出来;Agent 等发消息时再建 */
export async function openChat(conv: AiConversationFieldsFragment) {
  if (chat.running) return
  newChat()
  chat.loading = true
  chat.conversation = conv
  try {
    const { aiEntries } = await request(AiEntriesDocument, { conversationId: conv.id, fromSeq: 0 })
    const s = newStore({ ...conv, entries: aiEntries.length })
    await s.load(aiEntries)
    store = s
    const { restoreSession } = await import('@yophon/tau-kernel')
    chat.items = buildTranscript((await restoreSession(s)).messages)
  } catch (err) {
    chat.error = err instanceof Error ? err.message : String(err)
  } finally {
    chat.loading = false
  }
}

async function ensureAgent(setup: ChatSetup, firstText: string): Promise<AssistantHandle> {
  const key = `${setup.provider.id}|${setup.model}|${setup.mode}`
  if (handle.value && built === key) return handle.value
  if (!chat.conversation) {
    const { createAiConversation } = await request(CreateAiConversationDocument, { title: titleFrom(firstText), providerId: setup.provider.id, model: setup.model })
    chat.conversation = createAiConversation
    store = newStore(createAiConversation)
  }
  if (!store) throw new Error('对话没有打开')
  const auth = useAuthStore()
  const access = await request(AssistantAccessDocument)
  if (access.assistantAccess.disabled) throw new Error('助手已停用:在「MCP 访问 → 助手」里打开')
  const direct = setup.mode === 'direct' && !setup.provider.site
  handle.value = await createAssistant(
    {
      origin: location.origin,
      provider: setup.provider,
      model: setup.model,
      mode: setup.provider.site ? 'proxy' : setup.mode,
      conversationId: chat.conversation.id,
      store,
      groups: toGroups(access.assistantAccess.groups),
      systemPrompt: SYSTEM_PROMPT,
      onApproval: (view) =>
        new Promise<ApprovalAnswer>((resolve) => {
          chat.approval = {
            view,
            description: describeCall(view.meta?.tool ?? shortToolName(view.request.toolName), view.request.args),
            resolve: (answer) => {
              chat.approval = null
              resolve(answer)
            },
          }
        }),
    },
    {
      token: () => auth.accessToken,
      refresh: (stale) => singleFlightRefresh(stale),
      providerKey: async (id) => (await request(AiProviderKeyDocument, { id })).aiProviderKey,
      shellFetch: direct ? shellAiFetch(setup.provider) : undefined,
    },
  )
  built = key
  return handle.value
}

function friendly(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err)
  if (/Failed to fetch|NetworkError|Load failed/i.test(msg)) return '连不上模型服务。浏览器直连要对方允许跨域,不行就改用「经服务器转发」'
  return friendlyError(msg)
}

export async function send(text: string, setup: ChatSetup) {
  const input = text.trim()
  if (!input || !canSend.value) return
  chat.running = true
  chat.error = ''
  chat.live = ''
  // 先把这句话画上,建 Agent(连工具)要一会儿
  chat.items = [...chat.items, { kind: 'user', key: `pending-${Date.now()}`, text: input }]
  try {
    const h = await ensureAgent(setup, input)
    h.beginTurn()
    const ctx = describeContext(assistantContext.value)
    for await (const ev of h.agent.prompt(withContext(input, ctx)) as AsyncGenerator<AgentEvent>) {
      if (ev.type === 'text_delta') chat.live += ev.delta
      else if (ev.type === 'assistant_message' || ev.type === 'tool_start' || ev.type === 'tool_result' || ev.type === 'user_message') {
        chat.live = ''
        refresh()
      }
    }
  } catch (err) {
    chat.error = friendly(err)
  } finally {
    chat.live = ''
    refresh()
    chat.running = false
    if (chat.approval) chat.approval.resolve('deny')
    await store?.flush()
    if (store?.lastError) chat.error ||= '对话记录没存上,网络好了会再试'
  }
}

export function stop() {
  handle.value?.agent.abort('用户停止')
  chat.approval?.resolve('deny')
}

/** 权限在面板外改了:下一次调用起按新的来 */
export async function reloadAccess() {
  if (!handle.value) return
  const access = await request(AssistantAccessDocument)
  handle.value.setGroups(toGroups(access.assistantAccess.groups))
}
