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
 */

const gqlClient = new GraphQLClient('/query', {
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

// ---------- 单飞刷新 ----------

// 模块级 Promise 去重:并发多个 401 只触发一次 refresh,共享同一个结果。
let refreshing: Promise<boolean> | null = null

export function singleFlightRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = useAuthStore()
      .tryRefresh()
      .finally(() => {
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
  try {
    return await plainRequest(document, variables)
  } catch (err) {
    if (!hasErrorCode(err, 'UNAUTHENTICATED')) throw err

    const refreshed = await singleFlightRefresh()
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
