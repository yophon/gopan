import { describe, expect, it, vi } from 'vitest'
import { ClientError } from 'graphql-request'

// errors.ts → client.ts 的传递依赖需要 location 与 router,提前打桩
vi.hoisted(() => {
  Object.defineProperty(globalThis, 'location', {
    value: { origin: 'http://localhost' },
    writable: true,
    configurable: true,
  })
})
vi.mock('graphql-request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('graphql-request')>()
  return {
    ...actual,
    GraphQLClient: vi.fn(function GraphQLClientStub() {
      return { request: vi.fn() }
    }),
  }
})
vi.mock('@/router', () => ({
  default: { currentRoute: { value: { path: '/', fullPath: '/' } }, push: vi.fn() },
}))

import { errorMessages, errorText } from '@/api/errors'

function clientError(code?: string): ClientError {
  return new ClientError(
    {
      status: 200,
      errors: [{ message: 'boom', ...(code ? { extensions: { code } } : {}) }],
    } as unknown as ClientError['response'],
    { query: 'query X { x }' },
  )
}

describe('errorText', () => {
  it('已知业务码 → 全局中文文案', () => {
    expect(errorText(clientError('QUOTA_EXCEEDED'))).toBe(errorMessages.QUOTA_EXCEEDED)
    expect(errorText(clientError('SHARE_EXPIRED'))).toBe(errorMessages.SHARE_EXPIRED)
  })
  it('非 ClientError(网络异常等)→ 默认 fallback', () => {
    expect(errorText(new Error('fetch failed'))).toBe('请求失败,请稍后重试')
    expect(errorText(new TypeError('x'), '自定义兜底')).toBe('自定义兜底')
  })
  it('ClientError 但无业务码 / 未知码 → fallback', () => {
    expect(errorText(clientError(), '兜底')).toBe('兜底')
    expect(errorText(clientError('BRAND_NEW_CODE'), '兜底')).toBe('兜底')
  })
  it('overrides 按场景覆盖全局文案', () => {
    expect(
      errorText(clientError('NAME_CONFLICT'), '兜底', { NAME_CONFLICT: '用户名已被占用' }),
    ).toBe('用户名已被占用')
    // 未覆盖的码仍走全局表
    expect(errorText(clientError('RATE_LIMITED'), '兜底', { NAME_CONFLICT: 'x' })).toBe(
      errorMessages.RATE_LIMITED,
    )
  })
})
