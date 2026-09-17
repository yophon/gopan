import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

import { BP_COMPACT_MAX, BP_MOBILE_MAX } from '../composables/breakpoints'

// 断点数字写在两个地方(CSS 与 TS),靠这个测试防漂移。
// 读源码文本做断言是先例:browser_probe.mjs 也是这么数 mcpScopes.ts 的档位数。
const css = readFileSync(new URL('./responsive.css', import.meta.url), 'utf8')

describe('responsive.css', () => {
  it('断点数值与 breakpoints.ts 一致', () => {
    expect(css).toContain(`max-width: ${BP_MOBILE_MAX}px`)
    expect(css).toContain(`max-width: ${BP_COMPACT_MAX}px`)
    expect(css).toContain(`min-width: ${BP_MOBILE_MAX + 1}px`)
    expect(css).toContain(`min-width: ${BP_COMPACT_MAX + 1}px`)
  })

  it('保留安全区变量与触屏目标变量', () => {
    for (const token of [
      '--sat: env(safe-area-inset-top',
      '--sab: env(safe-area-inset-bottom',
      '--touch-target:',
    ]) {
      expect(css).toContain(token)
    }
  })

  it('Element Plus 的固定宽度容器在窄屏被钳住', () => {
    expect(css).toMatch(/\.el-dialog\s*\{[^}]*max-width:\s*94vw/)
    expect(css).toMatch(/\.el-message-box\s*\{[^}]*max-width:\s*90vw/)
  })

  it('三档显隐工具类齐全', () => {
    for (const cls of ['.only-mobile', '.only-compact', '.only-desktop']) {
      expect(css).toContain(cls)
    }
  })
})
