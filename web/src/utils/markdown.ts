/** Markdown 渲染(doc/23 五.2):服务端渲染成一页沙箱 HTML,前端只拼地址、放进 iframe。 */

export type MdTheme = 'light' | 'dark'

/**
 * GET /md/{nodeId}?token=<access>&theme=light|dark。token 是登录用户的或分享访客的;
 * 文中的相对路径(图片、别的 .md)服务端已经改写好,也带着同一个 token。
 */
export function mdUrl(nodeId: string, token: string, theme: MdTheme): string {
  return `/md/${encodeURIComponent(nodeId)}?token=${encodeURIComponent(token)}&theme=${theme}`
}

/** 跟着站点当前的主题走:Element Plus 的暗色是 <html class="dark"> */
export function appTheme(root: { classList: { contains(c: string): boolean } } | null | undefined): MdTheme {
  return root?.classList.contains('dark') ? 'dark' : 'light'
}

/**
 * iframe 的 sandbox:只许外链在新窗口打开(并且新窗口不受沙箱限制)。
 * 不给 allow-scripts / allow-same-origin:页面本身也带 CSP sandbox,两边都不让跑脚本。
 */
export const MD_SANDBOX = 'allow-popups allow-popups-to-escape-sandbox'
