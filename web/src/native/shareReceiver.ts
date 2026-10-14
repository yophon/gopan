import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'
import type { Router } from 'vue-router'

import { useIncomingShareStore } from '@/stores/incomingShare'

/**
 * 安卓壳的系统分享接收(app/android ShareReceiverPlugin.java 的 JS 侧)。
 *
 * 文件不经 bridge 整个传:原生侧先拷进 cacheDir,这里按 4MB 一片 readChunk,
 * 拼成 Blob → File,交给 incomingShare store;ChatView 取走后走既有的分片上传。
 * 浏览器/PWA 里 Capacitor.isNativePlatform() 为 false,整个模块是空操作。
 */

interface SharedFileMeta {
  id: string
  name: string
  type: string
  size: number
}

interface PendingShare {
  text?: string
  files: SharedFileMeta[]
}

interface ShareReceiverPlugin {
  getPending(): Promise<PendingShare>
  readChunk(opts: { id: string; offset: number; length: number }): Promise<{ data: string; eof: boolean }>
  release(opts: { id: string }): Promise<void>
  addListener(event: 'shareReceived', fn: () => void): Promise<PluginListenerHandle>
}

const CHUNK = 4 * 1024 * 1024

const ShareReceiver = registerPlugin<ShareReceiverPlugin>('ShareReceiver')

export function isNativeApp(): boolean {
  return Capacitor.isNativePlatform()
}

/** base64 → bytes(原生侧 NO_WRAP,没有换行) */
export function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

async function assembleFile(meta: SharedFileMeta): Promise<File> {
  const parts: BlobPart[] = []
  let offset = 0
  for (;;) {
    const { data, eof } = await ShareReceiver.readChunk({ id: meta.id, offset, length: CHUNK })
    if (data) {
      const bytes = base64ToBytes(data)
      parts.push(bytes)
      offset += bytes.length
    }
    if (eof || !data) break
  }
  await ShareReceiver.release({ id: meta.id }).catch(() => {})
  return new File(parts, meta.name, { type: meta.type || 'application/octet-stream' })
}

let draining = false

/** 取走原生侧待处理的分享,塞进 store 并跳到传输助手 */
export async function drainNativeShare(router: Router): Promise<void> {
  if (draining) return
  draining = true
  try {
    const pending = await ShareReceiver.getPending()
    const files: File[] = []
    for (const meta of pending.files ?? []) {
      try {
        files.push(await assembleFile(meta))
      } catch {
        // 单个文件读失败不影响其余
      }
    }
    if (!pending.text && files.length === 0) return
    useIncomingShareStore().push({ text: pending.text, files })
    if (router.currentRoute.value.name !== 'chat') await router.push({ name: 'chat' })
  } finally {
    draining = false
  }
}

/** main.ts 调用:原生环境下启动时取一次,之后监听 shareReceived 事件 */
export function initNativeShare(router: Router): void {
  if (!isNativeApp()) return
  void router.isReady().then(() => drainNativeShare(router))
  void ShareReceiver.addListener('shareReceived', () => {
    void drainNativeShare(router)
  })
}
