<script setup lang="ts">
import { ref, watch } from 'vue'

import type { SheetItem } from './types'

/**
 * 底部操作菜单(触屏用)。自研实现而不是 el-drawer:需要"高度随条目数自适应"
 * 和底部安全区,自己写 30 行 CSS 比绕 el-drawer 的 size 更省事。
 */
const props = defineProps<{
  visible: boolean
  title?: string
  items: SheetItem[]
}>()

const emit = defineEmits<{
  (e: 'select', key: string): void
  (e: 'update:visible', v: boolean): void
}>()

// 长按抬手时浏览器还会补一个 click,它会落在刚刚出现的遮罩上把菜单瞬间关掉。
// 开菜单后的短暂窗口内忽略遮罩点击。
const openedAt = ref(0)
watch(
  () => props.visible,
  (v) => {
    if (v) openedAt.value = Date.now()
  },
  { immediate: true },
)

function close() {
  emit('update:visible', false)
}

function onMaskClick() {
  if (Date.now() - openedAt.value < 350) return
  close()
}

function onSelect(item: SheetItem) {
  if (item.disabled) return
  close()
  emit('select', item.key)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="sheet">
      <div v-if="props.visible" class="sheet-mask" @click.self="onMaskClick">
        <div class="sheet" role="menu">
          <div v-if="props.title" class="sheet-title">{{ props.title }}</div>
          <button
            v-for="item in props.items"
            :key="item.key"
            class="sheet-item"
            :class="{ danger: item.danger, disabled: item.disabled }"
            :disabled="item.disabled"
            role="menuitem"
            @click="onSelect(item)"
          >
            <el-icon v-if="item.icon" class="sheet-icon">
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span>
          </button>
          <button class="sheet-item cancel" role="menuitem" @click="close">取消</button>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: flex;
  align-items: flex-end;
  background: rgb(0 0 0 / 45%);
}
.sheet {
  width: 100%;
  padding: 6px 8px calc(6px + var(--sab));
  border-radius: 14px 14px 0 0;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow);
}
.sheet-title {
  padding: 10px 14px 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.sheet-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 48px;
  padding: 0 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-primary);
  font-size: 16px;
  text-align: left;
}
.sheet-item:active {
  background: var(--el-fill-color-light);
}
.sheet-item.danger {
  color: var(--el-color-danger);
}
.sheet-item.disabled {
  color: var(--el-text-color-disabled);
}
.sheet-icon {
  font-size: 18px;
}
.sheet-item.cancel {
  margin-top: 6px;
  border-top: 1px solid var(--el-border-color-lighter);
  border-radius: 0 0 8px 8px;
  justify-content: center;
  color: var(--el-text-color-regular);
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.18s ease;
}
.sheet-enter-active .sheet,
.sheet-leave-active .sheet {
  transition: transform 0.18s ease;
}
.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}
.sheet-enter-from .sheet,
.sheet-leave-to .sheet {
  transform: translateY(100%);
}
</style>
