import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const { requestMock, elSuccess, elError } = vi.hoisted(() => ({
  requestMock: vi.fn(),
  elSuccess: vi.fn(),
  elError: vi.fn(),
}))

vi.mock('element-plus', () => ({ ElMessage: { success: elSuccess, error: elError } }))
vi.mock('@/api/client', () => ({
  request: requestMock,
  // 测试里用 { code } 普通对象模拟业务错误
  getErrorCode: (err: unknown) => (err as { code?: string } | null | undefined)?.code,
}))
vi.mock('@/api/errors', () => ({
  errorText: (err: unknown, fallback: string) =>
    (err as { msg?: string } | null | undefined)?.msg ?? fallback,
}))
// 保留真实 HashAbortedError(manager 用 instanceof 判断),只替换 hashFile
vi.mock('./hash', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./hash')>()
  return { ...actual, hashFile: vi.fn() }
})

import {
  AbortUploadDocument,
  CompleteUploadDocument,
  InitUploadDocument,
  UploadSessionDocument,
} from '@/api/gen/graphql'
import { useUploadsStore } from '@/stores/uploads'

import { HashAbortedError, hashFile } from './hash'
import {
  cancelTask,
  enqueueFiles,
  pauseTask,
  removeFinishedTask,
  resumeTask,
  retryTask,
  setUploadInvalidator,
} from './manager'

const hashFileMock = vi.mocked(hashFile)

// ---------- 环境桩 ----------

/** manager 用 window.setTimeout/setInterval,node 环境没有 window;懒代理到全局,兼容假时钟 */
function stubWindow() {
  vi.stubGlobal('window', {
    setTimeout: (fn: () => void, ms?: number) => setTimeout(fn, ms),
    clearTimeout: (id: Parameters<typeof clearTimeout>[0]) => clearTimeout(id),
    setInterval: (fn: () => void, ms?: number) => setInterval(fn, ms),
    clearInterval: (id: Parameters<typeof clearInterval>[0]) => clearInterval(id),
  })
}

function stubLocalStorage() {
  const map = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
  })
}

/** 可编程假 XHR:默认 send 后微任务内成功(200) */
class FakeXHR {
  static instances: FakeXHR[] = []
  static behavior: (xhr: FakeXHR) => void = (xhr) => xhr.succeed()

  method = ''
  url = ''
  status = 0
  body: Blob | null = null
  aborted = false
  upload: { onprogress: ((e: { loaded: number }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null

  open(method: string, url: string) {
    this.method = method
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

// ---------- 数据工具 ----------

function makeFile(name = 'f.bin', content = 'abcdefgh', lastModified = 111): File {
  return new File([content], name, { lastModified })
}

interface FakeSession {
  id: string
  partSize: number
  uploadedParts: number[]
  partUrls: { partNumber: number; url: string }[]
}

function session(over: Partial<FakeSession> = {}): FakeSession {
  return { id: 'sess1', partSize: 4, uploadedParts: [], partUrls: [], ...over }
}

/** 常用 request 桩:init 非秒传给 session、complete 成功、abort 成功 */
function mockHappyRequests(sess: FakeSession) {
  requestMock.mockImplementation((document: unknown) => {
    if (document === InitUploadDocument) {
      return Promise.resolve({ initUpload: { instant: false, session: sess } })
    }
    if (document === CompleteUploadDocument) return Promise.resolve({ completeUpload: {} })
    if (document === AbortUploadDocument) return Promise.resolve({ abortUpload: true })
    return Promise.reject(new Error('unexpected request'))
  })
}

function callsFor(document: unknown) {
  return requestMock.mock.calls.filter((c) => c[0] === document)
}

let invalidated: (string | null)[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  stubWindow()
  stubLocalStorage()
  vi.stubGlobal('XMLHttpRequest', FakeXHR)
  FakeXHR.instances = []
  FakeXHR.behavior = (xhr) => xhr.succeed()
  requestMock.mockReset()
  elSuccess.mockReset()
  elError.mockReset()
  hashFileMock.mockReset()
  hashFileMock.mockImplementation(async (file) => `hash-${file.name}`)
  invalidated = []
  setUploadInvalidator((parentId) => invalidated.push(parentId))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

// ---------- 入队与并发 ----------

describe('enqueueFiles', () => {
  it('初始化任务字段;全局最多 2 个文件同时在跑,其余排队', () => {
    hashFileMock.mockImplementation(() => new Promise<string>(() => {})) // 冻结在 hashing
    const store = useUploadsStore()
    store.collapsed = true

    enqueueFiles([makeFile('a'), makeFile('b'), makeFile('c')], 'folder1')

    expect(store.tasks).toHaveLength(3)
    expect(store.tasks.map((t) => t.status)).toEqual(['hashing', 'hashing', 'queued'])
    const t = store.tasks[0]!
    expect(t.fileName).toBe('a')
    expect(t.size).toBe(8)
    expect(t.parentId).toBe('folder1')
    expect(t.instant).toBe(false)
    expect(t.uploadedBytes).toBe(0)
    expect(t.sessionId).toBeNull()
    // 入队自动展开抽屉
    expect(store.collapsed).toBe(false)
  })

  it('命中断点记录:沿用记录的 sessionId 与 parentId', () => {
    hashFileMock.mockImplementation(() => new Promise<string>(() => {}))
    const store = useUploadsStore()
    store.saveRecord({
      sessionId: 'rec-sess',
      fileName: 'f.bin',
      size: 8,
      lastModified: 111,
      sha256: 'hash-f.bin',
      parentId: 'old-folder',
    })

    enqueueFiles([makeFile()], 'new-folder')

    const t = store.tasks[0]!
    expect(t.sessionId).toBe('rec-sess')
    expect(t.parentId).toBe('old-folder')
  })
})

// ---------- 主流程 ----------

describe('上传主流程', () => {
  it('秒传:init 返回 instant → 直接 done,进度拉满并刷新列表', async () => {
    requestMock.mockResolvedValue({ initUpload: { instant: true, session: null } })
    const store = useUploadsStore()

    enqueueFiles([makeFile()], 'folder1')
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('done'))

    expect(t.instant).toBe(true)
    expect(t.uploadedBytes).toBe(8)
    expect(invalidated).toEqual(['folder1'])
    expect(callsFor(InitUploadDocument)[0]![1]).toEqual({
      parentId: 'folder1',
      name: 'f.bin',
      sha256: 'hash-f.bin',
      size: 8,
    })
    // 无缺片,不该有任何 PUT
    expect(FakeXHR.instances).toHaveLength(0)
  })

  it('普通上传:分片 PUT → complete → done,断点记录先存后清', async () => {
    mockHappyRequests(
      session({
        partUrls: [
          { partNumber: 1, url: 'https://s3/p1' },
          { partNumber: 2, url: 'https://s3/p2' },
        ],
      }),
    )
    const store = useUploadsStore()

    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('done'))

    expect(FakeXHR.instances.map((x) => [x.method, x.url])).toEqual([
      ['PUT', 'https://s3/p1'],
      ['PUT', 'https://s3/p2'],
    ])
    expect(FakeXHR.instances.map((x) => x.body!.size)).toEqual([4, 4])
    expect(t.uploadedBytes).toBe(8)
    expect(callsFor(CompleteUploadDocument)[0]![1]).toEqual({ sessionId: 'sess1', etags: [] })
    // done 后断点记录清掉
    expect(store.records).toEqual([])
    expect(invalidated).toEqual([null])
  })

  it('恢复会话:已有分片计入基线进度,只补缺片', async () => {
    // 8 字节文件,partSize 4:1 号片已在服务端,只需传 2 号
    mockHappyRequests(
      session({ uploadedParts: [1], partUrls: [{ partNumber: 2, url: 'https://s3/p2' }] }),
    )
    const store = useUploadsStore()

    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('done'))

    expect(FakeXHR.instances.map((x) => x.url)).toEqual(['https://s3/p2'])
    expect(t.uploadedBytes).toBe(8)
  })

  it('complete 报 MISSING_PART:拉新会话补片后重试 complete', async () => {
    let completeCalls = 0
    requestMock.mockImplementation((document: unknown) => {
      if (document === InitUploadDocument) {
        return Promise.resolve({ initUpload: { instant: false, session: session() } })
      }
      if (document === CompleteUploadDocument) {
        completeCalls++
        if (completeCalls === 1) return Promise.reject({ code: 'MISSING_PART' })
        return Promise.resolve({ completeUpload: {} })
      }
      if (document === UploadSessionDocument) {
        return Promise.resolve({
          uploadSession: session({ partUrls: [{ partNumber: 1, url: 'https://s3/p1-again' }] }),
        })
      }
      return Promise.reject(new Error('unexpected request'))
    })
    const store = useUploadsStore()

    enqueueFiles([makeFile('f.bin', 'abcd')], null) // 4 字节单片
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('done'))

    expect(completeCalls).toBe(2)
    expect(FakeXHR.instances.map((x) => x.url)).toEqual(['https://s3/p1-again'])
  })

  it('分片 403(预签名过期):刷新会话;服务端已有该片则直接跳过', async () => {
    FakeXHR.behavior = (xhr) => (xhr.url === 'https://s3/p1' ? xhr.fail(403) : xhr.succeed())
    requestMock.mockImplementation((document: unknown) => {
      if (document === InitUploadDocument) {
        return Promise.resolve({
          initUpload: {
            instant: false,
            session: session({
              partUrls: [
                { partNumber: 1, url: 'https://s3/p1' },
                { partNumber: 2, url: 'https://s3/p2' },
              ],
            }),
          },
        })
      }
      if (document === UploadSessionDocument) {
        // 刷新后:1 号片其实已经在服务端了
        return Promise.resolve({ uploadSession: session({ uploadedParts: [1] }) })
      }
      if (document === CompleteUploadDocument) return Promise.resolve({ completeUpload: {} })
      return Promise.reject(new Error('unexpected request'))
    })
    const store = useUploadsStore()

    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('done'))

    expect(callsFor(UploadSessionDocument)).toHaveLength(1)
    expect(t.uploadedBytes).toBe(8)
  })
})

// ---------- 失败与重试 ----------

describe('失败与重试', () => {
  it('致命错误码不重试直接 failed;QUOTA_EXCEEDED 额外弹全局提示', async () => {
    requestMock.mockRejectedValue({ code: 'QUOTA_EXCEEDED', msg: '云盘空间不足' })
    const store = useUploadsStore()

    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('failed'))

    expect(t.error).toBe('云盘空间不足')
    expect(callsFor(InitUploadDocument)).toHaveLength(1) // 没有自动重试
    expect(elError).toHaveBeenCalledWith('「f.bin」上传失败:云盘空间不足')
  })

  it('普通错误自动重试(2s/4s 退避),3 次后落 failed', async () => {
    vi.useFakeTimers()
    requestMock.mockRejectedValue(new Error('network flaky'))
    const store = useUploadsStore()

    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    await vi.advanceTimersByTimeAsync(0)
    expect(t.status).toBe('queued') // 第 1 次失败,进 2s 退避

    await vi.advanceTimersByTimeAsync(2100)
    expect(t.status).toBe('queued') // 第 2 次失败,进 4s 退避
    expect(callsFor(InitUploadDocument)).toHaveLength(2)

    await vi.advanceTimersByTimeAsync(4100)
    expect(t.status).toBe('failed') // 第 3 次失败,不再重试
    expect(callsFor(InitUploadDocument)).toHaveLength(3)
    expect(t.error).toBe('上传失败,请稍后重试')
  })

  it('retryTask 只对 failed 生效:清零重试计数重新入队跑完', async () => {
    requestMock.mockRejectedValue({ code: 'NAME_CONFLICT', msg: '同名文件已存在' })
    const store = useUploadsStore()
    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    await vi.waitFor(() => expect(t.status).toBe('failed'))

    requestMock.mockResolvedValue({ initUpload: { instant: true, session: null } })
    retryTask(t.id)
    await vi.waitFor(() => expect(t.status).toBe('done'))
    expect(t.error).toBeNull()

    // 非 failed 状态调用 retryTask 是 no-op
    retryTask(t.id)
    expect(t.status).toBe('done')
  })
})

// ---------- 暂停 / 恢复 / 取消 ----------

describe('暂停 / 恢复 / 取消', () => {
  /** hashing 卡住、abort 时抛 HashAbortedError 的桩 */
  function abortableHash() {
    hashFileMock.mockImplementation(
      (_file, _onProgress, signal) =>
        new Promise<string>((_resolve, reject) => {
          signal?.addEventListener('abort', () => reject(new HashAbortedError()))
        }),
    )
  }

  it('hashing 中暂停 → paused;恢复 → 重新入队再跑', async () => {
    abortableHash()
    const store = useUploadsStore()
    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    expect(t.status).toBe('hashing')

    pauseTask(t.id)
    await vi.waitFor(() => expect(t.status).toBe('paused'))
    expect(t.speedBps).toBe(0)

    hashFileMock.mockImplementation(async (file) => `hash-${file.name}`)
    requestMock.mockResolvedValue({ initUpload: { instant: true, session: null } })
    resumeTask(t.id)
    await vi.waitFor(() => expect(t.status).toBe('done'))
    expect(hashFileMock).toHaveBeenCalledTimes(2) // 暂停时哈希被丢弃,恢复后重算
  })

  it('queued 任务暂停无需中断在飞请求,直接 paused', () => {
    hashFileMock.mockImplementation(() => new Promise<string>(() => {}))
    const store = useUploadsStore()
    enqueueFiles([makeFile('a'), makeFile('b'), makeFile('c')], null)
    const queued = store.tasks[2]!
    expect(queued.status).toBe('queued')
    pauseTask(queued.id)
    expect(queued.status).toBe('paused')
  })

  it('hashing 中取消:即使中断表现为 HashAbortedError 也判 canceled 并清断点记录', async () => {
    abortableHash()
    const store = useUploadsStore()
    // 断点记录让任务自带 sessionId,取消时应通知服务端释放
    store.saveRecord({
      sessionId: 'rec-sess',
      fileName: 'f.bin',
      size: 8,
      lastModified: 111,
      sha256: 'x',
      parentId: null,
    })
    requestMock.mockResolvedValue({ abortUpload: true })

    enqueueFiles([makeFile()], null)
    const t = store.tasks[0]!
    expect(t.status).toBe('hashing')

    cancelTask(t.id)
    await vi.waitFor(() => expect(t.status).toBe('canceled'))
    expect(store.records).toEqual([])
    await vi.waitFor(() =>
      expect(callsFor(AbortUploadDocument)[0]![1]).toEqual({ sessionId: 'rec-sess' }),
    )
  })

  it('removeFinishedTask 只移除终态任务', async () => {
    abortableHash()
    const store = useUploadsStore()
    enqueueFiles([makeFile('a'), makeFile('b')], null)
    const running = store.tasks[0]!

    removeFinishedTask(running.id) // 进行中 → no-op
    expect(store.tasks).toHaveLength(2)

    cancelTask(running.id)
    await vi.waitFor(() => expect(running.status).toBe('canceled'))
    removeFinishedTask(running.id)
    expect(store.tasks.map((t) => t.fileName)).toEqual(['b'])
  })
})
