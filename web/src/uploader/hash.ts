import type { HashWorkerResponse } from './hash.worker'

/** 取消哈希时抛出的错误,调用方据此区分「用户中断」与真实失败 */
export class HashAbortedError extends Error {
  constructor() {
    super('hash aborted')
    this.name = 'HashAbortedError'
  }
}

/**
 * 在 Web Worker 中流式计算文件 SHA-256(64 位小写 hex)。
 * signal 中断时 terminate worker 并抛 HashAbortedError。
 */
export function hashFile(
  file: File,
  onProgress?: (hashed: number, total: number) => void,
  signal?: AbortSignal,
): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new HashAbortedError())
      return
    }

    const worker = new Worker(new URL('./hash.worker.ts', import.meta.url), {
      type: 'module',
    })

    const cleanup = () => {
      worker.terminate()
      signal?.removeEventListener('abort', onAbort)
    }
    const onAbort = () => {
      cleanup()
      reject(new HashAbortedError())
    }
    signal?.addEventListener('abort', onAbort)

    worker.onmessage = (e: MessageEvent<HashWorkerResponse>) => {
      const msg = e.data
      if (msg.type === 'progress') {
        onProgress?.(msg.hashed, msg.total)
      } else if (msg.type === 'done') {
        cleanup()
        resolve(msg.hex)
      } else {
        cleanup()
        reject(new Error(`哈希计算失败:${msg.message}`))
      }
    }
    worker.onerror = (e) => {
      cleanup()
      reject(new Error(`哈希 Worker 异常:${e.message}`))
    }

    worker.postMessage({ file })
  })
}
