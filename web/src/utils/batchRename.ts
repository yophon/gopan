/**
 * 批量改名的规则(doc/27):在客户端算出每一项的新名字,服务端只收「哪个改成什么」。
 * App 端 feature/drive/BatchRename.kt 是同一套规则,改这里记得一起改。
 */

export type RenameRule =
  /** 按模板命名:`*` 是原来的名字(不含扩展名),`#` 是序号(几个 # 就至少几位,不够补 0);扩展名保留 */
  | { kind: 'template'; template: string; start: number }
  /** 名字里(不含扩展名)的 find 全部换成 replace,区分大小写 */
  | { kind: 'replace'; find: string; replace: string }
  /** 文件的扩展名换成 ext(空 = 去掉扩展名);文件夹不动 */
  | { kind: 'extension'; ext: string }

export interface RenameSource {
  id: string
  name: string
  isFolder: boolean
}

export type RenameProblem = 'invalid' | 'duplicate' | 'exists'

export interface RenamePlanItem {
  id: string
  oldName: string
  newName: string
  changed: boolean
  problem: RenameProblem | null
}

/** 拆成主名与扩展名:文件夹、以点开头的名字(.bashrc)、没有点的名字没有扩展名 */
export function splitName(name: string, isFolder: boolean): { base: string; ext: string } {
  const dot = name.lastIndexOf('.')
  if (isFolder || dot <= 0 || dot === name.length - 1) return { base: name, ext: '' }
  return { base: name.slice(0, dot), ext: name.slice(dot + 1) }
}

/** 与服务端 validateName 一致:非空、不超过 255 字节、不含 / 与 NUL、不是 . 或 .. */
export function isValidName(name: string): boolean {
  if (!name || name === '.' || name === '..') return false
  if (/[/\0]/.test(name)) return false
  return new TextEncoder().encode(name).length <= 255
}

function withExt(base: string, ext: string): string {
  return ext ? `${base}.${ext}` : base
}

function applyTemplate(template: string, base: string, n: number): string {
  // 连续的 # 是一个序号位;* 是原名
  return template.replace(/#+/g, (m) => String(n).padStart(m.length, '0')).replace(/\*/g, base)
}

/** 一项按规则得到的新名字(还没检查冲突);[index] 是它在这批里的位置(从 0 起) */
export function renameOne(src: RenameSource, rule: RenameRule, index: number): string {
  const { base, ext } = splitName(src.name, src.isFolder)
  switch (rule.kind) {
    case 'template':
      return withExt(applyTemplate(rule.template, base, rule.start + index), ext).trim()
    case 'replace':
      return rule.find ? withExt(base.split(rule.find).join(rule.replace), ext).trim() : src.name
    case 'extension': {
      if (src.isFolder) return src.name
      const next = rule.ext.trim().replace(/^\.+/, '')
      return withExt(base, next)
    }
  }
}

/**
 * 整批的改名计划:按 [items] 的顺序编号;[siblings] 是同一个文件夹里其它条目的名字(用来提前发现和这批之外的同名)。
 * problem:invalid 名字不合法、duplicate 这批里有两项改成了同一个名字、exists 和这批之外的同名。
 */
export function planRename(items: RenameSource[], rule: RenameRule, siblings: string[] = []): RenamePlanItem[] {
  const plan = items.map((src, i) => {
    const newName = renameOne(src, rule, i)
    return { id: src.id, oldName: src.name, newName, changed: newName !== src.name, problem: null as RenameProblem | null }
  })
  const others = new Set(siblings)
  // 这批里原来的名字在改完后会腾出来,不算占用
  items.forEach((i) => others.delete(i.name))
  const count = new Map<string, number>()
  plan.forEach((p) => count.set(p.newName, (count.get(p.newName) ?? 0) + 1))
  for (const p of plan) {
    if (!isValidName(p.newName)) p.problem = 'invalid'
    else if ((count.get(p.newName) ?? 0) > 1) p.problem = 'duplicate'
    else if (p.changed && others.has(p.newName)) p.problem = 'exists'
  }
  return plan
}

/** 能不能提交:没有问题,而且至少有一项变了 */
export function canApply(plan: RenamePlanItem[]): boolean {
  return plan.some((p) => p.changed) && plan.every((p) => p.problem === null)
}
