<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Delete, FolderOpened, Link } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ClipDocument, DeleteNodesDocument, MarkClipReadDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { useAuthStore } from '@/stores/auth'
import { siteOf } from '@/utils/clips'
import { formatTime } from '@/utils/format'
import MarkdownFrame from '@/components/preview/MarkdownFrame.vue'

/**
 * 剪藏的阅读视图(doc/23 五.2):/md/ 渲染页 + 工具栏(已读、原网页、在文件夹中显示、删除)。
 * 打开几秒后自动标成已读;标回未读后这次不再自动标。
 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ clipId: string | null }>()

const router = useRouter()
const queryClient = useQueryClient()
const auth = useAuthStore()
const { isMobile } = useBreakpoints()
const open = computed(() => visible.value && !!props.clipId)

// 先查一次:顺手让过期的 access token 刷新,iframe 拿到的是新鲜的
const { data, isError, error } = useQuery({
  queryKey: computed(() => ['clip', props.clipId]),
  queryFn: () => request(ClipDocument, { id: props.clipId! }),
  enabled: open,
  staleTime: 0,
})
const clip = computed(() => (data.value?.clip.id === props.clipId ? data.value.clip : null))
const token = computed(() => auth.accessToken ?? '')

function refreshLists() {
  void queryClient.invalidateQueries({ queryKey: ['clips'] })
  void queryClient.invalidateQueries({ queryKey: ['clip'] })
  void queryClient.invalidateQueries({ queryKey: ['nodeClip'] })
}

async function setRead(read: boolean, quiet = false) {
  const c = clip.value
  if (!c) return
  try {
    await request(MarkClipReadDocument, { id: c.id, read })
    refreshLists()
  } catch (err) {
    if (!quiet) ElMessage.error(errorText(err, '操作失败'))
  }
}

// ---------- 打开几秒后标已读 ----------

const AUTO_READ_MS = 4000
let readTimer: ReturnType<typeof setTimeout> | null = null
let manualUnread = false

function clearReadTimer() {
  if (readTimer) clearTimeout(readTimer)
  readTimer = null
}

watch(
  () => [open.value, clip.value?.id, !!clip.value?.readAt] as const,
  ([isOpen, id, read], old) => {
    // 换了一篇、或重新打开:之前手动标的未读不再拦着
    if (!old || id !== old[1] || (isOpen && !old[0])) manualUnread = false
    clearReadTimer()
    if (!isOpen || !id || read || manualUnread) return
    readTimer = setTimeout(() => void setRead(true, true), AUTO_READ_MS)
  },
  { immediate: true },
)
onBeforeUnmount(clearReadTimer)

function toggleRead() {
  const c = clip.value
  if (!c) return
  manualUnread = !!c.readAt
  void setRead(!c.readAt)
}

// ---------- 其余操作 ----------

function showInFolder() {
  const n = clip.value?.node
  if (!n) return
  visible.value = false
  void router.push(n.parentId ? `/drive/${n.parentId}` : '/drive')
}

async function onDelete() {
  const c = clip.value
  if (!c?.node) return
  try {
    await ElMessageBox.confirm(`「${c.node.name}」会移进回收站,可以在回收站还原。图片文件夹(.assets)不动。`, '删除这篇剪藏?', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await request(DeleteNodesDocument, { ids: [c.node.id] })
    ElMessage.success('已放入回收站')
    visible.value = false
    refreshLists()
    void queryClient.invalidateQueries({ queryKey: ['clipSites'] })
    void queryClient.invalidateQueries({ queryKey: ['children'] })
    void queryClient.invalidateQueries({ queryKey: ['trash'] })
  } catch (err) {
    ElMessage.error(errorText(err, '删除失败'))
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    :size="isMobile ? '100%' : 'min(960px, 92vw)'"
    :with-header="false"
    append-to-body
    class="clip-reader"
  >
    <div class="cr">
      <header class="cr-bar">
        <div class="cr-top">
          <div class="cr-title">
            <span class="cr-name" :title="clip?.title">{{ clip?.title || '剪藏' }}</span>
            <span v-if="clip" class="cr-meta">
              {{ siteOf(clip) }}<template v-if="clip.byline"> · {{ clip.byline }}</template> · 剪于
              {{ formatTime(clip.createdAt) }}
            </span>
          </div>
          <!-- 手机上关闭放在标题旁边,桌面放在工具栏最后 -->
          <el-button text :icon="Close" class="close-m" title="关闭" aria-label="关闭" @click="visible = false" />
        </div>
        <div class="cr-actions">
          <el-button v-if="clip" size="small" @click="toggleRead">{{ clip.readAt ? '标为未读' : '标记已读' }}</el-button>
          <el-button
            v-if="clip"
            size="small"
            :icon="Link"
            tag="a"
            :href="clip.url"
            target="_blank"
            rel="noopener noreferrer"
            class="cr-link"
          >
            原网页
          </el-button>
          <el-button v-if="clip?.node" size="small" :icon="FolderOpened" @click="showInFolder">在文件夹中显示</el-button>
          <el-button v-if="clip?.node" size="small" type="danger" plain :icon="Delete" @click="onDelete">删除</el-button>
          <el-button size="small" text :icon="Close" class="close-d" title="关闭" aria-label="关闭" @click="visible = false" />
        </div>
      </header>
      <div class="cr-body">
        <el-empty v-if="isError" :description="errorText(error, '这篇剪藏不在了')" />
        <MarkdownFrame v-else-if="clip?.node && token" :node-id="clip.node.id" :token="token" />
        <el-empty v-else-if="clip" description="这篇的文件不在了(可能在回收站里)" />
        <div v-else v-loading="true" class="cr-loading" />
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.cr {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.cr-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
  padding: 10px 16px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.cr-top {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.close-m {
  display: none;
}
.cr-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cr-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 600;
}
.cr-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cr-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}
.cr-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.cr-link {
  text-decoration: none;
}
.cr-body {
  flex: 1;
  min-height: 0;
}
.cr-loading {
  height: 200px;
}

@media (max-width: 767px) {
  .cr-bar {
    flex-direction: column;
    align-items: stretch;
    padding-top: calc(10px + var(--sat));
  }
  .cr-actions {
    justify-content: flex-start;
  }
  .close-m {
    display: inline-flex;
    flex: none;
  }
  .close-d {
    display: none;
  }
  .cr-body {
    padding-bottom: var(--sab);
  }
}
</style>

<style>
.clip-reader .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
