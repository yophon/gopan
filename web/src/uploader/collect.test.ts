import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/client', () => ({
  // 测试里用 { code } 普通对象模拟业务错误
  getErrorCode: (err: unknown) => (err as { code?: string } | null | undefined)?.code,
}))
vi.mock('@/api/errors', () => ({
  errorText: (err: unknown, fallback: string) =>
    (err as { msg?: string } | null | undefined)?.msg ?? fallback,
}))

import { createCollectUploader } from './collect'
import type { CollectSession, CollectTransport } from './collect'

// ---------- 环境桩 ----------

function stubLocalStorage() {
  const map = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
  })
  return map
}

class FakeXHR {
  static instances: FakeXHR[] = []
  static behavior: (xhr: FakeXHR) => void = (xhr) => xhr.succeed()

  url = ''
  status = 0
  body: Blob | null = null
  aborted = false
  upload: { onprogress: ((e: { loaded: number }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null

  open(_method: string, url: string) {
    this.url = url
  }
  send(body: Blob) {
    this.body = body
    FakeXHR.instances.push(this)
    queueMicrotask(() => FakeXHR.behavior(this))
  }
  abort() {
    this.aborted = true
    this.onabort?.()
  }
  succeed() {
    if (this.aborted) return
    this.upload.onprogress?.({ loaded: this.body!.size })
    this.status = 200
    this.onload?.()
  }
  fail(status: number) {
    if (this.aborted) return
    this.status = status
    this.onload?.()
  }
}

function makeFile(name = 'f.bin', content = 'abcdefgh', lastModified = 111): File {
  return new File([content], name, { lastModified })
}

function session(over: Partial<CollectSession> = {}): CollectSession {
  return {
    id: 'sess1',
    partSize: 4,
    status: 'uploading',
    uploadedParts: [],
    partUrls: [
      { partNumber: 1, url: 'u1' },
      { partNumber: 2, url: 'u2' },
    ],
    ...over,
  }
}

function transport(over: Partial<CollectTransport> = {}): CollectTransport & { [K in keyof CollectTransport]: ReturnType<typeof vi.fn> } {
  return {
    init: vi.fn(async () => session()),
    session: vi.fn(async () => session()),
    complete: vi.fn(async (_id: string) => ({ name: 'f.bin', size: 8 })),
    abort: vi.fn(async () => {}),
    ...over,
  } as never
}

/** 让排队的微任务与 0ms 定时器都跑完 */
async function flush(times = 20) {
  for (let i = 0; i < times; i++) await new Promise((r) => setTimeout(r, 0))
}

let storage: Map<string, string>

beforeEach(() => {
  storage = stubLocalStorage()
  vi.stubGlobal('XMLHttpRequest', FakeXHR)
  FakeXHR.instances = []
  FakeXHR.behavior = (xhr) => xhr.succeed()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('createCollectUploader', () => {
  it('不算 hash 直接开传:init → 分片 PUT → complete,落库名字回填', async () => {
    const t = transport({ complete: vi.fn(async () => ({ name: 'f (1).bin', size: 8 })) })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await flush()

    expect(t.init).toHaveBeenCalledWith('f.bin', 8, null)
    expect(FakeXHR.instances.map((x) => x.url).sort()).toEqual(['u1', 'u2'])
    const task = up.tasks[0]!
    expect(task.status).toBe('done')
    expect(task.finalName).toBe('f (1).bin')
    expect(task.uploadedBytes).toBe(8)
    expect(up.busy.value).toBe(false)
    // 完成后断点记录清掉
    expect(storage.get('k')).toBeUndefined()
  })

  it('complete 处理中会轮询,缺片会补传', async () => {
    let calls = 0
    const t = transport({
      complete: vi.fn(async () => {
        calls++
        if (calls === 1) throw { code: 'UPLOAD_PROCESSING' }
        if (calls === 2) throw { code: 'MISSING_PART' }
        return { name: 'f.bin', size: 8 }
      }),
      session: vi.fn(async () => session({ uploadedParts: [1], partUrls: [{ partNumber: 2, url: 'u2b' }] })),
    })
    vi.useFakeTimers({ toFake: ['setTimeout'] })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await vi.runAllTimersAsync()
    expect(up.tasks[0]!.status).toBe('done')
    expect(FakeXHR.instances.map((x) => x.url)).toContain('u2b')
  })

  it('同一个文件再选一次:沿用断点记录的会话,只传缺的片', async () => {
    storage.set('k', JSON.stringify([{ sessionId: 'old', fileName: 'f.bin', size: 8, lastModified: 111 }]))
    const t = transport({
      session: vi.fn(async (id: string) =>
        session({ id, uploadedParts: [1], partUrls: [{ partNumber: 2, url: 'u2' }] }),
      ),
    })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await flush()
    expect(t.init).not.toHaveBeenCalled()
    expect(t.session).toHaveBeenCalledWith('old')
    expect(FakeXHR.instances.map((x) => x.url)).toEqual(['u2'])
    expect(t.complete).toHaveBeenCalledWith('old')
    expect(up.tasks[0]!.status).toBe('done')
  })

  it('分享访客:目标文件夹交给 init;断点记录按文件夹区分', async () => {
    storage.set('k', JSON.stringify([{ sessionId: 'old', fileName: 'f.bin', size: 8, lastModified: 111, target: 'A' }]))
    const t = transport()
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()], 'B') // 同一个文件传到另一个文件夹:不沿用 A 的会话
    await flush()
    expect(t.session).not.toHaveBeenCalled()
    expect(t.init).toHaveBeenCalledWith('f.bin', 8, 'B')
    expect(up.tasks[0]!.target).toBe('B')
  })

  it('断点会话已过期:丢掉记录重新开会话', async () => {
    storage.set('k', JSON.stringify([{ sessionId: 'old', fileName: 'f.bin', size: 8, lastModified: 111 }]))
    const t = transport({ session: vi.fn(async () => Promise.reject({ code: 'UPLOAD_EXPIRED' })) })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await flush()
    expect(t.init).toHaveBeenCalled()
    expect(up.tasks[0]!.status).toBe('done')
  })

  it('收件已满等确定性拒绝:直接失败、不重试,文案透出', async () => {
    const t = transport({ init: vi.fn(async () => Promise.reject({ code: 'COLLECT_FULL', msg: '收件已满' })) })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await flush()
    expect(t.init).toHaveBeenCalledTimes(1)
    expect(up.tasks[0]!.status).toBe('failed')
    expect(up.tasks[0]!.error).toBe('收件已满')
  })

  it('网络错误:任务级退避重试,第 3 次仍失败才判 failed', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] })
    const t = transport({ init: vi.fn(async () => Promise.reject(new Error('net'))) })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await vi.runAllTimersAsync()
    expect(t.init).toHaveBeenCalledTimes(3)
    expect(up.tasks[0]!.status).toBe('failed')
    // 手动重试
    t.init.mockImplementation(async () => session())
    up.retry(up.tasks[0]!.id)
    await vi.runAllTimersAsync()
    expect(up.tasks[0]!.status).toBe('done')
  })

  it('同时最多 2 个文件在传', async () => {
    FakeXHR.behavior = () => {} // 分片挂起
    const t = transport()
    const up = createCollectUploader(t, 'k')
    up.add([makeFile('a'), makeFile('b'), makeFile('c')])
    await flush()
    expect(up.tasks.map((x) => x.status)).toEqual(['uploading', 'uploading', 'queued'])
    expect(up.busy.value).toBe(true)
  })

  it('取消进行中的上传:中断分片、通知服务端放弃会话', async () => {
    FakeXHR.behavior = () => {}
    const t = transport()
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await flush()
    const task = up.tasks[0]!
    expect(task.status).toBe('uploading')
    up.cancel(task.id)
    await flush()
    expect(task.status).toBe('canceled')
    expect(FakeXHR.instances.every((x) => x.aborted)).toBe(true)
    expect(t.abort).toHaveBeenCalledWith('sess1')
    expect(storage.get('k')).toBeUndefined()
    up.remove(task.id)
    expect(up.tasks).toHaveLength(0)
  })

  it('分片 403(签名过期):换新签名重试该片', async () => {
    let first = true
    FakeXHR.behavior = (xhr) => {
      if (xhr.url === 'u1' && first) {
        first = false
        xhr.fail(403)
      } else xhr.succeed()
    }
    const t = transport({
      session: vi.fn(async () => session({ partUrls: [{ partNumber: 1, url: 'u1-new' }, { partNumber: 2, url: 'u2' }] })),
    })
    const up = createCollectUploader(t, 'k')
    up.add([makeFile()])
    await flush(40)
    expect(FakeXHR.instances.map((x) => x.url)).toContain('u1-new')
    expect(up.tasks[0]!.status).toBe('done')
  })
})
