<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue'

/** 页面标题旁的小问号:说明文字收在这里,悬停 / 点一下才看,不再占着页面顶上一整段 */
defineProps<{ text: string }>()
</script>

<template>
  <el-tooltip :content="text" placement="bottom-start" trigger="hover" popper-class="page-help-popper">
    <button type="button" class="page-help" aria-label="说明">
      <el-icon><QuestionFilled /></el-icon>
    </button>
  </el-tooltip>
</template>

<style scoped>
.page-help {
  display: inline-flex;
  align-items: center;
  margin-left: 6px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-placeholder);
  font-size: 16px;
  vertical-align: -2px;
  cursor: help;
}
.page-help:hover {
  color: var(--el-text-color-secondary);
}
</style>

<style>
.page-help-popper.el-popper {
  max-width: 360px;
  line-height: 1.6;
}
</style>
