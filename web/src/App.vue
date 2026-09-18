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
  try { await request(MeDocument) } catch { if(!auth.accessToken) queryClient.clear() }
  finally { checking=false }
}
function resume() { if(!document.hidden) void check() }
onMounted(()=>{timer=setInterval(check,30000);document.addEventListener('visibilitychange',resume)})
onBeforeUnmount(()=>{clearInterval(timer);document.removeEventListener('visibilitychange',resume)})
</script>

<template>
  <router-view />
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
