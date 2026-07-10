<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { useEventListener } from '@vueuse/core'
import {
  ArrowDown,
  ArrowUp,
  Close,
  CircleCheck,
  Delete,
  RefreshRight,
  Upload,
  VideoPause,
  VideoPlay,
} from '@element-plus/icons-vue'

import { TERMINAL_STATUSES, useUploadsStore } from '@/stores/uploads'
import type { ResumableRecord, UploadTask } from '@/stores/uploads'
import {
  cancelTask,
  enqueueFiles,
  pauseTask,
  removeFinishedTask,
  resumeTask,
  retryTask,
  setUploadInvalidator,
} from '@/uploader/manager'
import { formatBytes } from '@/utils/format'

/**
 * 全局上传抽屉:右下角浮动面板,AppShell 挂载。
 * 任务列表 + 可恢复的上传(localStorage 断点记录)。
 */

const uploads = useUploadsStore()
const queryClient = useQueryClient()

// 任务 done → 刷新对应目录列表(manager 无组件上下文,由这里注入)
setUploadInvalidator((parentId) => {
  void queryClient.invalidateQueries({ queryKey: ['children', parentId] })
  void queryClient.invalidateQueries({ queryKey: ['breadcrumb'] })
})

// 有进行中任务时,关闭页面前提示
useEventListener(window, 'beforeunload', (e) => {
  if (uploads.hasActive) {
    e.preventDefault()
    // Chrome 需要设置 returnValue 才会弹确认框
    e.returnValue = ''
  }
})

const visible = computed(() => uploads.tasks.length > 0 || uploads.orphanRecords.length > 0)
const finishedCount = computed(
  () => uploads.tasks.filter((t) => TERMINAL_STATUSES.includes(t.status)).length,
)

// ---------- 任务展示 ----------

function percentage(task: UploadTask): number {
  if (task.status === 'done') return 100
  if (task.size === 0) return 0
  if (task.status === 'hashing') {
    return Math.min(100, Math.floor((task.hashedBytes / task.size) * 100))
  }
  return Math.min(100, Math.floor((task.uploadedBytes / task.size) * 100))
}

function progressStatus(task: UploadTask): 'success' | 'exception' | undefined {
  if (task.status === 'done') return 'success'
  if (task.status === 'failed' || task.status === 'canceled') return 'exception'
  return undefined
}

function statusText(task: UploadTask): string {
  switch (task.status) {
    case 'queued':
      return '排队中'
    case 'hashing':
      return `计算校验值 ${percentage(task)}%`
    case 'initiating':
      return '准备上传'
    case 'uploading':
      return `${formatBytes(task.speedBps)}/s`
    case 'completing':
      return '合并中'
    case 'done':
      return task.instant ? '秒传' : '已完成'
    case 'paused':
      return '已暂停'
    case 'failed':
      return task.error ?? '上传失败'
    case 'canceled':
      return '已取消'
  }
}

function canPause(task: UploadTask): boolean {
  return ['queued', 'hashing', 'initiating', 'uploading'].includes(task.status)
}

function isTerminal(task: UploadTask): boolean {
  return TERMINAL_STATUSES.includes(task.status)
}

// ---------- 恢复记录 ----------

const resumeInput = ref<HTMLInputElement | null>(null)
const resumeTarget = ref<ResumableRecord | null>(null)

function onPickResume(record: ResumableRecord) {
  resumeTarget.value = record
  resumeInput.value?.click()
}

function onResumeFileChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  const record = resumeTarget.value
  input.value = ''
  resumeTarget.value = null
  if (files.length === 0 || !record) return
  // enqueueFiles 内部按 (name, size, lastModified) 匹配记录续传;
  // 选错文件则匹配不上,按新上传处理。
  enqueueFiles(files, record.parentId)
}
</script>

<template>
  <div v-if="visible" class="upload-drawer">
    <!-- 收起态:浮动按钮 + 徽标 -->
    <el-badge
      v-if="uploads.collapsed"
      :value="uploads.activeCount"
      :hidden="uploads.activeCount === 0"
      class="fab-badge"
    >
      <el-button
        class="fab"
        type="primary"
        circle
        size="large"
        :icon="Upload"
        @click="uploads.collapsed = false"
      />
    </el-badge>

    <!-- 展开态:面板 -->
    <div v-else class="panel">
      <div class="panel-header">
        <span class="panel-title">
          上传任务
          <el-badge
            :value="uploads.activeCount"
            :hidden="uploads.activeCount === 0"
            class="title-badge"
          />
        </span>
        <span class="panel-actions">
          <el-button
            v-if="finishedCount > 0"
            link
            size="small"
            @click="uploads.clearFinished()"
          >
            清除已结束
          </el-button>
          <el-button
            link
            size="small"
            :icon="ArrowDown"
            title="收起"
            @click="uploads.collapsed = true"
          />
        </span>
      </div>

      <div class="panel-body">
        <!-- 可恢复的上传 -->
        <template v-if="uploads.orphanRecords.length > 0">
          <div class="section-title">可恢复的上传</div>
          <div v-for="record in uploads.orphanRecords" :key="record.sessionId" class="task resumable">
            <div class="task-main">
              <div class="task-name" :title="record.fileName">{{ record.fileName }}</div>
              <div class="task-status">
                {{ formatBytes(record.size) }} · 重新选择该文件即可续传
              </div>
            </div>
            <div class="task-ops">
              <el-button link size="small" :icon="ArrowUp" @click="onPickResume(record)">
                选择文件
              </el-button>
              <el-button
                link
                size="small"
                :icon="Close"
                title="忽略此记录"
                @click="uploads.removeRecord(record.sessionId)"
              />
            </div>
          </div>
          <el-divider v-if="uploads.tasks.length > 0" class="divider" />
        </template>

        <!-- 任务列表 -->
        <div v-for="task in uploads.tasks" :key="task.id" class="task">
          <div class="task-main">
            <div class="task-name" :title="task.fileName">{{ task.fileName }}</div>
            <el-progress
              :percentage="percentage(task)"
              :status="progressStatus(task)"
              :stroke-width="6"
              :show-text="false"
            />
            <div class="task-status" :class="{ error: task.status === 'failed' }">
              <span>{{ statusText(task) }}</span>
              <span v-if="task.status === 'uploading'">
                {{ formatBytes(task.uploadedBytes) }} / {{ formatBytes(task.size) }}
              </span>
              <span v-else-if="!isTerminal(task)">{{ formatBytes(task.size) }}</span>
            </div>
          </div>
          <div class="task-ops">
            <el-button
              v-if="canPause(task)"
              link
              size="small"
              :icon="VideoPause"
              title="暂停"
              @click="pauseTask(task.id)"
            />
            <el-button
              v-if="task.status === 'paused'"
              link
              size="small"
              :icon="VideoPlay"
              title="继续"
              @click="resumeTask(task.id)"
            />
            <el-button
              v-if="task.status === 'failed'"
              link
              size="small"
              :icon="RefreshRight"
              title="重试"
              @click="retryTask(task.id)"
            />
            <el-button
              v-if="!isTerminal(task)"
              link
              size="small"
              :icon="Close"
              title="取消"
              @click="cancelTask(task.id)"
            />
            <el-icon v-if="task.status === 'done'" class="done-icon"><CircleCheck /></el-icon>
            <el-button
              v-if="isTerminal(task)"
              link
              size="small"
              :icon="Delete"
              title="移除"
              @click="removeFinishedTask(task.id)"
            />
          </div>
        </div>
      </div>
    </div>

    <input ref="resumeInput" type="file" class="hidden-input" @change="onResumeFileChosen" />
  </div>
</template>

<style scoped>
.upload-drawer {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2000;
}
.fab {
  box-shadow: var(--el-box-shadow);
}
.panel {
  width: 380px;
  max-height: 60vh;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow);
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
}
.title-badge {
  margin-left: 10px;
}
.panel-actions {
  display: inline-flex;
  align-items: center;
}
.panel-body {
  overflow-y: auto;
  padding: 6px 12px 10px;
}
.section-title {
  margin: 6px 0 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.divider {
  margin: 8px 0;
}
.task {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
}
.task + .task {
  border-top: 1px solid var(--el-border-color-lighter);
}
.task-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.task-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-status {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.task-status.error {
  color: var(--el-color-danger);
}
.task-ops {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
}
.task-ops .el-button + .el-button {
  margin-left: 4px;
}
.done-icon {
  color: var(--el-color-success);
}
.hidden-input {
  display: none;
}
</style>
