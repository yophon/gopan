<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Paperclip, Promotion } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

type Message = { id: string; text: string; time: string; mine: boolean }
const draft = ref('')
const messages = ref<Message[]>([])
const auth = useAuthStore()
const list = ref<HTMLElement | null>(null)

onMounted(async () => {
  try {
    const res = await fetch('/chat/messages', { headers: { Authorization: `Bearer ${auth.accessToken}` } })
    if (res.ok) messages.value = (await res.json()).reverse()
  } catch { /* offline state remains empty */ }
  void scrollBottom()
})

async function scrollBottom() {
  await nextTick()
  if (list.value) list.value.scrollTop = list.value.scrollHeight
}

function send() {
  const text = draft.value.trim()
  if (!text) return
  void (async () => {
    const res = await fetch('/chat/messages', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.accessToken}` }, body: JSON.stringify({ body: text }) })
    if (!res.ok) { ElMessage.error('消息发送失败'); return }
    const m = await res.json(); messages.value.push({ id: m.id, text: m.body, time: new Date(m.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), mine: true }); draft.value = ''; void scrollBottom()
  })()
}

function attach() {
  ElMessage.info('文件上传接口将在下一步接入，届时会复用 gopan 的分片/断点续传。')
}
</script>

<template>
  <div class="chat-page">
    <header class="chat-header">
      <div>
        <h2>我的设备</h2>
        <span>在电脑和手机之间传输消息与文件</span>
      </div>
      <el-tag type="success" effect="plain">已连接</el-tag>
    </header>
    <main ref="list" class="message-list">
      <div v-if="messages.length === 0" class="empty">发送一条消息，开始在设备之间传输</div>
      <div v-for="message in messages" :key="message.id" class="message-row" :class="{ mine: message.mine }">
        <div class="bubble">
          <div>{{ message.text }}</div>
          <time>{{ message.time }}</time>
        </div>
      </div>
    </main>
    <footer class="composer">
      <el-button text :icon="Paperclip" title="发送文件" @click="attach" />
      <el-input v-model="draft" placeholder="输入消息" @keyup.enter="send" />
      <el-button type="primary" :icon="Promotion" :disabled="!draft.trim()" @click="send">发送</el-button>
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
.message-row { display: flex; margin: 8px 0; }
.message-row.mine { justify-content: flex-end; }
.bubble { max-width: min(70%, 560px); background: var(--el-fill-color-light); border-radius: 12px; padding: 10px 13px; line-height: 1.5; }
.mine .bubble { background: var(--el-color-primary); color: white; }
time { display: block; font-size: 11px; opacity: .65; margin-top: 4px; }
.composer { display: flex; gap: 8px; padding-top: 12px; border-top: 1px solid var(--el-border-color-light); }
@media (max-width: 640px) { .composer :deep(.el-button) { padding: 8px; } .bubble { max-width: 85%; } }
</style>
