import { createIncrementalUtf8Decoder, type Platform, type PlatformBodyReader, type PlatformFetch, type PlatformResponse } from '@yophon/tau-kernel'

import type { ApprovalAnswer } from './agent'
import { AssistantRuntime, type ApprovalRequestView, type SendArgs } from './runtime'

/**
 * 安卓 App 里 QuickJS 跑的入口(doc/33 八之 2),由 scripts/build-assistant-runtime.mjs 打成一个 iife,
 * 放进 App 的 assets。App(quickjs-kt)在全局注入下面这些 __host* 函数:
 *
 * - 网络走 App 的 OkHttp:__hostFetch 发出请求、拿到状态与响应头;__hostRead 一块块读响应体(读完给 null);
 *   __hostCancel 掐掉。同源请求由 App 加登录凭证。
 * - __hostRandom 随机字节;__hostSleep 等待(超时计时、重试退避),__hostCancelSleep 取消 —— 计时赢了就取消,
 *   不然 QuickJS 那边会一直等着这个没用的计时(evaluate 要等所有异步任务结束才返回)。
 * - __hostIdle:一次调用(send / open)结束,App 把还挂着的计时、读取都清掉。
 * - __hostEmit 把对话的变化交给界面;__hostApprove 弹确认卡并等用户选;__hostWaitStop / __hostTurnEnd 是「停止」按钮。
 *
 * App 调 gopanAssistant.init / reset / open / send,参数与事件都是 JSON 字符串。
 */

interface HostGlobals {
  __hostFetch(id: number, requestJson: string): Promise<string>
  __hostRead(id: number): Promise<Int8Array | null>
  __hostCancel(id: number): void
  __hostRandom(n: number): Int8Array
  __hostSleep(id: number, ms: number): Promise<void>
  __hostCancelSleep(id: number): void
  __hostIdle(): void
  __hostEmit(eventJson: string): void
  __hostApprove(viewJson: string): Promise<string>
  __hostWaitStop(): Promise<boolean>
  __hostTurnEnd(): void
}

const host = globalThis as unknown as HostGlobals

const toBytes = (a: Int8Array): Uint8Array => new Uint8Array(a.buffer, a.byteOffset, a.byteLength)

let nextId = 1

const bridgedFetch: PlatformFetch = async (url, init) => {
  const id = nextId++
  const signal = init?.signal
  if (signal?.aborted) throw new Error('aborted')
  const onAbort = () => host.__hostCancel(id)
  signal?.addEventListener('abort', onAbort, { once: true })
  let meta: { status: number; headers: Record<string, string> }
  try {
    meta = JSON.parse(await host.__hostFetch(id, JSON.stringify({ url, method: init?.method ?? 'GET', headers: init?.headers ?? {}, body: init?.body ?? null })))
  } catch (err) {
    signal?.removeEventListener('abort', onAbort)
    throw signal?.aborted ? new Error('aborted') : err
  }
  let finished = false
  const reader: PlatformBodyReader = {
    async read() {
      if (finished) return { done: true }
      const chunk = await host.__hostRead(id).catch((err: unknown) => {
        finished = true
        throw signal?.aborted ? new Error('aborted') : err
      })
      if (chunk === null || chunk === undefined) {
        finished = true
        signal?.removeEventListener('abort', onAbort)
        return { done: true }
      }
      return { done: false, value: toBytes(chunk) }
    },
    cancel() {
      finished = true
      host.__hostCancel(id)
      return undefined
    },
  }
  const response: PlatformResponse = {
    ok: meta.status >= 200 && meta.status < 300,
    status: meta.status,
    headers: { get: (name) => meta.headers[name.toLowerCase()] ?? null },
    body: { getReader: () => reader },
    async text() {
      const decoder = createIncrementalUtf8Decoder()
      let text = ''
      for (;;) {
        const r = await reader.read()
        if (r.value) text += decoder.decode(r.value)
        if (r.done) return text + decoder.flush()
      }
    },
  }
  return response
}

const platform: Platform = {
  fetch: bridgedFetch,
  createUtf8Decoder: createIncrementalUtf8Decoder,
  randomBytes: (n) => toBytes(host.__hostRandom(n)),
  sleep: (ms, signal) =>
    new Promise<void>((resolve, reject) => {
      if (signal?.aborted) return reject(new Error('aborted'))
      const id = nextId++
      signal?.addEventListener(
        'abort',
        () => {
          host.__hostCancelSleep(id)
          reject(new Error('aborted'))
        },
        { once: true },
      )
      host.__hostSleep(id, ms).then(resolve, () => reject(new Error('aborted')))
    }),
}

let runtime: AssistantRuntime | null = null

function need(): AssistantRuntime {
  if (!runtime) throw new Error('gopanAssistant.init 还没调')
  return runtime
}

const api = {
  init(origin: string) {
    runtime = new AssistantRuntime({
      origin: origin.replace(/\/+$/, ''),
      platform,
      emit: (e) => host.__hostEmit(JSON.stringify(e)),
      approve: async (view: ApprovalRequestView) => (await host.__hostApprove(JSON.stringify(view))) as ApprovalAnswer,
      waitStop: () => host.__hostWaitStop(),
      turnEnd: () => host.__hostTurnEnd(),
    })
  },
  reset() {
    need().reset()
  },
  /** 出错时把原因交给界面,不往外抛(宿主那边只管等它结束) */
  async open(id: string) {
    try {
      await need().open(id)
    } catch (err) {
      host.__hostEmit(JSON.stringify({ type: 'error', message: err instanceof Error ? err.message : String(err) }))
    } finally {
      host.__hostIdle()
    }
  },
  async send(argsJson: string) {
    try {
      await need().send(JSON.parse(argsJson) as SendArgs)
    } finally {
      host.__hostIdle()
    }
  },
}

;(globalThis as unknown as { gopanAssistant: typeof api }).gopanAssistant = api
