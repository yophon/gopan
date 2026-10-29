<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  AdminCreateAiProviderDocument,
  CreateAiProviderDocument,
  UpdateAiProviderDocument,
  type AiProviderFieldsFragment,
  type AiProviderKind,
} from '@/api/gen/graphql'

/** 新建 / 修改一个模型服务(doc/33 三)。site:管理员配站点提供的,多一个每人每日额度 */
const props = defineProps<{ provider?: AiProviderFieldsFragment | null; site?: boolean }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'saved'): void }>()

const KINDS: { value: AiProviderKind; label: string; url: string }[] = [
  { value: 'OPENAI', label: 'OpenAI 兼容(DeepSeek、通义、Kimi、Ollama…)', url: 'https://api.deepseek.com/v1' },
  { value: 'ANTHROPIC', label: 'Anthropic(Claude)', url: 'https://api.anthropic.com' },
  { value: 'GOOGLE', label: 'Google(Gemini)', url: 'https://generativelanguage.googleapis.com/v1beta' },
  { value: 'OPENAI_RESPONSES', label: 'OpenAI Responses', url: 'https://api.openai.com/v1' },
]

const form = reactive({ name: '', kind: 'OPENAI' as AiProviderKind, baseUrl: '', models: '', key: '', dailyTokens: 0 })
const saving = reactive({ on: false })
const editing = computed(() => !!props.provider)

watch(
  visible,
  (open) => {
    if (!open) return
    const p = props.provider
    Object.assign(form, p
      ? { name: p.name, kind: p.kind, baseUrl: p.baseUrl, models: p.models.join('\n'), key: '', dailyTokens: p.dailyTokens }
      : { name: '', kind: 'OPENAI', baseUrl: '', models: '', key: '', dailyTokens: 0 })
  },
  { immediate: true },
)

const placeholder = computed(() => KINDS.find((k) => k.value === form.kind)?.url ?? '')

async function save() {
  const models = form.models.split(/[\n,]/).map((m) => m.trim()).filter(Boolean)
  const input = {
    name: form.name.trim(),
    kind: form.kind,
    baseUrl: form.baseUrl.trim() || placeholder.value,
    models,
    // 修改时留空 = 不变
    key: editing.value && form.key === '' ? null : form.key.trim(),
    dailyTokens: props.site ? Math.max(0, Math.round(form.dailyTokens)) : 0,
  }
  saving.on = true
  try {
    if (props.provider) await request(UpdateAiProviderDocument, { id: props.provider.id, input })
    else if (props.site) await request(AdminCreateAiProviderDocument, { input })
    else await request(CreateAiProviderDocument, { input })
    emit('saved')
    visible.value = false
  } catch (err) {
    ElMessage.error(errorText(err, '保存失败'))
  } finally {
    saving.on = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="editing ? '修改模型服务' : '添加模型服务'" width="min(520px, 92vw)" append-to-body>
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="名字">
        <el-input v-model="form.name" maxlength="60" placeholder="DeepSeek" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="form.kind">
          <el-option v-for="k in KINDS" :key="k.value" :value="k.value" :label="k.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="地址">
        <el-input v-model="form.baseUrl" :placeholder="placeholder" />
      </el-form-item>
      <el-form-item label="模型(一行一个,第一个是默认)">
        <el-input v-model="form.models" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="deepseek-chat" />
      </el-form-item>
      <el-form-item label="key">
        <el-input v-model="form.key" type="password" show-password autocomplete="off" :placeholder="editing && provider?.hasKey ? '留空不变' : 'sk-…'" />
      </el-form-item>
      <el-form-item v-if="site" label="每人每天的 token 上限(0 = 不限)">
        <el-input-number v-model="form.dailyTokens" :min="0" :step="100000" controls-position="right" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving.on" :disabled="!form.name.trim() || !form.models.trim()" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
