import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { base64ToBytes, isNativeApp } from './shareReceiver'

describe('native/shareReceiver', () => {
  it('浏览器环境不是原生 App', () => {
    setActivePinia(createPinia())
    expect(isNativeApp()).toBe(false)
  })

  it('base64 → bytes 与原生 NO_WRAP 编码对齐', () => {
    expect(Array.from(base64ToBytes('aGVsbG8='))).toEqual([104, 101, 108, 108, 111])
    expect(base64ToBytes('').length).toBe(0)
    // 二进制(含 0 与高位字节)往返
    const raw = new Uint8Array([0, 1, 127, 128, 255, 10, 13])
    const b64 = btoa(String.fromCharCode(...raw))
    expect(Array.from(base64ToBytes(b64))).toEqual(Array.from(raw))
  })
})
