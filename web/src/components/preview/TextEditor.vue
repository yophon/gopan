<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useEventListener } from '@vueuse/core'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { getErrorCode } from '@/api/client'
import { errorText } from '@/api/errors'
import { SaveTextFileDocument, TextFileDocument } from '@/api/gen/graphql'
import { insertIndent } from '@/utils/textEdit'

/**
 * 在线编辑一个文本文件(doc/25):整份读进来改,Ctrl+S 保存。保存时带上打开时的 sha256,
 * 在别处被改过了会问要不要仍然保存(被盖掉的那份留在历史版本里)。有没保存的修改时离开要确认,由外面问 [confirmLeave]。
 */

const props = defineProps<{ nodeId: string }>()
const emit = defineEmits<{
  (e: 'saved'): void
  (e: 'close'): void
  (e: 'dirty', dirty: boolean): void
}>()

const loading = ref(true)
const loadError = ref<string | null>(null)
const content = ref('')
const original = ref('')
const base = ref<string | null>(null)
const saving = ref(false)
const textarea = ref<HTMLTextAreaElement | null>(null)

const dirty = computed(() => !loading.value && content.value !== original.value)

function syncDirty() {
  emit('dirty', dirty.value)
}

onMounted(async () => {
  try {
    const res = await request(TextFileDocument, { nodeId: props.nodeId })
    content.value = original.value = res.textFile.content
    base.value = res.textFile.sha256
  } catch (err) {
    loadError.value = errorText(err, '读取失败')
  } finally {
    loading.value = false
  }
  await nextTick()
  textarea.value?.focus()
})

/** 正在进行的保存:离开时先等它存完,存成功了就不用再问 */
let inflight: Promise<void> | null = null

function save(force = false): Promise<void> {
  if (inflight) return inflight
  inflight = doSave(force).finally(() => (inflight = null))
  return inflight
}

async function doSave(force: boolean) {
  if (loading.value || loadError.value) return
  if (!dirty.value && !force) return
  saving.value = true
  try {
    const res = await request(SaveTextFileDocument, {
      nodeId: props.nodeId,
      content: content.value,
      baseSha256: force ? null : base.value,
    })
    base.value = res.saveTextFile.sha256 ?? null
    original.value = content.value
    syncDirty()
    ElMessage.success('已保存,原来的内容留在历史版本里')
    emit('saved')
  } catch (err) {
    if (getErrorCode(err) === 'EDIT_CONFLICT') {
      saving.value = false
      try {
        await ElMessageBox.confirm(
          '这个文件在你打开之后又被改过了。仍然保存会盖掉那次改动,那次的内容会留在历史版本里。',
          '文件被改过了',
          { confirmButtonText: '仍然保存', cancelButtonText: '取消', type: 'warning' },
        )
        await doSave(true)
      } catch {
        // 取消
      }
      return
    }
    ElMessage.error(errorText(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

function onInput() {
  syncDirty()
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    void save()
    return
  }
  // Tab 插两个空格(Shift+Tab 照旧切换焦点)
  if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey && textarea.value) {
    e.preventDefault()
    const el = textarea.value
    const next = insertIndent(content.value, el.selectionStart, el.selectionEnd)
    content.value = next.text
    syncDirty()
    void nextTick(() => el.setSelectionRange(next.cursor, next.cursor))
  }
}

// 页面里任何地方按 Ctrl+S 都存(焦点不在编辑框时浏览器会弹「另存为」)
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && e.target !== textarea.value) {
    e.preventDefault()
    void save()
  }
})

// 关标签页 / 刷新时有没保存的修改:浏览器自己的确认框
useEventListener(window, 'beforeunload', (e: BeforeUnloadEvent) => {
  if (dirty.value) e.preventDefault()
})

onBeforeUnmount(() => emit('dirty', false))

/** 有没保存的修改时问一下;返回 true = 可以离开 */
async function confirmLeave(): Promise<boolean> {
  if (inflight) await inflight
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('改动还没保存,离开就丢了。', '放弃修改?', {
      confirmButtonText: '放弃',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

async function onCancel() {
  if (await confirmLeave()) emit('close')
}

defineExpose({ confirmLeave })
</script>

<template>
  <div class="text-editor">
    <div class="te-bar">
      <span class="te-status" :class="{ dirty }">{{ loading ? '读取中…' : dirty ? '未保存' : '已保存' }}</span>
      <span class="te-hint">Ctrl+S 保存</span>
      <span class="te-spacer" />
      <el-button size="small" :icon="Close" @click="onCancel">退出编辑</el-button>
      <el-button size="small" type="primary" :icon="Check" :loading="saving" :disabled="!dirty" @click="save()">保存</el-button>
    </div>
    <div v-if="loadError" class="te-error">
      <el-empty :description="loadError" />
    </div>
    <textarea
      v-else
      ref="textarea"
      v-model="content"
      v-loading="loading"
      class="te-area"
      spellcheck="false"
      autocapitalize="off"
      autocomplete="off"
      :disabled="loading"
      aria-label="文件内容"
      @input="onInput"
      @keydown="onKeydown"
    />
  </div>
</template>

<style scoped>
.text-editor {
  display: flex;
  flex-direction: column;
  width: min(960px, 100%);
  height: 100%;
  margin: 0 auto;
  border-radius: 12px;
  overflow: hidden;
  background: var(--el-bg-color);
}
.te-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.te-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.te-status {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.te-status.dirty {
  color: var(--gp-accent);
  font-weight: 600;
}
.te-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.te-spacer {
  flex: 1;
}
.te-area {
  flex: 1;
  width: 100%;
  min-height: 0;
  padding: 16px 20px;
  border: none;
  outline: none;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 14px;
  line-height: 1.7;
  tab-size: 2;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
}
.te-error {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
@media (max-width: 767px) {
  .text-editor {
    border-radius: 0;
  }
  .te-hint {
    display: none;
  }
  .te-area {
    padding: 12px 14px;
    font-size: 15px;
  }
}
</style>
