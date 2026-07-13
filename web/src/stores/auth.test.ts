import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', () => ({ plainRequest: vi.fn() }))

import { plainRequest } from '@/api/client'
import {
  LoginDocument,
  LogoutDocument,
  RefreshDocument,
  RegisterDocument,
} from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'

const plainRequestMock = vi.mocked(plainRequest)

const user = { id: 'u1', username: 'alice', quotaBytes: 100, usedBytes: 0, isAdmin: false }

beforeEach(() => {
  setActivePinia(createPinia())
  plainRequestMock.mockReset()
})

describe('auth store', () => {
  it('初始无登录态;setAuth / clear 成对生效', () => {
    const auth = useAuthStore()
    expect(auth.user).toBeNull()
    expect(auth.accessToken).toBeNull()

    auth.setAuth({ accessToken: 'tok', user })
    expect(auth.accessToken).toBe('tok')
    expect(auth.user).toEqual(user)

    auth.clear()
    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()
  })

  it('login 走裸请求并写入登录态', async () => {
    plainRequestMock.mockResolvedValue({ login: { accessToken: 'tok', user } })
    const auth = useAuthStore()
    await auth.login('alice', 'pw')
    expect(plainRequestMock).toHaveBeenCalledWith(LoginDocument, {
      username: 'alice',
      password: 'pw',
    })
    expect(auth.accessToken).toBe('tok')
    expect(auth.user?.username).toBe('alice')
  })

  it('login 失败不吞错、不写登录态', async () => {
    plainRequestMock.mockRejectedValue(new Error('bad'))
    const auth = useAuthStore()
    await expect(auth.login('alice', 'wrong')).rejects.toThrow('bad')
    expect(auth.accessToken).toBeNull()
  })

  it('register 同 login 写入登录态', async () => {
    plainRequestMock.mockResolvedValue({ register: { accessToken: 'tok2', user } })
    const auth = useAuthStore()
    await auth.register('alice', 'pw')
    expect(plainRequestMock).toHaveBeenCalledWith(RegisterDocument, {
      username: 'alice',
      password: 'pw',
    })
    expect(auth.accessToken).toBe('tok2')
  })

  it('logout:服务端失败也照常清本地态', async () => {
    const auth = useAuthStore()
    auth.setAuth({ accessToken: 'tok', user })
    plainRequestMock.mockRejectedValue(new Error('network down'))
    await auth.logoutAction()
    expect(plainRequestMock).toHaveBeenCalledWith(LogoutDocument)
    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()
  })

  it('tryRefresh:成功返回 true 并写入新 token;失败返回 false 不抛错', async () => {
    const auth = useAuthStore()
    plainRequestMock.mockResolvedValue({ refresh: { accessToken: 'fresh', user } })
    await expect(auth.tryRefresh()).resolves.toBe(true)
    expect(plainRequestMock).toHaveBeenCalledWith(RefreshDocument)
    expect(auth.accessToken).toBe('fresh')

    plainRequestMock.mockRejectedValue(new Error('no cookie'))
    await expect(auth.tryRefresh()).resolves.toBe(false)
    // 失败不清已有登录态(由调用方决定)
    expect(auth.accessToken).toBe('fresh')
  })
})
