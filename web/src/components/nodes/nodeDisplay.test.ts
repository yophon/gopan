import { describe, expect, it } from 'vitest'
import { Document, Folder, Grid, Headset, Memo, Picture, Reading, VideoCamera, DataBoard } from '@element-plus/icons-vue'

import { fileIcon, folderSizeText, sizeText } from './nodeDisplay'
import type { NodeListItem } from './types'

function node(partial: Partial<NodeListItem>): NodeListItem {
  return { id: 'n1', name: 'a', kind: 'FILE', updatedAt: '2026-01-01T00:00:00Z', ...partial }
}

describe('fileIcon', () => {
  it('文件夹优先,不看 preview', () => {
    expect(fileIcon(node({ kind: 'FOLDER', preview: { kind: 'IMAGE' } }))).toBe(Folder)
  })

  it('按预览类型给图标', () => {
    expect(fileIcon(node({ preview: { kind: 'IMAGE' } }))).toBe(Picture)
    expect(fileIcon(node({ preview: { kind: 'VIDEO' } }))).toBe(VideoCamera)
    expect(fileIcon(node({ preview: { kind: 'AUDIO' } }))).toBe(Headset)
    expect(fileIcon(node({ preview: { kind: 'PDF' } }))).toBe(Reading)
    expect(fileIcon(node({ preview: { kind: 'TEXT' } }))).toBe(Memo)
  })

  it('OFFICE 再按扩展名细分', () => {
    expect(fileIcon(node({ name: 'a.xlsx', preview: { kind: 'OFFICE' } }))).toBe(Grid)
    expect(fileIcon(node({ name: 'a.PPT', preview: { kind: 'OFFICE' } }))).toBe(DataBoard)
    expect(fileIcon(node({ name: 'a.csv', preview: { kind: 'OFFICE' } }))).toBe(Grid)
    expect(fileIcon(node({ name: 'a.docx', preview: { kind: 'OFFICE' } }))).toBe(Document)
  })

  it('未知类型回落文档图标', () => {
    expect(fileIcon(node({ preview: { kind: 'OTHER' } }))).toBe(Document)
    expect(fileIcon(node({ preview: null }))).toBe(Document)
  })
})

describe('folderSizeText', () => {
  it('未统计出来是 —', () => {
    expect(folderSizeText(node({ kind: 'FOLDER' }))).toBe('—')
  })

  it('统计滞后时加"约"', () => {
    expect(folderSizeText(node({ kind: 'FOLDER', subtreeBytes: 2048, statsStale: true }))).toBe(
      '约 2.0 KB',
    )
    expect(folderSizeText(node({ kind: 'FOLDER', subtreeBytes: 2048, statsStale: false }))).toBe(
      '2.0 KB',
    )
  })
})

describe('sizeText', () => {
  it('文件看自身大小,size 为空按 0', () => {
    expect(sizeText(node({ kind: 'FILE', size: 1024 }))).toBe('1.0 KB')
    expect(sizeText(node({ kind: 'FILE', size: null }))).toBe('0 B')
  })

  it('文件夹走子树体积', () => {
    expect(sizeText(node({ kind: 'FOLDER', subtreeBytes: 1024 }))).toBe('1.0 KB')
  })
})
