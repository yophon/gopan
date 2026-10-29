<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { AiProvidersDocument, DeleteAiProviderDocument, type AiProviderFieldsFragment } from '@/api/gen/graphql'
import { loadMode, saveMode, type ConnectionMode } from '@/assistant/state'
import { isDesktop } from '@/desktop/bridge'
import AiProviderForm from './AiProviderForm.vue'

/** 助手设置:连接方式(这台设备)、自己的模型服务;权限在「MCP 访问」里 */
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'mode', mode: ConnectionMode): void }>()

const router = useRouter()
const queryClient = useQueryClient()
const { data } = useQuery({ queryKey: ['aiProviders'], queryFn: () => request(AiProvidersDocument), enabled: visible })
const site = computed(() => data.value?.aiProviders.filter((p) => p.site) ?? [])
const mine = computed(() => data.value?.aiProviders.filter((p) => !p.site) ?? [])

const mode = ref<ConnectionMode>(loadMode())
function setMode(v: ConnectionMode) {
  mode.value = v
  saveMode(v)
  emit('mode', v)
}

const formOpen = ref(false)
const editing = ref<AiProviderFieldsFragment | null>(null)
function add() {
  editing.value = null
  formOpen.value = true
}
function edit(p: AiProviderFieldsFragment) {
  editing.value = p
  formOpen.value = true
}
async function remove(p: AiProviderFieldsFragment) {
  try {
    await ElMessageBox.confirm(`删除「${p.name}」?用它的对话之后要换个服务接着聊。`, '删除模型服务', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await request(DeleteAiProviderDocument, { id: p.id })
    void queryClient.invalidateQueries({ queryKey: ['aiProviders'] })
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err, '删除失败'))
  }
}

function openPermissions() {
  visible.value = false
  void router.replace({ query: { ...router.currentRoute.value.query, panel: 'mcp' } })
}

const fmt = (n: number) => (n >= 10000 ? `${Math.round(n / 1000) / 10} 万` : String(n))
</script>

<template>
  <el-dialog v-model="visible" title="助手设置" width="min(560px, 94vw)" append-to-body>
    <section class="block">
      <h4>连接方式(这台设备)</h4>
      <el-radio-group :model-value="mode" @update:model-value="(v) => setMode(v as ConnectionMode)">
        <el-radio value="proxy">经服务器转发</el-radio>
        <el-radio value="direct">直接连接</el-radio>
      </el-radio-group>
      <p class="note">
        <template v-if="mode === 'proxy'">key 不下发到这台设备,公共电脑用这个。</template>
        <template v-else-if="isDesktop()">由桌面端直接请求模型服务,key 不进网页。</template>
        <template v-else>浏览器直连要模型服务允许跨域(Anthropic 允许,很多 OpenAI 兼容服务不允许)。</template>
        站点提供的服务总是经服务器转发。
      </p>
    </section>

    <section v-if="site.length" class="block">
      <h4>站点提供</h4>
      <div v-for="p in site" :key="p.id" class="row">
        <div class="main">
          <div class="name">{{ p.name }}</div>
          <div class="meta">{{ p.models.join('、') }}</div>
        </div>
        <span class="usage">今天 {{ fmt(p.usedTokens) }}<template v-if="p.dailyTokens"> / {{ fmt(p.dailyTokens) }}</template></span>
      </div>
    </section>

    <section class="block">
      <h4>我的模型服务</h4>
      <div v-for="p in mine" :key="p.id" class="row">
        <div class="main">
          <div class="name">{{ p.name }}<el-tag v-if="!p.hasKey" size="small" type="warning" class="tag">没填 key</el-tag></div>
          <div class="meta">{{ p.baseUrl }} · {{ p.models.join('、') }}</div>
        </div>
        <el-button text :icon="Edit" title="修改" @click="edit(p)" />
        <el-button text :icon="Delete" title="删除" @click="remove(p)" />
      </div>
      <el-button :icon="Plus" @click="add">添加</el-button>
    </section>

    <template #footer>
      <el-button @click="openPermissions">权限设置</el-button>
      <el-button type="primary" @click="visible = false">完成</el-button>
    </template>
    <AiProviderForm v-model="formOpen" :provider="editing" @saved="queryClient.invalidateQueries({ queryKey: ['aiProviders'] })" />
  </el-dialog>
</template>

<style scoped>
.block + .block {
  margin-top: 18px;
}
h4 {
  margin: 0 0 8px;
  font-size: 14px;
}
.note {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.main {
  flex: 1;
  min-width: 0;
}
.name {
  font-weight: 500;
}
.tag {
  margin-left: 6px;
}
.meta {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.usage {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.row + .el-button,
.block > .el-button {
  margin-top: 8px;
}
</style>
