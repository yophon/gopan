<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { AdminAiProvidersDocument, DeleteAiProviderDocument, type AiProviderFieldsFragment } from '@/api/gen/graphql'
import AiProviderForm from './AiProviderForm.vue'

/** 管理页:站点提供的模型服务(doc/33 一)。家里人登录就能用,只能经服务器转发,每人每天有额度 */
const queryClient = useQueryClient()
const { data } = useQuery({ queryKey: ['adminAiProviders'], queryFn: () => request(AdminAiProvidersDocument) })
const list = computed(() => data.value?.adminAiProviders ?? [])

const formOpen = ref(false)
const editing = ref<AiProviderFieldsFragment | null>(null)
function open(p: AiProviderFieldsFragment | null) {
  editing.value = p
  formOpen.value = true
}
function saved() {
  void queryClient.invalidateQueries({ queryKey: ['adminAiProviders'] })
  void queryClient.invalidateQueries({ queryKey: ['aiProviders'] })
}
async function remove(p: AiProviderFieldsFragment) {
  try {
    await ElMessageBox.confirm(`删除「${p.name}」?大家就用不了它了。`, '删除模型服务', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await request(DeleteAiProviderDocument, { id: p.id })
    saved()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err, '删除失败'))
  }
}
const fmt = (n: number) => (n === 0 ? '不限' : n >= 10000 ? `${Math.round(n / 1000) / 10} 万 token` : `${n} token`)
</script>

<template>
  <div class="ai-providers">
    <h4 class="section-title">助手的模型服务</h4>
    <div v-for="p in list" :key="p.id" class="row">
      <div class="main">
        <div class="name">{{ p.name }}<el-tag v-if="!p.hasKey" size="small" type="warning" class="tag">没填 key</el-tag></div>
        <div class="meta">{{ p.baseUrl }} · {{ p.models.join('、') }} · 每人每天 {{ fmt(p.dailyTokens) }}</div>
      </div>
      <el-button text :icon="Edit" title="修改" @click="open(p)" />
      <el-button text :icon="Delete" title="删除" @click="remove(p)" />
    </div>
    <el-button :icon="Plus" @click="open(null)">添加</el-button>
    <AiProviderForm v-model="formOpen" :provider="editing" site @saved="saved" />
  </div>
</template>

<style scoped>
.ai-providers {
  margin: 16px 0;
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
.row + .el-button,
.section-title + .el-button {
  margin-top: 8px;
}
</style>
