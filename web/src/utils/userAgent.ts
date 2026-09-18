/**
 * 把原始 UA 归类成一句人话,给设备列表用。
 *
 * 不引第三方解析库:规则错了顶多是文案不准,不值得为它加一个几百 KB 的依赖
 * (而且那些库也没有中文输出)。拿不准就回退到原始 UA 的截断 —— 宁可难看,
 * 也别显示一个编出来的结论。
 */

type Rule = [RegExp, string]

const CLIENT_RULES: Rule[] = [
  [/micromessenger/i, '微信'],
  [/edg\//i, 'Edge'],
  [/opr\/|opera/i, 'Opera'],
  [/firefox/i, 'Firefox'],
  [/chrome|crios/i, 'Chrome'],
  [/safari/i, 'Safari'],
  [/rclone/i, 'rclone'],
  [/gopan-?webdav/i, 'gopan WebDAV'],
  [/curl/i, 'curl'],
  [/wget/i, 'wget'],
  [/okhttp|dart|python-requests|go-http-client|node-fetch/i, '脚本/客户端'],
]

const OS_RULES: Rule[] = [
  [/iphone|ipad|ipod/i, 'iOS'],
  [/android/i, 'Android'],
  [/windows/i, 'Windows'],
  [/mac os x|macintosh/i, 'macOS'],
  [/linux/i, 'Linux'],
]

const FORM_RULES: Rule[] = [
  [/ipad|tablet/i, '平板'],
  [/mobile|iphone|android/i, '手机'],
]

function matchFirst(rules: Rule[], ua: string): string | undefined {
  for (const [re, label] of rules) {
    if (re.test(ua)) return label
  }
  return undefined
}

function truncate(s: string, max = 40): string {
  return s.length <= max ? s : `${s.slice(0, max)}…`
}

/** 认不出来就返回原始 UA 的截断;空 UA 返回「未知设备」。 */
export function describeUserAgent(ua: string | null | undefined): string {
  const raw = (ua ?? '').trim()
  if (!raw) return '未知设备'

  const parts = [
    matchFirst(CLIENT_RULES, raw),
    matchFirst(OS_RULES, raw),
    matchFirst(FORM_RULES, raw),
  ].filter((p): p is string => !!p)

  return parts.length > 0 ? parts.join(' · ') : truncate(raw)
}
