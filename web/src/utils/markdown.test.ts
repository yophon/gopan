import { describe, expect, it } from 'vitest'

import { MD_SANDBOX, appTheme, mdUrl } from './markdown'

describe('mdUrl', () => {
  it('带上 token 与主题,token 做 URL 编码', () => {
    expect(mdUrl('0192-abc', 'tok', 'light')).toBe('/md/0192-abc?token=tok&theme=light')
    expect(mdUrl('n1', 'a+b/c=', 'dark')).toBe('/md/n1?token=a%2Bb%2Fc%3D&theme=dark')
  })
  it('节点 id 也编码,拼不出别的路径', () => {
    expect(mdUrl('../x', 't', 'light')).toBe('/md/..%2Fx?token=t&theme=light')
  })
})

describe('appTheme', () => {
  const root = (dark: boolean) => ({ classList: { contains: (c: string) => dark && c === 'dark' } })
  it('html 上有 dark 就是暗色', () => {
    expect(appTheme(root(true))).toBe('dark')
    expect(appTheme(root(false))).toBe('light')
    expect(appTheme(null)).toBe('light')
  })
})

describe('MD_SANDBOX', () => {
  it('不放行脚本和同源', () => {
    expect(MD_SANDBOX).not.toContain('allow-scripts')
    expect(MD_SANDBOX).not.toContain('allow-same-origin')
    expect(MD_SANDBOX.split(' ').sort()).toEqual(['allow-popups', 'allow-popups-to-escape-sandbox'])
  })
})
