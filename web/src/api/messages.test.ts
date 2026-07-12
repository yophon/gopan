import { describe, expect, it } from 'vitest'

import { errorMessages, messageFor } from './messages'

describe('messageFor', () => {
  it('无 code 走 fallback', () => {
    expect(messageFor(undefined, '默认')).toBe('默认')
  })
  it('已知 code 走全局表', () => {
    expect(messageFor('QUOTA_EXCEEDED', '默认')).toBe(errorMessages.QUOTA_EXCEEDED)
  })
  it('未知 code 走 fallback', () => {
    expect(messageFor('SOMETHING_NEW', '默认')).toBe('默认')
  })
  it('overrides 优先于全局表(注册场景 NAME_CONFLICT 换文案)', () => {
    expect(messageFor('NAME_CONFLICT', '默认', { NAME_CONFLICT: '用户名已被占用' })).toBe(
      '用户名已被占用',
    )
    // 未覆盖的 code 不受 overrides 影响
    expect(messageFor('RATE_LIMITED', '默认', { NAME_CONFLICT: 'x' })).toBe(
      errorMessages.RATE_LIMITED,
    )
  })
})
