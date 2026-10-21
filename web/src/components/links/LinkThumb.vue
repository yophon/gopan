<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { Document, Picture } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { NodeThumbDocument } from '@/api/gen/graphql'
import { isImageFile } from '@/utils/links'

/** 直链列表里的小图:图片取节点自己的缩略图(不走直链,免得自己看一眼也记成访问),其余给图标 */
const props = defineProps<{ nodeId: string; name: string; mime?: string | null; size?: number }>()

const image = computed(() => isImageFile(props.name, props.mime))

const { data } = useQuery({
  queryKey: computed(() => ['nodeThumb', props.nodeId]),
  queryFn: () => request(NodeThumbDocument, { id: props.nodeId }),
  enabled: image,
  // 缩略图是 15 分钟的预签名,10 分钟内复用
  staleTime: 10 * 60_000,
  gcTime: 10 * 60_000,
})
const thumb = computed(() => data.value?.node.preview.thumbUrl ?? null)
</script>

<template>
  <span class="thumb" :style="{ width: `${props.size ?? 32}px`, height: `${props.size ?? 32}px` }">
    <img v-if="thumb" :src="thumb" alt="" loading="lazy" />
    <el-icon v-else :size="(props.size ?? 32) * 0.6"><component :is="image ? Picture : Document" /></el-icon>
  </span>
</template>

<style scoped>
.thumb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  overflow: hidden;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
