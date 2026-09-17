<script setup lang="ts">
import { computed } from 'vue'
import { Check, MoreFilled } from '@element-plus/icons-vue'

import { useLongPress } from '@/composables/longPress'
import { formatTime } from '@/utils/format'
import NodeIcon from './NodeIcon.vue'
import { sizeText } from './nodeDisplay'
import type { NodeListItem } from './types'

/**
 * 手机上的单个节点卡片(列表/网格两种形态)。
 * 触屏语义:轻点 = 进目录/预览(多选态下改为勾选),长按 = 出操作菜单。
 * 鼠标右键仍由桌面表格的 contextmenu 负责,这里只处理触屏。
 */
const props = defineProps<{
  node: NodeListItem
  mode: 'list' | 'grid'
  selectionMode: boolean
  selected: boolean
  downloading?: boolean
}>()

const emit = defineEmits<{
  (e: 'open', node: NodeListItem): void
  (e: 'menu', node: NodeListItem): void
  (e: 'toggle', node: NodeListItem): void
}>()

const { pressed, handlers } = useLongPress({
  onLongPress: () => emit('menu', props.node),
  onTap: () => (props.selectionMode ? emit('toggle', props.node) : emit('open', props.node)),
})

const meta = computed(() => sizeText(props.node))
const time = computed(() => formatTime(props.node.updatedAt))
const iconSize = computed(() => (props.mode === 'grid' ? 64 : 40))
</script>

<template>
  <div
    class="node-card"
    :class="[`mode-${mode}`, { selected, pressed }]"
    v-bind="handlers"
  >
    <span v-if="selectionMode" class="check" :class="{ on: selected }">
      <el-icon v-if="selected"><Check /></el-icon>
    </span>

    <NodeIcon class="icon" :node="node" :size="iconSize" />

    <div class="info">
      <div class="name" :title="node.name">{{ node.name }}</div>
      <div class="meta">
        <span>{{ meta }}</span>
        <span class="dot">·</span>
        <span :title="time">{{ time }}</span>
      </div>
    </div>

    <el-button
      class="more"
      link
      :icon="MoreFilled"
      title="更多操作"
      aria-label="更多操作"
      @pointerdown.stop
      @pointerup.stop
      @click.stop="emit('menu', node)"
    />
    <span v-if="downloading" class="downloading">下载中…</span>
  </div>
</template>

<style scoped>
.node-card {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  /* iOS 长按会弹系统"拷贝/查询"气泡,必须关掉;user-select 防选中文字 */
  -webkit-touch-callout: none;
  user-select: none;
  touch-action: pan-y;
}
.node-card.pressed {
  background: var(--el-fill-color-light);
}
.node-card.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.info {
  flex: 1;
  min-width: 0;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-primary);
}
.meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.dot {
  margin: 0 4px;
}
.check {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid var(--el-border-color);
  border-radius: 50%;
  background: var(--el-bg-color);
  color: #fff;
  font-size: 12px;
}
.check.on {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
}
.more {
  align-self: center;
  width: var(--touch-target);
  height: var(--touch-target);
  font-size: 18px;
}
.downloading {
  position: absolute;
  right: 12px;
  bottom: 6px;
  font-size: 12px;
  color: var(--el-color-primary);
}

/* 网格:竖向卡片,缩略图居中,名字最多两行 */
.node-card.mode-grid {
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px 8px;
  text-align: center;
}
.node-card.mode-grid .icon {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 64px;
}
.node-card.mode-grid .name {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  white-space: normal;
  word-break: break-all;
  font-size: 13px;
}
.node-card.mode-grid .meta {
  display: flex;
  justify-content: center;
  gap: 2px;
}
.node-card.mode-grid .more {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 32px;
  height: 32px;
}
</style>
