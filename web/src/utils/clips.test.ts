import { describe, expect, it, vi } from 'vitest'

import { CLIP_PAGE, CLIP_READY, buildBookmarklet, clipPageHtml, clipTargetUrl, htmlBytes, siteOf } from './clips'

function code(href: string): string {
  expect(href.startsWith('javascript:')).toBe(true)
  return decodeURIComponent(href.slice('javascript:'.length))
}

/** 在假的页面环境里跑一遍书签:开小窗、听消息、回页面内容 */
function run(origin: string) {
  const posted: { msg: unknown; target: string }[] = []
  const popup = { postMessage: (msg: unknown, target: string) => posted.push({ msg, target }) }
  const listeners: ((e: unknown) => void)[] = []
  const open = vi.fn(() => popup)
  const env = {
    window: { open },
    location: { href: 'https://blog.example.com/p/1?a=b&c=中文' },
    document: { documentElement: { outerHTML: '<html><body>hi</body></html>' } },
    addEventListener: (_t: string, f: (e: unknown) => void) => listeners.push(f),
    removeEventListener: (_t: string, f: (e: unknown) => void) => listeners.splice(listeners.indexOf(f), 1),
  }
  // 书签本身不用 eval;这里只是测试里把它当一段函数体执行
  new Function(...Object.keys(env), code(buildBookmarklet(origin)))(...Object.values(env))
  return { open, popup, posted, listeners }
}

describe('buildBookmarklet', () => {
  it('小、不用 eval、带上本站地址与两种消息', () => {
    const href = buildBookmarklet('https://drive.example.com')
    const src = code(href)
    expect(href.length).toBeLessThan(1200)
    expect(src).not.toMatch(/\beval\b|new Function/)
    expect(src).toContain('"https://drive.example.com"')
    expect(src).toContain(CLIP_READY)
    expect(src).toContain(CLIP_PAGE)
    expect(src).toContain("'gopanclip','width=440,height=360'")
  })
  it('源里的引号、反斜杠、百分号、井号都转义,不会截断代码', () => {
    const href = buildBookmarklet('https://a"b\\c%d#e')
    expect(href).not.toContain('#')
    expect(code(href)).toContain('"https://a\\"b\\\\c%d#e"')
  })
  it('开小窗到 /clip?url=,只回应小窗自己、来自本站的「准备好了」,而且只回一次', () => {
    const origin = 'https://drive.example.com'
    const { open, popup, posted, listeners } = run(origin)
    expect(open).toHaveBeenCalledWith(
      `${origin}/clip?url=${encodeURIComponent('https://blog.example.com/p/1?a=b&c=中文')}`,
      'gopanclip',
      'width=440,height=360',
    )
    expect(listeners).toHaveLength(1)
    const f = listeners[0]
    f({ source: {}, origin, data: { type: CLIP_READY } }) // 别的窗口
    f({ source: popup, origin: 'https://evil.example', data: { type: CLIP_READY } }) // 别的源
    f({ source: popup, origin, data: { type: 'other' } })
    expect(posted).toHaveLength(0)
    f({ source: popup, origin, data: { type: CLIP_READY } })
    expect(posted).toEqual([
      {
        msg: { type: CLIP_PAGE, url: 'https://blog.example.com/p/1?a=b&c=中文', html: '<html><body>hi</body></html>' },
        target: origin,
      },
    ])
    expect(listeners).toHaveLength(0)
  })
})

describe('小窗收消息', () => {
  const url = 'https://blog.example.com/p/1'
  it('类型、地址都对才收', () => {
    expect(clipPageHtml({ type: CLIP_PAGE, url, html: '<p>x</p>' }, url)).toBe('<p>x</p>')
    expect(clipPageHtml({ type: CLIP_PAGE, url: 'https://other/', html: 'x' }, url)).toBeNull()
    expect(clipPageHtml({ type: CLIP_READY, url, html: 'x' }, url)).toBeNull()
    expect(clipPageHtml({ type: CLIP_PAGE, url, html: 1 }, url)).toBeNull()
    expect(clipPageHtml('x', url)).toBeNull()
    expect(clipPageHtml(null, url)).toBeNull()
  })
  it('地址只认 http(s)', () => {
    expect(clipTargetUrl(' https://a.com/x ')).toBe('https://a.com/x')
    expect(clipTargetUrl('javascript:alert(1)')).toBeNull()
    expect(clipTargetUrl('not a url')).toBeNull()
    expect(clipTargetUrl(undefined)).toBeNull()
  })
  it('按 UTF-8 字节算大小', () => {
    expect(htmlBytes('ab')).toBe(2)
    expect(htmlBytes('中')).toBe(3)
  })
})

describe('siteOf', () => {
  it('优先站点名,没有就用域名', () => {
    expect(siteOf({ site: '少数派', url: 'https://sspai.com/post/1' })).toBe('少数派')
    expect(siteOf({ site: '', url: 'https://www.example.com/a' })).toBe('example.com')
    expect(siteOf({ site: null, url: 'bad' })).toBe('')
  })
})
