<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { RenameNodesDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { canApply, planRename } from '@/utils/batchRename'
import type { RenamePlanItem, RenameProblem, RenameRule, RenameSource } from '@/utils/batchRename'

/**
 * 批量重命名(doc/27):按规则命名(* 原名、# 序号)、替换文字、改扩展名三种,下面实时列出改名前后;
 * 有冲突、不合法、一项都没变时不能提交。整批一次提交,服务端要么全改要么都不改。
 */

const props = defineProps<{
  /** 选中的条目,按列表里的顺序(序号按这个顺序编) */
  items: RenameSource[]
  /** 同一个文件夹里已加载的全部名字(提前发现和这批之外的同名) */
  siblings: string[]
}>()

const emit = defineEmits<{ done: [] }>()
const visible = defineModel<boolean>({ required: true })

const { isMobile } = useBreakpoints()

type Mode = 'template' | 'replace' | 'extension'
const mode = ref<Mode>('template')
const template = ref('')
const start = ref(1)
const find = ref('')
const replace = ref('')
const ext = ref('')
const saving = ref(false)

watch(visible, (open) => {
  if (!open) return
  mode.value = 'template'
  template.value = ''
  start.value = 1
  find.value = ''
  replace.value = ''
  ext.value = ''
})

/** 还没填规则时不改名(预览全是原名) */
const rule = computed<RenameRule | null>(() => {
  switch (mode.value) {
    case 'template':
      return template.value.trim() ? { kind: 'template', template: template.value, start: start.value ?? 1 } : null
    case 'replace':
      return find.value ? { kind: 'replace', find: find.value, replace: replace.value } : null
    case 'extension':
      return { kind: 'extension', ext: ext.value }
  }
  return null
})

const plan = computed<RenamePlanItem[]>(() =>
  rule.value
    ? planRename(props.items, rule.value, props.siblings)
    : props.items.map((i) => ({ id: i.id, oldName: i.name, newName: i.name, changed: false, problem: null })),
)
const changedCount = computed(() => plan.value.filter((p) => p.changed).length)
const ready = computed(() => canApply(plan.value))

const PROBLEMS: Record<RenameProblem, string> = {
  invalid: '名字不合法',
  duplicate: '和这批里的另一项重名',
  exists: '这里已经有同名的了',
}

async function submit() {
  if (!ready.value || saving.value) return
  saving.value = true
  try {
    const items = plan.value.filter((p) => p.changed).map((p) => ({ id: p.id, name: p.newName }))
    await request(RenameNodesDocument, { items })
    ElMessage.success(`已重命名 ${items.length} 项`)
    visible.value = false
    emit('done')
  } catch (err) {
    ElMessage.error(errorText(err, '重命名失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`批量重命名 ${items.length} 项`"
    :width="isMobile ? '100%' : '640px'"
    :fullscreen="isMobile"
    append-to-body
    class="batch-rename"
  >
    <el-radio-group v-model="mode" class="br-modes">
      <el-radio-button value="template">按规则命名</el-radio-button>
      <el-radio-button value="replace">替换文字</el-radio-button>
      <el-radio-button value="extension">改扩展名</el-radio-button>
    </el-radio-group>

    <div v-if="mode === 'template'" class="br-fields">
      <el-input v-model="template" placeholder="例如 第##集、*-##" clearable aria-label="新名字" />
      <el-input-number v-model="start" :min="0" :max="999999" controls-position="right" class="br-start" aria-label="起始序号" />
      <p class="br-tip"><code>*</code> 是原来的名字,<code>#</code> 是序号(<code>##</code> 是 01、02…),扩展名保持不变</p>
    </div>
    <div v-else-if="mode === 'replace'" class="br-fields">
      <el-input v-model="find" placeholder="查找" clearable aria-label="查找" />
      <el-input v-model="replace" placeholder="替换为(留空就是删掉)" clearable aria-label="替换为" />
    </div>
    <div v-else class="br-fields">
      <el-input v-model="ext" placeholder="新扩展名,如 jpg(留空去掉扩展名)" clearable aria-label="新扩展名" />
      <p class="br-tip">文件夹不受影响</p>
    </div>

    <ol class="br-preview" aria-label="改名预览">
      <li v-for="p in plan" :key="p.id" :class="{ same: !p.changed, bad: p.problem }">
        <span class="br-old" :title="p.oldName">{{ p.oldName }}</span>
        <span class="br-arrow">→</span>
        <span class="br-new" :title="p.newName">{{ p.newName }}</span>
        <span v-if="p.problem" class="br-problem">{{ PROBLEMS[p.problem] }}</span>
      </li>
    </ol>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!ready" :loading="saving" @click="submit">
        {{ changedCount ? `重命名 ${changedCount} 项` : '重命名' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.br-modes {
  margin-bottom: 12px;
}
.br-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.br-fields > .el-input {
  flex: 1 1 200px;
}
.br-start {
  width: 120px;
}
.br-tip {
  flex-basis: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.br-tip code {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}
.br-preview {
  max-height: 320px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.br-preview li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  padding: 6px 12px;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.br-preview li:last-child {
  border-bottom: none;
}
.br-old,
.br-new {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.br-old {
  color: var(--el-text-color-secondary);
}
.br-arrow {
  color: var(--el-text-color-placeholder);
}
.br-preview li.same .br-new {
  color: var(--el-text-color-secondary);
}
.br-preview li.bad .br-new {
  color: var(--el-color-danger);
}
.br-problem {
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--el-color-danger);
}
@media (max-width: 767px) {
  .br-preview {
    max-height: none;
  }
}
</style>
