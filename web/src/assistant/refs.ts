/**
 * 助手回答里引用的文件(doc/35 六):模型把网盘里的文件、文件夹写成 [名字](gopan:<id>),
 * 手里没有 id 时写成 [名字](gopan:/完整路径)。id 转成小写,路径原样(去掉结尾的 /)。
 *
 * - 句子里的引用 → 小标签(图标 + 名字),点了跳文件夹 / 开预览;
 * - 一段话里只有引用(可以是列表)→ 卡片:图片、视频直接显示缩略图。
 *
 * 纯函数,不碰 DOM;显示时按 id 现查,查不到的标成「已不在」。
 */

export interface FileRef {
  name: string
  /** 节点 id(小写),或以 / 开头的网盘路径 */
  id: string
}

export const isPathRef = (id: string): boolean => id.startsWith('/')

function refKey(raw: string): string {
  return raw.startsWith('/') ? raw.replace(/(.)\/+$/, '$1') : raw.toLowerCase()
}

export type ReplyBlock = { kind: 'md'; text: string } | { kind: 'cards'; refs: FileRef[] }

const LINK = /\[([^\]\n]+)\]\(gopan:([0-9a-fA-F-]{36}|\/[^)\n]*)\)/g

export function refsIn(text: string): FileRef[] {
  return [...text.matchAll(LINK)].map((m) => ({ name: m[1]!, id: refKey(m[2]!) }))
}

/** 去掉引用、列表记号与分隔符后什么都不剩:这一段只是在列文件 */
function onlyRefs(paragraph: string): boolean {
  if (!refsIn(paragraph).length) return false
  return paragraph
    .split('\n')
    .every((line) => line.replace(LINK, '').replace(/^\s*(?:[-*•]|\d+[.)])\s*/, '').replace(/[\s,，、;；。]/g, '') === '')
}

/** 按空行切段(代码块里的空行不算),只列文件的段落变成卡片,其余原样当 Markdown */
export function splitReply(text: string): ReplyBlock[] {
  const paragraphs: string[] = []
  let cur: string[] = []
  let fenced = false
  for (const line of text.replace(/\r\n/g, '\n').split('\n')) {
    if (/^\s*```/.test(line)) fenced = !fenced
    if (!fenced && !line.trim()) {
      if (cur.length) paragraphs.push(cur.join('\n'))
      cur = []
    } else {
      cur.push(line)
    }
  }
  if (cur.length) paragraphs.push(cur.join('\n'))

  const out: ReplyBlock[] = []
  for (const p of paragraphs) {
    const last = out[out.length - 1]
    if (onlyRefs(p)) {
      const refs = refsIn(p)
      if (last?.kind === 'cards') last.refs.push(...refs)
      else out.push({ kind: 'cards', refs })
    } else if (last?.kind === 'md') {
      last.text += `\n\n${p}`
    } else {
      out.push({ kind: 'md', text: p })
    }
  }
  return out
}

/**
 * 把 Markdown 里的引用先换成占位符(交给 miniMarkdown 转义、加标签),再换成标签的 HTML。
 * chip 自己负责转义名字。
 */
export function withRefChips(text: string, render: (md: string) => string, chip: (ref: FileRef) => string): string {
  const refs: FileRef[] = []
  const marked = text.replace(LINK, (_, name: string, id: string) => {
    refs.push({ name, id: refKey(id) })
    return `\u0002${refs.length - 1}\u0002`
  })
  return render(marked).replace(/\u0002(\d+)\u0002/g, (_, i: string) => chip(refs[Number(i)]!))
}
