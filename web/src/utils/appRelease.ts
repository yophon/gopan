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

export const DESKTOP_RELEASE_URL = '/api/app/desktop/info'
/** 固定链接,总是 302 到当前的 Windows 安装包 */
export const DESKTOP_EXE_URL = '/api/app/desktop/latest.exe'

export type DesktopRelease = {
  version: string
  size: number
  notes: string
  publishedAt: string
  url: string
}

/** Windows 桌面端(doc/29):404 = 本站没发桌面端,返回 null;其它失败抛错 */
export async function fetchDesktopRelease(fetchFn: typeof fetch = fetch): Promise<DesktopRelease | null> {
  const r = await fetchFn(DESKTOP_RELEASE_URL, { cache: 'no-cache' })
  if (r.status === 404) return null
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as DesktopRelease
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
  /** Windows 电脑:给桌面端安装包 */
  windows: boolean
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
  return { android, ios, inAppBrowser, harmonyNext, desktop: !mobile, windows: !mobile && /Windows NT/i.test(ua) }
}

/**
 * 「10月22日」式的日期,不是今年的带上年份。接受 YYYY-MM-DD(更新日志里的写法,按字面取,
 * 不经时区换算)或 RFC3339(安装包发布时间,换成本地日期);空或无效返回空串。
 */
export function dayLabel(s: string | null | undefined, now: Date = new Date()): string {
  if (!s) return ''
  let y: number, m: number, d: number
  const plain = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
  if (plain) {
    ;[y, m, d] = [Number(plain[1]), Number(plain[2]), Number(plain[3])]
  } else {
    const t = new Date(s)
    if (Number.isNaN(t.getTime())) return ''
    ;[y, m, d] = [t.getFullYear(), t.getMonth() + 1, t.getDate()]
  }
  return y === now.getFullYear() ? `${m}月${d}日` : `${y}年${m}月${d}日`
}
