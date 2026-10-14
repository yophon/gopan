import { ref } from 'vue'
import { defineStore } from 'pinia'

import { plainRequest } from '@/api/client'
import {
  LoginDocument,
  LogoutDocument,
  RefreshDocument,
  RegisterDocument,
} from '@/api/gen/graphql'
import type { LoginMutation } from '@/api/gen/graphql'

type AuthUser = LoginMutation['login']['user']

export const AUTH_CHANNEL_NAME = 'gopan-auth'

/** 标签页之间同步登录态的消息 */
export type AuthBroadcast =
  | { type: 'auth'; accessToken: string; user: AuthUser }
  | { type: 'clear' }

/**
 * 登录态:user 与 accessToken 只存内存,不碰 localStorage。
 * 页面刷新后靠 httpOnly cookie 里的 refresh token 静默续登录(路由守卫触发 tryRefresh)。
 *
 * 多标签页:refresh cookie 每次刷新都旋转,哪个标签刷新成功就把新 access token 广播给
 * 其他标签(BroadcastChannel),它们直接采用,不再各自去刷新、不会撞上服务端的重用检测。
 * 主动登出也广播,让其他标签一起退出。刷新失败不广播——可能只是本标签网络抖动。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const accessToken = ref<string | null>(null)

  const channel: BroadcastChannel | null =
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(AUTH_CHANNEL_NAME)

  function applyLocal(payload: { accessToken: string; user: AuthUser }) {
    accessToken.value = payload.accessToken
    user.value = payload.user
  }

  function clearLocal() {
    accessToken.value = null
    user.value = null
  }

  function broadcast(msg: AuthBroadcast) {
    try {
      channel?.postMessage(msg)
    } catch {
      // 频道已关闭等异常不影响本标签
    }
  }

  if (channel) {
    channel.onmessage = (e: MessageEvent<AuthBroadcast>) => {
      const msg = e.data
      if (!msg || typeof msg !== 'object') return
      if (msg.type === 'auth' && typeof msg.accessToken === 'string') applyLocal(msg)
      else if (msg.type === 'clear') clearLocal()
    }
  }

  function setAuth(payload: { accessToken: string; user: AuthUser }) {
    applyLocal(payload)
    broadcast({ type: 'auth', accessToken: payload.accessToken, user: payload.user })
  }

  /** 清本地登录态;notifyOthers 为 true 时同时让其他标签页退出(主动登出 / 刷新彻底失败) */
  function clear(notifyOthers = true) {
    clearLocal()
    if (notifyOthers) broadcast({ type: 'clear' })
  }

  async function login(username: string, password: string) {
    const data = await plainRequest(LoginDocument, { username, password })
    setAuth(data.login)
  }

  async function register(username: string, password: string) {
    const data = await plainRequest(RegisterDocument, { username, password })
    setAuth(data.register)
  }

  async function logoutAction() {
    try {
      await plainRequest(LogoutDocument)
    } catch {
      // 服务端登出失败也照常清本地态
    }
    clear()
  }

  /** 静默续登录:成功返回 true。失败(无 cookie / cookie 过期)返回 false,不抛错。 */
  async function tryRefresh(): Promise<boolean> {
    try {
      const data = await plainRequest(RefreshDocument)
      setAuth(data.refresh)
      return true
    } catch {
      clear(false)
      return false
    }
  }

  return { user, accessToken, setAuth, login, register, logoutAction, tryRefresh, clear }
})
