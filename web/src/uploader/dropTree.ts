/**
 * 拖进来、选进来的文件夹 → (相对目录段, 文件) 列表。网盘页与分享访客页共用(doc/32 一)。
 */

export const MAX_DROP_FILES = 1000

export type Collected = { dirs: string[]; file: File }

/** readEntries 每批最多返回 100 项,必须循环读空 */
function readAllEntries(dir: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = dir.createReader()
  return new Promise((resolve, reject) => {
    const all: FileSystemEntry[] = []
    const step = () =>
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all)
          return
        }
        all.push(...batch)
        step()
      }, reject)
    step()
  })
}

function entryFile(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

/** 深度优先展开 entry 树 → (相对目录段, 文件) 列表;空目录记进 emptyDirs */
export async function walkEntry(entry: FileSystemEntry, dirs: string[], out: Collected[], emptyDirs: string[][]): Promise<void> {
  if (out.length >= MAX_DROP_FILES) return
  if (entry.isFile) {
    out.push({ dirs, file: await entryFile(entry as FileSystemFileEntry) })
    return
  }
  if (entry.isDirectory) {
    const children = await readAllEntries(entry as FileSystemDirectoryEntry)
    const sub = [...dirs, entry.name]
    if (children.length === 0) {
      emptyDirs.push(sub)
      return
    }
    for (const c of children) await walkEntry(c, sub, out, emptyDirs)
  }
}

/** 拖进来的东西(文件、文件夹混着)全部展开 */
export async function collectDataTransfer(dt: DataTransfer): Promise<{ collected: Collected[]; emptyDirs: string[][] }> {
  const entries: FileSystemEntry[] = []
  const collected: Collected[] = []
  const emptyDirs: string[][] = []
  for (const item of Array.from(dt.items ?? [])) {
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.()
    if (entry) entries.push(entry)
    else {
      const f = item.getAsFile()
      if (f) collected.push({ dirs: [], file: f })
    }
  }
  if (!dt.items?.length) for (const f of Array.from(dt.files)) collected.push({ dirs: [], file: f })
  for (const entry of entries) await walkEntry(entry, [], collected, emptyDirs)
  return { collected, emptyDirs }
}

/** <input webkitdirectory> 选出来的:目录段在 webkitRelativePath 里(含选中的那个文件夹本身) */
export function collectRelativePaths(files: File[]): Collected[] {
  return files.slice(0, MAX_DROP_FILES).map((file) => {
    const segs = (file.webkitRelativePath || file.name).split('/')
    return { dirs: segs.slice(0, -1), file }
  })
}

/** 按目录分组,保持出现顺序 */
export function groupByDir(collected: Collected[]): { dirs: string[]; files: File[] }[] {
  const groups = new Map<string, { dirs: string[]; files: File[] }>()
  for (const c of collected) {
    const key = c.dirs.join('/')
    const g = groups.get(key) ?? { dirs: c.dirs, files: [] }
    g.files.push(c.file)
    groups.set(key, g)
  }
  return [...groups.values()]
}
