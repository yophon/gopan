<script setup lang="ts">
import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { AssistantAccessDocument, SetAssistantAccessDocument, type AssistantPermission } from '@/api/gen/graphql'

/**
 * 「MCP 访问」里固定的「助手」一项(doc/33 一):每组三选一 —— 允许直接做、询问先弹确认卡、拒绝不给它用。
 * 拒绝由服务端管;询问由助手面板管。能把东西发到外面的组改成允许时提示一句。
 */
defineProps<{ rootLabel: string }>()
const emit = defineEmits<{ (e: 'edit-root'): void }>()

const queryClient = useQueryClient()
const { data, isFetching } = useQuery({ queryKey: ['assistantAccess'], queryFn: () => request(AssistantAccessDocument) })
const access = computed(() => data.value?.assistantAccess ?? null)

const OPTIONS: { value: AssistantPermission; label: string }[] = [
  { value: 'ALLOW', label: '允许' },
  { value: 'ASK', label: '询问' },
  { value: 'DENY', label: '拒绝' },
]

async function save(disabled: boolean, groups: { key: string; permission: AssistantPermission }[]) {
  try {
    const r = await request(SetAssistantAccessDocument, { disabled, groups })
    queryClient.setQueryData(['assistantAccess'], { assistantAccess: r.setAssistantAccess })
  } catch (err) {
    ElMessage.error(errorText(err, '保存失败'))
  }
}

async function setGroup(key: string, permission: AssistantPermission, outbound: boolean) {
  if (!access.value) return
  if (permission === 'ALLOW' && outbound) {
    try {
      await ElMessageBox.confirm(
        '这组能把东西发到外面(公开链接、访问任意网址)。助手读到的文档里要是藏着坏指令,可能借它把内容带出去。确定不再询问?',
        '改成允许',
        { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  await save(access.value.disabled, [{ key, permission }])
}

function setDisabled(disabled: boolean) {
  if (access.value) void save(disabled, [])
}
</script>

<template>
  <div v-loading="isFetching && !access">
    <div v-if="access" class="top">
      <el-switch :model-value="!access.disabled" active-text="启用助手" @update:model-value="(v) => setDisabled(!v)" />
      <span class="root">目录范围 <el-button link type="primary" @click="emit('edit-root')">{{ rootLabel }}</el-button></span>
    </div>
    <div v-if="access" class="groups" :class="{ off: access.disabled }">
      <div v-for="g in access.groups" :key="g.key" class="group">
        <span class="label">{{ g.label }}<el-tag v-if="g.outbound" size="small" type="warning" effect="plain" class="tag">外发</el-tag></span>
        <el-radio-group
          :model-value="g.permission"
          size="small"
          :disabled="access.disabled"
          @update:model-value="(v) => setGroup(g.key, v as AssistantPermission, g.outbound)"
        >
          <el-radio-button v-for="o in OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>
  </div>
</template>

<style scoped>
.top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.root {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.groups.off {
  opacity: 0.6;
}
.group {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.tag {
  margin-left: 6px;
}
</style>
