/// <reference lib="webworker" />
import { createSHA256 } from 'hash-wasm'

/**
 * SHA-256 哈希 Worker:流式读 File(File.slice + arrayBuffer 循环),
 * 恒定内存,大文件不卡主线程。
 *
 * 入:{ file: File }
 * 出:{ type: 'progress', hashed, total } | { type: 'done', hex } | { type: 'error', message }
 */

const CHUNK_SIZE = 8 * 1024 * 1024 // 8MB

export interface HashWorkerRequest {
  file: File
}

export type HashWorkerResponse =
  | { type: 'progress'; hashed: number; total: number }
  | { type: 'done'; hex: string }
  | { type: 'error'; message: string }

function post(msg: HashWorkerResponse) {
  self.postMessage(msg)
}

self.onmessage = async (e: MessageEvent<HashWorkerRequest>) => {
  const { file } = e.data
  try {
    const hasher = await createSHA256()
    hasher.init()
    let offset = 0
    while (offset < file.size) {
      const buf = await file.slice(offset, offset + CHUNK_SIZE).arrayBuffer()
      hasher.update(new Uint8Array(buf))
      offset += buf.byteLength
      post({ type: 'progress', hashed: offset, total: file.size })
    }
    post({ type: 'done', hex: hasher.digest('hex') })
  } catch (err) {
    post({ type: 'error', message: err instanceof Error ? err.message : String(err) })
  }
}
