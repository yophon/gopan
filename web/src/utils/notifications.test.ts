import { describe, expect, it } from 'vitest'

import { badgeText, dayLabel, groupByDay, notificationRoute, shellPanel, withoutQuery } from './notifications'

describe('notificationRoute', () => {
  it('NODE:进所在文件夹并打开它;根目录下的不带文件夹 id', () => {
    expect(notificationRoute({ kind: 'NODE', id: 'n1', parentId: 'p1' })).toEqual({
      path: '/drive/p1',
      query: { open: 'n1' },
    })
    expect(notificationRoute({ kind: 'NODE', id: 'n1', parentId: null })).toEqual({
      path: '/drive',
      query: { open: 'n1' },
    })
    expect(notificationRoute({ kind: 'NODE', id: null })).toBeNull()
  })
  it('收集链接与直链:分享页对应标签并带 id', () => {
    expect(notificationRoute({ kind: 'FILE_REQUEST', id: 'r1' })).toEqual({
      path: '/shares',
      query: { tab: 'requests', id: 'r1' },
    })
    expect(notificationRoute({ kind: 'LINK', id: 'l1' })).toEqual({
      path: '/shares',
      query: { tab: 'links', id: 'l1' },
    })
    expect(notificationRoute({ kind: 'LINK' })).toEqual({ path: '/shares', query: { tab: 'links' } })
  })
  it('后台任务、登录设备、管理端', () => {
    expect(notificationRoute({ kind: 'JOBS' })).toEqual({ path: '/drive', query: { panel: 'jobs' } })
    expect(notificationRoute({ kind: 'SESSIONS' })).toEqual({ path: '/devices' })
    expect(notificationRoute({ kind: 'ADMIN' })).toEqual({ path: '/admin' })
  })
  it('访问凭据按类型去不同的地方', () => {
    expect(notificationRoute({ kind: 'CREDENTIALS', id: 'webdav' })).toEqual({
      path: '/drive',
      query: { panel: 'webdav' },
    })
    expect(notificationRoute({ kind: 'CREDENTIALS', id: 'mcp' })).toEqual({ path: '/drive', query: { panel: 'mcp' } })
    expect(notificationRoute({ kind: 'CREDENTIALS', id: 'img' })).toEqual({
      path: '/shares',
      query: { tab: 'links', panel: 'keys' },
    })
    // 认不出的类型(以后新增的凭据)落到 Agent 接入
    expect(notificationRoute({ kind: 'CREDENTIALS', id: 'oauth' })).toEqual({
      path: '/drive',
      query: { panel: 'mcp' },
    })
  })
  it('动态:带上来源和起始时间,和服务端的推送链接一致', () => {
    expect(
      notificationRoute({ kind: 'ACTIVITY', id: 'webdav:3f2a', parentId: '2026-10-21T08:00:00Z' }),
    ).toEqual({ path: '/activity', query: { actor: 'webdav:3f2a', since: '2026-10-21T08:00:00Z' } })
    // Agent 的 ref 自己带冒号(api_key:…),原样放进去,页面按第一个冒号拆
    expect(notificationRoute({ kind: 'ACTIVITY', id: 'agent:api_key:9', parentId: null })).toEqual({
      path: '/activity',
      query: { actor: 'agent:api_key:9' },
    })
    expect(notificationRoute({ kind: 'ACTIVITY' })).toEqual({ path: '/activity' })
  })
  it('订阅:有 id 打开那个订阅的详情', () => {
    expect(notificationRoute({ kind: 'FEED', id: 'f1' })).toEqual({ path: '/feeds', query: { id: 'f1' } })
    expect(notificationRoute({ kind: 'FEED', id: null })).toEqual({ path: '/feeds' })
  })
  it('没有目标', () => {
    expect(notificationRoute(null)).toBeNull()
    expect(notificationRoute(undefined)).toBeNull()
  })
})

describe('shellPanel / withoutQuery', () => {
  it('只认 AppShell 管的面板', () => {
    expect(shellPanel('jobs')).toBe('jobs')
    expect(shellPanel('webdav')).toBe('webdav')
    expect(shellPanel('keys')).toBeNull()
    expect(shellPanel(undefined)).toBeNull()
  })
  it('去掉指定键,其余原样', () => {
    expect(withoutQuery({ tab: 'links', panel: 'keys', id: 'x' }, ['panel', 'id'])).toEqual({ tab: 'links' })
    expect(withoutQuery({}, ['open'])).toEqual({})
  })
})

describe('dayLabel / groupByDay', () => {
  const now = new Date(2026, 9, 21, 10, 0)
  it('今天 / 昨天 / 本年日期 / 跨年', () => {
    expect(dayLabel(new Date(2026, 9, 21, 0, 1), now)).toBe('今天')
    expect(dayLabel(new Date(2026, 9, 20, 23, 59), now)).toBe('昨天')
    expect(dayLabel(new Date(2026, 9, 18), now)).toBe('10月18日')
    expect(dayLabel(new Date(2025, 11, 31), now)).toBe('2025年12月31日')
  })
  it('元旦的「昨天」是去年最后一天', () => {
    expect(dayLabel(new Date(2025, 11, 31, 22), new Date(2026, 0, 1, 8))).toBe('昨天')
  })
  it('按本地日期分组,组内保持原顺序', () => {
    const items = [
      { id: 'a', at: new Date(2026, 9, 21, 9).toISOString() },
      { id: 'b', at: new Date(2026, 9, 21, 1).toISOString() },
      { id: 'c', at: new Date(2026, 9, 20, 22).toISOString() },
      { id: 'd', at: new Date(2026, 9, 15, 8).toISOString() },
      { id: 'e', at: 'bad' },
    ]
    const groups = groupByDay(items, (i) => i.at, now)
    expect(groups.map((g) => g.label)).toEqual(['今天', '昨天', '10月15日', '更早'])
    expect(groups[0].items.map((i) => i.id)).toEqual(['a', 'b'])
  })
  it('顺序不单调时同一天并到第一次出现的组里', () => {
    const items = [
      { id: 'a', at: new Date(2026, 9, 21, 9).toISOString() },
      { id: 'b', at: new Date(2026, 9, 20, 9).toISOString() },
      { id: 'c', at: new Date(2026, 9, 21, 8).toISOString() },
    ]
    const groups = groupByDay(items, (i) => i.at, now)
    expect(groups.map((g) => g.items.map((i) => i.id))).toEqual([['a', 'c'], ['b']])
  })
})

describe('badgeText', () => {
  it('0 不显示,超过 99 显示 99+', () => {
    expect(badgeText(0)).toBe('')
    expect(badgeText(7)).toBe('7')
    expect(badgeText(120)).toBe('99+')
  })
})
