import type { DocumentTypeDecoration } from '@graphql-typed-document-node/core'

/**
 * GraphQL 的网络层:一次 POST,拿 data 或者抛 ClientError。
 *
 * 以前用 graphql-request,它连带把 graphql 运行时库(解析 / 打印查询)整个带进首屏包;
 * 查询现在由 codegen 直接生成字符串(documentMode: 'string'),这里只需要把字符串发出去,
 * 一百多 KB 的依赖换成几十行。接口与 graphql-request 对齐(GraphQLClient.request / ClientError),
 * client.ts 与测试只换了引用的位置。
 */

export type Variables = Record<string, any>

export interface GraphQLErrorItem {
  message: string
  path?: (string | number)[]
  extensions?: Record<string, unknown>
}

export interface GraphQLResponseInfo {
  status: number
  errors?: GraphQLErrorItem[]
  data?: unknown
}

export interface GraphQLRequestInfo {
  query: string
  variables?: unknown
}

/** 服务端回了 errors(哪怕同时有部分 data)、或者回的不是 GraphQL 响应,都抛它 */
export class ClientError extends Error {
  response: GraphQLResponseInfo
  request: GraphQLRequestInfo

  constructor(response: GraphQLResponseInfo, request: GraphQLRequestInfo) {
    super(response.errors?.[0]?.message ?? `GraphQL 请求失败(HTTP ${response.status})`)
    this.name = 'ClientError'
    this.response = response
    this.request = request
  }
}

export interface RequestOptions<TResult, TVariables> {
  document: DocumentTypeDecoration<TResult, TVariables> | string
  variables?: TVariables
  requestHeaders?: Record<string, string>
}

export class GraphQLClient {
  constructor(
    private readonly endpoint: string,
    private readonly init: { credentials?: RequestCredentials } = {},
  ) {}

  async request<TResult, TVariables = Variables>(opts: RequestOptions<TResult, TVariables>): Promise<TResult> {
    const query = String(opts.document)
    const res = await fetch(this.endpoint, {
      method: 'POST',
      credentials: this.init.credentials,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/graphql-response+json, application/json',
        ...opts.requestHeaders,
      },
      body: JSON.stringify({ query, variables: opts.variables }),
    })
    let body: { data?: TResult; errors?: GraphQLErrorItem[] } | undefined
    try {
      body = (await res.json()) as typeof body
    } catch {
      body = undefined // 反代回了 HTML 之类
    }
    if (!body || (body.errors && body.errors.length > 0) || body.data === undefined) {
      throw new ClientError({ status: res.status, errors: body?.errors, data: body?.data }, { query, variables: opts.variables })
    }
    return body.data
  }
}
