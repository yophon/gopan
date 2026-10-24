import { describe, expect, it, vi } from 'vitest'

import { dayLabel, detectVisitor, fetchAndroidRelease } from './appRelease'

const UA = {
  androidChrome:
    'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36',
  wechat:
    'Mozilla/5.0 (Linux; Android 13; V2049A) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0 Mobile Safari/537.36 XWEB/1160065 MMWEBSDK/20231202 MicroMessenger/8.0.47.2560(0x28002F30) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN',
  qq: 'Mozilla/5.0 (Linux; Android 12; M2012K11AC) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/98.0 MQQBrowser/6.2 TBS/046613 Mobile Safari/537.36 V1_AND_SQ_8.9.80_4614_YYB_D QQ/8.9.80.12440 NetType/WIFI',
  iphone:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
  ipadDesktop:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15',
  windows:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0',
  harmony:
    'Mozilla/5.0 (Phone; OpenHarmony 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 ArkWeb/4.1.6.1 Mobile HuaweiBrowser/5.0.4.300',
}

describe('detectVisitor', () => {
  it('安卓浏览器', () => {
    expect(detectVisitor(UA.androidChrome)).toEqual({ android: true, ios: false, inAppBrowser: null, harmonyNext: false, desktop: false, windows: false })
  })
  it('微信与 QQ 内置浏览器', () => {
    expect(detectVisitor(UA.wechat).inAppBrowser).toBe('wechat')
    expect(detectVisitor(UA.qq).inAppBrowser).toBe('qq')
    // QQ 浏览器(MQQBrowser)本身能下载,不算
    expect(detectVisitor(UA.androidChrome.replace('Chrome', 'MQQBrowser/14.0 Chrome')).inAppBrowser).toBeNull()
  })
  it('iPhone 与报桌面 UA 的 iPad', () => {
    expect(detectVisitor(UA.iphone).ios).toBe(true)
    expect(detectVisitor(UA.ipadDesktop, 5)).toMatchObject({ ios: true, desktop: false })
    expect(detectVisitor(UA.ipadDesktop, 0)).toMatchObject({ ios: false, desktop: true })
  })
  it('电脑给扫码;纯血鸿蒙不算电脑、也装不了安装包', () => {
    expect(detectVisitor(UA.windows)).toMatchObject({ desktop: true, harmonyNext: false, windows: true })
    expect(detectVisitor(UA.ipadDesktop, 0)).toMatchObject({ windows: false })
    expect(detectVisitor(UA.harmony)).toMatchObject({ android: false, harmonyNext: true, desktop: false })
    // 兼容安卓的鸿蒙 4 报 Android,照常给安装包
    expect(detectVisitor(UA.androidChrome + ' HarmonyOS')).toMatchObject({ android: true, harmonyNext: false })
  })
})

describe('fetchAndroidRelease', () => {
  it('404 = 没发安卓包', async () => {
    const f = vi.fn().mockResolvedValue(new Response('{"error":"no_release"}', { status: 404 }))
    await expect(fetchAndroidRelease(f)).resolves.toBeNull()
    expect(f).toHaveBeenCalledWith('/api/app/android/latest', { cache: 'no-cache' })
  })
  it('其它失败抛错', async () => {
    const f = vi.fn().mockResolvedValue(new Response('{"error":"bad_release"}', { status: 500 }))
    await expect(fetchAndroidRelease(f)).rejects.toThrow('HTTP 500')
  })
  it('成功返回 JSON', async () => {
    const body = { versionCode: 30500, versionName: '3.5.0', apk: 'gopan-3.5.0.apk', url: '/api/app/android/gopan-3.5.0.apk' }
    const f = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }))
    await expect(fetchAndroidRelease(f)).resolves.toMatchObject(body)
  })
})

describe('dayLabel', () => {
  const now = new Date(2026, 9, 22, 12)
  it('更新日志的日期按字面取,今年的不写年份', () => {
    expect(dayLabel('2026-10-22', now)).toBe('10月22日')
    expect(dayLabel('2026-07-02', now)).toBe('7月2日')
    expect(dayLabel('2025-12-31', now)).toBe('2025年12月31日')
  })
  it('RFC3339 换成本地日期', () => {
    expect(dayLabel('2026-10-22T05:00:00+08:00', now)).toMatch(/^10月2[12]日$/)
  })
  it('无效与空给空串', () => {
    expect(dayLabel('', now)).toBe('')
    expect(dayLabel(null, now)).toBe('')
    expect(dayLabel('nope', now)).toBe('')
  })
})
