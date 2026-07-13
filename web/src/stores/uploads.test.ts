import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import type { ResumableRecord, UploadTask, UploadStatus } from '@/stores/uploads'
import { TERMINAL_STATUSES, useUploadsStore } from '@/stores/uploads'

// node 环境没有 localStorage,store 初始化(loadRecords)与 persistRecords 都要用到
function stubLocalStorage(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial))
  const storage = {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
  }
  vi.stubGlobal('localStorage', storage)
  return map
}

function makeTask(overrides: Partial<UploadTask> = {}): UploadTask {
  return {
    id: overrides.id ?? crypto.randomUUID(),
    fileName: 'a.txt',
    size: 100,
    parentId: null,
    status: 'queued',
    instant: false,
    hashedBytes: 0,
    uploadedBytes: 0,
    speedBps: 0,
    error: null,
    sessionId: null,
    sha256: null,
    ...overrides,
  }
}

function makeRecord(overrides: Partial<ResumableRecord> = {}): ResumableRecord {
  return {
    sessionId: 's1',
    fileName: 'a.txt',
    size: 100,
    lastModified: 1700000000000,
    sha256: 'deadbeef',
    parentId: null,
    ...overrides,
  }
}

beforeEach(() => {
  vi.unstubAllGlobals()
  stubLocalStorage()
  setActivePinia(createPinia())
})

describe('loadRecords(localStorage 反序列化)', () => {
  it('无存档 / 存档损坏 / 非数组 → 空表且不炸', () => {
    expect(useUploadsStore().records).toEqual([])

    stubLocalStorage({ gopan_uploads: '{not json' })
    setActivePinia(createPinia())
    expect(useUploadsStore().records).toEqual([])

    stubLocalStorage({ gopan_uploads: '{"a":1}' })
    setActivePinia(createPinia())
    expect(useUploadsStore().records).toEqual([])
  })

  it('逐条校验字段类型,过滤脏数据只留合法记录', () => {
    const good = makeRecord()
    const bad = [
      null,
      42,
      { sessionId: 1, fileName: 'x', size: 1, lastModified: 1, sha256: 'a' },
      { sessionId: 's2', fileName: 'x', size: '1', lastModified: 1, sha256: 'a' },
      { sessionId: 's3' },
    ]
    stubLocalStorage({ gopan_uploads: JSON.stringify([good, ...bad]) })
    setActivePinia(createPinia())
    expect(useUploadsStore().records).toEqual([good])
  })
})

describe('任务列表', () => {
  it('addTask / getTask / removeTask', () => {
    const store = useUploadsStore()
    const t = makeTask({ id: 't1' })
    store.addTask(t)
    expect(store.getTask('t1')?.fileName).toBe('a.txt')
    expect(store.getTask('nope')).toBeUndefined()
    store.removeTask('t1')
    expect(store.tasks).toEqual([])
    // 删不存在的 id 不炸
    store.removeTask('t1')
  })

  it('clearFinished 只清终态(done/failed/canceled),保留进行中与暂停', () => {
    const store = useUploadsStore()
    const statuses: UploadStatus[] = [
      'queued',
      'hashing',
      'uploading',
      'paused',
      'done',
      'failed',
      'canceled',
    ]
    for (const status of statuses) store.addTask(makeTask({ id: status, status }))
    store.clearFinished()
    expect(store.tasks.map((t) => t.id)).toEqual(['queued', 'hashing', 'uploading', 'paused'])
  })

  it('activeCount 不含终态与 paused;hasActive 联动', () => {
    const store = useUploadsStore()
    expect(store.hasActive).toBe(false)
    store.addTask(makeTask({ id: 'a', status: 'uploading' }))
    store.addTask(makeTask({ id: 'b', status: 'queued' }))
    store.addTask(makeTask({ id: 'c', status: 'paused' }))
    store.addTask(makeTask({ id: 'd', status: 'done' }))
    store.addTask(makeTask({ id: 'e', status: 'failed' }))
    expect(store.activeCount).toBe(2)
    expect(store.hasActive).toBe(true)
  })

  it('TERMINAL_STATUSES 与状态机终态一致', () => {
    expect(TERMINAL_STATUSES).toEqual(['done', 'failed', 'canceled'])
  })
})

describe('可恢复记录', () => {
  it('saveRecord 持久化到 localStorage,同 sessionId 覆盖不重复', () => {
    const map = stubLocalStorage()
    setActivePinia(createPinia())
    const store = useUploadsStore()

    store.saveRecord(makeRecord({ sessionId: 's1', sha256: 'v1' }))
    store.saveRecord(makeRecord({ sessionId: 's1', sha256: 'v2' }))
    expect(store.records).toHaveLength(1)
    expect(store.records[0]!.sha256).toBe('v2')
    expect(JSON.parse(map.get('gopan_uploads')!)).toHaveLength(1)
  })

  it('removeRecord 删除并持久化;不存在的 sessionId 不写存储', () => {
    const map = stubLocalStorage()
    setActivePinia(createPinia())
    const store = useUploadsStore()
    store.saveRecord(makeRecord({ sessionId: 's1' }))

    map.delete('gopan_uploads') // 用于探测是否发生了写入
    store.removeRecord('nope')
    expect(map.has('gopan_uploads')).toBe(false)

    store.removeRecord('s1')
    expect(store.records).toEqual([])
    expect(JSON.parse(map.get('gopan_uploads')!)).toEqual([])
  })

  it('localStorage 写入失败(配额满)不影响内存记录', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
    })
    setActivePinia(createPinia())
    const store = useUploadsStore()
    store.saveRecord(makeRecord())
    expect(store.records).toHaveLength(1)
  })

  it('matchRecord 按 (name, size, lastModified) 三元组精确匹配', () => {
    const store = useUploadsStore()
    const rec = makeRecord({ fileName: 'a.txt', size: 100, lastModified: 111 })
    store.saveRecord(rec)

    const asFile = (name: string, size: number, lastModified: number) =>
      ({ name, size, lastModified }) as File

    expect(store.matchRecord(asFile('a.txt', 100, 111))).toEqual(rec)
    expect(store.matchRecord(asFile('a.txt', 100, 222))).toBeUndefined()
    expect(store.matchRecord(asFile('a.txt', 99, 111))).toBeUndefined()
    expect(store.matchRecord(asFile('b.txt', 100, 111))).toBeUndefined()
  })

  it('orphanRecords:未挂到进行中任务的记录才算孤儿', () => {
    const store = useUploadsStore()
    store.saveRecord(makeRecord({ sessionId: 's1' }))
    store.saveRecord(makeRecord({ sessionId: 's2' }))
    store.saveRecord(makeRecord({ sessionId: 's3' }))

    // s1 有进行中任务 → 非孤儿;s2 只有终态任务 → 孤儿;s3 无任务 → 孤儿
    store.addTask(makeTask({ id: 't1', status: 'uploading', sessionId: 's1' }))
    store.addTask(makeTask({ id: 't2', status: 'failed', sessionId: 's2' }))

    expect(store.orphanRecords.map((r) => r.sessionId).sort()).toEqual(['s2', 's3'])
  })
})
