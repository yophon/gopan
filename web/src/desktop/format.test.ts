import { describe, expect, it } from 'vitest'

import { desktopPercent, desktopStatusText, isActiveDesktopTask, pickTitle, type DesktopTask } from './format'

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

  it('titles the folder picker with what is being uploaded', () => {
    expect(pickTitle({ requestId: 1, count: 1, names: ['报告.pdf'] })).toBe('把「报告.pdf」上传到')
    expect(pickTitle({ requestId: 2, count: 3, names: ['a', 'b', 'c'] })).toBe('把「a」等 3 项上传到')
    const long = '很长的名字'.repeat(10)
    expect(pickTitle({ requestId: 3, count: 1, names: [long] })).toBe(`把「${long.slice(0, 24)}…」上传到`)
  })
})
