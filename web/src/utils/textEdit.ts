/** 在线编辑(doc/25)的小工具:能单测的纯函数 */

/** 编辑框里按 Tab:选区换成两个空格,返回新文本与光标位置 */
export function insertIndent(text: string, start: number, end: number, indent = '  '): { text: string; cursor: number } {
  const s = Math.max(0, Math.min(start, text.length))
  const e = Math.max(s, Math.min(end, text.length))
  return { text: text.slice(0, s) + indent + text.slice(e), cursor: s + indent.length }
}

/** 能在线编辑的文本上限(与服务端 MaxTextEdit 一致) */
export const MAX_TEXT_EDIT = 2 * 1024 * 1024

/** 这个文件能不能在线编辑:预览是 TEXT 且不超过上限 */
export function canEditText(previewKind: string | null | undefined, size: number | null | undefined): boolean {
  return previewKind === 'TEXT' && (size ?? 0) <= MAX_TEXT_EDIT
}

/** 新建文本文件的默认名字;输入框里只选中主名 */
export const NEW_TEXT_NAME = '未命名.md'
