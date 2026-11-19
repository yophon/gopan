/**
 * DeskNote 便签(doc/39):Windows 桌面便签把每张便签存成同步文件夹里的一个 json。
 * 这里是网页端看、改它要用的纯函数——认出便签、对它做一步操作、写回去。单测直接引。
 *
 * 文件长这样(桌面端写的,同步副本里没有 window 段):
 *   { "title": "ToDo", "items": [{ "text": "…", "done": false, "remind": "2026-11-19 15:00", "reminded": false }],
 *     "style": { "opacity": 0.9, "fontSize": 15, "textColor": "#C9D1D9", "bgColor": "#34424D", "checkboxes": true, … } }
 * 写回时只动 title 和 items,别的字段(包括不认识的)原样留着。
 */

export interface DeskNoteItem {
  text: string
  done?: boolean
  remind?: string
  reminded?: boolean
  [key: string]: unknown
}

export interface DeskNoteStyle {
  textColor?: string
  bgColor?: string
  checkboxes?: boolean
  [key: string]: unknown
}

export interface DeskNote {
  title: string
  items: DeskNoteItem[]
  style?: DeskNoteStyle
  [key: string]: unknown
}

/** 是不是一张便签:顶层有字符串 title 和 items 数组,每条都有字符串 text */
export function parseDeskNote(text: string): DeskNote | null {
  let v: unknown
  try {
    v = JSON.parse(text)
  } catch {
    return null
  }
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null
  const o = v as Record<string, unknown>
  if (typeof o.title !== 'string' || !Array.isArray(o.items)) return null
  if (!o.items.every((it) => it && typeof it === 'object' && typeof (it as Record<string, unknown>).text === 'string')) return null
  return o as DeskNote
}

/** 文件名像不像便签(内容还要过 parseDeskNote) */
export function isDeskNoteName(name: string | null | undefined): boolean {
  return !!name && /\.json$/i.test(name)
}

/** 写回:两格缩进,和桌面端一样每行 CRLF 结尾无所谓——桌面端两种都认 */
export function serializeDeskNote(note: DeskNote): string {
  return JSON.stringify(note, null, 2) + '\n'
}

/** 一步操作。带上操作时看到的文字,重放到别处改过的新内容上时据此找回同一条 */
export type DeskNoteOp =
  | { kind: 'toggle'; index: number; text: string; done: boolean }
  | { kind: 'edit'; index: number; text: string; newText: string }
  | { kind: 'remove'; index: number; text: string }
  | { kind: 'add'; text: string }
  | { kind: 'title'; title: string }

/** 先看原位置是不是那一条,不是就按文字找第一条;找不到返回 -1 */
function locate(items: DeskNoteItem[], index: number, text: string): number {
  if (index >= 0 && index < items.length && items[index].text === text) return index
  return items.findIndex((it) => it.text === text)
}

/**
 * 在 note 上做一步操作,返回新的便签(不改原对象)。
 * 找不到要改的那一条(别处已经改了或删了)返回 null,由调用方提示。
 */
export function applyDeskNoteOp(note: DeskNote, op: DeskNoteOp): DeskNote | null {
  const items = note.items.map((it) => ({ ...it }))
  switch (op.kind) {
    case 'title': {
      const t = op.title.replace(/\s*\n\s*/g, ' ').trim()
      return { ...note, title: t || 'ToDo', items }
    }
    case 'add': {
      const t = op.text.trim()
      if (!t) return { ...note, items }
      items.push({ text: t, done: false })
      return { ...note, items }
    }
    default: {
      const i = locate(items, op.index, op.text)
      if (i < 0) return null
      if (op.kind === 'toggle') items[i].done = op.done
      else if (op.kind === 'remove') items.splice(i, 1)
      else {
        const t = op.newText.trim()
        if (t) items[i].text = t
        else items.splice(i, 1) // 清空文字 = 删掉这一条,和桌面端一样
      }
      return { ...note, items }
    }
  }
}

/** 提醒到点了还没做:桌面端到点会把 reminded 置 true */
export function isDue(it: DeskNoteItem): boolean {
  return !!it.remind && !!it.reminded && !it.done
}

/** 提醒时间的说法,和桌面端一致:今天 15:00 → 「15:00」,明天 → 「明天 15:00」,其余 → 「11月21日 15:00」(跨年带年份) */
export function formatRemind(remind: string | null | undefined, now: Date = new Date()): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(remind ?? '')
  if (!m) return ''
  const [y, mo, d, hh, mm] = [Number(m[1]), Number(m[2]), Number(m[3]), m[4], m[5]]
  const hm = `${hh}:${mm}`
  const day = new Date(y, mo - 1, d)
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diff = Math.round((day.getTime() - today.getTime()) / 86_400_000)
  if (diff === 0) return hm
  if (diff === 1) return `明天 ${hm}`
  if (y === now.getFullYear()) return `${mo}月${d}日 ${hm}`
  return `${y}-${mo}-${d} ${hm}`
}

/** 把条目文字切成普通文字和链接(http(s):// 或 www. 开头,到空白或中文标点为止,和桌面端一致) */
export function splitLinks(text: string): { text: string; url?: string }[] {
  const out: { text: string; url?: string }[] = []
  const re = /(https?:\/\/|www\.)[^\s<>"'，。；：、！？（）【】《》「」]+/gi
  let last = 0
  for (let m = re.exec(text); m; m = re.exec(text)) {
    let raw = m[0]
    while (raw.length && '.,;:!?)]}'.includes(raw[raw.length - 1])) raw = raw.slice(0, -1)
    if (raw.length < 8) continue
    if (m.index > last) out.push({ text: text.slice(last, m.index) })
    out.push({ text: raw, url: /^www\./i.test(raw) ? `http://${raw}` : raw })
    last = m.index + raw.length
    re.lastIndex = last
  }
  if (last < text.length) out.push({ text: text.slice(last) })
  return out
}

/** 推送来的一条改动是不是碰到了便签所在的文件夹([folderId] 为 null = 根目录);resync = 可能漏了,也算 */
export function changeTouchesFolder(c: { t: string; f?: string[]; all?: boolean }, folderId: string | null | undefined): boolean {
  if (c.t === 'resync') return true
  if (c.t !== 'files') return false
  return !!c.all || !c.f || c.f.includes(folderId ?? '')
}

/** 便签的底色和字色;没设或格式不对时用桌面端的默认值 */
export function deskNoteColors(note: DeskNote): { bg: string; fg: string } {
  const hex = (v: unknown, def: string) => (typeof v === 'string' && /^#[0-9a-f]{6}$/i.test(v) ? v : def)
  return { bg: hex(note.style?.bgColor, '#34424D'), fg: hex(note.style?.textColor, '#A9B1B7') }
}

// ---------- 写回:排队 + 冲突时重放 ----------

export interface DeskNoteRemote {
  content: string
  sha256: string | null
}

export interface DeskNoteSyncDeps {
  /** 读最新内容和它的 sha256 */
  load: () => Promise<DeskNoteRemote>
  /** 带着 baseSha256 写回,返回新的 sha256;别处改过了要抛 isConflict 认得出的错误 */
  save: (content: string, baseSha256: string | null) => Promise<string | null>
  isConflict: (err: unknown) => boolean
  /** 显示的内容、保存状态变了 */
  onChange: () => void
  /** 要提示用户的事 */
  onNotice: (level: 'warning' | 'error', message: string, err?: unknown) => void
}

/**
 * 便签的写回:每一步先改显示的内容,再排队一步步存(后一步基于前一步存完的内容)。
 * 存的时候带上服务器上那份的 sha256;别处(桌面端)改过了就重新读,把这一步在最新内容上重放再存,
 * 不会盖掉别处的改动。那一条在别处已经改没了:放弃这一步,显示换成最新内容。
 */
export function createDeskNoteSync(deps: DeskNoteSyncDeps) {
  let server: DeskNote | null = null
  let base: string | null = null
  let view: DeskNote | null = null
  let pending = 0
  let failed = false
  let chain: Promise<void> = Promise.resolve()

  /** 读最新的;不是便签返回 false */
  async function load(): Promise<boolean> {
    const r = await deps.load()
    const n = parseDeskNote(r.content)
    if (!n) return false
    server = n
    base = r.sha256
    if (!pending) view = n
    deps.onChange()
    return true
  }

  async function reloadQuietly() {
    try {
      await load()
    } catch {
      // 读不到:留着现在的,下一步再试
    }
  }

  async function persist(op: DeskNoteOp) {
    for (let attempt = 0; attempt < 3; attempt++) {
      if (!server) return
      const target = applyDeskNoteOp(server, op)
      if (!target) {
        deps.onNotice('warning', '这一条刚在别处改过了,已换成最新的内容')
        return
      }
      try {
        base = await deps.save(serializeDeskNote(target), base)
        server = target
        failed = false
        return
      } catch (err) {
        if (deps.isConflict(err)) {
          await reloadQuietly() // 别处改过了:拿最新的再重放这一步
          continue
        }
        failed = true
        deps.onNotice('error', '保存失败', err)
        await reloadQuietly()
        return
      }
    }
    failed = true
    deps.onNotice('error', '保存失败:文件一直在被改,稍后再试')
  }

  /** 做一步操作;返回这一步存完(或放弃)的 Promise */
  function run(op: DeskNoteOp): Promise<void> {
    if (!view) return chain
    const next = applyDeskNoteOp(view, op)
    if (!next) return chain
    view = next
    pending++
    deps.onChange()
    chain = chain
      .then(() => persist(op))
      .finally(() => {
        pending--
        if (!pending && server) view = server
        deps.onChange()
      })
    return chain
  }

  return {
    load,
    run,
    get view() {
      return view
    },
    get pending() {
      return pending
    },
    get failed() {
      return failed
    },
  }
}
