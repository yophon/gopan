import { ElMessage } from 'element-plus'

import { getErrorCode, request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  AbortUploadDocument,
  CompleteUploadDocument,
  InitUploadDocument,
  UploadSessionDocument,
} from '@/api/gen/graphql'
import type { UploadSessionQuery } from '@/api/gen/graphql'
import { TERMINAL_STATUSES, useUploadsStore } from '@/stores/uploads'
import type { UploadTask } from '@/stores/uploads'

import { HashAbortedError, hashFile } from './hash'

/**
 * 上传管理器(模块级单例)。
 *
 * - 全局并发:同时 2 个文件在跑,每个文件 3 个分片并行 PUT。
 * - 分片 PUT 用 XMLHttpRequest(upload.onprogress 拿字节级进度),暂停/取消 = xhr.abort()。
 * - 自动重试:任务级 3 次指数退避;分片级 3 次,403(预签名过期)时经
 *   uploadSession(id) 拿新签名重试该片。
 * - 断点续传:sessionId + 文件指纹存 localStorage(见 stores/uploads.ts),
 *   恢复时先 hash 校验一致,再 uploadSession(id) 只传缺失分片。
 */

const MAX_ACTIVE_FILES = 2
const MAX_PARTS_PER_FILE = 3
const MAX_TASK_ATTEMPTS = 3
const MAX_PART_ATTEMPTS = 3
const SPEED_WINDOW_MS = 3000

/** 这些错误码重试也没用,直接判 failed */
const FATAL_CODES = new Set([
  'QUOTA_EXCEEDED',
  'TOO_MANY_SESSIONS',
  'NAME_CONFLICT',
  'BAD_SESSION_STATE',
  'UNAUTHENTICATED',
  'UPLOAD_EXPIRED',
  'HASH_MISMATCH',
  'SIZE_MISMATCH',
  'NOT_A_FOLDER',
  'NOT_FOUND',
])

type Session = UploadSessionQuery['uploadSession']

/** 用户暂停/取消导致的中断,与真实失败区分 */
class InterruptedError extends Error {
  constructor() {
    super('interrupted')
    this.name = 'InterruptedError'
  }
}

class PartHttpError extends Error {
  constructor(readonly status: number) {
    super(`分片上传失败(HTTP ${status})`)
    this.name = 'PartHttpError'
  }
}

/** 任务的非响应式运行时(File 句柄、xhr、进度簿记),不进 store */
interface Runtime {
  file: File
  interrupt: 'pause' | 'cancel' | null
  hashAbort: AbortController | null
  xhrs: Set<XMLHttpRequest>
  session: Session | null
  /** 已完成分片的字节合计 */
  completedBytes: number
  /** 进行中分片的 loaded(partNumber → bytes) */
  partLoaded: Map<number, number>
  /** 速度滑动窗口样本 */
  samples: Array<{ t: number; bytes: number }>
  /** 任务级已重试次数 */
  attempts: number
  /** 退避中,此时间点之前调度器不捡起 */
  nextAttemptAt: number
  /** 断点记录声称的 sha256,恢复时校验 */
  expectedSha256: string | null
  /** 预签名过期刷新会话的单飞 Promise */
  refreshing: Promise<Session> | null
}

const runtimes = new Map<string, Runtime>()

// ---------- 外部钩子:任务 done 后刷新文件列表 ----------

let invalidateChildren: (parentId: string | null) => void = () => {}
let uploadDoneListener: (task: UploadTask) => void = () => {}

/** 由 UI 层注入 vue-query 的 invalidate(manager 不依赖组件上下文) */
export function setUploadInvalidator(fn: (parentId: string | null) => void) {
  invalidateChildren = fn
}
export function setUploadDoneListener(fn: (task: UploadTask) => void) { uploadDoneListener = fn }

// ---------- 入队 ----------

export function enqueueFiles(files: File[], parentId: string | null) {
  const store = useUploadsStore()
  for (const file of files) {
    // 断点续传匹配:同 (name, size, lastModified) 的记录 → 挂上 sessionId 走恢复
    const record = store.matchRecord(file)
    const task: UploadTask = {
      id: crypto.randomUUID(),
      fileName: file.name,
      size: file.size,
      parentId: record ? record.parentId : parentId,
      status: 'queued',
      instant: false,
      hashedBytes: 0,
      uploadedBytes: 0,
      speedBps: 0,
      error: null,
      sessionId: record?.sessionId ?? null,
      sha256: null,
    }
    runtimes.set(task.id, {
      file,
      interrupt: null,
      hashAbort: null,
      xhrs: new Set(),
      session: null,
      completedBytes: 0,
      partLoaded: new Map(),
      samples: [],
      attempts: 0,
      nextAttemptAt: 0,
      expectedSha256: record?.sha256 ?? null,
      refreshing: null,
    })
    store.addTask(task)
  }
  store.collapsed = false
  schedule()
}

// ---------- 调度 ----------

const RUNNING_STATUSES = ['hashing', 'initiating', 'uploading', 'completing'] as const

function schedule() {
  const store = useUploadsStore()
  const running = store.tasks.filter((t) =>
    (RUNNING_STATUSES as readonly string[]).includes(t.status),
  ).length
  let slots = MAX_ACTIVE_FILES - running
  if (slots <= 0) return
  const now = Date.now()
  for (const task of store.tasks) {
    if (slots <= 0) break
    if (task.status !== 'queued') continue
    const rt = runtimes.get(task.id)
    if (!rt || rt.interrupt) continue
    if (rt.nextAttemptAt > now) continue // 退避中
    slots--
    void runTask(task.id)
  }
}

// ---------- 主流程 ----------

async function runTask(taskId: string) {
  const store = useUploadsStore()
  const task = store.getTask(taskId)
  const rt = runtimes.get(taskId)
  if (!task || !rt || task.status !== 'queued') return

  try {
    // 1. hashing(已算过则直接复用,暂停恢复不重算)
    if (!task.sha256) {
      task.status = 'hashing'
      rt.hashAbort = new AbortController()
      const hex = await hashFile(
        rt.file,
        (hashed) => {
          task.hashedBytes = hashed
        },
        rt.hashAbort.signal,
      )
      rt.hashAbort = null
      task.sha256 = hex
    }
    // 恢复校验:指纹相同但内容已变 → 放弃旧会话,走全新上传
    if (rt.expectedSha256 && rt.expectedSha256 !== task.sha256) {
      if (task.sessionId) store.removeRecord(task.sessionId)
      task.sessionId = null
      rt.expectedSha256 = null
    }
    throwIfInterrupted(rt)

    // 2. initiating:恢复已有会话,或 initUpload 新建
    task.status = 'initiating'
    let session: Session | null = null
    if (task.sessionId) {
      try {
        const res = await request(UploadSessionDocument, { id: task.sessionId })
        session = res.uploadSession
      } catch (err) {
        if (!['NOT_FOUND', 'UPLOAD_EXPIRED', 'BAD_SESSION_STATE'].includes(getErrorCode(err) ?? '')) throw err
        // 会话过期/不存在(48h)→ 清记录走全新上传
        store.removeRecord(task.sessionId)
        task.sessionId = null
      }
      throwIfInterrupted(rt)
    }
    if (!session) {
      const res = await request(InitUploadDocument, {
        parentId: task.parentId,
        name: task.fileName,
        sha256: task.sha256,
        size: task.size,
      })
      throwIfInterrupted(rt)
      if (res.initUpload.instant) {
        // 秒传:直接 done,进度条瞬间满
        task.instant = true
        task.uploadedBytes = task.size
        finishDone(task)
        return
      }
      session = res.initUpload.session!
      task.sessionId = session.id
      store.saveRecord({
        sessionId: session.id,
        fileName: task.fileName,
        size: task.size,
        lastModified: rt.file.lastModified,
        sha256: task.sha256,
        parentId: task.parentId,
      })
    }
    rt.session = session

    // 3. uploading:并发 PUT 缺失分片
    task.status = 'uploading'
    await uploadMissingParts(task, rt)
    throwIfInterrupted(rt)

    // 4. completing:etags 传空数组,服务端自己对账;
    //    MISSING_PART → 拉新会话补传缺片再试(至多 2 轮)
    task.status = 'completing'
    task.uploadedBytes = task.size
    rt.completedBytes = task.size
    let missingRounds = 0
    for (;;) {
      throwIfInterrupted(rt)
      try {
        await request(CompleteUploadDocument, { sessionId: task.sessionId!, etags: [] })
        break
      } catch (err) {
        if (getErrorCode(err) === 'UPLOAD_PROCESSING') {
          // Hashing large files runs in the server worker, not a long HTTP
          // request. Poll without spending the network-failure retry budget.
          await new Promise<void>((resolve) => window.setTimeout(resolve, 1000))
          continue
        }
        if (getErrorCode(err) === 'MISSING_PART' && missingRounds++ < 2) {
          const res = await request(UploadSessionDocument, { id: task.sessionId! })
          rt.session = res.uploadSession
          task.status = 'uploading'
          await uploadMissingParts(task, rt)
          task.status = 'completing'
          continue
        }
        throw err
      }
    }
    task.uploadedBytes = task.size
    finishDone(task)
  } catch (err) {
    handleTaskError(task, rt, err)
  } finally {
    schedule()
  }
}

function finishDone(task: UploadTask) {
  const store = useUploadsStore()
  if (task.sessionId) store.removeRecord(task.sessionId)
  task.status = 'done'
  task.speedBps = 0
  task.error = null
  runtimes.delete(task.id)
  uploadDoneListener(task)
  queueDoneToast(task)
  invalidateChildren(task.parentId)
}

function handleTaskError(task: UploadTask, rt: Runtime, err: unknown) {
  const store = useUploadsStore()

  if (rt.interrupt === 'cancel') {
    doCancelCleanup(task, rt)
    return
  }
  if (rt.interrupt === 'pause' || err instanceof HashAbortedError) {
    rt.interrupt = null
    rt.partLoaded.clear()
    rt.samples = []
    task.status = 'paused'
    task.speedBps = 0
    task.uploadedBytes = rt.completedBytes
    return
  }

  const code = getErrorCode(err)
  rt.attempts++
  if (!(code && FATAL_CODES.has(code)) && rt.attempts < MAX_TASK_ATTEMPTS) {
    // 自动重试:指数退避 2s / 4s
    const delay = 1000 * 2 ** rt.attempts
    rt.nextAttemptAt = Date.now() + delay
    rt.partLoaded.clear()
    task.status = 'queued'
    task.speedBps = 0
    task.uploadedBytes = rt.completedBytes
    window.setTimeout(schedule, delay + 20)
    return
  }

  task.status = 'failed'
  task.speedBps = 0
  task.error = errorText(err, '上传失败,请稍后重试')
  if (code === 'QUOTA_EXCEEDED') {
    ElMessage.error(`「${task.fileName}」上传失败:云盘空间不足`)
  }
  // failed 是终态,断点记录一并清掉
  if (task.sessionId) store.removeRecord(task.sessionId)
  if (code && FATAL_CODES.has(code)) {
    // Manual retry must create a new session after deterministic rejection.
    if (task.sessionId) void request(AbortUploadDocument, { sessionId: task.sessionId }).catch(() => {})
    task.sessionId = null
  }
}

// ---------- 分片上传 ----------

async function uploadMissingParts(task: UploadTask, rt: Runtime) {
  const session = rt.session!
  const partSize = session.partSize

  // 已有分片按字节计入基线进度
  rt.completedBytes = 0
  for (const n of session.uploadedParts) {
    rt.completedBytes += partByteLength(n, partSize, task.size)
  }
  rt.partLoaded.clear()
  rt.samples = []
  updateProgress(task, rt)
  ensureTicker()

  const queue = [...session.partUrls]
  if (queue.length === 0) return

  const workers: Promise<void>[] = []
  for (let i = 0; i < MAX_PARTS_PER_FILE; i++) {
    workers.push(
      (async () => {
        for (;;) {
          const part = queue.shift()
          if (!part) return
          throwIfInterrupted(rt)
          await uploadOnePart(task, rt, part.partNumber, part.url, partSize)
          rt.partLoaded.delete(part.partNumber)
          rt.completedBytes += partByteLength(part.partNumber, partSize, task.size)
          updateProgress(task, rt)
        }
      })(),
    )
  }
  try {
    await Promise.all(workers)
  } catch (err) {
    // 某片彻底失败:停掉队列和其余在飞分片,交给任务级重试
    queue.length = 0
    for (const xhr of rt.xhrs) xhr.abort()
    throw err
  }
}

async function uploadOnePart(
  task: UploadTask,
  rt: Runtime,
  partNumber: number,
  url: string,
  partSize: number,
) {
  const start = (partNumber - 1) * partSize
  const end = Math.min(start + partSize, task.size)
  const blob = rt.file.slice(start, end)

  let currentUrl = url
  for (let attempt = 0; ; attempt++) {
    throwIfInterrupted(rt)
    try {
      await putPart(rt, currentUrl, blob, (loaded) => {
        rt.partLoaded.set(partNumber, Math.min(loaded, blob.size))
        updateProgress(task, rt)
      })
      return
    } catch (err) {
      rt.partLoaded.delete(partNumber)
      updateProgress(task, rt)
      if (err instanceof InterruptedError) throw err
      if (attempt >= MAX_PART_ATTEMPTS - 1) throw err
      if (err instanceof PartHttpError && err.status === 403) {
        // 预签名过期 → 单飞刷新会话拿新签名,重试该片
        const session = await refreshSession(task, rt)
        if (session.uploadedParts.includes(partNumber)) return // 服务端已有该片
        const fresh = session.partUrls.find((p) => p.partNumber === partNumber)
        if (fresh) currentUrl = fresh.url
      } else {
        await sleep(1000 * 2 ** attempt)
      }
    }
  }
}

function refreshSession(task: UploadTask, rt: Runtime): Promise<Session> {
  if (!rt.refreshing) {
    rt.refreshing = request(UploadSessionDocument, { id: task.sessionId! })
      .then((res) => {
        rt.session = res.uploadSession
        return res.uploadSession
      })
      .finally(() => {
        rt.refreshing = null
      })
  }
  return rt.refreshing
}

/** 分片 PUT。全项目唯一一处 XHR:为了 upload.onprogress 和可 abort。不读响应 ETag。 */
function putPart(
  rt: Runtime,
  url: string,
  blob: Blob,
  onProgress: (loaded: number) => void,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    rt.xhrs.add(xhr)
    const settle = () => rt.xhrs.delete(xhr)
    xhr.open('PUT', url)
    xhr.upload.onprogress = (e) => onProgress(e.loaded)
    xhr.onload = () => {
      settle()
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new PartHttpError(xhr.status))
    }
    xhr.onerror = () => {
      settle()
      reject(new Error('网络错误'))
    }
    xhr.onabort = () => {
      settle()
      reject(new InterruptedError())
    }
    xhr.send(blob)
  })
}

function partByteLength(partNumber: number, partSize: number, total: number): number {
  const start = (partNumber - 1) * partSize
  return Math.max(0, Math.min(partSize, total - start))
}

// ---------- 进度与速度 ----------

function updateProgress(task: UploadTask, rt: Runtime) {
  let inflight = 0
  for (const v of rt.partLoaded.values()) inflight += v
  task.uploadedBytes = Math.min(task.size, rt.completedBytes + inflight)
}

let ticker: number | null = null

function ensureTicker() {
  if (ticker !== null) return
  ticker = window.setInterval(() => {
    const store = useUploadsStore()
    let anyUploading = false
    const now = Date.now()
    for (const task of store.tasks) {
      if (task.status !== 'uploading') continue
      const rt = runtimes.get(task.id)
      if (!rt) continue
      anyUploading = true
      rt.samples.push({ t: now, bytes: task.uploadedBytes })
      while (rt.samples.length > 1 && rt.samples[0]!.t < now - SPEED_WINDOW_MS) {
        rt.samples.shift()
      }
      const first = rt.samples[0]!
      const last = rt.samples[rt.samples.length - 1]!
      const dt = (last.t - first.t) / 1000
      task.speedBps = dt >= 0.4 ? Math.max(0, (last.bytes - first.bytes) / dt) : 0
    }
    if (!anyUploading && ticker !== null) {
      window.clearInterval(ticker)
      ticker = null
    }
  }, 500)
}

// ---------- 用户操作:暂停 / 继续 / 取消 / 重试 ----------

export function pauseTask(taskId: string) {
  const store = useUploadsStore()
  const task = store.getTask(taskId)
  const rt = runtimes.get(taskId)
  if (!task || !rt) return
  if (TERMINAL_STATUSES.includes(task.status) || task.status === 'paused') return

  if (task.status === 'queued') {
    task.status = 'paused'
    return
  }
  rt.interrupt = 'pause'
  rt.hashAbort?.abort()
  for (const xhr of rt.xhrs) xhr.abort()
  // 后续由 runTask 的 catch → handleTaskError 落到 paused
}

export function resumeTask(taskId: string) {
  const store = useUploadsStore()
  const task = store.getTask(taskId)
  const rt = runtimes.get(taskId)
  if (!task || !rt || task.status !== 'paused') return
  rt.interrupt = null
  rt.attempts = 0
  rt.nextAttemptAt = 0
  task.error = null
  task.status = 'queued'
  schedule()
}

export function cancelTask(taskId: string) {
  const store = useUploadsStore()
  const task = store.getTask(taskId)
  const rt = runtimes.get(taskId)
  if (!task) return
  if (TERMINAL_STATUSES.includes(task.status)) return

  if (!rt || task.status === 'queued' || task.status === 'paused' || task.status === 'failed') {
    // 没有在飞的请求,直接清
    if (rt) doCancelCleanup(task, rt)
    else task.status = 'canceled'
    return
  }
  rt.interrupt = 'cancel'
  rt.hashAbort?.abort()
  for (const xhr of rt.xhrs) xhr.abort()
  // 后续由 handleTaskError → doCancelCleanup
}

function doCancelCleanup(task: UploadTask, _rt: Runtime) {
  const store = useUploadsStore()
  task.status = 'canceled'
  task.speedBps = 0
  if (task.sessionId) {
    store.removeRecord(task.sessionId)
    // 尽力通知服务端释放会话,失败无所谓(48h 自动过期)
    void request(AbortUploadDocument, { sessionId: task.sessionId }).catch(() => {})
  }
  runtimes.delete(task.id)
}

/** failed 任务手动重试(File 句柄还在时才可用) */
export function retryTask(taskId: string) {
  const store = useUploadsStore()
  const task = store.getTask(taskId)
  const rt = runtimes.get(taskId)
  if (!task || !rt || task.status !== 'failed') return
  rt.attempts = 0
  rt.nextAttemptAt = 0
  rt.interrupt = null
  task.error = null
  task.status = 'queued'
  schedule()
}

/** 任务卡片上的「移除」:终态任务从列表拿掉 */
export function removeFinishedTask(taskId: string) {
  const store = useUploadsStore()
  const task = store.getTask(taskId)
  if (!task || !TERMINAL_STATUSES.includes(task.status)) return
  runtimes.delete(taskId)
  store.removeTask(taskId)
}

// ---------- done 提示合并(多文件不刷屏) ----------

let doneBuffer: string[] = []
let doneTimer: number | null = null

function queueDoneToast(task: UploadTask) {
  doneBuffer.push(task.fileName)
  if (doneTimer !== null) window.clearTimeout(doneTimer)
  doneTimer = window.setTimeout(() => {
    const names = doneBuffer
    doneBuffer = []
    doneTimer = null
    ElMessage.success(
      names.length === 1 ? `「${names[0]}」上传完成` : `${names.length} 个文件上传完成`,
    )
  }, 800)
}

// ---------- 工具 ----------

function throwIfInterrupted(rt: Runtime) {
  if (rt.interrupt) throw new InterruptedError()
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
