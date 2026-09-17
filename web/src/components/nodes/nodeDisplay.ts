import {
  DataBoard,
  Document,
  Folder,
  Grid,
  Headset,
  Memo,
  Picture,
  Reading,
  VideoCamera,
} from '@element-plus/icons-vue'

import { formatBytes } from '@/utils/format'
import type { NodeListItem } from './types'

/**
 * 节点在列表里的展示逻辑(桌面表格与手机卡片共用一份,别各写一边)。
 * 纯函数,可以单测。
 */

/** 文件夹体积:异步统计,statsStale 时数字可能滞后,加"约"并弱化 */
export function folderSizeText(node: NodeListItem): string {
  if (node.subtreeBytes == null) return '—'
  const text = formatBytes(node.subtreeBytes)
  return node.statsStale ? `约 ${text}` : text
}

/** 卡片/表格里的"大小"文本:文件夹看子树体积,文件看自身大小 */
export function sizeText(node: NodeListItem): string {
  return node.kind === 'FOLDER' ? folderSizeText(node) : formatBytes(node.size ?? 0)
}

/** 图标:先看文件夹,再按预览类型;OFFICE 再按扩展名细分表格/演示/文档 */
export function fileIcon(node: NodeListItem) {
  if (node.kind === 'FOLDER') return Folder
  switch (node.preview?.kind) {
    case 'IMAGE':
      return Picture
    case 'VIDEO':
      return VideoCamera
    case 'AUDIO':
      return Headset
    case 'PDF':
      return Reading
    case 'TEXT':
      return Memo
    case 'OFFICE': {
      const ext = node.name.split('.').pop()?.toLowerCase() ?? ''
      if (['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return Grid
      if (['ppt', 'pptx', 'odp'].includes(ext)) return DataBoard
      return Document
    }
    default:
      return Document
  }
}
