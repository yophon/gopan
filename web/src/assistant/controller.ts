import { computed, reactive, shallowRef } from 'vue'
import { defaultPlatform, type AgentEvent } from '@yophon/tau-kernel'

import { getErrorCode, request, singleFlightRefresh } from '@/api/client'
import {
  AiConversationDocument,
  AiEntriesDocument,
  AiMemoryDocument,
  AiProviderKeyDocument,
  AiRunDocument,
  AnswerAiRunDocument,
  AppendAiEntriesDocument,
  AssistantAccessDocument,
  CreateAiConversationDocument,
  SetAiConversationRunnerDocument,
  SetAiMemoryDocument,
  StartAiRunDocument,
  StopAiRunDocument,
  type AiConversationFieldsFragment,
  type AiRunFieldsFragment,
} from '@/api/gen/graphql'
import { onAssistantEvent } from '@/api/realtime'
import { shellAiFetch } from '@/desktop/aifetch'
import { useAuthStore } from '@/stores/auth'
import { createAssistant, type ApprovalAnswer, type ApprovalView, type AssistantHandle, type ProviderView } from './agent'
import { describeCall, type CallDescription } from './describe'
import { SYSTEM_PROMPT } from './prompt'
import type { AccessGroup, Permission } from './policy'
import { ConflictError, GopanSessionStore } from './session'
import { assistantContext, describeContext, type ConnectionMode } from './state'
import type { ViewItem } from './runtime'
import { buildTranscript, friendlyError, shortToolName, titleFrom, userContent, type TranscriptItem } from './transcript'

/**
 * 助手面板的控制器(doc/33 四):一个对话一个 tau Agent;对话记录写在 gopan 上。
 * 换了服务 / 模型 / 连接方式就按同一份记录重建 Agent,接着聊。
 *
 * 对话也可以交给服务器跑(doc/34,chat.onServer):不建 Agent,发消息走 startAiRun,画面由推送来的事件驱动,
 * 确认卡答复走 answerAiRun。本机跑的那一套不变。
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
  /** 这个对话(或还没建的新对话)交给服务器跑 */
  onServer: false,
  /** 服务器上这个对话最近的一轮 */
  run: null as AiRunFieldsFragment | null,
})

/** 本机正在跑一轮(服务器上跑的不算:那时可以去看别的对话) */
let localTurn = false

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

/** 新对话:清空面板,第一句话发出去时再建;「在服务器上运行」的开关留着 */
export function newChat() {
  if (localTurn) return
  handle.value = null
  store = null
  built = ''
  Object.assign(chat, { conversation: null, items: [], live: '', error: '', conflict: false, approval: null, running: false, run: null })
}

/** 从服务器读回这个对话的记录(打开时;服务器跑完一轮后,本机接着聊之前也要读一遍,不然序号对不上) */
async function loadEntries(conv: AiConversationFieldsFragment) {
  const { aiEntries } = await request(AiEntriesDocument, { conversationId: conv.id, fromSeq: 0 })
  const s = newStore({ ...conv, entries: aiEntries.length })
  await s.load(aiEntries)
  store = s
  handle.value = null
  built = ''
  const { restoreSession } = await import('@yophon/tau-kernel')
  chat.items = buildTranscript((await restoreSession(s)).messages)
}

/** 打开一个旧对话:读回记录,画出来;Agent 等发消息时再建。服务器上还没跑完的一轮接着显示 */
export async function openChat(conv: AiConversationFieldsFragment) {
  if (localTurn) return
  newChat()
  chat.loading = true
  chat.conversation = conv
  chat.onServer = conv.runner === 'SERVER'
  try {
    await loadEntries(conv)
    if (chat.onServer) {
      const { aiRun } = await request(AiRunDocument, { conversationId: conv.id })
      if (aiRun && ACTIVE.includes(aiRun.status)) applyRun(aiRun)
    }
  } catch (err) {
    chat.error = err instanceof Error ? err.message : String(err)
  } finally {
    chat.loading = false
  }
}

/** 按对话 id 打开(通知点进来) */
export async function openChatById(id: string) {
  const { aiConversation } = await request(AiConversationDocument, { id })
  await openChat(aiConversation)
}

/** 「在服务器上运行」开关:有对话时记到对话上 */
export async function setOnServer(on: boolean) {
  if (localTurn || chat.run && ACTIVE.includes(chat.run.status)) return
  chat.onServer = on
  const conv = chat.conversation
  if (!conv) return
  try {
    await request(SetAiConversationRunnerDocument, { id: conv.id, runner: on ? 'SERVER' : 'DEVICE' })
    chat.conversation = { ...conv, runner: on ? 'SERVER' : 'DEVICE' }
    if (!on) await loadEntries(chat.conversation) // 服务器写过的记录先读回来
  } catch (err) {
    chat.error = friendly(err)
  }
}

// ---------- 服务器上跑(doc/34) ----------

const ACTIVE: string[] = ['QUEUED', 'RUNNING', 'WAITING']

/**
 * 服务器推来的条目已经是人话(工具那一行写好了)。用户发的图推送里只有张数(doc/35):
 * 按顺序沿用面板上已有的那几条用户消息的图(刚发的、打开对话时从记录读的)。
 */
function fromViewItems(items: ViewItem[]): TranscriptItem[] {
  const known = chat.items.filter((it): it is Extract<TranscriptItem, { kind: 'user' }> => it.kind === 'user').map((it) => it.images)
  let u = 0
  return items.map((it) => {
    if (it.kind === 'tool') return { kind: 'tool', key: it.key, id: it.key, name: '', args: {}, status: (it.status ?? 'running') as 'running', output: '', label: it.text }
    if (it.kind === 'user') {
      const images = it.images ?? (it.imageCount ? known[u] : undefined)
      u++
      return { kind: 'user', key: it.key, text: it.text, ...(images?.length ? { images } : {}) }
    }
    return { kind: it.kind, key: it.key, text: it.text } as TranscriptItem
  })
}

function applyRun(run: AiRunFieldsFragment) {
  // 同一轮已经结束了,迟到的旧状态(startAiRun 的回应晚于推送)不认
  if (chat.run?.id === run.id && !ACTIVE.includes(chat.run.status) && ACTIVE.includes(run.status)) return
  chat.run = run
  chat.running = ACTIVE.includes(run.status)
  if (run.status === 'WAITING' && run.approval) {
    const card = JSON.parse(run.approval) as { title: string; names?: string[]; more?: number; detail?: string; canAllowSession?: boolean }
    const names = [...(card.names ?? [])]
    if (card.more) names.push(`……还有 ${card.more} 项`)
    chat.approval = {
      view: { canAllowSession: card.canAllowSession === true } as ApprovalView,
      description: { title: card.title, ids: [], paths: names, detail: card.detail },
      resolve: (answer) => {
        chat.approval = null
        void request(AnswerAiRunDocument, { id: run.id, answer }).catch((err: unknown) => (chat.error = friendly(err)))
      },
    }
  } else if (chat.onServer) {
    chat.approval = null
  }
  if (run.status === 'FAILED' && run.error) chat.error = friendlyError(run.error)
  if (!chat.running) chat.live = ''
}

// 推送来的事件:只认正开着的这个对话
onAssistantEvent((conversationId, raw) => {
  if (chat.conversation?.id !== conversationId) return
  const e = raw as { type?: string; items?: ViewItem[]; text?: string; message?: string; conversation?: Partial<AiConversationFieldsFragment>; run?: { id: string; status: string; approval?: unknown; error?: string } }
  switch (e.type) {
    case 'items':
      chat.items = fromViewItems(e.items ?? [])
      break
    case 'live':
      chat.live = e.text ?? ''
      break
    case 'conversation':
      if (e.conversation && chat.conversation) chat.conversation = { ...chat.conversation, ...e.conversation, runner: chat.conversation.runner, runStatus: chat.conversation.runStatus }
      break
    case 'error':
      chat.error = friendlyError(e.message ?? '')
      break
    case 'run':
      if (e.run) {
        applyRun({
          __typename: 'AiRun',
          id: e.run.id,
          conversationId,
          status: e.run.status.toUpperCase() as AiRunFieldsFragment['status'],
          approval: e.run.approval ? JSON.stringify(e.run.approval) : null,
          error: e.run.error ?? '',
        })
      }
      break
  }
})

async function sendOnServer(input: string, images: string[], setup: ChatSetup) {
  chat.error = ''
  chat.items = [...chat.items, { kind: 'user', key: `pending-${Date.now()}`, text: input, ...(images.length ? { images } : {}) }]
  chat.running = true
  try {
    const { startAiRun } = await request(StartAiRunDocument, {
      conversationId: chat.conversation?.id ?? null,
      text: input,
      images: images.length ? images : null,
      context: describeContext(assistantContext.value),
      providerId: setup.provider.id,
      model: setup.model,
    })
    if (!chat.conversation) {
      const { aiConversation } = await request(AiConversationDocument, { id: startAiRun.conversationId })
      chat.conversation = aiConversation
    }
    applyRun(startAiRun)
    // 这一轮可能在回应回来之前就结束了(新对话那时推送还对不上号,被丢掉):再读一次它现在的状态
    const { aiRun } = await request(AiRunDocument, { conversationId: startAiRun.conversationId })
    if (aiRun?.id === startAiRun.id && !ACTIVE.includes(aiRun.status) && chat.conversation?.id === aiRun.conversationId) {
      const pending = chat.items
      await loadEntries(chat.conversation)
      if (!chat.items.length) chat.items = pending // 一条也没写进去(出错在前):留着刚发的那句
      applyRun(aiRun)
    }
  } catch (err) {
    chat.running = false
    chat.error = friendly(err)
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
      memory: memoryStore,
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

/** 长期记忆存在服务器上(doc/38);只有 USER.md 一份,没有项目层,list 用不到 */
const memoryStore = {
  read: async (file: string) => (await request(AiMemoryDocument, { name: file })).aiMemory,
  write: async (file: string, content: string) => {
    await request(SetAiMemoryDocument, { name: file, content })
  },
  list: async () => [] as string[],
}

function friendly(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err)
  if (/Failed to fetch|NetworkError|Load failed/i.test(msg)) return '连不上模型服务。浏览器直连要对方允许跨域,不行就改用「经服务器转发」'
  return friendlyError(msg)
}

/** images:随这句话发的图(data URL,面板已缩好,doc/35) */
export async function send(text: string, setup: ChatSetup, images: string[] = []) {
  const input = text.trim()
  if ((!input && !images.length) || !canSend.value) return
  if (chat.onServer) return sendOnServer(input, images, setup)
  localTurn = true
  chat.running = true
  chat.error = ''
  chat.live = ''
  // 先把这句话画上,建 Agent(连工具)要一会儿
  chat.items = [...chat.items, { kind: 'user', key: `pending-${Date.now()}`, text: input, ...(images.length ? { images } : {}) }]
  try {
    const h = await ensureAgent(setup, input || '图片')
    h.beginTurn()
    const ctx = describeContext(assistantContext.value)
    for await (const ev of h.agent.prompt(userContent(input, ctx, images)) as AsyncGenerator<AgentEvent>) {
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
    localTurn = false
    if (chat.approval) chat.approval.resolve('deny')
    await store?.flush()
    if (store?.lastError) chat.error ||= '对话记录没存上,网络好了会再试'
  }
}

export function stop() {
  if (chat.onServer && chat.run) {
    void request(StopAiRunDocument, { id: chat.run.id }).catch((err: unknown) => (chat.error = friendly(err)))
    return
  }
  handle.value?.agent.abort('用户停止')
  chat.approval?.resolve('deny')
}

/** 权限在面板外改了:下一次调用起按新的来 */
export async function reloadAccess() {
  if (!handle.value) return
  const access = await request(AssistantAccessDocument)
  handle.value.setGroups(toGroups(access.assistantAccess.groups))
}
