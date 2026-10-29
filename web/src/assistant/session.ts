import { InMemorySessionStore, type SessionEntry, type SessionMetadata, type SessionStore } from '@yophon/tau-kernel'

/**
 * 对话记录存在 gopan 上(doc/33 三):tau 的会话是一条条追加的条目,这里在内存里留一份给 tau 用,
 * 同时按序号追加到服务端(appendAiEntries,fromSeq 对不上就是别的设备先写了)。设备上不落盘。
 *
 * 追加排成一条队:前一批没写完,后来的攒着下一批一起写。写失败的条目留在队里,下次再试;
 * 冲突时不再写,交给面板提示「重新打开」。
 */

export interface EntrySink {
  append(fromSeq: number, entries: string[]): Promise<number>
}

export class ConflictError extends Error {
  constructor() {
    super('这个对话在别处有了新内容,请重新打开')
  }
}

export class GopanSessionStore implements SessionStore {
  private readonly inner: InMemorySessionStore
  private readonly sink: EntrySink
  private seq: number
  private queue: string[] = []
  private writing: Promise<void> | null = null
  conflict = false
  lastError: unknown = null

  constructor(metadata: SessionMetadata, randomBytes: (n: number) => Uint8Array, sink: EntrySink, seq: number) {
    this.inner = new InMemorySessionStore(metadata, randomBytes)
    this.sink = sink
    this.seq = seq
  }

  /** 从服务端读回来的条目,只进内存,不再写回去 */
  async load(entries: readonly string[]): Promise<void> {
    for (const raw of entries) {
      try {
        await this.inner.appendEntry(JSON.parse(raw) as SessionEntry)
      } catch {
        // 坏的一条跳过,不让整个对话打不开
      }
    }
  }

  getMetadata() { return this.inner.getMetadata() }
  getLeafId() { return this.inner.getLeafId() }
  // 面板不做分支跳转,用不到;真用到时只在内存里换
  setLeafId(leafId: string | null) { return this.inner.setLeafId(leafId) }
  createEntryId() { return this.inner.createEntryId() }
  async appendEntry(entry: SessionEntry) {
    await this.inner.appendEntry(entry)
    this.enqueue(entry)
  }
  getEntry(id: string) { return this.inner.getEntry(id) }
  getPathToRoot(leafId: string | null) { return this.inner.getPathToRoot(leafId) }
  getEntries() { return this.inner.getEntries() }

  private enqueue(entry: SessionEntry) {
    this.queue.push(JSON.stringify(entry))
    void this.flush()
  }

  /** 把排着的条目写到服务端;一轮回答结束时等它写完 */
  flush(): Promise<void> {
    if (this.writing) return this.writing
    if (this.conflict || this.queue.length === 0) return Promise.resolve()
    const batch = this.queue.slice(0, 500)
    this.writing = (async () => {
      try {
        this.seq = await this.sink.append(this.seq, batch)
        this.queue.splice(0, batch.length)
        this.lastError = null
      } catch (err) {
        if (err instanceof ConflictError) this.conflict = true
        else this.lastError = err
      } finally {
        this.writing = null
      }
      if (!this.conflict && this.lastError === null && this.queue.length > 0) await this.flush()
    })()
    return this.writing
  }

  get pending(): number {
    return this.queue.length
  }
}
