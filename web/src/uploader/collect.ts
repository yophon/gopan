import { computed, reactive } from 'vue'

import { getErrorCode } from '@/api/client'
import { errorText } from '@/api/errors'
import { randomId } from '@/utils/id'

import { InterruptedError, uploadParts, uploadedBytes } from './parts'
import type { PartSession } from './parts'

/**
 * 收集链接与分享访客页的上传器(doc/20 二.5、doc/32 一)。与登录用户的 manager.ts 共用分片引擎(parts.ts),
 * 但它是页面级的:一个页面一个实例,不进全局 store,不走登录态的 request。
 *
 * - 不算 sha256(没有秒传),选完文件直接开传。
 * - 同时 2 个文件、每个 3 片并行;任务级 3 次指数退避。
 * - 断点续传:会话 id 按 (名字, 大小, 修改时间) 存 localStorage,键里带链接 token,
 *   不和属主自己的上传记录混。同一浏览器重新打开链接、重新选同一个文件就接着传。
 * - 分享访客能传进不同的文件夹:add 时带 target(文件夹 id),原样交给 transport.init。
 */

export interface CollectTransport {
  init(name: string, size: number, target: string | null): Promise<CollectSession>
  session(id: string): Promise<CollectSession>
  complete(id: string): Promise<{ name: string; size: number }>
  abort(id: string): Promise<void>
}

export interface CollectSession extends PartSession {
  status: string
}

export type CollectTaskStatus = 'queued' | 'uploading' | 'completing' | 'done' | 'failed' | 'canceled'

export interface CollectTask {
  id: string
  fileName: string
  size: number
  status: CollectTaskStatus
  uploadedBytes: number
  error: string | null
  /** 落库后的名字(撞名时服务端会自动改名) */
  finalName: string | null
  sessionId: string | null
  /** 传到哪个文件夹(分享访客);收集链接为空 */
  target: string | null
}

interface ResumeRecord {
  sessionId: string
  fileName: string
  size: number
  lastModified: number
  target?: string | null
}

const MAX_ACTIVE_FILES = 2
const MAX_TASK_ATTEMPTS = 3

/** 重试也没用的错误 */
export const COLLECT_FATAL_CODES = new Set([
  'COLLECT_CLOSED',
  'COLLECT_FULL',
  'FILE_TOO_LARGE',
  'UNAUTHENTICATED',
  'UPLOAD_EXPIRED',
  'SIZE_MISMATCH',
  'BAD_SESSION_STATE',
  'INVALID_INPUT',
  'NOT_FOUND',
  'NAME_CONFLICT',
  'NOT_A_FOLDER',
  'SHARE_EXPIRED',
  'SHARE_NO_UPLOAD',
  'SHARE_FULL',
])

/** 会话已不可用,丢掉断点记录走全新上传 */
const STALE_SESSION_CODES = ['NOT_FOUND', 'UPLOAD_EXPIRED', 'BAD_SESSION_STATE']

interface Runtime {
  file: File
  interrupt: 'cancel' | null
  xhrs: Set<XMLHttpRequest>
  completedBytes: number
  partLoaded: Map<number, number>
  session: CollectSession | null
  attempts: number
  refreshing: Promise<CollectSession> | null
}

export function createCollectUploader(transport: CollectTransport, resumeKey: string) {
  const tasks = reactive<CollectTask[]>([])
  const runtimes = new Map<string, Runtime>()

  // ---------- 断点记录 ----------

  function loadRecords(): ResumeRecord[] {
    try {
      const raw = localStorage.getItem(resumeKey)
      const parsed = raw ? (JSON.parse(raw) as unknown) : []
      return Array.isArray(parsed) ? (parsed as ResumeRecord[]) : []
    } catch {
      return []
    }
  }
  function saveRecords(records: ResumeRecord[]) {
    try {
      if (records.length) localStorage.setItem(resumeKey, JSON.stringify(records))
      else localStorage.removeItem(resumeKey)
    } catch {
      // 隐私模式等:没有断点续传,不影响上传
    }
  }
  function matchRecord(file: File, target: string | null): ResumeRecord | undefined {
    return loadRecords().find(
      (r) =>
        r.fileName === file.name &&
        r.size === file.size &&
        r.lastModified === file.lastModified &&
        (r.target ?? null) === target,
    )
  }
  function addRecord(r: ResumeRecord) {
    saveRecords([...loadRecords().filter((x) => x.sessionId !== r.sessionId), r])
  }
  function removeRecord(sessionId: string | null) {
    if (!sessionId) return
    saveRecords(loadRecords().filter((r) => r.sessionId !== sessionId))
  }

  // ---------- 入队与调度 ----------

  function add(files: File[], target: string | null = null) {
    for (const file of files) {
      const record = matchRecord(file, target)
      const task: CollectTask = {
        id: randomId(),
        fileName: file.name,
        size: file.size,
        status: 'queued',
        uploadedBytes: 0,
        error: null,
        finalName: null,
        sessionId: record?.sessionId ?? null,
        target,
      }
      tasks.push(task)
      runtimes.set(task.id, {
        file,
        interrupt: null,
        xhrs: new Set(),
        completedBytes: 0,
        partLoaded: new Map(),
        session: null,
        attempts: 0,
        refreshing: null,
      })
    }
    schedule()
  }

  function schedule() {
    let slots = MAX_ACTIVE_FILES - tasks.filter((t) => t.status === 'uploading' || t.status === 'completing').length
    for (const task of tasks) {
      if (slots <= 0) break
      if (task.status !== 'queued') continue
      const rt = runtimes.get(task.id)
      if (!rt || rt.interrupt) continue
      slots--
      void runTask(task, rt)
    }
  }

  function find(id: string): CollectTask | undefined {
    return tasks.find((t) => t.id === id)
  }

  // ---------- 主流程 ----------

  async function runTask(task: CollectTask, rt: Runtime) {
    task.status = 'uploading'
    try {
      let session: CollectSession | null = null
      if (task.sessionId) {
        try {
          session = await transport.session(task.sessionId)
        } catch (err) {
          if (!STALE_SESSION_CODES.includes(getErrorCode(err) ?? '')) throw err
          removeRecord(task.sessionId)
          task.sessionId = null
        }
      }
      throwIfCanceled(rt)
      if (!session) {
        session = await transport.init(task.fileName, task.size, task.target)
        task.sessionId = session.id
        addRecord({
          sessionId: session.id,
          fileName: task.fileName,
          size: task.size,
          lastModified: rt.file.lastModified,
          target: task.target,
        })
      }
      rt.session = session
      throwIfCanceled(rt)

      if (session.status === 'uploading') await sendParts(task, rt, session)
      throwIfCanceled(rt)

      // complete:服务端在后台校验落库,处理中返回 UPLOAD_PROCESSING,轮询;缺片则补传
      task.status = 'completing'
      task.uploadedBytes = task.size
      let missingRounds = 0
      for (;;) {
        throwIfCanceled(rt)
        try {
          const res = await transport.complete(task.sessionId!)
          task.finalName = res.name
          break
        } catch (err) {
          const code = getErrorCode(err)
          if (code === 'UPLOAD_PROCESSING') {
            await wait(1000)
            continue
          }
          if (code === 'MISSING_PART' && missingRounds++ < 2) {
            const fresh = await transport.session(task.sessionId!)
            rt.session = fresh
            task.status = 'uploading'
            await sendParts(task, rt, fresh)
            task.status = 'completing'
            continue
          }
          throw err
        }
      }
      removeRecord(task.sessionId)
      task.status = 'done'
      task.error = null
      runtimes.delete(task.id)
    } catch (err) {
      onError(task, rt, err)
    } finally {
      schedule()
    }
  }

  function onError(task: CollectTask, rt: Runtime, err: unknown) {
    if (rt.interrupt === 'cancel' || err instanceof InterruptedError) {
      task.status = 'canceled'
      removeRecord(task.sessionId)
      if (task.sessionId) void transport.abort(task.sessionId).catch(() => {})
      runtimes.delete(task.id)
      return
    }
    const code = getErrorCode(err)
    const fatal = !!code && COLLECT_FATAL_CODES.has(code)
    rt.attempts++
    if (!fatal && rt.attempts < MAX_TASK_ATTEMPTS) {
      task.status = 'queued'
      rt.partLoaded.clear()
      task.uploadedBytes = rt.completedBytes
      const delay = 1000 * 2 ** rt.attempts
      setTimeout(schedule, delay)
      return
    }
    task.status = 'failed'
    task.error = errorText(err, '上传失败,请稍后重试')
    if (fatal) {
      // 确定性的拒绝:会话作废,手动重试会开新的
      removeRecord(task.sessionId)
      if (task.sessionId) void transport.abort(task.sessionId).catch(() => {})
      task.sessionId = null
    }
  }

  /** 传会话里缺的分片;已有分片计入进度基线 */
  function sendParts(task: CollectTask, rt: Runtime, session: CollectSession): Promise<void> {
    rt.completedBytes = uploadedBytes(session, task.size)
    rt.partLoaded.clear()
    updateProgress(task, rt)
    return uploadParts(
      {
        file: rt.file,
        size: task.size,
        xhrs: rt.xhrs,
        partLoaded: rt.partLoaded,
        isInterrupted: () => rt.interrupt !== null,
        onPartDone: (bytes) => {
          rt.completedBytes += bytes
        },
        onProgress: () => updateProgress(task, rt),
        refresh: () => refreshSession(task, rt),
      },
      session,
    )
  }

  function refreshSession(task: CollectTask, rt: Runtime): Promise<CollectSession> {
    if (!rt.refreshing) {
      rt.refreshing = transport
        .session(task.sessionId!)
        .then((s) => {
          rt.session = s
          return s
        })
        .finally(() => {
          rt.refreshing = null
        })
    }
    return rt.refreshing
  }

  function updateProgress(task: CollectTask, rt: Runtime) {
    let inflight = 0
    for (const v of rt.partLoaded.values()) inflight += v
    task.uploadedBytes = Math.min(task.size, rt.completedBytes + inflight)
  }

  // ---------- 用户操作 ----------

  function cancel(id: string) {
    const task = find(id)
    const rt = runtimes.get(id)
    if (!task || task.status === 'done' || task.status === 'canceled') return
    if (!rt) {
      task.status = 'canceled'
      return
    }
    if (task.status === 'queued' || task.status === 'failed') {
      onError(task, rt, new InterruptedError())
      return
    }
    rt.interrupt = 'cancel'
    for (const xhr of rt.xhrs) xhr.abort()
  }

  function retry(id: string) {
    const task = find(id)
    const rt = runtimes.get(id)
    if (!task || !rt || task.status !== 'failed') return
    rt.attempts = 0
    task.error = null
    task.status = 'queued'
    schedule()
  }

  function remove(id: string) {
    const i = tasks.findIndex((t) => t.id === id)
    if (i < 0) return
    const t = tasks[i]!
    if (t.status !== 'done' && t.status !== 'canceled' && t.status !== 'failed') return
    tasks.splice(i, 1)
    runtimes.delete(id)
  }

  const busy = computed(() => tasks.some((t) => t.status === 'queued' || t.status === 'uploading' || t.status === 'completing'))

  return { tasks, add, cancel, retry, remove, busy }
}

export type CollectUploader = ReturnType<typeof createCollectUploader>

function throwIfCanceled(rt: Runtime) {
  if (rt.interrupt) throw new InterruptedError()
}

function wait(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
