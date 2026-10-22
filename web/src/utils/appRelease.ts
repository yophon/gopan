/**
 * 发布页 /app 用:站点发布的安卓安装包信息(服务端 httpx/appupdate.go,公开、不用登录)
 * 与访客环境判断(手机 / 电脑、微信 / QQ 内置浏览器)。
 */

export const ANDROID_RELEASE_URL = '/api/app/android/latest'
/** 固定链接,总是 302 到当前安装包 */
export const ANDROID_APK_URL = '/api/app/android/latest.apk'

export type AndroidRelease = {
  versionCode: number
  versionName: string
  apk: string
  sha256: string
  size: number
  notes: string
  publishedAt: string
  url: string
}

/** 404 = 本站没有发安卓包(没配目录或还没放 latest.json),返回 null;其它失败抛错 */
export async function fetchAndroidRelease(fetchFn: typeof fetch = fetch): Promise<AndroidRelease | null> {
  const r = await fetchFn(ANDROID_RELEASE_URL, { cache: 'no-cache' })
  if (r.status === 404) return null
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as AndroidRelease
}

export type Visitor = {
  android: boolean
  ios: boolean
  /** 微信 / QQ 内置浏览器:不让下载安装包,要换到系统浏览器 */
  inAppBrowser: 'wechat' | 'qq' | null
  /** 纯血鸿蒙(HarmonyOS NEXT,UA 里是 OpenHarmony):装不了安卓安装包 */
  harmonyNext: boolean
  /** 不是手机也不是平板:给扫码 */
  desktop: boolean
}

export function detectVisitor(ua: string, maxTouchPoints = 0): Visitor {
  const android = /Android/i.test(ua)
  // iPadOS 13 起默认报桌面 Safari 的 UA,只能靠触点数认出来
  const ios = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && maxTouchPoints > 1)
  let inAppBrowser: Visitor['inAppBrowser'] = null
  if (/MicroMessenger/i.test(ua)) inAppBrowser = 'wechat'
  else if (/\bQQ\/\d/i.test(ua)) inAppBrowser = 'qq'
  const harmonyNext = !android && /OpenHarmony/i.test(ua)
  const mobile = android || ios || harmonyNext || /Mobile/i.test(ua)
  return { android, ios, inAppBrowser, harmonyNext, desktop: !mobile }
}

/** RFC3339 → 本地日期 YYYY-MM-DD;空或无效返回空串 */
export function releaseDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
