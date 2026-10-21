/**
 * 分片上传引擎:并发 PUT 会话里缺失的分片,预签名过期(403)时换新签名重试。
 * 登录用户的上传管理器(manager.ts)和收集链接的访客上传(collect.ts)共用,
 * 两边只是"怎么调接口、进度记到哪"不同。
 */

export const MAX_PARTS_PER_FILE = 3
export const MAX_PART_ATTEMPTS = 3

/** 两种会话(uploadSession / collectUploadSession)共有的形状 */
export interface PartSession {
  id: string
  partSize: number
  partUrls: ReadonlyArray<{ partNumber: number; url: string }>
  uploadedParts: ReadonlyArray<number>
}

/** 用户暂停/取消导致的中断,与真实失败区分 */
export class InterruptedError extends Error {
  constructor() {
    super('interrupted')
    this.name = 'InterruptedError'
  }
}

export class PartHttpError extends Error {
  constructor(readonly status: number) {
    super(`分片上传失败(HTTP ${status})`)
    this.name = 'PartHttpError'
  }
}

export interface PartRun {
  file: Blob
  size: number
  /** 在飞的 XHR,暂停 / 取消时由调用方 abort */
  xhrs: Set<XMLHttpRequest>
  /** 进行中分片的 loaded(partNumber → bytes),调用方据此算进度 */
  partLoaded: Map<number, number>
  isInterrupted: () => boolean
  /** 一片传完 */
  onPartDone: (bytes: number) => void
  /** 字节级进度变化 */
  onProgress: () => void
  /** 预签名过期:拿新会话(调用方负责单飞) */
  refresh: () => Promise<PartSession>
}

/** 并发上传 session.partUrls 里的分片;某片彻底失败时停掉其余分片并抛出 */
export async function uploadParts(run: PartRun, session: PartSession): Promise<void> {
  const queue = [...session.partUrls]
  if (queue.length === 0) return
  const partSize = session.partSize

  const workers: Promise<void>[] = []
  for (let i = 0; i < MAX_PARTS_PER_FILE; i++) {
    workers.push(
      (async () => {
        for (;;) {
          const part = queue.shift()
          if (!part) return
          if (run.isInterrupted()) throw new InterruptedError()
          await uploadOnePart(run, part.partNumber, part.url, partSize)
          run.partLoaded.delete(part.partNumber)
          run.onPartDone(partByteLength(part.partNumber, partSize, run.size))
          run.onProgress()
        }
      })(),
    )
  }
  try {
    await Promise.all(workers)
  } catch (err) {
    queue.length = 0
    for (const xhr of run.xhrs) xhr.abort()
    throw err
  }
}

async function uploadOnePart(run: PartRun, partNumber: number, url: string, partSize: number) {
  const start = (partNumber - 1) * partSize
  const end = Math.min(start + partSize, run.size)
  const blob = run.file.slice(start, end)

  let currentUrl = url
  for (let attempt = 0; ; attempt++) {
    if (run.isInterrupted()) throw new InterruptedError()
    try {
      await putPart(run.xhrs, currentUrl, blob, (loaded) => {
        run.partLoaded.set(partNumber, Math.min(loaded, blob.size))
        run.onProgress()
      })
      return
    } catch (err) {
      run.partLoaded.delete(partNumber)
      run.onProgress()
      if (err instanceof InterruptedError) throw err
      if (attempt >= MAX_PART_ATTEMPTS - 1) throw err
      if (err instanceof PartHttpError && err.status === 403) {
        // 预签名过期 → 拿新签名,重试该片
        const session = await run.refresh()
        if (session.uploadedParts.includes(partNumber)) return // 服务端已有该片
        const fresh = session.partUrls.find((p) => p.partNumber === partNumber)
        if (fresh) currentUrl = fresh.url
        else await sleep(1000 * 2 ** attempt) // 没拿到新签名:照常退避,别拿过期 URL 连打
      } else {
        await sleep(1000 * 2 ** attempt)
      }
    }
  }
}

/** 分片 PUT。用 XHR:为了 upload.onprogress 和可 abort。不读响应 ETag(服务端自己对账)。 */
function putPart(
  xhrs: Set<XMLHttpRequest>,
  url: string,
  blob: Blob,
  onProgress: (loaded: number) => void,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhrs.add(xhr)
    const settle = () => xhrs.delete(xhr)
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

/** 第 partNumber 片(从 1 起)的字节数 */
export function partByteLength(partNumber: number, partSize: number, total: number): number {
  const start = (partNumber - 1) * partSize
  return Math.max(0, Math.min(partSize, total - start))
}

/** 会话里已传分片的字节合计(断点续传的进度基线) */
export function uploadedBytes(session: PartSession, total: number): number {
  let sum = 0
  for (const n of session.uploadedParts) sum += partByteLength(n, session.partSize, total)
  return sum
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
