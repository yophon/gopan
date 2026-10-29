/**
 * 助手回答里常见的 Markdown(doc/33):段落、换行、列表、标题、粗体、行内代码、代码块、链接。
 * 先整段转义 HTML 再加标签,模型写的 <script> 之类只会原样显示;链接只认 http(s)。
 */

function escape(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function inline(s: string): string {
  const codes: string[] = []
  let out = escape(s).replace(/`([^`\n]+)`/g, (_, c: string) => {
    codes.push(`<code>${c}</code>`)
    return `\u0000${codes.length - 1}\u0000`
  })
  out = out
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  return out.replace(/\u0000(\d+)\u0000/g, (_, i: string) => codes[Number(i)]!)
}

export function miniMarkdown(src: string): string {
  const lines = src.replace(/\r\n/g, '\n').split('\n')
  const html: string[] = []
  let para: string[] = []
  let list: { tag: 'ul' | 'ol'; items: string[] } | null = null
  const flushPara = () => {
    if (para.length) html.push(`<p>${para.map(inline).join('<br>')}</p>`)
    para = []
  }
  const flushList = () => {
    if (list) html.push(`<${list.tag}>${list.items.map((i) => `<li>${inline(i)}</li>`).join('')}</${list.tag}>`)
    list = null
  }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!
    if (/^\s*```/.test(line)) {
      flushPara()
      flushList()
      const body: string[] = []
      while (++i < lines.length && !/^\s*```/.test(lines[i]!)) body.push(lines[i]!)
      html.push(`<pre><code>${escape(body.join('\n'))}</code></pre>`)
      continue
    }
    const bullet = /^\s*[-*]\s+(.*)$/.exec(line)
    const ordered = /^\s*\d+[.)]\s+(.*)$/.exec(line)
    if (bullet || ordered) {
      flushPara()
      const tag = bullet ? 'ul' : 'ol'
      if (list && list.tag !== tag) flushList()
      list ??= { tag, items: [] }
      list.items.push((bullet ?? ordered)![1]!)
      continue
    }
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(line)
    if (heading) {
      flushPara()
      flushList()
      html.push(`<p><strong>${inline(heading[1]!)}</strong></p>`)
      continue
    }
    if (!line.trim()) {
      flushPara()
      flushList()
      continue
    }
    flushList()
    para.push(line)
  }
  flushPara()
  flushList()
  return html.join('')
}
