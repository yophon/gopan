import { ref } from 'vue'
import type { Ref } from 'vue'
import { useMutationObserver } from '@vueuse/core'

import { appTheme } from '@/utils/markdown'
import type { MdTheme } from '@/utils/markdown'

/** 站点当前的主题(<html class="dark">),切换时跟着变;给 /md/ 这类自带样式的页面传 theme 用 */
export function useAppTheme(): Ref<MdTheme> {
  const root = typeof document === 'undefined' ? null : document.documentElement
  const theme = ref<MdTheme>(appTheme(root))
  if (root) {
    useMutationObserver(root, () => (theme.value = appTheme(root)), { attributes: true, attributeFilter: ['class'] })
  }
  return theme
}
