<script setup lang="ts"></script>

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
