import { describe, expect, it } from 'vitest'

import { HashAbortedError, hashFile } from './hash'

describe('HashAbortedError', () => {
  it('携带可判别的 name,供调用方区分「用户中断」与真实失败', () => {
    const err = new HashAbortedError()
    expect(err).toBeInstanceOf(Error)
    expect(err.name).toBe('HashAbortedError')
  })
})

describe('hashFile', () => {
  it('signal 已中止时立即拒绝,不创建 Worker(node 环境无 Worker,不抛即证明)', async () => {
    const ctrl = new AbortController()
    ctrl.abort()
    const file = new File(['hello'], 'a.txt')
    await expect(hashFile(file, undefined, ctrl.signal)).rejects.toBeInstanceOf(
      HashAbortedError,
    )
  })
})
