<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { useEventListener } from '@vueuse/core'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  Close,
  CircleCheck,
  Delete,
  FolderOpened,
  RefreshRight,
  Upload,
  VideoPause,
  VideoPlay,
  WarningFilled,
} from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { CancelJobDocument, ClearFinishedJobsDocument, RetryJobDocument } from '@/api/gen/graphql'
import { useJobs } from '@/composables/jobs'
import type { JobItem } from '@/composables/jobs'
import { isActiveJob, jobKindLabel, jobPercent, jobStatusText } from '@/utils/jobs'

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
 * 任务列表 + 可恢复的上传(localStorage 断点记录)+ 服务端的后台任务(离线下载、解压,doc/21;剪藏,doc/23)。
 */

const uploads = useUploadsStore()
const queryClient = useQueryClient()
const route = useRoute()
const router = useRouter()
const jobs = useJobs(ref(true))

// 任务 done → 刷新对应目录列表(manager 无组件上下文,由这里注入)
setUploadInvalidator((parentId) => {
  void queryClient.invalidateQueries({ queryKey: ['children', parentId] })
  void queryClient.invalidateQueries({ queryKey: ['breadcrumb'] })
  void queryClient.invalidateQueries({ queryKey: ['me'] }) // 用量条跟进
})

// 有进行中任务时,关闭页面前提示
useEventListener(window, 'beforeunload', (e) => {
  if (uploads.hasActive) {
    e.preventDefault()
    // Chrome 需要设置 returnValue 才会弹确认框
    e.returnValue = ''
  }
})

// ---------- 通知「后台任务」点进来:展开并定位,没有任务也显示一个空分组 ----------

const jobsPinned = ref(false)
const jobsSection = ref<HTMLElement | null>(null)

watch(
  () => uploads.jobsFocus,
  async (v) => {
    if (!v) return
    uploads.jobsFocus = false
    jobsPinned.value = true
    uploads.collapsed = false
    void jobs.refetch()
    await nextTick()
    jobsSection.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  },
  { immediate: true },
)
watch(
  () => uploads.collapsed,
  (c) => {
    if (c) jobsPinned.value = false
  },
)

/** 定位时把 7 天内的全列出来(失败的、早结束的都在),平时只列值得看的 */
const jobItems = computed(() => (jobsPinned.value ? jobs.allItems.value : jobs.items.value))

// 传输助手页自己渲染上传进度,抽屉(含收起后的浮动按钮)在那页会压住输入栏,整个隐藏
const visible = computed(
  () =>
    route.name !== 'chat' &&
    (jobsPinned.value ||
      uploads.tasks.length > 0 ||
      uploads.orphanRecords.length > 0 ||
      jobs.items.value.length > 0),
)
const finishedJobs = computed(() => jobItems.value.filter((j) => !isActiveJob(j)).length)
const finishedCount = computed(
  () => uploads.tasks.filter((t) => TERMINAL_STATUSES.includes(t.status)).length + finishedJobs.value,
)
const activeCount = computed(() => uploads.activeCount + jobs.activeCount.value)

async function clearFinished() {
  uploads.clearFinished()
  if (finishedJobs.value === 0) return
  try {
    await request(ClearFinishedJobsDocument)
  } catch (err) {
    ElMessage.error(errorText(err, '清除失败'))
  }
  void queryClient.invalidateQueries({ queryKey: ['jobs'] })
}

// ---------- 后台任务 ----------

async function jobAction(job: JobItem, action: 'cancel' | 'retry') {
  try {
    if (action === 'cancel') await request(CancelJobDocument, { id: job.id })
    else await request(RetryJobDocument, { id: job.id })
  } catch (err) {
    ElMessage.error(errorText(err, action === 'cancel' ? '取消失败' : '重试失败'))
  }
  void queryClient.invalidateQueries({ queryKey: ['jobs'] })
}

/** 打开所在位置:下载的文件进它的文件夹;解压出来的进新文件夹;剪藏的那篇直接打开 */
function openJob(job: JobItem) {
  const r = job.result
  if (!r) return
  if (job.kind === 'CLIP_URL') {
    void router.push({ path: r.parentId ? `/drive/${r.parentId}` : '/drive', query: { open: r.id } })
    return
  }
  const folder = r.kind === 'FOLDER' ? r.id : r.parentId
  void router.push(folder ? `/drive/${folder}` : '/drive')
}

/** 剪藏的标题先是网站域名,抓到网页后换成文章标题 */
function jobHover(job: JobItem): string {
  return job.url ? `${job.title}\n${job.url}` : job.title
}

function jobProgressStatus(job: JobItem): 'success' | 'exception' | 'warning' | undefined {
  if (job.status === 'DONE') return job.errorCode ? 'warning' : 'success'
  if (job.status === 'FAILED' || job.status === 'CANCELED') return 'exception'
  return undefined
}

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
      return '校验并保存中'
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
  return ['queued', 'hashing', 'initiating', 'uploading', 'completing'].includes(task.status)
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
      :value="activeCount"
      :hidden="activeCount === 0"
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
          传输
          <el-badge
            :value="activeCount"
            :hidden="activeCount === 0"
            class="title-badge"
          />
        </span>
        <span class="panel-actions">
          <el-button
            v-if="finishedCount > 0"
            link
            size="small"
            @click="clearFinished()"
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

        <!-- 后台任务:离线下载、解压(服务端执行,关掉页面也继续) -->
        <template v-if="jobItems.length > 0 || jobsPinned">
          <el-divider v-if="uploads.tasks.length > 0 || uploads.orphanRecords.length > 0" class="divider" />
          <div ref="jobsSection" class="section-title">后台任务</div>
          <div v-if="jobItems.length === 0" class="task-status empty-jobs">没有后台任务</div>
          <div v-for="job in jobItems" :key="job.id" class="task job">
            <div class="task-main">
              <div class="task-name" :title="jobHover(job)">
                <span class="job-kind">{{ jobKindLabel[job.kind] }}</span>
                {{ job.title }}
              </div>
              <el-progress
                :percentage="jobPercent(job) ?? 30"
                :indeterminate="job.status === 'RUNNING' && jobPercent(job) === null"
                :status="jobProgressStatus(job)"
                :stroke-width="6"
                :show-text="false"
              />
              <div
                class="task-status"
                :class="{ error: job.status === 'FAILED', warn: job.status === 'DONE' && !!job.errorCode }"
              >
                <span class="job-text" :title="jobStatusText(job, jobs.speeds.value[job.id])">
                  {{ jobStatusText(job, jobs.speeds.value[job.id]) }}
                </span>
              </div>
            </div>
            <div class="task-ops">
              <el-button
                v-if="isActiveJob(job)"
                link
                size="small"
                :icon="Close"
                title="取消"
                @click="jobAction(job, 'cancel')"
              />
              <el-button
                v-if="job.status === 'FAILED' || job.status === 'CANCELED'"
                link
                size="small"
                :icon="RefreshRight"
                title="重试"
                @click="jobAction(job, 'retry')"
              />
              <el-icon v-if="job.status === 'DONE' && job.errorCode" class="warn-icon"><WarningFilled /></el-icon>
              <el-button
                v-if="job.status === 'DONE' && job.result"
                link
                size="small"
                :icon="FolderOpened"
                :title="job.kind === 'CLIP_URL' ? '打开' : '打开所在位置'"
                @click="openJob(job)"
              />
            </div>
          </div>
        </template>
      </div>
    </div>

    <input ref="resumeInput" type="file" class="hidden-input" @change="onResumeFileChosen" />
  </div>
</template>

<style scoped>
.upload-drawer {
  position: fixed;
  right: 24px;
  /* 底部迷你播放条显示时往上让(MiniPlayer 写这个变量) */
  bottom: calc(24px + var(--player-bar-h, 0px));
  z-index: 2000;
}
.empty-jobs {
  padding: 6px 0;
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

/* 手机:容器撑到两边,面板随之满宽,底部留安全区 */
@media (max-width: 767px) {
  .upload-drawer {
    right: 12px;
    bottom: calc(12px + max(var(--sab), var(--player-bar-h, 0px)));
    left: 12px;
  }
  .panel {
    width: auto;
    max-height: 70vh;
  }
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
.task-status.warn {
  color: var(--el-color-warning);
}
.job-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.job-kind {
  margin-right: 4px;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.warn-icon {
  color: var(--el-color-warning);
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
