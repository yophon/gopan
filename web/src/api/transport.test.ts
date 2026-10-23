import { afterEach, describe, expect, it, vi } from 'vitest'

import { ClientError, GraphQLClient } from './transport'

function respond(status: number, body: unknown, json = true) {
  return vi.fn(async () => ({
    status,
    ok: status >= 200 && status < 300,
    json: async () => {
      if (!json) throw new SyntaxError('Unexpected token <')
      return body
    },
  }))
}

afterEach(() => vi.unstubAllGlobals())

/** 等一个应该失败的请求,把抛出来的错误拿回来 */
async function failure(p: Promise<unknown>): Promise<ClientError> {
  try {
    await p
  } catch (e) {
    return e as ClientError
  }
  throw new Error('应该失败却成功了')
}

describe('GraphQLClient', () => {
  it('把查询字符串和变量 POST 出去,带上请求头和 cookie 设置,拿回 data', async () => {
    const fetchMock = respond(200, { data: { me: { id: 'u1' } } })
    vi.stubGlobal('fetch', fetchMock)
    const client = new GraphQLClient('http://x/query', { credentials: 'include' })
    const doc = new String('query Me { me { id } }') as never
    await expect(client.request({ document: doc, variables: { a: 1 }, requestHeaders: { Authorization: 'Bearer t' } })).resolves.toEqual({
      me: { id: 'u1' },
    })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://x/query')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer t')
    expect(JSON.parse(init.body as string)).toEqual({ query: 'query Me { me { id } }', variables: { a: 1 } })
  })

  it('有 errors(哪怕带着部分 data)就抛 ClientError,错误码在 response.errors 里', async () => {
    vi.stubGlobal('fetch', respond(200, { data: { me: null }, errors: [{ message: '没登录', extensions: { code: 'UNAUTHENTICATED' } }] }))
    const err = await failure(new GraphQLClient('http://x/query').request({ document: 'query { me { id } }' }))
    expect(err).toBeInstanceOf(ClientError)
    expect(err.message).toBe('没登录')
    expect(err.response.errors?.[0]?.extensions?.code).toBe('UNAUTHENTICATED')
    expect(err.response.data).toEqual({ me: null })
    expect(err.request.query).toBe('query { me { id } }')
  })

  it('回的不是 JSON(反代的错误页)也抛 ClientError,带着状态码', async () => {
    vi.stubGlobal('fetch', respond(502, null, false))
    const err = await failure(new GraphQLClient('http://x/query').request({ document: 'query { me { id } }' }))
    expect(err).toBeInstanceOf(ClientError)
    expect(err.response.status).toBe(502)
    expect(err.response.errors).toBeUndefined()
    expect(err.message).toContain('502')
  })

  it('网络断了:fetch 自己的异常原样往外抛', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await expect(new GraphQLClient('http://x/query').request({ document: 'query { me { id } }' })).rejects.toBeInstanceOf(TypeError)
  })
})
