<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { useAuthStore } from './stores/auth'
import { request } from './api/client'
import { MeDocument } from './api/gen/graphql'
const auth=useAuthStore(), queryClient=useQueryClient()
let timer:ReturnType<typeof setInterval>, checking=false
async function check() {
  if (!auth.accessToken || checking) return
  checking=true
  // 结果写回 ['me'] 缓存:侧栏的用量条与通知角标都跟着这一次轮询更新,不另起轮询
  try { queryClient.setQueryData(['me'], await request(MeDocument)) } catch { if(!auth.accessToken) queryClient.clear() }
  finally { checking=false }
}
function resume() { if(!document.hidden) void check() }
onMounted(()=>{timer=setInterval(check,30000);document.addEventListener('visibilitychange',resume)})
onBeforeUnmount(()=>{clearInterval(timer);document.removeEventListener('visibilitychange',resume)})
</script>

<template>
  <!-- Element 的弹层(确认框、提示、对话框)从 3000 起排:要盖得住全屏预览层(2500),在预览里编辑时的确认框才看得见 -->
  <el-config-provider :z-index="3000">
    <router-view />
  </el-config-provider>
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}

/* iOS Safari:地址栏收起/展开时 100% 算出来的高度会跳,底部被裁。
   支持 dvh 就挂到动态视口高度上(桌面 dvh == vh,无副作用)。 */
@supports (height: 100dvh) {
  html,
  body,
  #app {
    height: 100dvh;
  }
}

body {
  /* 挡住下拉刷新/橡皮筋,让 PWA 独立窗口更像原生 */
  overscroll-behavior-y: none;
}
</style>
