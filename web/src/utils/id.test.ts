import { describe, expect, it } from 'vitest'

import { randomId } from './id'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('randomId', () => {
  it('安全上下文直接用 crypto.randomUUID', () => {
    expect(randomId()).toMatch(UUID_V4)
  })

  it('没有 randomUUID(http 非安全上下文)时自拼 v4,格式一致且不重复', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis.crypto, 'randomUUID')
    Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined, configurable: true })
    try {
      const a = randomId()
      const b = randomId()
      expect(a).toMatch(UUID_V4)
      expect(b).toMatch(UUID_V4)
      expect(a).not.toBe(b)
    } finally {
      if (original) Object.defineProperty(globalThis.crypto, 'randomUUID', original)
    }
  })
})
