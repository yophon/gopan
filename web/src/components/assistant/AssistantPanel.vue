<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Clock, Close, Cloudy, Delete, EditPen, Picture, Promotion, Setting, VideoPause } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { AiConversationsDocument, AiProvidersDocument, AiRunAvailableDocument, DeleteAiConversationDocument, NodeDocument } from '@/api/gen/graphql'
import { canSend, chat, newChat, openChat, send, setOnServer, stop } from '@/assistant/controller'
import { toolLabel } from '@/assistant/describe'
import { fitBudget, MAX_IMAGES, shrinkImage, type PendingImage } from '@/assistant/images'
import { assistantContext, loadMode, loadPick, savePick, toggleAssistant, type ConnectionMode } from '@/assistant/state'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatTime } from '@/utils/format'
import AssistantReply from './AssistantReply.vue'
import AssistantSettings from './AssistantSettings.vue'

/**
 * 助手面板(doc/33 一):贴在右边,开着也能继续点文件列表;手机上占满屏。
 * 对话记录在 gopan 上,右上角「对话记录」切换;改东西前弹确认卡。
 * 能随消息发图(附件按钮、粘贴、拖进来,doc/35);回答里引用的文件能点,图片视频直接显示。
 * 样子跟着 gopan 走:标题与通知抽屉一样,输入框是传输助手那样的胶囊,用户的话是同样的气泡。
 */

const { isMobile } = useBreakpoints()
const queryClient = useQueryClient()

const { data: providersData } = useQuery({ queryKey: ['aiProviders'], queryFn: () => request(AiProvidersDocument) })
const providers = computed(() => providersData.value?.aiProviders ?? [])

// 服务与模型:这台设备上次选的;不在了就用第一个
const pick = ref(loadPick())
const options = computed(() => providers.value.map((p) => ({ provider: p, models: p.models })))
const current = computed(() => {
  const p = providers.value.find((x) => x.id === pick.value?.provider) ?? providers.value[0]
  if (!p) return null
  const model = p.models.includes(pick.value?.model ?? '') ? pick.value!.model : p.models[0] ?? ''
  return { provider: p, model }
})
const pickValue = computed({
  get: () => (current.value ? `${current.value.provider.id}|${current.value.model}` : ''),
  set: (v: string) => {
    const [provider = '', model = ''] = v.split('|')
    pick.value = { provider, model }
    savePick(provider, model)
  },
})
const mode = ref<ConnectionMode>(loadMode())

// 在服务器上运行(doc/34):服务器装了运行环境才显示开关
const { data: runAvailable } = useQuery({ queryKey: ['aiRunAvailable'], queryFn: () => request(AiRunAvailableDocument), staleTime: 10 * 60_000 })
const serverAvailable = computed(() => runAvailable.value?.aiRunAvailable === true)
const runBadge = (status: string | null | undefined) =>
  status === 'WAITING' ? '等你确认' : status === 'RUNNING' || status === 'QUEUED' ? '运行中' : ''

// ---------- 对话记录 ----------
const showHistory = ref(false)
const { data: convData, refetch: refetchConvs } = useQuery({
  queryKey: ['aiConversations'],
  queryFn: () => request(AiConversationsDocument, { limit: 100 }),
  enabled: showHistory,
})
const conversations = computed(() => convData.value?.aiConversations ?? [])
function pickConversation(c: (typeof conversations.value)[number]) {
  showHistory.value = false
  void openChat(c)
  const p = providers.value.find((x) => x.id === c.providerId)
  if (p && c.model) pickValue.value = `${p.id}|${p.models.includes(c.model) ? c.model : p.models[0]}`
}
async function removeConversation(id: string) {
  try {
    await ElMessageBox.confirm('删除这个对话?', '删除对话', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await request(DeleteAiConversationDocument, { id })
    if (chat.conversation?.id === id) newChat()
    void refetchConvs()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err, '删除失败'))
  }
}
function startNew() {
  showHistory.value = false
  newChat()
}

// ---------- 发消息 ----------
const input = ref('')
const settingsOpen = ref(false)
const pending = ref<PendingImage[]>([])
const picker = ref<HTMLInputElement | null>(null)
const canSubmit = computed(() => (input.value.trim() !== '' || pending.value.length > 0) && canSend.value && !!current.value)

async function addImages(files: Iterable<File>) {
  for (const file of files) {
    if (!file.type.startsWith('image/')) continue
    if (pending.value.length >= MAX_IMAGES) {
      ElMessage.warning(`一次最多发 ${MAX_IMAGES} 张图`)
      return
    }
    try {
      pending.value = [...pending.value, { file, url: await shrinkImage(file) }]
    } catch (err) {
      ElMessage.error(errorText(err, '这张图读不了'))
    }
  }
}
function onPick(e: Event) {
  const el = e.target as HTMLInputElement
  void addImages(el.files ?? [])
  el.value = ''
}
function onPaste(e: ClipboardEvent) {
  const files = [...(e.clipboardData?.files ?? [])].filter((f) => f.type.startsWith('image/'))
  if (!files.length) return
  e.preventDefault()
  void addImages(files)
}
function onDrop(e: DragEvent) {
  void addImages(e.dataTransfer?.files ?? [])
}

async function submit() {
  const c = current.value
  if (!c || !canSubmit.value) return
  let images: string[] = []
  try {
    images = await fitBudget(pending.value)
  } catch (err) {
    ElMessage.error(errorText(err, '图片太大了'))
    return
  }
  const text = input.value
  input.value = ''
  pending.value = []
  void send(text, { provider: c.provider, model: c.model, mode: mode.value }, images).then(() => {
    void queryClient.invalidateQueries({ queryKey: ['aiConversations'] })
    void queryClient.invalidateQueries({ queryKey: ['aiProviders'] }) // 今天的用量
  })
}
function onKey(e: Event | KeyboardEvent) {
  if (!(e instanceof KeyboardEvent)) return
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    void submit()
  }
}

// 选中了文件才提一句(「这几个」指的是它们);在哪个文件夹不用说,模型那边知道
const selectionLabel = computed(() => {
  const n = assistantContext.value?.selection.length ?? 0
  return n ? `选中的 ${n} 项` : ''
})
const placeholder = computed(() =>
  !providers.value.length ? '先添加模型服务' : chat.onServer ? '让助手做点什么,交给服务器跑' : '让助手做点什么',
)

// ---------- 确认卡:把 id 换成名字 ----------
const approvalNames = ref<string[]>([])
watch(
  () => chat.approval,
  async (a) => {
    approvalNames.value = []
    if (!a) return
    const names = await Promise.all(
      a.description.ids.slice(0, 20).map((id) =>
        request(NodeDocument, { id }).then((r) => r.node.name).catch(() => '(找不到)'),
      ),
    )
    approvalNames.value = [...names, ...a.description.paths]
  },
)

// ---------- 滚到底 ----------
const scroller = ref<HTMLElement | null>(null)
watch(
  () => [chat.items.length, chat.live, chat.approval] as const,
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

const statusMark: Record<string, string> = { running: '…', ok: '✓', error: '✕', denied: '⊘' }
</script>

<template>
  <aside class="assistant" :class="{ mobile: isMobile }" aria-label="助手" @dragover.prevent @drop.prevent="onDrop">
    <header class="head">
      <div class="head-main">
        <span class="title">助手</span>
        <el-dropdown v-if="options.length" trigger="click" :disabled="chat.running" @command="(v: string) => (pickValue = v)">
          <button type="button" class="model" :title="current ? `${current.provider.name} · ${current.model}` : ''">
            <span class="model-name">{{ current?.model ?? '选模型' }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="model-menu">
              <template v-for="o in options" :key="o.provider.id">
                <li class="model-group">{{ o.provider.site ? `${o.provider.name}(站点提供)` : o.provider.name }}</li>
                <el-dropdown-item v-for="m in o.models" :key="m" :command="`${o.provider.id}|${m}`" :class="{ on: pickValue === `${o.provider.id}|${m}` }">
                  {{ m }}
                </el-dropdown-item>
              </template>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <span class="head-actions">
        <el-button link :icon="Clock" :class="{ on: showHistory }" title="对话记录" @click="showHistory = !showHistory" />
        <el-button link :icon="EditPen" title="新对话" :disabled="chat.running" @click="startNew" />
        <el-button link :icon="Setting" title="设置" @click="settingsOpen = true" />
        <el-button link :icon="Close" title="关闭" @click="toggleAssistant(false)" />
      </span>
    </header>

    <div v-if="showHistory" class="history">
      <el-empty v-if="!conversations.length" description="还没有对话" :image-size="60" />
      <div v-for="c in conversations" :key="c.id" class="conv" :class="{ active: chat.conversation?.id === c.id }" @click="pickConversation(c)">
        <div class="conv-main">
          <div class="conv-title">
            {{ c.title || '(没有标题)' }}
            <span v-if="runBadge(c.runStatus)" class="badge" :class="{ waiting: c.runStatus === 'WAITING' }">{{ runBadge(c.runStatus) }}</span>
          </div>
          <div class="conv-time">{{ formatTime(c.updatedAt) }}</div>
        </div>
        <el-button link size="small" :icon="Delete" title="删除" class="conv-delete" @click.stop="removeConversation(c.id)" />
      </div>
    </div>

    <div v-else ref="scroller" class="body">
      <div v-if="!providers.length" class="empty">
        <p>还没有可用的模型服务。</p>
        <el-button type="primary" round @click="settingsOpen = true">添加模型服务</el-button>
      </div>
      <div v-else-if="!chat.items.length && !chat.loading" class="empty hints">
        <button v-for="h in ['把这几个文件改成日期开头', '上个月传的 PDF 放哪了', '这张截图里报的什么错', '给这个文件夹建个能上传的分享']" :key="h" type="button" class="hint" @click="input = h">{{ h }}</button>
      </div>
      <div v-if="chat.loading" class="loading" v-loading="true" />
      <template v-for="it in chat.items" :key="it.key">
        <div v-if="it.kind === 'user'" class="row-user">
          <div v-if="it.images?.length" class="sent-images" :class="`n${Math.min(it.images.length, 3)}`">
            <el-image
              v-for="(src, i) in it.images"
              :key="i"
              :src="src"
              :preview-src-list="it.images"
              :initial-index="i"
              :z-index="3000"
              preview-teleported
              fit="cover"
              class="sent-image"
            />
          </div>
          <div v-if="it.text" class="bubble">{{ it.text }}</div>
        </div>
        <AssistantReply v-else-if="it.kind === 'assistant'" class="bot" :text="it.text" />
        <div v-else-if="it.kind === 'tool'" class="tool" :class="it.status" :title="it.output.slice(0, 500)">
          <span class="mark">{{ statusMark[it.status] }}</span>{{ it.label ?? toolLabel(it.name, it.args) }}<span v-if="it.status === 'denied'" class="denied">(没做)</span>
        </div>
        <div v-else class="notice error">{{ it.text }}</div>
      </template>
      <AssistantReply v-if="chat.live" class="bot" :text="chat.live" />
      <div v-else-if="chat.running && !chat.approval" class="thinking"><span /><span /><span /></div>

      <div v-if="chat.approval" class="approval">
        <div class="approval-title">{{ chat.approval.description.title }}</div>
        <ul v-if="approvalNames.length" class="approval-list">
          <li v-for="(n, i) in approvalNames" :key="i">{{ n }}</li>
          <li v-if="chat.approval.description.ids.length > 20">……还有 {{ chat.approval.description.ids.length - 20 }} 项</li>
        </ul>
        <div v-if="chat.approval.description.detail" class="approval-detail">{{ chat.approval.description.detail }}</div>
        <div class="approval-actions">
          <el-button type="primary" round size="small" @click="chat.approval.resolve('once')">允许</el-button>
          <el-button v-if="chat.approval.view.canAllowSession" round size="small" @click="chat.approval.resolve('session')">这次对话都允许</el-button>
          <el-button round size="small" @click="chat.approval.resolve('deny')">不行</el-button>
        </div>
      </div>

      <div v-if="chat.conflict" class="notice error">这个对话在别处有了新内容,请从「对话记录」重新打开。</div>
      <div v-else-if="chat.error" class="notice error">{{ chat.error }}</div>
    </div>

    <!-- 输入框和传输助手的一样是个胶囊:发图、在服务器上跑、输入、发送在一起 -->
    <footer v-if="!showHistory" class="composer">
      <div class="composer-box">
        <div v-if="pending.length || selectionLabel" class="composer-top">
          <span v-if="selectionLabel" class="selection">{{ selectionLabel }}</span>
          <div v-for="(p, i) in pending" :key="i" class="pending-item">
            <img :src="p.url" alt="" />
            <button type="button" class="pending-remove" title="不发这张" @click="pending = pending.filter((_, j) => j !== i)">×</button>
          </div>
        </div>
        <div class="composer-row">
          <el-button
            text
            circle
            :icon="Picture"
            title="发图片"
            aria-label="发图片"
            class="composer-icon"
            :disabled="!providers.length || pending.length >= MAX_IMAGES"
            @click="picker?.click()"
          />
          <el-button
            v-if="serverAvailable"
            text
            circle
            :icon="Cloudy"
            :title="chat.onServer ? '在服务器上运行(关掉网页也照样跑完);点一下改回本机' : '在本机运行;点一下交给服务器跑'"
            aria-label="在服务器上运行"
            class="composer-icon"
            :class="{ on: chat.onServer }"
            :disabled="chat.running"
            @click="setOnServer(!chat.onServer)"
          />
          <input ref="picker" type="file" accept="image/*" multiple hidden @change="onPick" />
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            resize="none"
            class="composer-input"
            :placeholder="placeholder"
            :disabled="!providers.length"
            @keydown="onKey"
            @paste="onPaste"
          />
          <el-button v-if="chat.running" circle :icon="VideoPause" title="停止" aria-label="停止" class="composer-send" @click="stop" />
          <el-button v-else type="primary" circle :icon="Promotion" title="发送" aria-label="发送" class="composer-send" :disabled="!canSubmit" @click="submit" />
        </div>
      </div>
    </footer>

    <AssistantSettings v-model="settingsOpen" @mode="(m) => (mode = m)" />
  </aside>
</template>

<style scoped>
.assistant {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  width: 420px;
  border-left: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
/* 窗口不够宽时面板浮在内容上(AppShell 不再让出位置):加影子,看得出是盖在上面的 */
@media (max-width: 1443px) {
  .assistant:not(.mobile) {
    box-shadow: var(--el-box-shadow-dark);
  }
}
.assistant.mobile {
  left: 0;
  width: auto;
  padding-top: var(--sat);
  padding-bottom: var(--sab);
  border-left: none;
}

/* 标题:和通知抽屉一样,大字 + 右边一排链接按钮 */
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px 8px;
}
.head-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.title {
  flex: none;
  font-size: 16px;
  font-weight: 600;
}
.model {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-secondary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.model:hover {
  color: var(--el-text-color-primary);
}
.model-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-actions {
  display: flex;
  flex: none;
  align-items: center;
}
.head-actions .el-button {
  font-size: 17px;
}
.head-actions .el-button + .el-button {
  margin-left: 14px;
}
.head-actions .el-button.on {
  color: var(--gp-accent);
}

/* 对话记录 */
.history,
.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 16px 12px;
}
.conv {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 -8px;
  padding: 9px 8px;
  border-radius: 10px;
  cursor: pointer;
}
.conv:hover,
.conv.active {
  background: var(--el-fill-color-light);
}
.conv-main {
  flex: 1;
  min-width: 0;
}
.conv-title {
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-time {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.conv-delete {
  opacity: 0;
  transition: opacity 0.15s;
}
.conv:hover .conv-delete {
  opacity: 1;
}
.badge {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  font-size: 11px;
  vertical-align: 1px;
}
.badge.waiting {
  background: color-mix(in srgb, var(--gp-accent) 14%, transparent);
  color: var(--gp-accent);
}

/* 对话 */
.empty {
  margin-top: 28px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.hints {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  margin-top: 16px;
}
.hint {
  padding: 10px 14px;
  border: none;
  border-radius: 12px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}
.hint:hover {
  background: var(--el-fill-color);
  color: var(--el-text-color-primary);
}
.loading {
  height: 80px;
}
/* 用户的话:和传输助手同样的气泡,靠右 */
.row-user {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  margin: 14px 0 10px;
}
.bubble {
  max-width: 85%;
  padding: 9px 13px;
  border-radius: 12px;
  background: var(--el-fill-color-light);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.sent-images {
  display: grid;
  gap: 4px;
  width: min(85%, 300px);
}
.sent-images.n1 {
  grid-template-columns: 1fr;
  width: min(70%, 220px);
}
.sent-images.n2 {
  grid-template-columns: repeat(2, 1fr);
}
.sent-images.n3 {
  grid-template-columns: repeat(3, 1fr);
}
.sent-image {
  display: block;
  aspect-ratio: 1;
  width: 100%;
  border-radius: 8px;
}
.sent-images.n1 .sent-image {
  aspect-ratio: auto;
  max-height: 240px;
}
.bot {
  margin: 6px 0;
  line-height: 1.65;
  word-break: break-word;
}
.tool {
  margin: 3px 0;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
}
.tool .mark {
  display: inline-block;
  width: 16px;
}
.tool.error .mark {
  color: var(--el-color-danger);
}
.tool.ok .mark {
  color: var(--el-color-success);
}
.denied {
  margin-left: 4px;
}
.notice {
  margin: 8px 0;
  padding: 9px 12px;
  border-radius: 10px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.notice.error {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
/* 等模型开口:三个点 */
.thinking {
  display: flex;
  gap: 4px;
  margin: 10px 0;
}
.thinking span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  animation: blink 1.2s infinite var(--gp-ease);
}
.thinking span:nth-child(2) {
  animation-delay: 0.2s;
}
.thinking span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}
/* 确认卡:和文件卡片同一种底色,不用警告色 */
.approval {
  margin: 10px 0;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
}
.approval-title {
  font-weight: 600;
}
.approval-list {
  max-height: 160px;
  margin: 8px 0 0;
  padding-left: 18px;
  overflow-y: auto;
  font-size: 13px;
}
.approval-detail {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}
.approval-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.approval-actions .el-button {
  margin-left: 0;
}

/* 输入框:传输助手那样的胶囊 */
.composer {
  padding: 6px 12px 12px;
}
.composer-box {
  padding: 5px;
  border: 1px solid var(--el-border-color);
  border-radius: 24px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
  transition: border-color 0.15s;
}
.composer-box:focus-within {
  border-color: var(--el-color-primary-light-5);
}
.composer-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 4px 6px 6px;
}
.selection {
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.composer-row {
  display: flex;
  align-items: flex-end;
  gap: 2px;
}
.composer-input :deep(.el-textarea__inner) {
  padding: 7px 4px;
  border: none;
  box-shadow: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.5;
}
.composer-icon,
.composer-send {
  flex: none;
  width: 36px;
  height: 36px;
  margin-left: 0;
}
.composer-icon {
  color: var(--el-text-color-secondary);
  font-size: 18px;
}
.composer-icon.on {
  color: var(--gp-accent);
}
.composer-send.is-disabled {
  --el-button-disabled-bg-color: transparent;
  --el-button-disabled-border-color: transparent;
  --el-button-disabled-text-color: var(--el-text-color-placeholder);
}
.pending-item {
  position: relative;
  width: 52px;
  height: 52px;
}
.pending-item img {
  width: 100%;
  height: 100%;
  border-radius: 10px;
  object-fit: cover;
}
.pending-remove {
  position: absolute;
  top: -5px;
  right: -5px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--el-text-color-primary);
  color: var(--el-bg-color);
  font-size: 13px;
  line-height: 18px;
  cursor: pointer;
}
</style>

<style>
/* 模型菜单(挂在 body 上):分组名是小字,选中的那个加粗 */
.model-menu .model-group {
  padding: 8px 16px 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  list-style: none;
}
.model-menu .el-dropdown-menu__item.on {
  font-weight: 600;
}
</style>
