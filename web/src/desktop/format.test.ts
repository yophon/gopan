import { describe, expect, it } from 'vitest'

import { desktopPercent, desktopStatusText, editStatusText, isActiveDesktopTask, pickTitle, type DesktopEdit, type DesktopTask } from './format'

function task(over: Partial<DesktopTask>): DesktopTask {
  return {
    id: 1,
    kind: 'download',
    state: 'running',
    phase: 'transferring',
    name: 'a.bin',
    isFolder: false,
    localPath: 'C:/d/a.bin',
    nodeId: 'n',
    parentId: null,
    toChat: false,
    purpose: '',
    size: 200,
    done: 50,
    files: 1,
    filesDone: 0,
    error: null,
    retryAt: null,
    createdAt: 0,
    updatedAt: 0,
    ...over,
  }
}

describe('desktop transfers', () => {
  it('describes each state', () => {
    expect(desktopPercent(task({}))).toBe(25)
    expect(desktopPercent(task({ state: 'done', done: 0 }))).toBe(100)
    expect(desktopStatusText(task({}), 2048)).toBe('2.0 KB/s')
    expect(desktopStatusText(task({}))).toBe('下载中')
    expect(desktopStatusText(task({ phase: 'listing' }))).toBe('正在列出文件')
    expect(desktopStatusText(task({ kind: 'upload', phase: 'hashing' }))).toBe('计算校验值 25%')
    expect(desktopStatusText(task({ state: 'queued', retryAt: 1, error: '网络错误' }))).toBe('等会儿重试:网络错误')
    expect(desktopStatusText(task({ state: 'queued' }))).toBe('排队中')
    expect(desktopStatusText(task({ state: 'done', kind: 'upload', toChat: true }))).toBe('已发到传输助手')
    expect(desktopStatusText(task({ state: 'failed', error: '云端文件不在了' }))).toBe('云端文件不在了')
    expect(isActiveDesktopTask(task({ state: 'paused' }))).toBe(false)
    expect(isActiveDesktopTask(task({ state: 'queued' }))).toBe(true)
  })

  it('labels open-locally and save-back transfers', () => {
    expect(desktopStatusText(task({ purpose: 'open' }))).toBe('下载后打开')
    expect(desktopStatusText(task({ purpose: 'edit', kind: 'upload' }))).toBe('传回新版本')
  })

  it('describes files opened with a local program', () => {
    const edit = (over: Partial<DesktopEdit>): DesktopEdit => ({
      nodeId: 'n',
      name: '报告.docx',
      parentId: null,
      localPath: 'C:/c/报告.docx',
      state: 'watching',
      note: null,
      uploads: 0,
      unsynced: false,
      updatedAt: 0,
      ...over,
    })
    expect(editStatusText(edit({}))).toBe('已打开,存盘后自动传回')
    expect(editStatusText(edit({ uploads: 2 }))).toBe('存盘后自动传回 · 已传回 2 次')
    expect(editStatusText(edit({ unsynced: true }))).toBe('有改动,存完就传回')
    expect(editStatusText(edit({ state: 'downloading' }))).toBe('正在下载,下完自动打开')
    expect(editStatusText(edit({ state: 'conflict', note: '另存成了「x」' }))).toBe('另存成了「x」')
    expect(editStatusText(edit({ note: '传回失败:网络错误' }))).toBe('传回失败:网络错误')
  })

  it('titles the folder picker with what is being uploaded', () => {
    expect(pickTitle({ requestId: 1, count: 1, names: ['报告.pdf'] })).toBe('把「报告.pdf」上传到')
    expect(pickTitle({ requestId: 2, count: 3, names: ['a', 'b', 'c'] })).toBe('把「a」等 3 项上传到')
    const long = '很长的名字'.repeat(10)
    expect(pickTitle({ requestId: 3, count: 1, names: [long] })).toBe(`把「${long.slice(0, 24)}…」上传到`)
  })
})
