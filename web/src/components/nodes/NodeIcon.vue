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
  <el-icon v-else class="node-icon" :style="boxStyle">
    <component :is="fileIcon(node)" />
  </el-icon>
</template>

<style scoped>
.node-thumb {
  flex: none;
  border-radius: 4px;
  object-fit: cover;
  background: var(--el-fill-color-light);
}
.node-icon {
  flex: none;
  color: var(--el-color-primary);
}
</style>
