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

/** 假 BroadcastChannel:同名频道的实例互相投递(模拟多个标签页),postMessage 可断言 */
class FakeChannel {
  static instances: FakeChannel[] = []
  onmessage: ((e: { data: unknown }) => void) | null = null
  postMessage = vi.fn((data: unknown) => {
    for (const other of FakeChannel.instances) {
      if (other !== this && other.name === this.name) other.onmessage?.({ data })
    }
  })
  constructor(readonly name: string) {
    FakeChannel.instances.push(this)
  }
}

beforeEach(() => {
  FakeChannel.instances = []
  Object.defineProperty(globalThis, 'BroadcastChannel', {
    value: FakeChannel,
    writable: true,
    configurable: true,
  })
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
    // A revoked/failed refresh must not leave a stale private identity visible.
    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()
  })
})

describe('多标签页同步(BroadcastChannel)', () => {
  it('setAuth 广播新 token,另一个标签的 store 直接采用且不回播', () => {
    const tabA = useAuthStore()
    // 第二个 pinia 模拟另一个标签页里的 store 实例
    const piniaB = createPinia()
    setActivePinia(piniaB)
    const tabB = useAuthStore(piniaB)
    expect(FakeChannel.instances).toHaveLength(2)
    const [chA, chB] = FakeChannel.instances as [FakeChannel, FakeChannel]

    tabA.setAuth({ accessToken: 'tok-a', user })
    expect(chA.postMessage).toHaveBeenCalledWith({ type: 'auth', accessToken: 'tok-a', user })
    expect(tabB.accessToken).toBe('tok-a')
    expect(tabB.user).toEqual(user)
    // 接收方只应用,不再广播(否则两个标签互相 ping-pong)
    expect(chB.postMessage).not.toHaveBeenCalled()
  })

  it('logout 广播 clear,其他标签一起退出;刷新失败只清本标签', async () => {
    const tabA = useAuthStore()
    const piniaB = createPinia()
    const tabB = useAuthStore(piniaB)
    tabA.setAuth({ accessToken: 'tok', user })
    expect(tabB.accessToken).toBe('tok')

    const [chA] = FakeChannel.instances as [FakeChannel]
    chA.postMessage.mockClear()
    plainRequestMock.mockRejectedValue(new Error('network'))
    await tabA.tryRefresh()
    expect(tabA.accessToken).toBeNull()
    expect(chA.postMessage).not.toHaveBeenCalled()
    expect(tabB.accessToken).toBe('tok')

    tabB.setAuth({ accessToken: 'tok2', user })
    expect(tabA.accessToken).toBe('tok2')
    await tabA.logoutAction()
    expect(tabB.accessToken).toBeNull()
    expect(tabB.user).toBeNull()
  })

  it('没有 BroadcastChannel 的环境照常工作', () => {
    Object.defineProperty(globalThis, 'BroadcastChannel', {
      value: undefined,
      writable: true,
      configurable: true,
    })
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.setAuth({ accessToken: 'tok', user })
    expect(auth.accessToken).toBe('tok')
    auth.clear()
    expect(auth.accessToken).toBeNull()
  })

  it('忽略格式不对的频道消息', () => {
    const auth = useAuthStore()
    auth.setAuth({ accessToken: 'tok', user })
    const [ch] = FakeChannel.instances as [FakeChannel]
    ch.onmessage?.({ data: null })
    ch.onmessage?.({ data: { type: 'auth', accessToken: 42 } })
    expect(auth.accessToken).toBe('tok')
  })
})
