<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Close, Delete, Picture, Plus, Setting } from '@element-plus/icons-vue'

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
 * 对话记录在 gopan 上,左上角「对话记录」切换;改东西前弹确认卡。
 * 能随消息发图(附件按钮、粘贴、拖进来,doc/35);回答里引用的文件能点,图片视频直接显示。
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

const contextLabel = computed(() => {
  const ctx = assistantContext.value
  if (!ctx) return ''
  const where = ctx.path === '/' ? '我的文件' : ctx.path
  return ctx.selection.length ? `${where} · 选中 ${ctx.selection.length} 项` : where
})

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
      <el-select v-if="options.length" v-model="pickValue" class="pick" size="small" :disabled="chat.running" placeholder="选模型">
        <el-option-group v-for="o in options" :key="o.provider.id" :label="o.provider.site ? `${o.provider.name}(站点提供)` : o.provider.name">
          <el-option v-for="m in o.models" :key="m" :value="`${o.provider.id}|${m}`" :label="`${o.provider.name} · ${m}`" />
        </el-option-group>
      </el-select>
      <span v-else class="pick" />
      <el-button text :icon="Clock" :type="showHistory ? 'primary' : undefined" title="对话记录" @click="showHistory = !showHistory" />
      <el-button text :icon="Plus" title="新对话" :disabled="chat.running" @click="startNew" />
      <el-button text :icon="Setting" title="设置" @click="settingsOpen = true" />
      <el-button text :icon="Close" title="关闭" @click="toggleAssistant(false)" />
    </header>

    <div v-if="showHistory" class="history">
      <el-empty v-if="!conversations.length" description="还没有对话" :image-size="60" />
      <div v-for="c in conversations" :key="c.id" class="conv" :class="{ active: chat.conversation?.id === c.id }" @click="pickConversation(c)">
        <div class="conv-main">
          <div class="conv-title">
            {{ c.title || '(没有标题)' }}
            <el-tag v-if="runBadge(c.runStatus)" size="small" :type="c.runStatus === 'WAITING' ? 'warning' : 'primary'" class="badge">{{ runBadge(c.runStatus) }}</el-tag>
          </div>
          <div class="conv-time">{{ formatTime(c.updatedAt) }}</div>
        </div>
        <el-button text size="small" :icon="Delete" title="删除" @click.stop="removeConversation(c.id)" />
      </div>
    </div>

    <div v-else ref="scroller" class="body">
      <div v-if="!providers.length" class="empty">
        <p>还没有可用的模型服务。</p>
        <el-button type="primary" @click="settingsOpen = true">添加模型服务</el-button>
      </div>
      <div v-else-if="!chat.items.length && !chat.loading" class="empty hints">
        <p>可以这样问:</p>
        <button v-for="h in ['把这几个文件改成日期开头', '上个月传的 PDF 放哪了', '给这个文件夹建个能上传的分享']" :key="h" type="button" class="hint" @click="input = h">{{ h }}</button>
      </div>
      <div v-if="chat.loading" class="loading" v-loading="true" />
      <template v-for="it in chat.items" :key="it.key">
        <div v-if="it.kind === 'user'" class="msg user">
          <div v-if="it.images?.length" class="sent-images">
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
          <template v-if="it.text">{{ it.text }}</template>
        </div>
        <AssistantReply v-else-if="it.kind === 'assistant'" class="msg bot" :text="it.text" />
        <div v-else-if="it.kind === 'tool'" class="tool" :class="it.status" :title="it.output.slice(0, 500)">
          <span class="mark">{{ statusMark[it.status] }}</span>{{ it.label ?? toolLabel(it.name, it.args) }}<span v-if="it.status === 'denied'" class="denied">(没做)</span>
        </div>
        <div v-else class="msg error">{{ it.text }}</div>
      </template>
      <AssistantReply v-if="chat.live" class="msg bot" :text="chat.live" />

      <div v-if="chat.approval" class="approval">
        <div class="approval-title">{{ chat.approval.description.title }}</div>
        <ul v-if="approvalNames.length" class="approval-list">
          <li v-for="(n, i) in approvalNames" :key="i">{{ n }}</li>
          <li v-if="chat.approval.description.ids.length > 20">……还有 {{ chat.approval.description.ids.length - 20 }} 项</li>
        </ul>
        <div v-if="chat.approval.description.detail" class="approval-detail">{{ chat.approval.description.detail }}</div>
        <div class="approval-actions">
          <el-button type="primary" size="small" @click="chat.approval.resolve('once')">允许</el-button>
          <el-button v-if="chat.approval.view.canAllowSession" size="small" @click="chat.approval.resolve('session')">这次对话都允许</el-button>
          <el-button size="small" @click="chat.approval.resolve('deny')">不行</el-button>
        </div>
      </div>

      <div v-if="chat.conflict" class="msg error">这个对话在别处有了新内容,请从「对话记录」重新打开。</div>
      <div v-else-if="chat.error" class="msg error">{{ chat.error }}</div>
    </div>

    <footer v-if="!showHistory" class="foot">
      <div v-if="contextLabel || serverAvailable" class="foot-row">
        <span class="context" :title="contextLabel">{{ contextLabel }}</span>
        <el-switch
          v-if="serverAvailable"
          :model-value="chat.onServer"
          size="small"
          active-text="在服务器上运行"
          :disabled="chat.running"
          title="开着时这一句交给服务器跑,关掉网页也照样跑完"
          @update:model-value="(v) => setOnServer(v === true)"
        />
      </div>
      <div v-if="pending.length" class="pending">
        <div v-for="(p, i) in pending" :key="i" class="pending-item">
          <img :src="p.url" alt="" />
          <button type="button" class="pending-remove" title="不发这张" @click="pending = pending.filter((_, j) => j !== i)">×</button>
        </div>
      </div>
      <div class="compose">
        <el-button text :icon="Picture" title="发图片" :disabled="!providers.length || pending.length >= MAX_IMAGES" @click="picker?.click()" />
        <input ref="picker" type="file" accept="image/*" multiple hidden @change="onPick" />
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 6 }"
          :placeholder="providers.length ? '让助手做点什么' : '先添加模型服务'"
          :disabled="!providers.length"
          @keydown="onKey"
          @paste="onPaste"
        />
        <el-button v-if="chat.running" @click="stop">停止</el-button>
        <el-button v-else type="primary" :disabled="!canSubmit" @click="submit">发送</el-button>
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
  box-shadow: var(--el-box-shadow-light);
}
/* 窗口不够宽时面板浮在内容上(AppShell 不再让出位置):影子重一点,看得出是盖在上面的 */
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
.head {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 8px 8px 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.head .el-button {
  margin-left: 0;
}
.pick {
  flex: 1;
  min-width: 0;
}
.history,
.body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
.conv {
  display: flex;
  align-items: center;
  padding: 8px;
  border-radius: 6px;
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
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.empty {
  margin-top: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.hints {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.hint {
  padding: 6px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 16px;
  background: none;
  color: var(--el-text-color-regular);
  cursor: pointer;
  font: inherit;
}
.hint:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.loading {
  height: 80px;
}
.msg {
  margin: 8px 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.bot {
  white-space: normal;
}
.sent-images {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 4px;
}
.sent-image {
  width: 72px;
  height: 72px;
  border-radius: 6px;
}
.pending {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}
.pending-item {
  position: relative;
  width: 56px;
  height: 56px;
}
.pending-item img {
  width: 100%;
  height: 100%;
  border-radius: 6px;
  object-fit: cover;
}
.pending-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--el-text-color-primary);
  color: var(--el-bg-color);
  font-size: 14px;
  line-height: 20px;
  cursor: pointer;
}
.msg.user {
  margin-left: 15%;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}
.msg.error {
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.tool {
  margin: 4px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.tool .mark {
  display: inline-block;
  width: 18px;
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
.approval {
  margin: 10px 0;
  padding: 12px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 10px;
  background: var(--el-color-warning-light-9);
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
  word-break: break-all;
}
.approval-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.approval-actions .el-button {
  margin-left: 0;
}
.foot {
  padding: 8px 12px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.foot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.badge {
  margin-left: 6px;
  vertical-align: 1px;
}
.context {
  overflow: hidden;
  min-width: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.compose {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}
.compose .el-button {
  margin-left: 0;
}
</style>
