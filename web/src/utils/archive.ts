/** 压缩包浏览的展示口径(doc/21 四)。纯函数,单测直接引。 */

export interface ArchiveCrumb {
  name: string
  path: string
}

/** 包内路径 → 面包屑(不含根)。"a/b/c" → a, a/b, a/b/c */
export function archiveCrumbs(path: string): ArchiveCrumb[] {
  const parts = path.split('/').filter(Boolean)
  return parts.map((name, i) => ({ name, path: parts.slice(0, i + 1).join('/') }))
}

/** 单个文件的地址:/arc/{node}/{idx}?token=…(预览地址没法带 Authorization 头,token 走查询串,同 /pack) */
export function archiveEntryUrl(nodeId: string, idx: number, token: string, download = false): string {
  const q = new URLSearchParams({ token })
  if (download) q.set('download', '1')
  return `/arc/${encodeURIComponent(nodeId)}/${idx}?${q.toString()}`
}

const FLAG_TEXT: Record<string, string> = {
  encrypted: '已加密',
  unsafe: '路径不安全',
  link: '链接',
  unsupported: '不支持的压缩方式',
}

export function flagText(flag: string | null | undefined): string {
  return flag ? (FLAG_TEXT[flag] ?? flag) : ''
}

const ARCHIVE_RE = /\.(zip|7z|rar|tar|tgz|tbz2?|txz|tar\.(gz|bz2|xz))$/i
const LATER_PART_RE = /\.part0*([0-9]+)\.rar$/i

/** 与服务端 archive.ByName 同一口径:分卷 rar 只认第一卷 */
export function isArchiveName(name: string): boolean {
  if (!ARCHIVE_RE.test(name)) return false
  const m = LATER_PART_RE.exec(name)
  return !m || m[1] === '1'
}

/** 预览类型:图片 / PDF / 文本(其余只能下载) */
export function entryPreviewKind(name: string): 'image' | 'pdf' | 'text' | null {
  const ext = name.toLowerCase().split('.').pop() ?? ''
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'avif', 'ico'].includes(ext)) return 'image'
  if (ext === 'pdf') return 'pdf'
  return 'text'
}
