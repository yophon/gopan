/** 把片段按高亮区间切成段,模板里逐段渲染(不用 v-html,片段是用户文件里的文字)。 */
export interface Segment {
  text: string
  hit: boolean
}

/** ranges 按字符(码点)计,与服务端一致;越界、重叠的区间忽略 */
export function splitHighlights(snippet: string, ranges: ReadonlyArray<{ start: number; length: number }>): Segment[] {
  const chars = Array.from(snippet)
  const out: Segment[] = []
  let pos = 0
  for (const r of [...ranges].sort((a, b) => a.start - b.start)) {
    if (r.length <= 0 || r.start < pos || r.start + r.length > chars.length) continue
    if (r.start > pos) out.push({ text: chars.slice(pos, r.start).join(''), hit: false })
    out.push({ text: chars.slice(r.start, r.start + r.length).join(''), hit: true })
    pos = r.start + r.length
  }
  if (pos < chars.length) out.push({ text: chars.slice(pos).join(''), hit: false })
  return out
}
