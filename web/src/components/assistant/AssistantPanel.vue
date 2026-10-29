<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Close, Delete, Plus, Setting } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { AiConversationsDocument, AiProvidersDocument, DeleteAiConversationDocument, NodeDocument } from '@/api/gen/graphql'
import { canSend, chat, newChat, openChat, send, stop } from '@/assistant/controller'
import { toolLabel } from '@/assistant/describe'
import { assistantContext, loadMode, loadPick, savePick, toggleAssistant, type ConnectionMode } from '@/assistant/state'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatTime } from '@/utils/format'
import { miniMarkdown } from '@/utils/miniMarkdown'
import AssistantSettings from './AssistantSettings.vue'

/**
 * 助手面板(doc/33 一):贴在右边,开着也能继续点文件列表;手机上占满屏。
 * 对话记录在 gopan 上,左上角「对话记录」切换;改东西前弹确认卡。
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
function submit() {
  const c = current.value
  if (!c || !input.value.trim() || !canSend.value) return
  const text = input.value
  input.value = ''
  void send(text, { provider: c.provider, model: c.model, mode: mode.value }).then(() => {
    void queryClient.invalidateQueries({ queryKey: ['aiConversations'] })
    void queryClient.invalidateQueries({ queryKey: ['aiProviders'] }) // 今天的用量
  })
}
function onKey(e: Event | KeyboardEvent) {
  if (!(e instanceof KeyboardEvent)) return
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
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
  <aside class="assistant" :class="{ mobile: isMobile }" aria-label="助手">
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
          <div class="conv-title">{{ c.title || '(没有标题)' }}</div>
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
        <div v-if="it.kind === 'user'" class="msg user">{{ it.text }}</div>
        <!-- miniMarkdown 先整段转义再加标签 -->
        <div v-else-if="it.kind === 'assistant'" class="msg bot md" v-html="miniMarkdown(it.text)" />
        <div v-else-if="it.kind === 'tool'" class="tool" :class="it.status" :title="it.output.slice(0, 500)">
          <span class="mark">{{ statusMark[it.status] }}</span>{{ toolLabel(it.name, it.args) }}<span v-if="it.status === 'denied'" class="denied">(没做)</span>
        </div>
        <div v-else class="msg error">{{ it.text }}</div>
      </template>
      <div v-if="chat.live" class="msg bot md" v-html="miniMarkdown(chat.live)" />

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
      <div v-if="contextLabel" class="context" :title="contextLabel">{{ contextLabel }}</div>
      <div class="compose">
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 6 }"
          :placeholder="providers.length ? '让助手做点什么' : '先添加模型服务'"
          :disabled="!providers.length"
          @keydown="onKey"
        />
        <el-button v-if="chat.running" @click="stop">停止</el-button>
        <el-button v-else type="primary" :disabled="!input.trim() || !canSend || !current" @click="submit">发送</el-button>
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
.msg.md {
  white-space: normal;
}
.md :deep(p),
.md :deep(ul),
.md :deep(ol),
.md :deep(pre) {
  margin: 0 0 6px;
}
.md :deep(ul),
.md :deep(ol) {
  padding-left: 20px;
}
.md :deep(code) {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.92em;
}
.md :deep(pre) {
  overflow-x: auto;
  padding: 8px;
  border-radius: 6px;
  background: var(--el-fill-color);
}
.md :deep(pre code) {
  padding: 0;
  background: none;
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
.context {
  overflow: hidden;
  margin-bottom: 6px;
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
