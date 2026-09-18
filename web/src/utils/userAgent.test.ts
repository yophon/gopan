import { describe, expect, it } from 'vitest'

import { describeUserAgent } from './userAgent'

const CHROME_WIN =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'
const EDGE_WIN =
  'Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 Edg/120.0'
const SAFARI_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
const WECHAT_IOS =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.40'
const ANDROID_CHROME =
  'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0 Mobile Safari/537.36'

describe('describeUserAgent', () => {
  it('空 UA 归为未知设备', () => {
    expect(describeUserAgent('')).toBe('未知设备')
    expect(describeUserAgent('   ')).toBe('未知设备')
    expect(describeUserAgent(null)).toBe('未知设备')
    expect(describeUserAgent(undefined)).toBe('未知设备')
  })

  it('桌面 Chrome', () => {
    expect(describeUserAgent(CHROME_WIN)).toBe('Chrome · Windows')
  })

  it('Edge 不会被 Chrome 规则抢走', () => {
    expect(describeUserAgent(EDGE_WIN)).toBe('Edge · Windows')
  })

  it('桌面 Safari', () => {
    expect(describeUserAgent(SAFARI_MAC)).toBe('Safari · macOS')
  })

  it('微信内置浏览器带出形态', () => {
    expect(describeUserAgent(WECHAT_IOS)).toBe('微信 · iOS · 手机')
  })

  it('安卓 Chrome 也标成手机', () => {
    expect(describeUserAgent(ANDROID_CHROME)).toBe('Chrome · Android · 手机')
  })

  it('WebDAV / 脚本客户端', () => {
    expect(describeUserAgent('rclone/v1.65.0')).toBe('rclone')
    expect(describeUserAgent('curl/8.4.0')).toBe('curl')
    expect(describeUserAgent('gopan-webdav/1.0')).toBe('gopan WebDAV')
  })

  it('认不出来就截断原始 UA,不编结论', () => {
    const out = describeUserAgent('x'.repeat(60))
    expect(out).toBe(`${'x'.repeat(40)}…`)
  })
})
