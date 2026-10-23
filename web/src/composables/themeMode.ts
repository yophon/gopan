import { computed, ref, watchEffect } from 'vue'

/**
 * 外观:跟随系统 / 浅色 / 深色。选择记在 localStorage,<html class="dark"> 跟着变。
 * public/theme-init.js 在首屏渲染前按同一个 key 先挂上 class(CSP 不许内联脚本,所以是单独的文件),
 * 这里接手之后的切换与系统深浅色变化。
 */
export type ThemeMode = 'system' | 'light' | 'dark'

export const THEME_KEY = 'gopan_theme'

/** 只认 light / dark,其余(含脏值、没存过)都当跟随系统 */
export function parseThemeMode(raw: unknown): ThemeMode {
  return raw === 'light' || raw === 'dark' ? raw : 'system'
}

export function resolveDark(mode: ThemeMode, systemDark: boolean): boolean {
  return mode === 'dark' || (mode === 'system' && systemDark)
}

function load(): ThemeMode {
  try {
    return parseThemeMode(localStorage.getItem(THEME_KEY))
  } catch {
    return 'system'
  }
}

const mode = ref<ThemeMode>(typeof window === 'undefined' ? 'system' : load())
const systemDark = ref(false)

if (typeof window !== 'undefined' && window.matchMedia) {
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  systemDark.value = mq.matches
  mq.addEventListener('change', (e) => (systemDark.value = e.matches))
}

const isDark = computed(() => resolveDark(mode.value, systemDark.value))

if (typeof document !== 'undefined') {
  watchEffect(() => {
    const root = document.documentElement
    root.classList.toggle('dark', isDark.value)
    // 手机浏览器地址栏 / PWA 标题栏的颜色跟着底色走
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', isDark.value ? '#141312' : '#ffffff')
  })
}

function setMode(next: ThemeMode) {
  mode.value = next
  try {
    if (next === 'system') localStorage.removeItem(THEME_KEY)
    else localStorage.setItem(THEME_KEY, next)
  } catch {
    // 存不进去也照样切,只是下次打开不记得
  }
}

export function useThemeMode() {
  return { mode, isDark, setMode }
}
