/**
 * 仓库根目录 CHANGELOG.md 的解析(发布页 /app 用)。格式约定写在 CHANGELOG.md 开头:
 *   ## 版本 · 日期 · 标题(标题可省)
 *   一段可选的说明
 *   - 条目,能用 **加粗** 与 `代码`,缩进的下一行接在上一条后面
 * 第一个 ## 之前的内容是给写日志的人看的,不展示。只认这几种写法,不是通用 Markdown。
 */

export type Inline = { kind: 'text' | 'strong' | 'code'; text: string }

export type ChangelogEntry = {
  version: string
  date: string
  title: string
  intro: Inline[][]
  items: Inline[][]
}

export function parseChangelog(src: string): ChangelogEntry[] {
  const entries: ChangelogEntry[] = []
  let cur: ChangelogEntry | null = null
  let intro: string[] = []
  let items: string[] = []
  // 上一行是条目时,缩进的续行接在它后面;空行打断
  let inItem = false

  const flush = () => {
    if (!cur) return
    cur.intro = intro.map(parseInline)
    cur.items = items.map(parseInline)
    entries.push(cur)
  }

  for (const raw of src.split(/\r?\n/)) {
    const line = raw.trimEnd()
    if (line.startsWith('## ')) {
      flush()
      const [version = '', date = '', ...rest] = line.slice(3).split('·').map((s) => s.trim())
      cur = { version, date, title: rest.join(' · '), intro: [], items: [] }
      intro = []
      items = []
      inItem = false
      continue
    }
    if (!cur) continue
    if (line.trim() === '') {
      inItem = false
      continue
    }
    const item = /^[-*]\s+(.*)$/.exec(line)
    if (item) {
      items.push(item[1].trim())
      inItem = true
    } else if (inItem && /^\s/.test(line)) {
      items[items.length - 1] += line.trim()
    } else if (items.length === 0) {
      intro.push(line.trim())
    } else {
      // 条目之后的散段落当作一条普通条目,不丢内容
      items.push(line.trim())
      inItem = true
    }
  }
  flush()
  return entries
}

/** 行内:**加粗** 与 `代码`,其余原样当文字(不产出 HTML,模板里逐段渲染,不用 v-html) */
export function parseInline(s: string): Inline[] {
  const out: Inline[] = []
  const re = /\*\*(.+?)\*\*|`([^`]+)`/g
  let last = 0
  for (let m = re.exec(s); m; m = re.exec(s)) {
    if (m.index > last) out.push({ kind: 'text', text: s.slice(last, m.index) })
    out.push(m[1] !== undefined ? { kind: 'strong', text: m[1] } : { kind: 'code', text: m[2] })
    last = m.index + m[0].length
  }
  if (last < s.length) out.push({ kind: 'text', text: s.slice(last) })
  return out
}
