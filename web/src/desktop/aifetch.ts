import type { PlatformFetch, PlatformResponse } from '@yophon/tau-kernel'

import { desktopWindow, invokeDesktop, isDesktop, onDesktop } from './bridge'

/**
 * 桌面端里助手直连模型服务(doc/33 四):窗口里的网页也有跨域限制,交给外壳发(desktop_ai_fetch)。
 * 网页只给服务 id 和路径,外壳自己取地址与 key,key 不进网页;响应经 desktop://ai 事件一块块回来。
 */

type AiEvent =
  | { kind: 'head'; id: string; status: number; contentType: string }
  | { kind: 'chunk'; id: string; data: string }
  | { kind: 'end'; id: string }
  | { kind: 'error'; id: string; message: string }

interface Pending {
  head(e: Extract<AiEvent, { kind: 'head' }>): void
  chunk(bytes: Uint8Array): void
  end(error?: string): void
}

const pending = new Map<string, Pending>()
let listening = false

function listen() {
  if (listening) return
  listening = true
  onDesktop<AiEvent>('desktop://ai', (e) => {
    const p = pending.get(e.id)
    if (!p) return
    if (e.kind === 'head') p.head(e)
    else if (e.kind === 'chunk') p.chunk(Uint8Array.from(atob(e.data), (c) => c.charCodeAt(0)))
    else {
      pending.delete(e.id)
      p.end(e.kind === 'error' ? e.message : undefined)
    }
  })
}

/** 外壳在、并且给了这个站点权限时才有 */
export function shellAiFetch(provider: { id: string; baseUrl: string }): PlatformFetch | undefined {
  if (!isDesktop() || desktopWindow() === null) return undefined
  const base = provider.baseUrl.replace(/\/+$/, '')
  return (url, init) => {
    if (!url.startsWith(`${base}/`)) return Promise.reject(new Error('只能请求这个模型服务'))
    listen()
    const rest = url.slice(base.length + 1)
    const q = rest.indexOf('?')
    const path = q < 0 ? rest : rest.slice(0, q)
    const query = q < 0 ? '' : rest.slice(q + 1)
    const id = crypto.randomUUID()

    const chunks: Uint8Array[] = []
    let done = false
    let failure: string | undefined
    let wake: (() => void) | null = null
    const notify = () => {
      wake?.()
      wake = null
    }

    return new Promise<PlatformResponse>((resolve, reject) => {
      let headed = false
      pending.set(id, {
        head(e) {
          headed = true
          const reader = {
            async read(): Promise<{ done: boolean; value?: Uint8Array }> {
              while (chunks.length === 0 && !done) await new Promise<void>((r) => (wake = r))
              if (chunks.length > 0) return { done: false, value: chunks.shift()! }
              if (failure) throw new Error(failure)
              return { done: true }
            },
            cancel() {
              void invokeDesktop('desktop_ai_abort', { id }).catch(() => {})
            },
          }
          resolve({
            ok: e.status >= 200 && e.status < 300,
            status: e.status,
            headers: { get: (n: string) => (n.toLowerCase() === 'content-type' ? e.contentType : null) },
            body: { getReader: () => reader },
            async text() {
              const dec = new TextDecoder()
              let s = ''
              for (;;) {
                const r = await reader.read()
                if (r.done) return s + dec.decode()
                s += dec.decode(r.value, { stream: true })
              }
            },
          })
        },
        chunk(bytes) {
          chunks.push(bytes)
          notify()
        },
        end(error) {
          done = true
          failure = error
          notify()
          if (!headed) reject(new Error(error ?? '模型服务没有回应'))
        },
      })
      init?.signal?.addEventListener('abort', () => void invokeDesktop('desktop_ai_abort', { id }).catch(() => {}), { once: true })
      invokeDesktop('desktop_ai_fetch', { args: { id, provider: provider.id, path, query, headers: init?.headers ?? {}, body: init?.body ?? '' } }).catch((err: unknown) => {
        pending.delete(id)
        if (!headed) reject(err instanceof Error ? err : new Error(String(err)))
      })
    })
  }
}
