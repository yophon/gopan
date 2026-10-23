<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useEventListener } from '@vueuse/core'

import { clampMenu } from '@/components/nodes/browser'

/**
 * 桌面右键菜单:贴着鼠标弹出、整块留在视口里;点别处、Esc、滚动、窗口失焦都会关。
 * 条目可以带图标、快捷键提示、危险色、一层子菜单;divider 画分隔线。
 */
export interface MenuEntry {
  key: string
  label?: string
  icon?: unknown
  hint?: string
  danger?: boolean
  disabled?: boolean
  divider?: boolean
  children?: MenuEntry[]
}

const props = defineProps<{
  visible: boolean
  x: number
  y: number
  items: MenuEntry[]
}>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'select', key: string): void
}>()

const el = ref<HTMLElement | null>(null)
const pos = ref({ left: 0, top: 0 })
const subLeft = ref(false)

watch(
  () => [props.visible, props.x, props.y] as const,
  async ([v]) => {
    if (!v) return
    pos.value = { left: props.x, top: props.y }
    await nextTick()
    const r = el.value?.getBoundingClientRect()
    if (!r) return
    pos.value = clampMenu(props.x, props.y, r.width, r.height, window.innerWidth, window.innerHeight)
    // 右边放不下子菜单就往左开
    subLeft.value = pos.value.left + r.width + 180 > window.innerWidth
  },
  { immediate: true },
)

function close() {
  if (props.visible) emit('update:visible', false)
}

function pick(item: MenuEntry) {
  if (item.disabled || item.children?.length) return
  close()
  emit('select', item.key)
}

useEventListener(window, 'pointerdown', (e: PointerEvent) => {
  if (props.visible && el.value && !el.value.contains(e.target as Node)) close()
}, { capture: true })
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (props.visible && e.key === 'Escape') {
    e.stopPropagation()
    close()
  }
}, { capture: true })
useEventListener(window, 'blur', close)
useEventListener(window, 'resize', close)
useEventListener(window, 'wheel', close, { passive: true })
</script>

<template>
  <Teleport to="body">
    <ul
      v-if="visible"
      ref="el"
      class="ctx-menu"
      role="menu"
      :style="{ left: `${pos.left}px`, top: `${pos.top}px` }"
      @contextmenu.prevent
    >
      <template v-for="item in items" :key="item.key">
        <li v-if="item.divider" class="ctx-divider" role="separator" />
        <li
          v-else
          class="ctx-item"
          :class="{ danger: item.danger, disabled: item.disabled, 'has-sub': item.children?.length }"
          role="menuitem"
          :aria-disabled="item.disabled"
          @click="pick(item)"
        >
          <el-icon v-if="item.icon" class="ctx-icon"><component :is="item.icon" /></el-icon>
          <span v-else class="ctx-icon" />
          <span class="ctx-label">{{ item.label }}</span>
          <span v-if="item.hint" class="ctx-hint">{{ item.hint }}</span>
          <span v-if="item.children?.length" class="ctx-arrow">›</span>
          <ul v-if="item.children?.length" class="ctx-menu ctx-sub" :class="{ left: subLeft }" role="menu">
            <li
              v-for="sub in item.children"
              :key="sub.key"
              class="ctx-item"
              :class="{ disabled: sub.disabled }"
              role="menuitem"
              @click.stop="pick(sub)"
            >
              <span class="ctx-label">{{ sub.label }}</span>
            </li>
          </ul>
        </li>
      </template>
    </ul>
  </Teleport>
</template>

<style scoped>
.ctx-menu {
  position: fixed;
  z-index: 3001;
  min-width: 196px;
  margin: 0;
  padding: 5px;
  list-style: none;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  box-shadow: var(--el-box-shadow-light);
  font-size: 13px;
}
.ctx-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 32px;
  padding: 0 10px;
  border-radius: 7px;
  color: var(--el-text-color-primary);
  cursor: pointer;
  white-space: nowrap;
}
.ctx-item:hover {
  background: var(--el-fill-color-light);
}
.ctx-item.danger {
  color: var(--el-color-danger);
}
.ctx-item.disabled {
  color: var(--el-text-color-disabled);
  cursor: default;
}
.ctx-item.disabled:hover {
  background: none;
}
.ctx-icon {
  width: 16px;
  font-size: 15px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.ctx-item.danger .ctx-icon {
  color: var(--el-color-danger);
}
.ctx-label {
  flex: 1;
}
.ctx-hint,
.ctx-arrow {
  margin-left: 16px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.ctx-divider {
  height: 1px;
  margin: 4px 6px;
  background: var(--el-border-color-lighter);
}
.ctx-sub {
  display: none;
  position: absolute;
  top: -6px;
  left: 100%;
  min-width: 120px;
}
.ctx-sub.left {
  left: auto;
  right: 100%;
}
.has-sub:hover > .ctx-sub {
  display: block;
}
</style>
