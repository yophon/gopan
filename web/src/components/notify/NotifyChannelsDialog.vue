<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Delete, EditPen, Plus, Promotion } from '@element-plus/icons-vue'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  CreateNotifyChannelDocument,
  DeleteNotifyChannelDocument,
  NotifyChannelsDocument,
  NotifyEventKindsDocument,
  TestNotifyChannelDocument,
  UpdateNotifyChannelDocument,
} from '@/api/gen/graphql'
import type { NotifyChannelFieldsFragment } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatRelative } from '@/utils/format'
import {
  channelFormFrom,
  channelInput,
  channelKindMeta,
  channelKinds,
  emptyChannelForm,
  switchChannelKind,
  validateChannelForm,
} from '@/utils/notifyChannels'
import type { ChannelForm, ChannelKindValue } from '@/utils/notifyChannels'

type Channel = NotifyChannelFieldsFragment

/**
 * 推送设置(doc/22 三.2):Webhook / ntfy / Bark / Telegram,每个渠道选推哪些事件、能发测试。
 * 密钥只写不读:编辑时不动就保持原值,点「清除」才清掉。
 */

const visible = defineModel<boolean>({ required: true })
const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: ['notifyChannels'],
  queryFn: () => request(NotifyChannelsDocument),
  enabled: visible,
})
const channels = computed(() => data.value?.notifyChannels ?? [])

const { data: eventsData } = useQuery({
  queryKey: ['notifyEventKinds'],
  queryFn: () => request(NotifyEventKindsDocument),
  enabled: visible,
  staleTime: Infinity,
})
const eventKinds = computed(() => eventsData.value?.notifyEventKinds ?? [])
const eventLabel = computed(() => new Map(eventKinds.value.map((e) => [e.kind, e.label])))

function invalidate() {
  void queryClient.invalidateQueries({ queryKey: ['notifyChannels'] })
}

// ---------- 编辑 ----------

const editing = ref<{ id: string | null; hasSecret: boolean; secretHint: string } | null>(null)
const form = ref<ChannelForm>(emptyChannelForm('NTFY', []))
const saving = ref(false)
const meta = computed(() => channelKindMeta(form.value.kind))

watch(visible, (v) => {
  if (!v) editing.value = null
})

function startCreate() {
  form.value = emptyChannelForm('NTFY', eventKinds.value)
  editing.value = { id: null, hasSecret: false, secretHint: '' }
}

function startEdit(ch: Channel) {
  form.value = channelFormFrom(ch)
  editing.value = { id: ch.id, hasSecret: ch.hasSecret, secretHint: ch.secretHint }
}

function onKindChange(kind: string | number | boolean | undefined) {
  form.value = switchChannelKind(form.value, kind as ChannelKindValue)
}

const secretPlaceholder = computed(() => {
  const e = editing.value
  if (e?.hasSecret && !form.value.clearSecret) {
    return e.secretHint ? `已设置(末 4 位 ${e.secretHint}),留空不改` : '已设置,留空不改'
  }
  return meta.value.secretRequired ? '必填' : '选填'
})

async function onSave() {
  const e = editing.value
  if (!e) return
  const problem = validateChannelForm(form.value, e.hasSecret)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  saving.value = true
  try {
    const input = channelInput(form.value)
    if (e.id) await request(UpdateNotifyChannelDocument, { id: e.id, input })
    else await request(CreateNotifyChannelDocument, { input })
    ElMessage.success('已保存')
    editing.value = null
    invalidate()
  } catch (err) {
    ElMessage.error(getErrorMessage(err) ?? errorText(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

// ---------- 列表操作 ----------

const togglingId = ref<string | null>(null)

async function onToggle(ch: Channel, enabled: string | number | boolean) {
  togglingId.value = ch.id
  try {
    // 整表单提交,密钥不传 = 保持原值
    await request(UpdateNotifyChannelDocument, {
      id: ch.id,
      input: channelInput({ ...channelFormFrom(ch), enabled: !!enabled }),
    })
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  } finally {
    togglingId.value = null
  }
}

const testingId = ref<string | null>(null)

async function onTest(ch: Channel) {
  testingId.value = ch.id
  try {
    await request(TestNotifyChannelDocument, { id: ch.id })
    ElMessage.success('测试消息已发出,去看看收到没有')
  } catch (err) {
    // NOTIFY_FAILED 带着对方的回应,原样给出来
    ElMessage.error({ message: getErrorMessage(err) ?? errorText(err, '发送失败'), duration: 8000 })
  } finally {
    testingId.value = null
    invalidate()
  }
}

async function onDelete(ch: Channel) {
  try {
    await ElMessageBox.confirm(`删除后不再往「${ch.name}」推送。`, '删除推送渠道?', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await request(DeleteNotifyChannelDocument, { id: ch.id })
    ElMessage.success('已删除')
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '删除失败'))
  }
}

function eventsText(ch: Channel): string {
  if (ch.events.length === 0) return '不推送任何事件'
  return ch.events.map((k) => eventLabel.value.get(k) ?? k).join('、')
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editing ? (editing.id ? '编辑推送渠道' : '添加推送渠道') : '推送设置'"
    width="min(620px, 94vw)"
    :fullscreen="isMobile"
    append-to-body
  >
    <!-- 列表 -->
    <template v-if="!editing">
      <p class="hint">
        通知除了在铃铛里看,还能推到手机:ntfy、Bark(iPhone)、Telegram,或者用 Webhook 接你自己的服务。
        同一件事短时间内重复发生只推一次,每个渠道每小时最多 20 条。
      </p>
      <div v-loading="isFetching && !data" class="channels">
        <el-empty v-if="!channels.length && !isFetching" description="还没有推送渠道" :image-size="80" />
        <div v-for="ch in channels" :key="ch.id" class="channel">
          <div class="ch-main">
            <div class="ch-line">
              <span class="ch-name">{{ ch.name }}</span>
              <el-tag size="small" effect="plain">{{ channelKindMeta(ch.kind).label }}</el-tag>
            </div>
            <div class="ch-sub">{{ eventsText(ch) }}</div>
            <div v-if="ch.lastError" class="ch-sub error" :title="ch.lastError">
              最近一次失败({{ formatRelative(ch.lastErrorAt) }}):{{ ch.lastError }}
            </div>
            <div v-else-if="ch.lastOkAt" class="ch-sub ok">最近一次成功 {{ formatRelative(ch.lastOkAt) }}</div>
          </div>
          <div class="ch-ops">
            <el-switch
              :model-value="ch.enabled"
              :loading="togglingId === ch.id"
              size="small"
              @change="(v: string | number | boolean) => onToggle(ch, v)"
            />
            <el-button link type="primary" :icon="Promotion" :loading="testingId === ch.id" @click="onTest(ch)">
              测试
            </el-button>
            <el-button link :icon="EditPen" @click="startEdit(ch)">编辑</el-button>
            <el-button link type="danger" :icon="Delete" @click="onDelete(ch)" />
          </div>
        </div>
      </div>
    </template>

    <!-- 表单 -->
    <el-form v-else :label-position="isMobile ? 'top' : 'right'" label-width="96px" class="ch-form" @submit.prevent>
      <el-form-item label="类型">
        <el-radio-group :model-value="form.kind" :disabled="!!editing.id" @change="onKindChange">
          <el-radio-button v-for="k in channelKinds" :key="k.value" :value="k.value">{{ k.label }}</el-radio-button>
        </el-radio-group>
        <div class="field-hint">{{ meta.hint }}</div>
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" maxlength="64" :placeholder="meta.label" />
      </el-form-item>
      <el-form-item v-if="meta.urlLabel" :label="meta.urlLabel">
        <el-input v-model="form.url" :placeholder="meta.urlDefault ?? 'https://'" />
      </el-form-item>
      <el-form-item v-if="meta.topicLabel" :label="meta.topicLabel">
        <el-input v-model="form.topic" :placeholder="meta.topicPlaceholder" />
      </el-form-item>
      <el-form-item :label="meta.secretLabel">
        <div class="secret-row">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            autocomplete="new-password"
            :disabled="form.clearSecret"
            :placeholder="secretPlaceholder"
          />
          <template v-if="editing.hasSecret">
            <el-button v-if="!form.clearSecret" link type="danger" @click="form.clearSecret = true; form.secret = ''">
              清除
            </el-button>
            <el-button v-else link @click="form.clearSecret = false">撤销清除</el-button>
          </template>
        </div>
        <div v-if="form.clearSecret" class="field-hint warn">保存后清掉原来的{{ meta.secretLabel }}</div>
      </el-form-item>
      <el-form-item label="推送事件">
        <el-checkbox-group v-model="form.events" class="events">
          <el-checkbox v-for="e in eventKinds" :key="e.kind" :value="e.kind">
            {{ e.label }}<span v-if="e.adminOnly" class="admin-only">(管理员)</span>
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>

    <template #footer>
      <template v-if="editing">
        <el-button :icon="ArrowLeft" @click="editing = null">返回</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
      <template v-else>
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" :icon="Plus" @click="startCreate">添加渠道</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.channels {
  min-height: 80px;
}
.channel {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ch-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ch-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ch-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ch-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ch-sub.error {
  color: var(--el-color-danger);
}
.ch-sub.ok {
  color: var(--el-color-success);
}
.ch-ops {
  display: flex;
  align-items: center;
  flex: none;
  gap: 4px;
}
.ch-ops :deep(.el-button + .el-button) {
  margin-left: 6px;
}
.field-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.field-hint.warn {
  color: var(--el-color-danger);
}
.secret-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.events {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 12px;
}
.admin-only {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 767px) {
  .channel {
    flex-wrap: wrap;
  }
  .ch-ops {
    width: 100%;
    justify-content: flex-end;
  }
  .events {
    grid-template-columns: 1fr;
  }
}
</style>
