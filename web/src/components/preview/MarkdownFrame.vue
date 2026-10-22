<script setup lang="ts">
import { ref, watch } from 'vue'

import { useAppTheme } from '@/composables/theme'
import { MD_SANDBOX, mdUrl } from '@/utils/markdown'

/**
 * Markdown 渲染(doc/23 五.2):服务端渲染好的一页 HTML,放进沙箱 iframe。
 * sandbox 只放行外链开新窗口,不给脚本、不给同源;页面自己也带 CSP sandbox。
 * token 只在换文件 / 换主题时取一次:access token 定期刷新,跟着变会让 iframe 重新加载、丢掉阅读位置。
 * 调用方负责先发一个请求让 token 是新鲜的(预览查询、剪藏查询都会顺手刷新)。
 */
const props = defineProps<{ nodeId: string; token: string }>()

const theme = useAppTheme()
const src = ref('')

watch(
  () => [props.nodeId, theme.value] as const,
  ([id, t]) => (src.value = mdUrl(id, props.token, t)),
  { immediate: true },
)
</script>

<template>
  <iframe
    :key="src"
    class="md-frame"
    :src="src"
    :sandbox="MD_SANDBOX"
    referrerpolicy="no-referrer"
    title="Markdown 预览"
  />
</template>

<style scoped>
.md-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: var(--el-bg-color);
}
</style>
