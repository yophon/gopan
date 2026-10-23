<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { fileIcon } from './nodeDisplay'
import type { NodeListItem } from './types'

/** 缩略图优先、失败回落图标。缩略图 URL 是乐观签发的,派生物可能还没生成。 */
const props = withDefaults(defineProps<{ node: NodeListItem; size?: number }>(), { size: 28 })

const thumbFailed = ref(false)
watch(
  () => props.node.id,
  () => (thumbFailed.value = false),
)

const thumbUrl = computed(() => props.node.preview?.thumbUrl ?? '')
const canThumb = computed(
  () =>
    props.node.kind === 'FILE' &&
    (props.node.preview?.kind === 'IMAGE' || props.node.preview?.kind === 'VIDEO') &&
    !!thumbUrl.value &&
    !thumbFailed.value,
)

const boxStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }))
</script>

<template>
  <img
    v-if="canThumb"
    class="node-thumb"
    :src="thumbUrl"
    :style="boxStyle"
    alt=""
    loading="lazy"
    @error="thumbFailed = true"
  />
  <!-- 文件夹:实心的暖灰文件夹,与发布页的文件夹记号一致 -->
  <svg v-else-if="node.kind === 'FOLDER'" class="node-folder" viewBox="0 0 34 29" :style="boxStyle" aria-hidden="true">
    <path d="M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" class="folder-back" />
    <path d="M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" class="folder-front" />
  </svg>
  <el-icon v-else class="node-icon" :style="boxStyle">
    <component :is="fileIcon(node)" />
  </el-icon>
</template>

<style scoped>
.node-thumb {
  flex: none;
  border-radius: 6px;
  object-fit: cover;
  background: var(--el-fill-color-light);
}
.node-icon {
  flex: none;
  color: var(--gp-ink-2);
}
/* 文件夹两层的颜色跟着深浅色走(theme.css 的 --gp-folder-*) */
.folder-back {
  fill: var(--gp-folder-back);
}
.folder-front {
  fill: var(--gp-folder-front);
}
.node-folder {
  flex: none;
  padding: 1px;
  box-sizing: border-box;
}
</style>
