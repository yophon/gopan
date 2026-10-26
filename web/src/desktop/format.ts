/** 桌面端传输与右键菜单上传用到的类型和文案(不碰外壳,单测直接测) */
import { formatBytes } from '@/utils/format'

export type DesktopTaskState = 'queued' | 'running' | 'paused' | 'done' | 'failed'
export type DesktopTaskAction = 'pause' | 'resume' | 'retry' | 'cancel' | 'remove' | 'clear' | 'open' | 'reveal'

export interface DesktopTask {
  id: number
  kind: 'download' | 'upload'
  state: DesktopTaskState
  phase: 'hashing' | 'preparing' | 'transferring' | 'verifying' | 'listing' | null
  name: string
  isFolder: boolean
  localPath: string
  nodeId: string | null
  parentId: string | null
  toChat: boolean
  /** 空 = 普通传输;open = 下载完用本地程序打开;edit = 本地改完传回新版本 */
  purpose: '' | 'open' | 'edit'
  size: number
  done: number
  files: number
  filesDone: number
  error: string | null
  retryAt: number | null
  createdAt: number
  updatedAt: number
}

export interface PickRequest {
  requestId: number
  /** 选中了几项(文件夹算一项) */
  count: number
  /** 前几项的名字,拿来写标题 */
  names: string[]
}

/** 用本地程序打开的文件(外壳盯着它,存盘后传回新版本) */
export interface DesktopEdit {
  nodeId: string
  name: string
  parentId: string | null
  localPath: string
  state: 'downloading' | 'watching' | 'uploading' | 'conflict' | 'stopped'
  note: string | null
  uploads: number
  /** 本地有还没传回的改动 */
  unsynced: boolean
  updatedAt: number
}

export function editStatusText(e: DesktopEdit): string {
  switch (e.state) {
    case 'downloading':
      return '正在下载,下完自动打开'
    case 'uploading':
      return '正在传回新版本'
    case 'conflict':
      return e.note ?? '有冲突,没有覆盖云端'
    case 'stopped':
      return e.note ?? '已停止'
    case 'watching':
      if (e.note) return e.note
      if (e.unsynced) return '有改动,存完就传回'
      return e.uploads > 0 ? `存盘后自动传回 · 已传回 ${e.uploads} 次` : '已打开,存盘后自动传回'
  }
}

export function isActiveDesktopTask(t: DesktopTask): boolean {
  return t.state === 'queued' || t.state === 'running'
}

export function desktopPercent(t: DesktopTask): number {
  if (t.state === 'done') return 100
  if (t.size <= 0) return 0
  return Math.min(100, Math.floor((t.done / t.size) * 100))
}

export function desktopStatusText(t: DesktopTask, speed?: number): string {
  switch (t.state) {
    case 'queued':
      if (t.retryAt && t.error) return `等会儿重试:${t.error}`
      return '排队中'
    case 'running':
      if (t.purpose === 'edit' && t.phase !== 'hashing') return '传回新版本'
      switch (t.phase) {
        case 'listing':
          return '正在列出文件'
        case 'hashing':
          return `计算校验值 ${desktopPercent(t)}%`
        case 'preparing':
          return '准备中'
        case 'verifying':
          return '校验中'
        default:
          if (t.purpose === 'open') return speed ? `下载后打开 · ${formatBytes(speed)}/s` : '下载后打开'
          return speed ? `${formatBytes(speed)}/s` : t.kind === 'download' ? '下载中' : '上传中'
      }
    case 'paused':
      return '已暂停'
    case 'done':
      if (t.kind === 'download') return '已下载'
      return t.toChat ? '已发到传输助手' : '已上传'
    case 'failed':
      return t.error ?? (t.kind === 'download' ? '下载失败' : '上传失败')
  }
}

export function pickTitle(r: PickRequest): string {
  const first = r.names[0] ?? ''
  const shown = first.length > 24 ? `${first.slice(0, 24)}…` : first
  return r.count > 1 ? `把「${shown}」等 ${r.count} 项上传到` : `把「${shown}」上传到`
}
