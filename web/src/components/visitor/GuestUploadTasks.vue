<script setup lang="ts">
import { Close, Refresh } from '@element-plus/icons-vue'

import type { CollectTask, CollectUploader } from '@/uploader/collect'
import { formatBytes } from '@/utils/format'

/** 访客页的上传进度(收集链接与分享访客页共用) */
const props = defineProps<{ uploader: CollectUploader; tasks: CollectTask[] }>()

function percent(t: CollectTask): number {
  return t.size ? Math.floor((t.uploadedBytes / t.size) * 100) : 0
}

function statusText(t: CollectTask): string {
  switch (t.status) {
    case 'queued':
      return '等待中'
    case 'uploading':
      return `${percent(t)}%`
    case 'completing':
      return '校验中'
    case 'failed':
      return t.error ?? '上传失败'
    case 'canceled':
      return '已取消'
    default:
      return ''
  }
}
</script>

<template>
  <ul v-if="props.tasks.length" class="c-tasks">
    <li v-for="t in props.tasks" :key="t.id" class="c-task" :class="t.status">
      <div class="c-row">
        <span class="c-name" :title="t.fileName">{{ t.fileName }}</span>
        <span class="c-status">{{ formatBytes(t.size) }} · {{ statusText(t) }}</span>
        <el-button v-if="t.status === 'failed'" link :icon="Refresh" title="重试" aria-label="重试" @click="props.uploader.retry(t.id)" />
        <el-button
          v-if="t.status === 'failed' || t.status === 'canceled'"
          link
          :icon="Close"
          title="移除"
          aria-label="移除"
          @click="props.uploader.remove(t.id)"
        />
        <el-button v-else link :icon="Close" title="取消" aria-label="取消" @click="props.uploader.cancel(t.id)" />
      </div>
      <div class="c-bar"><i :style="{ width: `${percent(t)}%` }" /></div>
    </li>
  </ul>
</template>

<style scoped>
.c-tasks {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}
.c-task {
  display: grid;
  gap: 4px;
}
.c-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.c-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  color: var(--gp-ink);
}
.c-status {
  flex: none;
  font-size: 12px;
  color: var(--gp-ink-3);
}
.c-task.failed .c-status {
  color: var(--el-color-danger);
}
.c-bar {
  height: 4px;
  border-radius: 2px;
  background: var(--el-fill-color-dark);
  overflow: hidden;
}
.c-bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--gp-ink);
  transition: width 0.3s;
}
.c-task.failed .c-bar i {
  background: var(--el-color-danger);
}
</style>
