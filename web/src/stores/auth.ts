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

/**
 * 登录态:user 与 accessToken 只存内存,不碰 localStorage。
 * 页面刷新后靠 httpOnly cookie 里的 refresh token 静默续登录(路由守卫触发 tryRefresh)。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const accessToken = ref<string | null>(null)

  function setAuth(payload: { accessToken: string; user: AuthUser }) {
    accessToken.value = payload.accessToken
    user.value = payload.user
  }

  function clear() {
    accessToken.value = null
    user.value = null
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
      return false
    }
  }

  return { user, accessToken, setAuth, login, register, logoutAction, tryRefresh, clear }
})
