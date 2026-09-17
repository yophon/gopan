import { computed, effectScope, type ComputedRef, type Ref } from 'vue'
import { useMediaQuery } from '@vueuse/core'

/**
 * 断点只有两个(数值必须与 src/styles/responsive.css 的注释保持一致,
 * responsiveCss.test.ts 会读两个文件做一致性断言):
 *   手机  ≤ 767px  → 卡片列表 + 抽屉导航
 *   紧凑  ≤ 1023px → 仍是表格,隐藏次要列
 *   桌面  > 1023px → 现状不变
 *
 * 767 的由来:该宽度以下 el-table 的最小列宽(44+320+120+180+80≈744)必然溢出;
 * 1023 的由来:桌面内容宽 = 视口 − 侧栏 220 − 内边距 48,到 1024 才放得下 744 的表。
 */
export const BP_MOBILE_MAX = 767
export const BP_COMPACT_MAX = 1023

export interface BreakpointState {
  isMobile: boolean
  isCompact: boolean
  isDesktop: boolean
}

export interface Breakpoints {
  isMobile: Ref<boolean>
  isCompact: Ref<boolean>
  isDesktop: ComputedRef<boolean>
}

/** 纯函数:不碰 window,node 环境下可直接单测 */
export function resolveBreakpoint(width: number): BreakpointState {
  const isCompact = width <= BP_COMPACT_MAX
  return {
    isMobile: width <= BP_MOBILE_MAX,
    isCompact,
    isDesktop: !isCompact,
  }
}

/**
 * 模块级单例:全 App 只注册一份 matchMedia 监听。
 * 用 detached 的 effectScope,免得"第一个调用它的组件卸载"把监听一起销毁。
 */
const scope = effectScope(true)
let cached: Breakpoints | null = null

function create(): Breakpoints {
  const isMobile = useMediaQuery(`(max-width: ${BP_MOBILE_MAX}px)`)
  const isCompact = useMediaQuery(`(max-width: ${BP_COMPACT_MAX}px)`)
  return { isMobile, isCompact, isDesktop: computed(() => !isCompact.value) }
}

export function useBreakpoints(): Breakpoints {
  if (!cached) cached = scope.run(create)!
  return cached
}
