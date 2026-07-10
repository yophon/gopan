import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 上传任务状态机:
 *   queued → hashing → initiating → uploading → completing → done
 *                        │(instant)──────────────────────────↗
 *   任一非终态 → paused(用户)/ failed(自动重试 3 次后)/ canceled
 */
export type UploadStatus =
  | 'queued'
  | 'hashing'
  | 'initiating'
  | 'uploading'
  | 'completing'
  | 'done'
  | 'paused'
  | 'failed'
  | 'canceled'

export const TERMINAL_STATUSES: readonly UploadStatus[] = ['done', 'failed', 'canceled']

export interface UploadTask {
  /** 本地任务 id(与服务端无关) */
  id: string
  fileName: string
  size: number
  parentId: string | null
  status: UploadStatus
  /** 是否秒传完成 */
  instant: boolean
  /** hashing 阶段进度(字节) */
  hashedBytes: number
  /** uploading 阶段进度 = 已完成分片字节 + 进行中分片 loaded 累计 */
  uploadedBytes: number
  /** 最近 3 秒滑动窗口速度(字节/秒),仅 uploading 时有意义 */
  speedBps: number
  /** failed 时的人话错误 */
  error: string | null
  sessionId: string | null
  sha256: string | null
}

/** localStorage 里的可恢复上传记录(gopan_uploads) */
export interface ResumableRecord {
  sessionId: string
  fileName: string
  size: number
  lastModified: number
  sha256: string
  parentId: string | null
}

const STORAGE_KEY = 'gopan_uploads'

function loadRecords(): ResumableRecord[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (r): r is ResumableRecord =>
        typeof r === 'object' &&
        r !== null &&
        typeof (r as ResumableRecord).sessionId === 'string' &&
        typeof (r as ResumableRecord).fileName === 'string' &&
        typeof (r as ResumableRecord).size === 'number' &&
        typeof (r as ResumableRecord).lastModified === 'number' &&
        typeof (r as ResumableRecord).sha256 === 'string',
    )
  } catch {
    return []
  }
}

export const useUploadsStore = defineStore('uploads', () => {
  const tasks = ref<UploadTask[]>([])
  const records = ref<ResumableRecord[]>(loadRecords())

  /** 抽屉收起状态 */
  const collapsed = ref(false)

  // ---------- 任务 ----------

  function addTask(task: UploadTask) {
    tasks.value.push(task)
  }

  function getTask(id: string): UploadTask | undefined {
    return tasks.value.find((t) => t.id === id)
  }

  function removeTask(id: string) {
    const i = tasks.value.findIndex((t) => t.id === id)
    if (i >= 0) tasks.value.splice(i, 1)
  }

  function clearFinished() {
    tasks.value = tasks.value.filter((t) => !TERMINAL_STATUSES.includes(t.status))
  }

  /** 进行中的任务数(徽标、beforeunload 依据) */
  const activeCount = computed(
    () =>
      tasks.value.filter(
        (t) => !TERMINAL_STATUSES.includes(t.status) && t.status !== 'paused',
      ).length,
  )
  const hasActive = computed(() => activeCount.value > 0)

  // ---------- 可恢复记录(localStorage) ----------

  function persistRecords() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(records.value))
    } catch {
      // 存储满等异常不影响上传本身
    }
  }

  function saveRecord(record: ResumableRecord) {
    records.value = records.value.filter((r) => r.sessionId !== record.sessionId)
    records.value.push(record)
    persistRecords()
  }

  function removeRecord(sessionId: string) {
    const before = records.value.length
    records.value = records.value.filter((r) => r.sessionId !== sessionId)
    if (records.value.length !== before) persistRecords()
  }

  /** 用户重新选择文件时按 (name, size, lastModified) 匹配可恢复记录 */
  function matchRecord(file: File): ResumableRecord | undefined {
    return records.value.find(
      (r) =>
        r.fileName === file.name &&
        r.size === file.size &&
        r.lastModified === file.lastModified,
    )
  }

  /** 未挂到任何进行中任务的记录,展示为「可恢复的上传」 */
  const orphanRecords = computed(() =>
    records.value.filter(
      (r) =>
        !tasks.value.some(
          (t) => t.sessionId === r.sessionId && !TERMINAL_STATUSES.includes(t.status),
        ),
    ),
  )

  return {
    tasks,
    records,
    collapsed,
    activeCount,
    hasActive,
    orphanRecords,
    addTask,
    getTask,
    removeTask,
    clearFinished,
    saveRecord,
    removeRecord,
    matchRecord,
  }
})
