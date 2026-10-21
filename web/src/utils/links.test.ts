import { describe, expect, it } from 'vitest'

import {
  absoluteLinkUrl,
  bytesToGiB,
  fillHourBuckets,
  formatLink,
  formatLinks,
  giBToBytes,
  hitStatusType,
  isImageFile,
  linkSettingsFrom,
  linkSettingsInput,
  linkStatusMeta,
  normalizeHost,
  parseHostList,
  refererHost,
  refererLabel,
  thumbLinkUrl,
  validateLinkSettings,
} from './links'

const origin = 'https://pan.example.com'
const img = { url: 'https://pan.example.com/x/abc/猫.png', name: '猫.png', mime: 'image/png' }
const doc = { url: '/x/def/报告 (终版).pdf', name: '报告 [终版].pdf', mime: 'application/pdf' }

describe('absoluteLinkUrl', () => {
  it('站内路径补上站点地址,完整地址原样', () => {
    expect(absoluteLinkUrl('/x/t/a.png', origin)).toBe('https://pan.example.com/x/t/a.png')
    expect(absoluteLinkUrl('/x/t/a.png', 'https://pan.example.com/')).toBe('https://pan.example.com/x/t/a.png')
    expect(absoluteLinkUrl('https://cdn.example.com/x/t/a.png', origin)).toBe('https://cdn.example.com/x/t/a.png')
  })
})

describe('isImageFile', () => {
  it('有 mime 按 mime,没有按扩展名', () => {
    expect(isImageFile('a.bin', 'image/webp')).toBe(true)
    expect(isImageFile('a.png', 'application/octet-stream')).toBe(false)
    expect(isImageFile('A.JPG')).toBe(true)
    expect(isImageFile('a.pdf', null)).toBe(false)
  })
})

describe('formatLink', () => {
  it('链接', () => {
    expect(formatLink(img, 'url', origin)).toBe(img.url)
    expect(formatLink(doc, 'url', origin)).toBe('https://pan.example.com/x/def/报告 (终版).pdf')
  })
  it('Markdown:图片用 ![], 其余 [];地址里的空格括号转义,文字里的方括号加反斜杠', () => {
    expect(formatLink(img, 'markdown', origin)).toBe('![猫.png](https://pan.example.com/x/abc/猫.png)')
    expect(formatLink(doc, 'markdown', origin)).toBe(
      '[报告 \\[终版\\].pdf](https://pan.example.com/x/def/报告%20%28终版%29.pdf)',
    )
  })
  it('HTML:图片 <img>,其余 <a>,属性转义', () => {
    expect(formatLink(img, 'html', origin)).toBe('<img src="https://pan.example.com/x/abc/猫.png" alt="猫.png">')
    expect(formatLink({ url: '/x/a/"x".txt', name: 'a<b>&"c".txt' }, 'html', origin)).toBe(
      '<a href="https://pan.example.com/x/a/&quot;x&quot;.txt">a&lt;b&gt;&amp;&quot;c&quot;.txt</a>',
    )
  })
  it('BBCode', () => {
    expect(formatLink(img, 'bbcode', origin)).toBe('[img]https://pan.example.com/x/abc/猫.png[/img]')
    expect(formatLink(doc, 'bbcode', origin)).toBe(
      '[url=https://pan.example.com/x/def/报告 (终版).pdf]报告 [终版].pdf[/url]',
    )
  })
  it('多选一行一条', () => {
    expect(formatLinks([img, doc], 'url', origin).split('\n')).toHaveLength(2)
    expect(formatLinks([], 'url', origin)).toBe('')
  })
})

describe('thumbLinkUrl', () => {
  it('补上 ?s=', () => {
    expect(thumbLinkUrl('/x/t/a.png', origin)).toBe('https://pan.example.com/x/t/a.png?s=256')
    expect(thumbLinkUrl('/x/t/a.png?v=1', origin, 2048)).toBe('https://pan.example.com/x/t/a.png?v=1&s=2048')
  })
})

describe('normalizeHost / parseHostList', () => {
  it('去掉协议、路径、端口、账号,转小写', () => {
    expect(normalizeHost('https://WWW.Example.com:8443/a/b?c#d')).toBe('www.example.com')
    expect(normalizeHost('example.com/path')).toBe('example.com')
    expect(normalizeHost('//cdn.example.com')).toBe('cdn.example.com')
    expect(normalizeHost('user:pw@blog.example.com:80')).toBe('blog.example.com')
    expect(normalizeHost('  Example.COM.  ')).toBe('example.com')
  })
  it('*.example.com 与 .example.com 当 example.com', () => {
    expect(normalizeHost('*.example.com')).toBe('example.com')
    expect(normalizeHost('.example.com')).toBe('example.com')
  })
  it('认不出的给空串', () => {
    expect(normalizeHost('')).toBe('')
    expect(normalizeHost('http://')).toBe('')
    expect(normalizeHost('a b')).toBe('')
    expect(normalizeHost('a_b!.com')).toBe('')
  })
  it('中文域名与 IPv6', () => {
    expect(normalizeHost('https://例子.中国/x')).toBe('例子.中国')
    expect(normalizeHost('http://[::1]:8080/')).toBe('::1')
  })
  it('列表:一行一个也认逗号空格,去重保序', () => {
    expect(parseHostList('https://a.com/x\nb.com, A.com  c.com:80\n\n')).toEqual(['a.com', 'b.com', 'c.com'])
  })
})

describe('refererLabel', () => {
  it('空串是直接访问,* 是其他', () => {
    expect(refererLabel('')).toBe('直接访问')
    expect(refererLabel('*')).toBe('其他')
    expect(refererLabel('blog.example.com')).toBe('blog.example.com')
  })
})

describe('linkStatusMeta', () => {
  it('四种状态与暂停原因', () => {
    expect(linkStatusMeta('ACTIVE').type).toBe('success')
    expect(linkStatusMeta('PAUSED', 'daily_cap').detail).toContain('流量上限')
    expect(linkStatusMeta('PAUSED', 'manual').detail).toBe('手动暂停')
    expect(linkStatusMeta('PAUSED', 'later').detail).toBe('later')
    expect(linkStatusMeta('EXPIRED').label).toBe('已过期')
    expect(linkStatusMeta('UNAVAILABLE').type).toBe('danger')
  })
})

describe('GiB 换算', () => {
  it('双向', () => {
    expect(bytesToGiB(5 * 1024 ** 3)).toBe(5)
    expect(bytesToGiB(1.5 * 1024 ** 3)).toBe(1.5)
    expect(giBToBytes(0.5)).toBe(512 * 1024 ** 2)
  })
})

describe('fillHourBuckets', () => {
  it('补齐成最近 N 个整点,旧的在前,含当前小时', () => {
    const now = Date.UTC(2026, 9, 21, 15, 40)
    const stats = [
      { hour: new Date(Date.UTC(2026, 9, 21, 15)).toISOString(), hits: 3, bytes: 300, blocked: 1 },
      { hour: new Date(Date.UTC(2026, 9, 21, 13)).toISOString(), hits: 1, bytes: 100, blocked: 0 },
      { hour: new Date(Date.UTC(2026, 9, 1, 0)).toISOString(), hits: 9, bytes: 9, blocked: 0 },
    ]
    const b = fillHourBuckets(stats, 4, now)
    expect(b.map((x) => x.hits)).toEqual([0, 1, 0, 3])
    expect(b[3].at).toBe(Date.UTC(2026, 9, 21, 15))
    expect(b[0].at).toBe(Date.UTC(2026, 9, 21, 12))
    expect(b[3].blocked).toBe(1)
  })
})

describe('refererHost / hitStatusType', () => {
  it('完整 Referer 取主机名,没有是直接访问', () => {
    expect(refererHost('https://blog.example.com/post/1')).toBe('blog.example.com')
    expect(refererHost('')).toBe('直接访问')
  })
  it('状态码颜色', () => {
    expect(hitStatusType(200)).toBe('success')
    expect(hitStatusType(206)).toBe('success')
    expect(hitStatusType(304)).toBe('info')
    expect(hitStatusType(403)).toBe('warning')
    expect(hitStatusType(503)).toBe('danger')
  })
})

describe('单条直链设置', () => {
  const cap = 5 * 1024 ** 3
  it('null 字段 = 用默认', () => {
    const f = linkSettingsFrom({ refererHosts: null, allowNoReferer: null, dailyBytesCap: null, autoPause: false }, cap)
    expect(f).toMatchObject({ useDefaultHosts: true, noReferer: 'default', capMode: 'default', capGiB: 5, expires: 'never' })
    expect(linkSettingsInput(f)).toEqual({
      refererHosts: null,
      allowNoReferer: null,
      dailyBytesCap: null,
      expiresAt: null,
      autoPause: false,
    })
  })
  it('自定义的值来回转换', () => {
    const f = linkSettingsFrom(
      {
        refererHosts: ['a.com', 'b.com'],
        allowNoReferer: false,
        dailyBytesCap: 2 * 1024 ** 3,
        expiresAt: '2027-01-01T00:00:00Z',
        autoPause: true,
      },
      cap,
    )
    expect(f).toMatchObject({ useDefaultHosts: false, hostsText: 'a.com\nb.com', noReferer: 'deny', capMode: 'custom', capGiB: 2, expires: 'at' })
    expect(linkSettingsInput({ ...f, hostsText: 'https://A.com/x\nc.com' })).toEqual({
      refererHosts: ['a.com', 'c.com'],
      allowNoReferer: false,
      dailyBytesCap: 2 * 1024 ** 3,
      expiresAt: '2027-01-01T00:00:00.000Z',
      autoPause: true,
    })
  })
  it('不用默认规则且白名单留空 = 不限来源(空数组,不是 null)', () => {
    const f = { ...linkSettingsFrom({ autoPause: false }, cap), useDefaultHosts: false, hostsText: '' }
    expect(linkSettingsInput(f).refererHosts).toEqual([])
  })
  it('校验上限与到期时间', () => {
    const base = linkSettingsFrom({ autoPause: false }, cap)
    const now = Date.UTC(2026, 9, 21)
    expect(validateLinkSettings({ ...base, capMode: 'custom', capGiB: 0 }, now)).toContain('大于 0')
    expect(validateLinkSettings({ ...base, expires: 'at', expiresAt: null }, now)).toBe('请选择到期时间')
    expect(validateLinkSettings({ ...base, expires: 'at', expiresAt: new Date(now - 1) }, now)).toContain('之后')
    expect(validateLinkSettings({ ...base, expires: 'at', expiresAt: new Date(now + 1000) }, now)).toBeNull()
  })
})
