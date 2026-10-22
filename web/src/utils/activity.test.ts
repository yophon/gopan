import { describe, expect, it } from 'vitest'

import {
  actionVerb,
  activityPlace,
  activitySize,
  actorName,
  actorParam,
  batchTitle,
  batchTotals,
  groupBatches,
  notUndoableReason,
  parseActorParam,
  presetSince,
  undoPreviewText,
  undoReasons,
  undoResultText,
} from './activity'
import type { ActivityLike } from './activity'

function act(over: Partial<ActivityLike> = {}): ActivityLike {
  return {
    id: '1',
    at: '2026-10-21T08:00:00Z',
    actor: { kind: 'WEBDAV', ref: 'p1', label: 'rclone' },
    batchId: 'b1',
    action: 'DELETE',
    nodeKind: 'FILE',
    name: 'a.jpg',
    parentPath: '/照片',
    files: 1,
    bytes: 100,
    undoable: true,
    ...over,
  }
}

describe('动词与来源', () => {
  it('每种操作都有中文动词', () => {
    expect(actionVerb.MKDIR).toBe('新建文件夹')
    expect(actionVerb.PURGE).toBe('彻底删除')
    expect(actionVerb.EXTRACT).toBe('解压')
  })
  it('来源名字为空时用类型名', () => {
    expect(actorName({ kind: 'WEBDAV', ref: 'x', label: 'rclone' })).toBe('rclone')
    expect(actorName({ kind: 'SYSTEM', ref: '', label: '' })).toBe('系统')
    expect(actorName({ kind: 'ANDROID', ref: 'x', label: '  ' })).toBe('安卓 App')
  })
})

describe('位置与大小', () => {
  it('移动显示从哪到哪,改名显示前后名字,其余是所在文件夹', () => {
    expect(activityPlace(act({ action: 'MOVE', fromPath: '/下载', toPath: '/照片' }))).toBe('/下载 → /照片')
    expect(activityPlace(act({ action: 'RENAME', oldName: 'a.jpg', newName: 'b.jpg' }))).toBe('a.jpg → b.jpg')
    expect(activityPlace(act())).toBe('/照片')
    expect(activityPlace(act({ parentPath: '' }))).toBe('/')
  })
  it('多于一个文件才显示个数与字节', () => {
    expect(activitySize({ files: 1, bytes: 100 })).toBe('')
    expect(activitySize({ files: 37, bytes: 2048 })).toBe('37 个文件 · 2.0 KB')
    expect(activitySize({ files: 3, bytes: 0 })).toBe('3 个文件')
  })
  it('撤不了的原因', () => {
    expect(notUndoableReason(act({ action: 'OVERWRITE', undoable: false }))).toContain('撤不回')
    expect(notUndoableReason(act({ action: 'PURGE', undoable: false }))).toBe('彻底删除撤不回')
    expect(notUndoableReason(act({ undoneAt: '2026-10-21T09:00:00Z', undoable: false }))).toBe('已经撤回过了')
  })
})

describe('地址里的来源', () => {
  it('kind:ref,按第一个冒号拆,类型转大写', () => {
    expect(parseActorParam('webdav:3f2a')).toEqual({ actorKind: 'WEBDAV', actorRef: '3f2a' })
    expect(parseActorParam('agent:api_key:9')).toEqual({ actorKind: 'AGENT', actorRef: 'api_key:9' })
    expect(parseActorParam('system:')).toEqual({ actorKind: 'SYSTEM', actorRef: '' })
    expect(parseActorParam('system')).toEqual({ actorKind: 'SYSTEM', actorRef: '' })
  })
  it('认不出的类型、空值不算', () => {
    expect(parseActorParam('hacker:1')).toBeNull()
    expect(parseActorParam('')).toBeNull()
    expect(parseActorParam(['webdav:1'])).toBeNull()
  })
  it('往返一致', () => {
    const a = { kind: 'AGENT' as const, ref: 'oauth:7' }
    expect(parseActorParam(actorParam(a))).toEqual({ actorKind: 'AGENT', actorRef: 'oauth:7' })
  })
})

describe('时间预设', () => {
  const now = new Date(2026, 9, 21, 15, 30)
  it('今天从本地零点算,7 / 30 天往前推,全部不限', () => {
    expect(presetSince('today', now)).toBe(new Date(2026, 9, 21, 0, 0).toISOString())
    expect(presetSince('7d', now)).toBe(new Date(now.getTime() - 7 * 86_400_000).toISOString())
    expect(presetSince('30d', now)).toBe(new Date(now.getTime() - 30 * 86_400_000).toISOString())
    expect(presetSince('all', now)).toBeNull()
  })
})

describe('同一批折叠', () => {
  it('相邻同批的折成一组,不相邻的不合并', () => {
    const items = [
      act({ id: '5', batchId: 'x' }),
      act({ id: '4', batchId: 'x' }),
      act({ id: '3', batchId: 'y' }),
      act({ id: '2', batchId: 'x' }),
    ]
    const groups = groupBatches(items)
    expect(groups.map((g) => g.items.map((i) => i.id))).toEqual([['5', '4'], ['3'], ['2']])
    expect(groups[0].key).toBe('5')
  })
  it('一组的一句话', () => {
    const moved = Array.from({ length: 37 }, (_, i) =>
      act({ id: String(i), action: 'MOVE', fromPath: '/下载', toPath: '/照片', parentPath: '/照片' }),
    )
    expect(batchTitle(moved)).toBe('rclone 移动了 37 个文件到 /照片')
    expect(batchTitle([act(), act({ id: '2' })])).toBe('rclone 在 /照片 删除了 2 个文件')
    expect(batchTitle([act({ action: 'MKDIR', nodeKind: 'FOLDER' }), act({ action: 'MKDIR', nodeKind: 'FOLDER' })])).toBe(
      'rclone 在 /照片 新建了 2 个文件夹',
    )
    expect(batchTitle([act(), act({ nodeKind: 'FOLDER', parentPath: '/' })])).toBe('rclone 删除了 2 项')
    expect(batchTitle([act(), act({ action: 'MOVE' })])).toBe('rclone 改动了 2 项')
    expect(batchTitle([])).toBe('')
  })
  it('合计:删文件夹按子树算,单个文件至少算 1', () => {
    expect(batchTotals([{ files: 0, bytes: 0 }, { files: 10, bytes: 500 }])).toEqual({ files: 11, bytes: 500 })
  })
})

describe('撤回预览与结果', () => {
  it('原因按条数排,多种时带条数', () => {
    expect(undoReasons([{ ok: true }, { ok: false, reason: '原名已被占用' }])).toBe('原名已被占用')
    expect(
      undoReasons([
        { ok: false, reason: '节点已不在' },
        { ok: false, reason: '原名已被占用' },
        { ok: false, reason: '节点已不在' },
      ]),
    ).toBe('节点已不在 2 项;原名已被占用 1 项')
    expect(
      undoReasons(
        ['a', 'b', 'c', 'd'].map((r) => ({ ok: false, reason: r })),
        2,
      ),
    ).toBe('a 1 项;b 1 项 等')
    expect(undoReasons([{ ok: false, reason: '' }])).toBe('原因不明')
  })
  it('预览的一句话', () => {
    expect(undoPreviewText({ done: 12, failed: 0, items: [] })).toBe('会撤回 12 项')
    expect(
      undoPreviewText({ done: 12, failed: 3, items: Array.from({ length: 3 }, () => ({ ok: false, reason: '已彻底删除' })) }),
    ).toBe('会撤回 12 项、3 项撤不了(已彻底删除)')
    expect(undoPreviewText({ done: 0, failed: 1, items: [{ ok: false, reason: '已彻底删除' }] })).toBe(
      '1 项都撤不了(已彻底删除)',
    )
    expect(undoPreviewText({ done: 0, failed: 0, items: [] })).toBe('没有要撤回的')
  })
  it('执行后的一句话', () => {
    expect(undoResultText({ done: 3, failed: 0, items: [] })).toBe('已撤回 3 项')
    expect(undoResultText({ done: 0, failed: 1, items: [{ ok: false, reason: '原名已被占用' }] })).toBe(
      '没撤成:原名已被占用',
    )
    expect(undoResultText({ done: 2, failed: 1, items: [{ ok: false, reason: '节点已不在' }] })).toBe(
      '已撤回 2 项,1 项没撤成(节点已不在)',
    )
  })
})
