import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ClientError } from 'graphql-request'

// client.ts 模块顶层用 `${location.origin}/query` 建客户端,node 环境没有 location,
// 必须在模块加载前(vi.hoisted)补上
const { gqlRequestMock, routerPushMock } = vi.hoisted(() => {
  Object.defineProperty(globalThis, 'location', {
    value: { origin: 'http://localhost' },
    writable: true,
    configurable: true,
  })
  return { gqlRequestMock: vi.fn(), routerPushMock: vi.fn() }
})

// 保留真实 ClientError(getErrorCode 用 instanceof 判断),只替换 GraphQLClient 的网络层
vi.mock('graphql-request', async (importOriginal) => {
  const actual = await importOriginal<typeof import('graphql-request')>()
  return {
    ...actual,
    GraphQLClient: vi.fn(function GraphQLClientStub() {
      return { request: gqlRequestMock }
    }),
  }
})

vi.mock('@/router', () => ({
  default: {
    currentRoute: { value: { path: '/drive', fullPath: '/drive?folder=1' } },
    push: routerPushMock,
  },
}))

import {
  getErrorCode,
  hasErrorCode,
  plainRequest,
  request,
  singleFlightRefresh,
} from '@/api/client'
import { LogoutDocument, RefreshDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'

/** 构造带业务错误码的真实 ClientError */
function clientError(...codes: (string | undefined)[]): ClientError {
  return new ClientError(
    {
      status: 200,
      errors: codes.map((code) => ({
        message: 'boom',
        ...(code ? { extensions: { code } } : {}),
      })),
    } as unknown as ClientError['response'],
    { query: 'query X { x }' },
  )
}

const refreshData = {
  refresh: {
    accessToken: 'new-token',
    user: { id: 'u1', username: 'alice', quotaBytes: 100, usedBytes: 0, isAdmin: false },
  },
}

beforeEach(() => {
  setActivePinia(createPinia())
  gqlRequestMock.mockReset()
  routerPushMock.mockReset()
})

describe('getErrorCode / hasErrorCode', () => {
  it('非 ClientError 一律取不到 code', () => {
    expect(getErrorCode(new Error('x'))).toBeUndefined()
    expect(getErrorCode(undefined)).toBeUndefined()
    expect(hasErrorCode(new Error('x'), 'FORBIDDEN')).toBe(false)
  })
  it('从 extensions.code 取第一个字符串 code', () => {
    expect(getErrorCode(clientError('QUOTA_EXCEEDED'))).toBe('QUOTA_EXCEEDED')
    // 第一条 error 没 code 时跳过,取后面的
    expect(getErrorCode(clientError(undefined, 'NOT_FOUND'))).toBe('NOT_FOUND')
    expect(getErrorCode(clientError())).toBeUndefined()
  })
  it('hasErrorCode 精确匹配任意一条 error 的 code', () => {
    const err = clientError('FORBIDDEN', 'NOT_FOUND')
    expect(hasErrorCode(err, 'NOT_FOUND')).toBe(true)
    expect(hasErrorCode(err, 'UNAUTHENTICATED')).toBe(false)
  })
})

describe('plainRequest', () => {
  it('有 token 时带 Bearer 头,无 token 时不带', async () => {
    const auth = useAuthStore()
    gqlRequestMock.mockResolvedValue({ logout: true })

    await plainRequest(LogoutDocument)
    expect(gqlRequestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ requestHeaders: {} }),
    )

    auth.setAuth({ accessToken: 'tok-1', user: refreshData.refresh.user })
    await plainRequest(LogoutDocument)
    expect(gqlRequestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ requestHeaders: { Authorization: 'Bearer tok-1' } }),
    )
  })
})

describe('request(401 → 单飞刷新 → 重放)', () => {
  it('成功响应直接返回,不触发刷新', async () => {
    gqlRequestMock.mockResolvedValue({ logout: true })
    await expect(request(LogoutDocument)).resolves.toEqual({ logout: true })
    expect(gqlRequestMock).toHaveBeenCalledTimes(1)
  })

  it('非 UNAUTHENTICATED 错误原样抛出,不刷新不重放', async () => {
    const err = clientError('QUOTA_EXCEEDED')
    gqlRequestMock.mockRejectedValue(err)
    await expect(request(LogoutDocument)).rejects.toBe(err)
    expect(gqlRequestMock).toHaveBeenCalledTimes(1)
  })

  it('UNAUTHENTICATED → 刷新成功 → 带新 token 重放一次', async () => {
    gqlRequestMock.mockImplementation(({ document }: { document: unknown }) => {
      if (document === RefreshDocument) return Promise.resolve(refreshData)
      if (gqlRequestMock.mock.calls.length === 1) {
        return Promise.reject(clientError('UNAUTHENTICATED'))
      }
      return Promise.resolve({ logout: true })
    })

    await expect(request(LogoutDocument)).resolves.toEqual({ logout: true })
    // 原请求 → refresh → 重放,共 3 次
    expect(gqlRequestMock).toHaveBeenCalledTimes(3)
    expect(useAuthStore().accessToken).toBe('new-token')
    // 重放带上了刷新后的新 token
    expect(gqlRequestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ requestHeaders: { Authorization: 'Bearer new-token' } }),
    )
  })

  it('刷新也失败 → 清登录态、跳登录页并携带回跳地址、原错误抛出', async () => {
    const auth = useAuthStore()
    auth.setAuth({ accessToken: 'old', user: refreshData.refresh.user })
    const original = clientError('UNAUTHENTICATED')
    gqlRequestMock.mockImplementation(({ document }: { document: unknown }) => {
      if (document === RefreshDocument) return Promise.reject(clientError('UNAUTHENTICATED'))
      return Promise.reject(original)
    })

    await expect(request(LogoutDocument)).rejects.toBe(original)
    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()
    expect(routerPushMock).toHaveBeenCalledWith({
      path: '/login',
      query: { redirect: '/drive?folder=1' },
    })
  })
})

describe('singleFlightRefresh', () => {
  it('并发调用只发一次 refresh,共享同一结果', async () => {
    let resolveRefresh!: (v: typeof refreshData) => void
    gqlRequestMock.mockImplementation(
      () => new Promise((resolve) => (resolveRefresh = resolve)),
    )

    const p1 = singleFlightRefresh()
    const p2 = singleFlightRefresh()
    expect(p2).toBe(p1)
    expect(gqlRequestMock).toHaveBeenCalledTimes(1)

    resolveRefresh(refreshData)
    await expect(p1).resolves.toBe(true)
    await expect(p2).resolves.toBe(true)
    expect(useAuthStore().accessToken).toBe('new-token')

    // 结束后再次调用会重新发起(单飞窗口已释放)
    gqlRequestMock.mockResolvedValue(refreshData)
    await singleFlightRefresh()
    expect(gqlRequestMock).toHaveBeenCalledTimes(2)
  })
})
