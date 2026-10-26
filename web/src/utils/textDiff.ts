/**
 * 按行比较两份文本(历史版本「和现在比较」):Myers 差分,先去掉相同的头尾再算。
 * 改动太多(超过 [maxEdits] 处)返回 null,界面上提示没法逐行比较,免得卡住页面。
 */

export interface DiffLine {
  kind: 'same' | 'add' | 'del'
  text: string
  /** 旧 / 新文件里的行号(从 1 开始);新增的没有旧行号,删掉的没有新行号 */
  oldNo?: number
  newNo?: number
}

/** 界面上的一行:连续没变的折成一行「… N 行没变」 */
export type DiffRow = DiffLine | { kind: 'skip'; count: number; key: number }

export function splitLines(s: string): string[] {
  const lines = s.replace(/\r\n?/g, '\n').split('\n')
  // 末尾的换行不算一行
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  return lines
}

type Op = { kind: DiffLine['kind']; a?: number; b?: number }

function myers(a: string[], b: string[], maxEdits: number): Op[] | null {
  const n = a.length
  const m = b.length
  const limit = Math.min(n + m, maxEdits)
  const off = limit + 1
  const v = new Int32Array(2 * limit + 3)
  v[off + 1] = 0
  // 每一步开始时 v 的快照(只存用得到的那一段),回溯时用
  const trace: { arr: Int32Array; base: number }[] = []
  for (let d = 0; d <= limit; d++) {
    trace.push({ arr: v.slice(off - d - 1, off + d + 2), base: -d - 1 })
    for (let k = -d; k <= d; k += 2) {
      let x = k === -d || (k !== d && v[off + k - 1]! < v[off + k + 1]!) ? v[off + k + 1]! : v[off + k - 1]! + 1
      let y = x - k
      while (x < n && y < m && a[x] === b[y]) {
        x++
        y++
      }
      v[off + k] = x
      if (x >= n && y >= m) return backtrack(trace, n, m)
    }
  }
  return null
}

function backtrack(trace: { arr: Int32Array; base: number }[], n: number, m: number): Op[] {
  const ops: Op[] = []
  let x = n
  let y = m
  for (let d = trace.length - 1; d >= 0; d--) {
    const { arr, base } = trace[d]!
    const at = (k: number) => arr[k - base]!
    const k = x - y
    const prevK = k === -d || (k !== d && at(k - 1) < at(k + 1)) ? k + 1 : k - 1
    const prevX = at(prevK)
    const prevY = prevX - prevK
    while (x > prevX && y > prevY) {
      ops.push({ kind: 'same', a: x - 1, b: y - 1 })
      x--
      y--
    }
    if (d > 0) {
      if (x === prevX) ops.push({ kind: 'add', b: prevY })
      else ops.push({ kind: 'del', a: prevX })
    }
    x = prevX
    y = prevY
  }
  return ops.reverse()
}

export function diffLines(oldText: string, newText: string, maxEdits = 2000): DiffLine[] | null {
  const a = splitLines(oldText)
  const b = splitLines(newText)
  let head = 0
  while (head < a.length && head < b.length && a[head] === b[head]) head++
  let endA = a.length
  let endB = b.length
  while (endA > head && endB > head && a[endA - 1] === b[endB - 1]) {
    endA--
    endB--
  }
  const core = myers(a.slice(head, endA), b.slice(head, endB), maxEdits)
  if (!core) return null
  const out: DiffLine[] = []
  for (let i = 0; i < head; i++) out.push({ kind: 'same', text: a[i]!, oldNo: i + 1, newNo: i + 1 })
  for (const op of core) {
    if (op.kind === 'same') out.push({ kind: 'same', text: a[head + op.a!]!, oldNo: head + op.a! + 1, newNo: head + op.b! + 1 })
    else if (op.kind === 'del') out.push({ kind: 'del', text: a[head + op.a!]!, oldNo: head + op.a! + 1 })
    else out.push({ kind: 'add', text: b[head + op.b!]!, newNo: head + op.b! + 1 })
  }
  for (let i = 0; i < a.length - endA; i++) {
    out.push({ kind: 'same', text: a[endA + i]!, oldNo: endA + i + 1, newNo: endB + i + 1 })
  }
  return out
}

/** 改动前后各留 [context] 行,其余没变的折起来 */
export function diffRows(lines: DiffLine[], context = 3): DiffRow[] {
  const keep = new Array<boolean>(lines.length).fill(false)
  lines.forEach((l, i) => {
    if (l.kind === 'same') return
    for (let j = Math.max(0, i - context); j <= Math.min(lines.length - 1, i + context); j++) keep[j] = true
  })
  const rows: DiffRow[] = []
  let skipped = 0
  lines.forEach((l, i) => {
    if (keep[i]) {
      if (skipped) rows.push({ kind: 'skip', count: skipped, key: i })
      skipped = 0
      rows.push(l)
    } else {
      skipped++
    }
  })
  if (skipped) rows.push({ kind: 'skip', count: skipped, key: lines.length })
  return rows
}

export function diffStats(lines: DiffLine[]): { added: number; removed: number } {
  let added = 0
  let removed = 0
  for (const l of lines) {
    if (l.kind === 'add') added++
    else if (l.kind === 'del') removed++
  }
  return { added, removed }
}
