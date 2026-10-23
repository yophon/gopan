import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type ViewMode = 'list' | 'grid'

const VIEW_KEY = 'gopan_ui_view'

/** localStorage 里的视图偏好:只认 'grid',其余(含脏值)一律回落到 list */
export function parseViewMode(raw: unknown): ViewMode {
  return raw === 'grid' ? 'grid' : 'list'
}

function loadViewMode(): ViewMode {
  try {
    return parseViewMode(localStorage.getItem(VIEW_KEY))
  } catch {
    // 隐私模式/禁用存储:不记住偏好即可,不影响使用
    return 'list'
  }
}

export const useUiStore = defineStore('ui', () => {
  /** 文件列表的形态(列表 / 网格),手机与桌面共用 */
  const viewMode = ref<ViewMode>(loadViewMode())
  /** 触屏多选态:桌面单击就是选中,手机靠它把卡片点击切成勾选 */
  const selectionMode = ref(false)

  watch(viewMode, (mode) => {
    try {
      localStorage.setItem(VIEW_KEY, mode)
    } catch {
      // 同上,写不进去就算了
    }
  })

  function toggleViewMode() {
    viewMode.value = viewMode.value === 'grid' ? 'list' : 'grid'
  }

  return { viewMode, selectionMode, toggleViewMode }
})
