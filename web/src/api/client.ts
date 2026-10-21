import { GraphQLClient, ClientError } from 'graphql-request'
import type { Variables } from 'graphql-request'
import type { TypedDocumentNode } from '@graphql-typed-document-node/core'

import { useAuthStore } from '@/stores/auth'
import router from '@/router'

/**
 * 唯一请求出口。
 *
 * - plainRequest:裸请求,只带 Authorization 头,不做任何重试。
 *   auth 流程(login / register / refresh / logout)必须走它,避免刷新逻辑自我递归。
 * - request:业务请求。遇到 UNAUTHENTICATED → 单飞刷新 → 成功后重放一次;
 *   刷新也失败 → 清 auth store,跳 /login?redirect=当前路由。
 *
 * 多标签页:refresh token 是 httpOnly cookie、浏览器内所有标签共用且每次刷新都旋转。
 * 两个标签同时 401、同时拿同一个旧 cookie 去刷新,后到的一方会命中服务端重用检测。
 * 所以刷新前先拿跨标签锁(Web Locks),拿到锁后若 store 里的 token 已经被别的标签
 * (经 BroadcastChannel,见 stores/auth.ts)换成新的,就直接复用,不再发请求。
 */

// graphql-request v7 内部用 new URL() 解析 endpoint,相对路径会抛 Invalid URL,
// 必须给绝对地址(同源部署,origin 即当前站点)
const gqlClient = new GraphQLClient(`${location.origin}/query`, {
  credentials: 'include', // refresh token 在 httpOnly cookie 里
})

// ---------- 错误码提取 ----------

export function getErrorCode(err: unknown): string | undefined {
  if (!(err instanceof ClientError)) return undefined
  for (const e of err.response.errors ?? []) {
    const code = (e.extensions as Record<string, unknown> | undefined)?.code
    if (typeof code === 'string') return code
  }
  return undefined
}

/** 服务端给的原始错误文案(如推送测试失败时对方的回应);拿不到为 undefined */
export function getErrorMessage(err: unknown): string | undefined {
  if (!(err instanceof ClientError)) return undefined
  const msg = err.response.errors?.[0]?.message
  return typeof msg === 'string' && msg ? msg : undefined
}

export function hasErrorCode(err: unknown, code: string): boolean {
  if (!(err instanceof ClientError)) return false
  return (err.response.errors ?? []).some(
    (e) => (e.extensions as Record<string, unknown> | undefined)?.code === code,
  )
}

// ---------- 裸请求 ----------

function authHeaders(): Record<string, string> {
  const token = useAuthStore().accessToken
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export async function plainRequest<TResult, TVariables extends Variables>(
  document: TypedDocumentNode<TResult, TVariables>,
  variables?: TVariables,
): Promise<TResult> {
  return gqlClient.request<TResult>({
    document,
    variables,
    requestHeaders: authHeaders(),
  })
}

// ---------- 访客请求(分享页) ----------

/**
 * 访客通道:带指定的访客 token,不走用户登录态、不做 401 刷新。
 * 访客 token 30 分钟过期且无 refresh,过期由分享页捕获后引导重新验密。
 */
export async function guestRequest<TResult, TVariables extends Variables>(
  document: TypedDocumentNode<TResult, TVariables>,
  accessToken: string,
  variables?: TVariables,
): Promise<TResult> {
  return gqlClient.request<TResult>({
    document,
    variables,
    requestHeaders: { Authorization: `Bearer ${accessToken}` },
  })
}

// ---------- 单飞刷新 ----------

export const AUTH_LOCK_NAME = 'gopan-auth-refresh'

/** 跨标签互斥:支持 Web Locks 就排队,不支持(旧 Safari / 测试环境)就直接跑 */
function withTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (!locks) return fn()
  return locks.request(AUTH_LOCK_NAME, fn)
}

// 模块级 Promise 去重:并发多个 401 只触发一次 refresh,共享同一个结果。
let refreshing: Promise<boolean> | null = null

/**
 * @param staleToken 触发这次刷新的那个 access token(拿到 401 的那个)。拿到跨标签锁后
 *   若 store 里的 token 已不是它,说明别的标签刚刷新过并广播了新 token,直接复用。
 *   不传则无条件刷新(路由守卫的静默续登录)。
 */
export function singleFlightRefresh(staleToken?: string | null): Promise<boolean> {
  if (!refreshing) {
    refreshing = withTabLock(async () => {
      const auth = useAuthStore()
      if (staleToken !== undefined && auth.accessToken && auth.accessToken !== staleToken) {
        return true
      }
      return auth.tryRefresh()
    }).finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

// ---------- 业务请求(带 401 → 刷新 → 重放) ----------

export async function request<TResult, TVariables extends Variables>(
  document: TypedDocumentNode<TResult, TVariables>,
  variables?: TVariables,
): Promise<TResult> {
  const tokenUsed = useAuthStore().accessToken
  try {
    return await plainRequest(document, variables)
  } catch (err) {
    if (!hasErrorCode(err, 'UNAUTHENTICATED')) throw err

    const refreshed = await singleFlightRefresh(tokenUsed)
    if (refreshed) {
      // 带新 token 重放原请求,只重放一次
      return plainRequest(document, variables)
    }

    // 刷新失败:清登录态,跳登录页并记录回跳地址
    const auth = useAuthStore()
    auth.clear()
    const current = router.currentRoute.value
    if (current.path !== '/login') {
      void router.push({ path: '/login', query: { redirect: current.fullPath } })
    }
    throw err
  }
}
