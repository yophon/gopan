import { createIncrementalUtf8Decoder, type Platform, type PlatformBodyReader, type PlatformFetch, type PlatformResponse } from '@yophon/tau-kernel'

import type { ApprovalAnswer } from './agent'
import { AssistantRuntime, type SendArgs } from './runtime'

/**
 * 服务器上跑助手(doc/34):gopan 每一轮起一个 quickjs-ng 的 `qjs --std` 进程跑这个入口,
 * 由 scripts/build-assistant-runtime.mjs 打成 iife,embed 进 gopan。
 *
 * 和 Go 之间一行一条 JSON:
 * - 标准输出(JS → Go):fetch / cancel 发请求与掐掉;sleep / unsleep 等待与取消;emit 事件;approve 要确认;done 这一轮完了。
 * - 标准输入(Go → JS):start 开跑;head / chunk / end / error 响应;wake 到点;answer 确认的答复;stop 停。
 *
 * qjs 里什么都不用自己等(网络、计时都交给 Go,到点了 Go 写一行过来):Windows 版的 qjs 在等标准输入时
 * 计时器会被耽误,这样两边行为一致。没有 crypto,随机数用 Math.random(只用来生成条目 id)。
 */

declare const std: {
  in: { fileno(): number }
  out: { puts(s: string): void; flush(): void }
}
declare const os: {
  setReadHandler(fd: number, fn: (() => void) | null): void
  read(fd: number, buffer: ArrayBuffer, offset: number, length: number): number
}

type Incoming =
  | { op: 'start'; origin: string; conversationId: string; args: SendArgs }
  | { op: 'head'; id: number; status: number; headers: Record<string, string> }
  | { op: 'chunk'; id: number; data: string }
  | { op: 'end'; id: number }
  | { op: 'error'; id: number; message: string }
  | { op: 'wake'; id: number }
  | { op: 'answer'; answer: ApprovalAnswer }
  | { op: 'stop' }

function send(msg: Record<string, unknown>) {
  std.out.puts(JSON.stringify(msg) + '\n')
  std.out.flush()
}

let nextId = 1

// ---- 请求 ----

interface Pending {
  head?: (h: { status: number; headers: Record<string, string> }) => void
  fail?: (err: Error) => void
  chunks: Uint8Array[]
  done: boolean
  error?: string
  wake?: () => void
  /** 有新数据 / 结束了:叫醒等着读的那一次 */
  notify: () => void
}
const requests = new Map<number, Pending>()

function fromBase64(s: string): Uint8Array {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

const bridgedFetch: PlatformFetch = (url, init) =>
  new Promise<PlatformResponse>((resolve, reject) => {
    const id = nextId++
    const signal = init?.signal
    if (signal?.aborted) return reject(new Error('aborted'))
    const p: Pending = {
      chunks: [],
      done: false,
      notify: () => {
        const w = p.wake
        p.wake = undefined
        w?.()
      },
    }
    requests.set(id, p)
    signal?.addEventListener(
      'abort',
      () => {
        send({ op: 'cancel', id })
        p.error = 'aborted'
        p.done = true
        p.fail?.(new Error('aborted'))
        p.notify()
      },
      { once: true },
    )
    p.fail = (err) => {
      p.fail = undefined
      p.head = undefined
      reject(err)
    }
    p.head = ({ status, headers }) => {
      p.head = undefined
      p.fail = undefined
      const reader: PlatformBodyReader = {
        async read() {
          while (p.chunks.length === 0 && !p.done) await new Promise<void>((r) => (p.wake = r))
          if (p.chunks.length > 0) return { done: false, value: p.chunks.shift()! }
          requests.delete(id)
          if (p.error) throw new Error(p.error)
          return { done: true }
        },
        cancel() {
          send({ op: 'cancel', id })
          p.done = true
          requests.delete(id)
          return undefined
        },
      }
      resolve({
        ok: status >= 200 && status < 300,
        status,
        headers: { get: (n) => headers[n.toLowerCase()] ?? null },
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
      })
    }
    send({ op: 'fetch', id, url, method: init?.method ?? 'GET', headers: init?.headers ?? {}, body: init?.body ?? null })
  })

// ---- 等待 ----

const sleeps = new Map<number, () => void>()

const platform: Platform = {
  fetch: bridgedFetch,
  createUtf8Decoder: createIncrementalUtf8Decoder,
  randomBytes: (n) => {
    const b = new Uint8Array(n)
    for (let i = 0; i < n; i++) b[i] = Math.floor(Math.random() * 256)
    return b
  },
  sleep: (ms, signal) =>
    new Promise<void>((resolve, reject) => {
      if (signal?.aborted) return reject(new Error('aborted'))
      const id = nextId++
      sleeps.set(id, resolve)
      signal?.addEventListener(
        'abort',
        () => {
          sleeps.delete(id)
          send({ op: 'unsleep', id })
          reject(new Error('aborted'))
        },
        { once: true },
      )
      send({ op: 'sleep', id, ms })
    }),
}

// ---- 确认与停止 ----

let answerWaiter: ((a: ApprovalAnswer) => void) | null = null
let stopWaiter: ((stop: boolean) => void) | null = null
let stopped = false

function handle(msg: Incoming) {
  switch (msg.op) {
    case 'start':
      void run(msg)
      return
    case 'head':
      requests.get(msg.id)?.head?.({ status: msg.status, headers: msg.headers })
      return
    case 'chunk': {
      const p = requests.get(msg.id)
      if (!p) return
      p.chunks.push(fromBase64(msg.data))
      p.notify()
      return
    }
    case 'end':
    case 'error': {
      const p = requests.get(msg.id)
      if (!p) return
      if (msg.op === 'error') {
        p.error = msg.message
        p.fail?.(new Error(msg.message))
      }
      p.done = true
      p.notify()
      return
    }
    case 'wake': {
      const r = sleeps.get(msg.id)
      sleeps.delete(msg.id)
      r?.()
      return
    }
    case 'answer': {
      const w = answerWaiter
      answerWaiter = null
      w?.(msg.answer)
      return
    }
    case 'stop': {
      stopped = true
      answerWaiter?.('deny')
      answerWaiter = null
      stopWaiter?.(true)
      stopWaiter = null
      return
    }
  }
}

async function run(msg: Extract<Incoming, { op: 'start' }>) {
  const runtime = new AssistantRuntime({
    origin: msg.origin.replace(/\/+$/, ''),
    platform,
    emit: (event) => send({ op: 'emit', event }),
    approve: (view) =>
      new Promise<ApprovalAnswer>((resolve) => {
        if (stopped) return resolve('deny')
        answerWaiter = resolve
        send({ op: 'approve', view })
      }),
    waitStop: () =>
      new Promise<boolean>((resolve) => {
        if (stopped) return resolve(true)
        stopWaiter = resolve
      }),
    turnEnd: () => {
      stopWaiter?.(false)
      stopWaiter = null
    },
  })
  let error: string | undefined
  try {
    await runtime.open(msg.conversationId)
    if (!stopped) await runtime.send(msg.args)
  } catch (err) {
    error = err instanceof Error ? err.message : String(err)
  }
  send({ op: 'done', error, stopped })
  os.setReadHandler(stdin, null)
}

// 标准输入:每次可读就把能读到的字节全读出来,自己按换行切行。不用 std.in.getline():Go 连着写几行时,
// 一次回调只取走一行,剩下的留在 C 库的缓冲里,描述符不再「可读」,回调就再也不来了(Linux 上会卡住)。
const stdin = std.in.fileno()
const readBuf = new ArrayBuffer(64 << 10)
const decoder = createIncrementalUtf8Decoder()
let pendingText = ''
os.setReadHandler(stdin, () => {
  const n = os.read(stdin, readBuf, 0, readBuf.byteLength)
  if (n <= 0) {
    // Go 那边关了标准输入:不会再有任何回应,结束
    os.setReadHandler(stdin, null)
    return
  }
  pendingText += decoder.decode(new Uint8Array(readBuf, 0, n))
  for (let i = pendingText.indexOf('\n'); i >= 0; i = pendingText.indexOf('\n')) {
    const line = pendingText.slice(0, i)
    pendingText = pendingText.slice(i + 1)
    if (!line.trim()) continue
    try {
      handle(JSON.parse(line) as Incoming)
    } catch {
      // 坏的一行跳过
    }
  }
})
send({ op: 'ready' })
