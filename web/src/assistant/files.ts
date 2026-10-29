import { cleanReadPath, createCodingTools, errorResult, NON_VISION_IMAGE_NOTE, requireString, type Tool, type ToolResult } from '@yophon/tau-kernel'

import { isDocument, isVisual, type GopanFs } from './gopan-fs'
import type { ToolMeta } from './policy'

/**
 * 模型手里的读写工具(doc/35 三):tau 自带的 read / write / edit,底下是 GopanFs。
 * tau 的 grep / find / ls 不给(网盘上要整棵树走一遍;搜索、列目录用 gopan 自己的工具)。
 */

export const FILE_TOOLS = ['read', 'write', 'edit'] as const

/** 权限与确认卡用:和底层的 MCP 工具同一组 scope */
export const FILE_TOOL_METAS: ToolMeta[] = [
  { name: 'read', tool: 'read', scopes: ['files:download'], readOnly: true, destructive: false },
  { name: 'write', tool: 'write', scopes: ['files:write'], readOnly: false, destructive: false },
  { name: 'edit', tool: 'edit', scopes: ['files:write'], readOnly: false, destructive: false },
]

export function fileTools(fs: GopanFs, supportsImages?: () => boolean): Tool[] {
  const names: readonly string[] = FILE_TOOLS
  return createCodingTools({ fs }, supportsImages ? { supportsImages } : undefined)
    .filter((t) => names.includes(t.name))
    .map((t) => withNodeId(t.name === 'read' ? wrapRead(t, fs, supportsImages) : t, fs))
}

/** 成功的结果末尾附上文件 id:模型在回答里引用这个文件要写 [名字](gopan:<id>)(doc/35 六) */
function withNodeId(tool: Tool, fs: GopanFs): Tool {
  return {
    ...tool,
    async execute(args, signal, onUpdate, ctx) {
      const res = await tool.execute(args, signal, onUpdate, ctx)
      const id = typeof args.path === 'string' && !res.isError ? fs.idOf(cleanReadPath(args.path)) : undefined
      if (!id) return res
      const note = `[gopan id: ${id}]`
      const i = res.content.findIndex((c) => c.type === 'text')
      if (i < 0) return { ...res, content: [{ type: 'text', text: note }, ...res.content] }
      const content = [...res.content]
      const first = content[i] as { type: 'text'; text: string }
      content[i] = { ...first, text: `${first.text}\n${note}` }
      return { ...res, content }
    },
  }
}

async function imageResult(fs: GopanFs, path: string, what: string, supportsImages?: () => boolean): Promise<ToolResult> {
  const img = await fs.image(path)
  const nonVision = supportsImages?.() === false ? `\n${NON_VISION_IMAGE_NOTE}` : ''
  return {
    content: [
      // 给的是缩过的 JPEG 预览,不是原文件:说清楚,免得模型以为文件格式不对
      { type: 'text', text: `Read image file ${path} (attached as a ${img.mimeType} preview${what ? `; ${what}` : ''})${nonVision}` },
      { type: 'image', data: img.data, mimeType: img.mimeType },
    ],
  }
}

/**
 * read 包一层(doc/35 四):tau 的 read 只按扩展名认 png/jpg/gif/webp/bmp,这里先按网盘里的类型分流——
 * 任何格式的图片、视频(封面)给图;抽不出文字的文档(扫描件)给第一页;其余交回 tau 的 read。
 */
function wrapRead(read: Tool, fs: GopanFs, supportsImages?: () => boolean): Tool {
  return {
    ...read,
    description:
      `${read.description} Paths are Gopan drive paths such as /Notes/meeting.md. ` +
      'Any image format (HEIC, RAW…) is returned as an image too, a video as its cover frame, ' +
      'PDF and Office files as their extracted text (a scanned document without text as its first page).',
    async execute(args, signal, onUpdate, ctx) {
      let path: string
      try {
        path = cleanReadPath(requireString(args, 'path'))
      } catch {
        return read.execute(args, signal, onUpdate, ctx)
      }
      const node = await fs.node(path).catch(() => null)
      if (node) {
        try {
          if (isVisual(node)) return await imageResult(fs, path, node.mime?.startsWith('video/') ? 'video cover frame' : '', supportsImages)
          if (isDocument(node) && (await fs.documentText(path)).trim() === '') {
            return await imageResult(fs, path, 'scanned document without extractable text; this is its first page', supportsImages)
          }
        } catch (err) {
          return errorResult(err instanceof Error ? err.message : String(err))
        }
      }
      // 不存在等错误也交给 tau 的 read 去报,说法和它别的错误一致
      return read.execute(args, signal, onUpdate, ctx)
    },
  }
}
