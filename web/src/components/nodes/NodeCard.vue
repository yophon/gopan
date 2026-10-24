<script setup lang="ts">
import { computed } from 'vue'
import { Check, MoreFilled, StarFilled } from '@element-plus/icons-vue'

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
  /** 覆盖第二行文案(回收站要显示删除时间、分享要显示有效期) */
  secondary?: string
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
const iconSize = computed(() => (props.mode === 'grid' ? 120 : 40))
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

    <!-- 网格:缩略图铺满一块 4:3 的方框;列表:左边一个图标 -->
    <div v-if="mode === 'grid'" class="visual"><NodeIcon class="icon" :node="node" :size="iconSize" /></div>
    <NodeIcon v-else class="icon" :node="node" :size="iconSize" />

    <div class="info">
      <div class="name" :title="node.name">
        <el-icon v-if="node.starred" class="star" aria-label="已收藏"><StarFilled /></el-icon>{{ node.name }}
      </div>
      <div v-if="props.secondary" class="meta">{{ props.secondary }}</div>
      <div v-else class="meta">
        <span>{{ meta }}</span>
        <!-- 网格太窄,只写大小 -->
        <template v-if="mode === 'list'">
          <span class="dot">·</span>
          <span :title="time">{{ time }}</span>
        </template>
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
.star {
  margin-right: 4px;
  vertical-align: -2px;
  color: var(--gp-star);
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
  color: var(--gp-on-primary);
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

/* 网格:上面一块 4:3 的缩略图,下面名字(最多两行)和大小;「⋯」是浮在缩略图右上角的小圆钮 */
.node-card.mode-grid {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  padding: 6px 6px 10px;
}
.visual {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 4 / 3;
  border-radius: 8px;
  background: var(--gp-panel);
  overflow: hidden;
}
.visual :deep(.node-thumb) {
  width: 100% !important;
  height: 100% !important;
  border-radius: 0;
}
.visual :deep(.node-icon) {
  width: 40px !important;
  height: 40px !important;
  font-size: 34px !important;
}
.visual :deep(.node-folder) {
  width: 64px !important;
  height: 56px !important;
}
.node-card.mode-grid .info {
  padding: 0 4px;
}
.node-card.mode-grid .name {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  white-space: normal;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.35;
}
.node-card.mode-grid .more {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 28px;
  height: 28px;
  min-height: 0;
  border-radius: 50%;
  background: color-mix(in srgb, var(--el-bg-color) 85%, transparent);
  font-size: 15px;
}
.node-card.mode-grid .check {
  top: 12px;
  left: 12px;
}
</style>
