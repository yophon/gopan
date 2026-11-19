<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useEventListener } from '@vueuse/core'
import { ElMessage } from 'element-plus'
import { Bell, Delete, Plus } from '@element-plus/icons-vue'

import { getErrorCode, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { SaveTextFileDocument, TextFileDocument } from '@/api/gen/graphql'
import {
  createDeskNoteSync,
  deskNoteColors,
  formatRemind,
  isDue,
  splitLinks,
  type DeskNote,
  type DeskNoteOp,
} from '@/utils/desknote'

/**
 * DeskNote 便签(doc/39):把桌面便签同步上来的 json 画成清单,能勾选、改字、删、加、改标题。
 * 每一步先在界面上生效,再排队写回(带服务器上那份的 sha256)。写回时发现桌面端改过了:读最新的内容,
 * 把这一步在它上面重放一遍再存——不会盖掉桌面端的改动。那一条在别处已经改掉了就换成最新内容并提示。
 * 排队和重放在 createDeskNoteSync 里(有单测),这里只管界面。
 */

const props = defineProps<{ nodeId: string }>()
const emit = defineEmits<{ (e: 'saved'): void }>()

const loading = ref(true)
const loadError = ref<string | null>(null)
/** 界面上显示的(含还没存完的操作) */
const note = ref<DeskNote | null>(null)
const pending = ref(0)
const failed = ref(false)

const sync = createDeskNoteSync({
  load: async () => {
    const res = await request(TextFileDocument, { nodeId: props.nodeId })
    return { content: res.textFile.content, sha256: res.textFile.sha256 ?? null }
  },
  save: async (content, baseSha256) => {
    const res = await request(SaveTextFileDocument, { nodeId: props.nodeId, content, baseSha256 })
    emit('saved')
    return res.saveTextFile.sha256 ?? null
  },
  isConflict: (err) => getErrorCode(err) === 'EDIT_CONFLICT',
  onChange: () => {
    note.value = sync.view
    pending.value = sync.pending
    failed.value = sync.failed
  },
  onNotice: (level, message, err) => {
    if (level === 'warning') ElMessage.warning(message)
    else ElMessage.error(err ? errorText(err, message) : message)
  },
})

const colors = computed(() => (note.value ? deskNoteColors(note.value) : { bg: '#34424D', fg: '#A9B1B7' }))
const checkboxes = computed(() => note.value?.style?.checkboxes !== false)
const status = computed(() => (pending.value ? '保存中…' : failed.value ? '没存上' : '已同步'))

async function load(quiet = false) {
  try {
    if (!(await sync.load())) loadError.value = '这个文件不是 DeskNote 便签'
    else loadError.value = null
  } catch (err) {
    if (!quiet) loadError.value = errorText(err, '读取失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

// 切回这个页面(手机上从别的 App 回来)时拉一次最新的,桌面端刚改的能看到
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'visible' && !pending.value && editing.value === null) void load(true)
})

function run(op: DeskNoteOp) {
  void sync.run(op)
}

// ---------- 编辑 ----------

/** 正在改的:条目序号,或 'title' */
const editing = ref<number | 'title' | null>(null)
const draft = ref('')
const newText = ref('')
const editBox = ref<HTMLTextAreaElement | HTMLInputElement | null>(null)
/** 编辑框在 v-for 里:用函数 ref,只记当前那一个 */
function setEditBox(el: unknown) {
  if (el) editBox.value = el as HTMLTextAreaElement | HTMLInputElement
}

function fit() {
  const el = editBox.value
  if (el instanceof HTMLTextAreaElement) {
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }
}

async function startEdit(target: number | 'title') {
  if (!note.value) return
  editing.value = target
  draft.value = target === 'title' ? note.value.title : note.value.items[target].text
  await nextTick()
  editBox.value?.focus()
  fit()
}

function finishEdit(save: boolean) {
  const target = editing.value
  editing.value = null
  if (!save || target === null || !note.value) return
  if (target === 'title') {
    if (draft.value.trim() !== note.value.title) run({ kind: 'title', title: draft.value })
    return
  }
  const it = note.value.items[target]
  if (it && draft.value.trim() !== it.text) run({ kind: 'edit', index: target, text: it.text, newText: draft.value })
}

function onEditKey(e: KeyboardEvent) {
  if (e.isComposing) return // 输入法选字时的回车不算
  if (e.key === 'Escape') {
    e.preventDefault()
    finishEdit(false)
  } else if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    finishEdit(true)
  }
}

function toggle(i: number) {
  const it = note.value?.items[i]
  if (it) run({ kind: 'toggle', index: i, text: it.text, done: !it.done })
}

function remove(i: number) {
  const it = note.value?.items[i]
  if (it) run({ kind: 'remove', index: i, text: it.text })
}

function onAddKey(e: KeyboardEvent) {
  if (e.isComposing || e.key !== 'Enter') return
  e.preventDefault()
  if (!newText.value.trim()) return
  run({ kind: 'add', text: newText.value })
  newText.value = ''
}
</script>

<template>
  <div class="dn-wrap" v-loading="loading" element-loading-background="transparent">
    <el-empty v-if="loadError" :description="loadError" />
    <template v-else-if="note">
      <div class="dn-card" :style="{ '--dn-bg': colors.bg, '--dn-fg': colors.fg }">
        <header class="dn-head">
          <input
            v-if="editing === 'title'"
            :ref="setEditBox"
            v-model="draft"
            class="dn-title-input"
            aria-label="标题"
            @keydown="onEditKey"
            @blur="finishEdit(true)"
          />
          <h2 v-else class="dn-title" title="点一下改标题" @click="startEdit('title')">{{ note.title }}</h2>
          <span class="dn-status" :class="{ bad: failed }">{{ status }}</span>
        </header>

        <p v-if="!note.items.length" class="dn-empty">还没有条目,在下面添加</p>
        <ul class="dn-items">
          <li
            v-for="(it, i) in note.items"
            :key="`${i}:${it.text}`"
            class="dn-item"
            :class="{ done: checkboxes && it.done, editing: editing === i }"
          >
            <button
              v-if="checkboxes"
              class="dn-check"
              :class="{ on: it.done }"
              :aria-label="it.done ? '标为未完成' : '标为完成'"
              @click="toggle(i)"
            >
              <svg v-if="it.done" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8.5l3 3 6-7" /></svg>
            </button>
            <span v-else class="dn-dot" />
            <textarea
              v-if="editing === i"
              :ref="setEditBox"
              v-model="draft"
              class="dn-edit"
              rows="1"
              aria-label="条目内容"
              @input="fit"
              @keydown="onEditKey"
              @blur="finishEdit(true)"
            />
            <div v-else class="dn-text" title="点一下修改" @click="startEdit(i)">
              <template v-for="(seg, k) in splitLinks(it.text)" :key="k">
                <a v-if="seg.url" :href="seg.url" target="_blank" rel="noopener noreferrer" @click.stop>{{ seg.text }}</a>
                <template v-else>{{ seg.text }}</template>
              </template>
            </div>
            <span v-if="it.remind && editing !== i" class="dn-chip" :class="{ due: isDue(it) }">
              <el-icon :size="12"><Bell /></el-icon>{{ formatRemind(it.remind) }}
            </span>
            <button v-if="editing !== i" class="dn-del" aria-label="删除这一条" title="删除这一条" @click="remove(i)">
              <el-icon :size="15"><Delete /></el-icon>
            </button>
          </li>
        </ul>

        <label class="dn-add">
          <el-icon :size="16"><Plus /></el-icon>
          <input v-model="newText" placeholder="添加一条,回车保存" aria-label="添加一条" @keydown="onAddKey" />
        </label>
      </div>
      <p class="dn-foot">改动会同步到电脑上的 DeskNote(几分钟内);两边同时改也不会互相覆盖。</p>
    </template>
  </div>
</template>

<style scoped>
.dn-wrap {
  width: min(560px, 100%);
  max-height: 100%;
  margin: 0 auto;
  padding: 24px 16px;
  overflow-y: auto;
}
.dn-card {
  --dn-dim: color-mix(in srgb, var(--dn-fg) 55%, transparent);
  --dn-faint: color-mix(in srgb, var(--dn-fg) 12%, transparent);
  padding: 18px 20px 14px;
  border-radius: 12px;
  background: var(--dn-bg);
  color: var(--dn-fg);
  border: 1px solid color-mix(in srgb, var(--dn-fg) 14%, transparent);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.25);
  font-size: 15px;
  line-height: 1.5;
}
.dn-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.dn-title,
.dn-title-input {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  line-height: 1.4;
  color: inherit;
}
.dn-title {
  cursor: text;
  overflow-wrap: anywhere;
}
.dn-title-input,
.dn-edit,
.dn-add input {
  font: inherit;
  color: inherit;
  background: var(--dn-faint);
  border: none;
  outline: none;
  border-radius: 6px;
  padding: 2px 6px;
  margin: -2px -6px;
}
.dn-title-input {
  font-size: 19px;
  font-weight: 700;
}
.dn-status {
  flex: none;
  font-size: 12px;
  color: var(--dn-dim);
}
.dn-status.bad {
  color: #e55b5b;
}
.dn-empty {
  margin: 6px 0;
  color: var(--dn-dim);
}
.dn-items {
  list-style: none;
  margin: 0;
  padding: 0;
}
.dn-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 5px 6px;
  margin: 0 -6px;
  border-radius: 6px;
}
.dn-item:hover,
.dn-item.editing {
  background: var(--dn-faint);
}
.dn-check {
  flex: none;
  width: 18px;
  height: 18px;
  margin-top: 2.5px;
  padding: 0;
  border-radius: 5px;
  border: 1.5px solid var(--dn-dim);
  background: transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.dn-check.on {
  border-color: transparent;
  background: color-mix(in srgb, var(--dn-fg) 80%, transparent);
}
.dn-check svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: var(--dn-bg);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.dn-dot {
  flex: none;
  width: 5px;
  height: 5px;
  margin: 9.5px 6px 0;
  border-radius: 50%;
  background: var(--dn-dim);
}
.dn-text,
.dn-edit {
  flex: 1;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.dn-text {
  cursor: text;
}
.dn-text a {
  color: inherit;
  text-decoration: underline;
}
.dn-item.done .dn-text {
  opacity: 0.45;
  text-decoration: line-through;
}
.dn-edit {
  resize: none;
  overflow: hidden;
  line-height: 1.5;
}
.dn-chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 1px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 20px;
  background: var(--dn-faint);
  color: var(--dn-dim);
}
.dn-chip.due {
  background: rgba(229, 91, 91, 0.22);
  color: #e55b5b;
  font-weight: 600;
}
.dn-del {
  flex: none;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--dn-dim);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
}
.dn-item:hover .dn-del {
  opacity: 1;
}
.dn-del:hover {
  background: var(--dn-faint);
  color: #e55b5b;
}
.dn-add {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 6px 0 2px;
  color: var(--dn-dim);
  cursor: text;
}
.dn-add input {
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 4px 8px;
  background: transparent;
}
.dn-add input:focus {
  background: var(--dn-faint);
}
.dn-add input::placeholder {
  color: var(--dn-dim);
}
.dn-foot {
  margin: 12px 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 手机上没有悬停:删除按钮一直露着,淡一点 */
@media (hover: none) {
  .dn-del {
    opacity: 0.6;
  }
}
@media (max-width: 767px) {
  .dn-wrap {
    padding: 12px 10px;
  }
  .dn-card {
    padding: 14px 14px 10px;
    font-size: 16px;
  }
}
</style>
