<script setup lang="ts">
/** 文件夹剪影 + 「gopan」字标。大小跟着 font-size 走,颜色跟着 color 走。 */
withDefaults(defineProps<{ markOnly?: boolean }>(), { markOnly: false })
</script>

<template>
  <span class="brand-logo">
    <svg class="brand-mark" viewBox="0 0 24 20" aria-hidden="true">
      <path d="M0 3a3 3 0 0 1 3-3h5.2a2 2 0 0 1 1.5.7L11.6 3H21a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H3a3 3 0 0 1-3-3z" />
    </svg>
    <span v-if="!markOnly" class="brand-word">gopan</span>
  </span>
</template>

<style scoped>
.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 0.45em;
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1;
}
.brand-mark {
  width: 1.2em;
  height: 1em;
  flex: none;
  fill: currentColor;
}
</style>
