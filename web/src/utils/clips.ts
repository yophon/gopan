/** 剪藏(doc/23 五)的书签栏小工具与小窗之间的约定。纯函数,单测直接引。 */

/** 小窗 → 原页面:我准备好了,把页面内容发过来 */
export const CLIP_READY = 'gopan-clip-ready'
/** 原页面 → 小窗:{ type, url, html } */
export const CLIP_PAGE = 'gopan-clip-page'
/** 服务端收页面内容的上限 */
export const MAX_CLIP_HTML_BYTES = 5 << 20

/**
 * 书签栏小工具。点一下:开一个小窗到 <origin>/clip?url=当前地址,并且只听一次小窗的「准备好了」,
 * 收到后把当前页面的 HTML 只发给 gopan 的源(postMessage 的 targetOrigin)。
 * 不勾「连同页面内容」小窗就不会来要,页面内容不会离开这个页面。
 * 源用 JSON 转义放进代码,整段再做 URL 编码,任何字符都不会把 javascript: 地址截断。
 */
export function buildBookmarklet(origin: string): string {
  const o = JSON.stringify(origin)
  const code =
    `(function(){var o=${o},u=location.href,` +
    `w=window.open(o+'/clip?url='+encodeURIComponent(u),'gopanclip','width=440,height=360');` +
    `if(!w)return;` +
    `function f(e){if(e.source!==w||e.origin!==o||!e.data||e.data.type!=='${CLIP_READY}')return;` +
    `removeEventListener('message',f);` +
    `w.postMessage({type:'${CLIP_PAGE}',url:u,html:document.documentElement.outerHTML},o)}` +
    `addEventListener('message',f)})()`
  return `javascript:${encodeURIComponent(code)}`
}

/** 小窗收到的消息是不是那一页的内容:类型对、地址对、html 是字符串;是的话返回 html */
export function clipPageHtml(data: unknown, url: string): string | null {
  if (!data || typeof data !== 'object') return null
  const d = data as { type?: unknown; url?: unknown; html?: unknown }
  if (d.type !== CLIP_PAGE || d.url !== url || typeof d.html !== 'string') return null
  return d.html
}

export function htmlBytes(html: string): number {
  return new TextEncoder().encode(html).length
}

/** 卡片上的站点:剪藏没认出站点名时用域名(去掉 www.) */
export function siteOf(c: { site?: string | null; url: string }): string {
  if (c.site?.trim()) return c.site.trim()
  try {
    return new URL(c.url).hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}

/** 地址栏里的 ?url=,只认 http(s) */
export function clipTargetUrl(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const s = raw.trim()
  try {
    const u = new URL(s)
    return u.protocol === 'http:' || u.protocol === 'https:' ? s : null
  } catch {
    return null
  }
}
