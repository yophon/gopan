import { describe, expect, it } from 'vitest'

import { BP_COMPACT_MAX, BP_MOBILE_MAX, resolveBreakpoint } from './breakpoints'

describe('resolveBreakpoint', () => {
  // 表驱动:每个断点的两侧边界都要钉住,改数值必须同时改这里
  const cases: Array<[number, boolean, boolean, boolean]> = [
    [320, true, true, false],
    [375, true, true, false],
    [414, true, true, false],
    [767, true, true, false],
    [768, false, true, false],
    [900, false, true, false],
    [1023, false, true, false],
    [1024, false, false, true],
    [1440, false, false, true],
  ]

  it.each(cases)(
    '宽度 %i → mobile=%s compact=%s desktop=%s',
    (width, isMobile, isCompact, isDesktop) => {
      expect(resolveBreakpoint(width)).toEqual({ isMobile, isCompact, isDesktop })
    },
  )

  it('不变量:desktop 与 compact 互补,mobile ⊆ compact', () => {
    for (const width of [0, 320, 767, 768, 1023, 1024, 3840]) {
      const s = resolveBreakpoint(width)
      expect(s.isDesktop).toBe(!s.isCompact)
      // 手机一定也是"非桌面",所以 compact 判 true 时不能拿它当"平板专属"
      if (s.isMobile) expect(s.isCompact).toBe(true)
    }
  })

  it('断点常量是手机/紧凑的上界', () => {
    expect(BP_MOBILE_MAX).toBe(767)
    expect(BP_COMPACT_MAX).toBe(1023)
  })
})
