<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DataBoard,
  Document,
  Download,
  Folder,
  Grid,
  Headset,
  Memo,
  Paperclip,
  Picture,
  Promotion,
  Reading,
  VideoCamera,
} from '@element-plus/icons-vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ChatFolderInfoDocument,
  ChatMessagesDocument,
  EnsureChatFolderDocument,
  NodeDownloadUrlDocument,
  SendChatMessageDocument,
} from '@/api/gen/graphql'
import { openPreview } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import { enqueueFiles, setUploadDoneListener } from '@/uploader/manager'
import { mergeChatMessages, type ChatMsg } from '@/utils/chat'
import { formatBytes } from '@/utils/format'

// ---------- 会话指针(模块级,upload 完成回调与组件共享) ----------

/** 「我的设备」文件夹 id;upload 完成后据此判断是否发文件消息 */
let chatFolderId: string | null = null

// ---------- 消息列表(vue-query 轮询 + 本地去重合并) ----------

const messages = ref<ChatMsg[]>([])

const { data } = useQuery({
  queryKey: ['chatMessages'],
  // 每次全量拉最新 200 条,与本地去重合并(append-only,不会出现真删)
  queryFn: () => request(ChatMessagesDocument, { limit: 200 }).then((r) => r.chatMessages),
  refetchInterval: 5000,
})
watch(data, (incoming) => {
  if (!incoming) return
  messages.value = mergeChatMessages(messages.value, incoming)
})

// ---------- chatFolder 指针(只读查询,不触发创建) ----------

const { data: folderData } = useQuery({
  queryKey: ['chatFolder'],
  queryFn: () => request(ChatFolderInfoDocument).then((r) => r.chatFolder),
})
// 用户此前已经在聊天页传过文件 → 指针落库,这里读回来以便 upload 回调判定
watch(folderData, (f) => {
  if (f) chatFolderId = f.id
})

const queryClient = useQueryClient()

// ---------- 发送文本 ----------

const draft = ref('')
const { isMobile } = useBreakpoints()

// ---------- 手机键盘 ----------
// iOS 上键盘弹起时布局视口不会变小,固定 100% 高度的聊天页会把输入框盖在键盘下面。
// 用 visualViewport 的真实高度顶替页面高度,键盘一弹一收都跟着变。
const viewportHeight = ref<number | null>(null)

function syncViewport() {
  viewportHeight.value = window.visualViewport?.height ?? null
}

onMounted(() => {
  if (!window.visualViewport) return
  syncViewport()
  window.visualViewport.addEventListener('resize', syncViewport)
  window.visualViewport.addEventListener('scroll', syncViewport)
})

onBeforeUnmount(() => {
  window.visualViewport?.removeEventListener('resize', syncViewport)
  window.visualViewport?.removeEventListener('scroll', syncViewport)
})

const pageHeightStyle = computed(() =>
  isMobile.value && viewportHeight.value ? { height: `${viewportHeight.value}px` } : {},
)

const sendMutation = useMutation({
  mutationFn: (body: string) => request(SendChatMessageDocument, { body }),
  onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['chatMessages'] }),
})

function send() {
  const body = draft.value.trim()
  if (!body || !sendMutation.isIdle.value) return
  draft.value = ''
  sendMutation.mutate(body, {
    onError: () => {
      draft.value = body
      ElMessage.error('消息发送失败')
    },
  })
}

// ---------- 附件:上传到「我的设备」→ 完成后自动发文件消息 ----------

setUploadDoneListener((task) => {
  // 只有发到「我的设备」文件夹的上传才转成文件消息
  if (chatFolderId === null || task.parentId !== chatFolderId) return
  if (!task.nodeId) return
  const nodeId = task.nodeId
  void request(SendChatMessageDocument, { nodeId })
    .then(() => queryClient.invalidateQueries({ queryKey: ['chatMessages'] }))
    .catch(() => ElMessage.error('发送文件消息失败'))
})

function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.onchange = () => {
    const files = Array.from(input.files ?? [])
    if (!files.length) return
    void sendFiles(files)
  }
  input.click()
}

async function sendFiles(files: File[]) {
  try {
    // 幂等创建「我的设备」文件夹(指针已在库,改名/移动后仍复用)
    const res = await request(EnsureChatFolderDocument)
    const folder = res.ensureChatFolder
    chatFolderId = folder.id
    enqueueFiles(files, folder.id)
    ElMessage.success(`已加入 ${files.length} 个上传任务`)
  } catch (err) {
    ElMessage.error(errorText(err, '无法创建「我的设备」文件夹'))
  }
}

// ---------- 文件卡片 / 预览 / 下载 ----------

function showThumb(m: ChatMsg): boolean {
  const n = m.node
  if (!n) return false
  const k = n.preview?.kind
  return (k === 'IMAGE' || k === 'VIDEO') && !!n.preview?.thumbUrl
}

function fileIcon(m: ChatMsg) {
  const n = m.node
  const k = n?.preview?.kind
  const name = n?.name ?? ''
  switch (k) {
    case 'IMAGE': return Picture
    case 'VIDEO': return VideoCamera
    case 'AUDIO': return Headset
    case 'PDF': return Reading
    case 'TEXT': return Memo
    case 'OFFICE': {
      const ext = name.split('.').pop()?.toLowerCase() ?? ''
      if (['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return Grid
      if (['ppt', 'pptx', 'odp'].includes(ext)) return DataBoard
      return Document
    }
    default: {
      const ext = name.split('.').pop()?.toLowerCase() ?? ''
      if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return Folder
      return Document
    }
  }
}

/** 点文件卡片:拿当前聊天里全部文件节点组预览列表,index 对齐 */
function openChatFile(m: ChatMsg) {
  const n = m.node
  if (!n) return
  const files = messages.value.filter((x) => x.node).map((x) => x.node!.id)
  const idx = files.indexOf(n.id)
  if (idx >= 0) openPreview(files, idx)
}

async function onDownload(m: ChatMsg) {
  const n = m.node
  if (!n) return
  try {
    const res = await request(NodeDownloadUrlDocument, { id: n.id })
    const url = res.node.downloadUrl
    if (!url) {
      ElMessage.error('无法获取下载链接')
      return
    }
    const a = document.createElement('a')
    a.href = url
    a.download = n.name
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (err) {
    ElMessage.error(errorText(err, '获取下载链接失败'))
  }
}

function timeOf(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div class="chat-page" :style="pageHeightStyle">
    <header class="chat-header">
      <div>
        <h2>传输助手</h2>
        <span>在电脑和手机之间传输消息与文件</span>
      </div>
      <el-tag type="success" effect="plain">已连接</el-tag>
    </header>

    <main class="message-list">
      <div v-if="messages.length === 0" class="empty">发一条消息或传一个文件</div>
      <template v-for="message in messages" :key="message.id">
        <div v-if="message.node" class="message-row">
          <div class="file-card" @click="openChatFile(message)">
            <img
              v-if="showThumb(message)"
              class="file-thumb"
              :src="message.node.preview.thumbUrl!"
              :alt="message.node.name"
              loading="lazy"
            />
            <el-icon v-else class="file-icon"><component :is="fileIcon(message)" /></el-icon>
            <div class="file-meta">
              <div class="file-name">{{ message.node.name }}</div>
              <div class="file-size">{{ formatBytes(message.node.size) }}</div>
            </div>
            <el-icon class="dl" title="下载" @click.stop="onDownload(message)"><Download /></el-icon>
          </div>
          <time>{{ timeOf(message.createdAt) }}</time>
        </div>
        <div v-else class="message-row">
          <div class="bubble">{{ message.body }}</div>
          <time>{{ timeOf(message.createdAt) }}</time>
        </div>
      </template>
    </main>

    <footer class="composer">
      <el-button text :icon="Paperclip" title="发送文件" @click="pickFile" />
      <el-input v-model="draft" placeholder="输入消息" @keyup.enter="send" />
      <el-button type="primary" :icon="Promotion" :disabled="!draft.trim() || !sendMutation.isIdle.value" @click="send">
        发送
      </el-button>
    </footer>
  </div>
</template>

<style scoped>
.chat-page { height: 100%; display: flex; flex-direction: column; max-width: 900px; margin: 0 auto; }
.chat-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--el-border-color-light); padding: 4px 0 14px; }
h2 { margin: 0 0 4px; font-size: 20px; }
.chat-header span { color: var(--el-text-color-secondary); font-size: 13px; }
.message-list { flex: 1; overflow: auto; padding: 20px 4px; }
.empty { text-align: center; color: var(--el-text-color-secondary); margin-top: 30vh; }
.message-row { display: flex; flex-direction: column; align-items: flex-start; margin: 12px 0; }
.bubble { max-width: min(70%, 560px); background: var(--el-fill-color-light); border-radius: 12px; padding: 10px 13px; line-height: 1.5; word-break: break-word; }
.file-card { display: flex; align-items: center; gap: 10px; max-width: min(84%, 480px); background: var(--el-fill-color-light); border-radius: 12px; padding: 8px 12px; cursor: pointer; }
.file-card:hover { background: var(--el-fill-color-darker); }
.file-thumb { width: 44px; height: 44px; object-fit: cover; border-radius: 8px; }
.file-icon { width: 40px; height: 40px; font-size: 28px; color: var(--el-text-color-secondary); flex-shrink: 0; }
.file-meta { min-width: 0; flex: 1; }
.file-name { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.file-size { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.dl { color: var(--el-text-color-secondary); cursor: pointer; flex-shrink: 0; }
.dl:hover { color: var(--el-color-primary); }
time { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 4px; }
.composer { display: flex; gap: 8px; padding-top: 12px; border-top: 1px solid var(--el-border-color-light); }
@media (max-width: 767px) { .composer :deep(.el-button) { padding: 8px; } .bubble { max-width: 85%; } }
</style>