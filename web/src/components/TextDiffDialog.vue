<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RefreshLeft } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { TextFileDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { diffLines, diffRows, diffStats, type DiffRow } from '@/utils/textDiff'
import { formatTime } from '@/utils/format'

/**
 * 历史版本「和现在比较」(doc/24、doc/25):按行标出这个版本到现在删掉(红)和新增(绿)的,
 * 没变的折起来。只给文本文件,两边都不超过 2 MB;旧版本直接读它的下载地址。
 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{
  nodeId: string
  name: string
  version: { id: string; savedAt: string; downloadUrl: string } | null
}>()
const emit = defineEmits<{ (e: 'restore', id: string, savedAt: string): void }>()

const { isMobile } = useBreakpoints()
const loading = ref(false)
const error = ref<string | null>(null)
const rows = ref<DiffRow[]>([])
const stats = ref({ added: 0, removed: 0 })
const tooMany = ref(false)
/** 点开的折叠段 */
const expanded = ref(new Set<number>())
let full: ReturnType<typeof diffLines> = null

async function load() {
  const v = props.version
  if (!v) return
  loading.value = true
  error.value = null
  tooMany.value = false
  expanded.value = new Set()
  rows.value = []
  try {
    const [current, old] = await Promise.all([
      request(TextFileDocument, { nodeId: props.nodeId }).then((r) => r.textFile.content),
      fetch(v.downloadUrl).then(async (r) => {
        if (!r.ok) throw new Error(`读不到这个版本(HTTP ${r.status})`)
        try {
          return new TextDecoder('utf-8', { fatal: true }).decode(await r.arrayBuffer())
        } catch {
          throw new Error('这个版本不是 UTF-8 文本,没法比较')
        }
      }),
    ])
    full = diffLines(old, current)
    if (!full) {
      tooMany.value = true
      return
    }
    stats.value = diffStats(full)
    rows.value = diffRows(full)
  } catch (err) {
    error.value = err instanceof Error && !('response' in err) ? err.message : errorText(err, '读取失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => [visible.value, props.version?.id] as const,
  ([open]) => {
    if (open) void load()
  },
  { immediate: true },
)

/** 点「… N 行没变」展开那一段 */
const shown = computed<DiffRow[]>(() => {
  if (!full || expanded.value.size === 0) return rows.value
  const out: DiffRow[] = []
  for (const r of rows.value) {
    if (r.kind === 'skip' && expanded.value.has(r.key)) out.push(...full.slice(r.key - r.count, r.key))
    else out.push(r)
  }
  return out
})

function expand(key: number) {
  expanded.value = new Set([...expanded.value, key])
}

const title = computed(() => (props.version ? `${formatTime(props.version.savedAt)} 的版本 → 现在` : ''))
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="isMobile ? '100%' : '880px'"
    :fullscreen="isMobile"
    top="6vh"
    class="diff-dialog"
    append-to-body
  >
    <div class="diff-head">
      <span class="diff-name">{{ name }}</span>
      <span v-if="!loading && !error && !tooMany" class="diff-stats">
        <span class="plus">+{{ stats.added }}</span>
        <span class="minus">−{{ stats.removed }}</span>
      </span>
    </div>
    <div v-loading="loading" class="diff-body">
      <p v-if="error" class="diff-note">{{ error }}</p>
      <p v-else-if="tooMany" class="diff-note">改动太多,没法逐行比较。可以下载这个版本自己对照。</p>
      <p v-else-if="!loading && stats.added === 0 && stats.removed === 0" class="diff-note">和现在一模一样</p>
      <div v-else class="diff-table" role="table">
        <template v-for="(r, i) in shown" :key="r.kind === 'skip' ? `s${r.key}` : i">
          <button v-if="r.kind === 'skip'" type="button" class="diff-skip" @click="expand(r.key)">… {{ r.count }} 行没变,点开看</button>
          <div v-else class="diff-row" :class="r.kind" role="row">
            <span class="no">{{ r.oldNo ?? '' }}</span>
            <span class="no">{{ r.newNo ?? '' }}</span>
            <span class="mark">{{ r.kind === 'add' ? '+' : r.kind === 'del' ? '−' : '' }}</span>
            <span class="text">{{ r.text }}</span>
          </div>
        </template>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
        v-if="version"
        type="primary"
        :icon="RefreshLeft"
        @click="emit('restore', version.id, version.savedAt)"
      >
        恢复这个版本
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.diff-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin: -8px 0 10px;
}
.diff-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.diff-stats {
  display: inline-flex;
  gap: 8px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.plus {
  color: var(--el-color-success);
}
.minus {
  color: var(--el-color-danger);
}
.diff-body {
  min-height: 160px;
  max-height: 68vh;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.diff-note {
  padding: 32px 16px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.diff-table {
  font: 12.5px/1.6 ui-monospace, SFMono-Regular, Menlo, Consolas, 'Microsoft YaHei Mono', monospace;
  min-width: max-content;
}
.diff-row {
  display: grid;
  grid-template-columns: 44px 44px 18px 1fr;
}
.diff-row .no {
  padding-right: 8px;
  text-align: right;
  color: var(--el-text-color-placeholder);
  user-select: none;
}
.diff-row .mark {
  text-align: center;
  user-select: none;
}
.diff-row .text {
  padding-right: 12px;
  white-space: pre;
}
.diff-row.add {
  background: var(--el-color-success-light-9);
}
.diff-row.add .mark {
  color: var(--el-color-success);
}
.diff-row.del {
  background: var(--el-color-danger-light-9);
}
.diff-row.del .mark {
  color: var(--el-color-danger);
}
.diff-skip {
  display: block;
  width: 100%;
  padding: 2px 0 2px 106px;
  border: 0;
  border-top: 1px dashed var(--el-border-color-lighter);
  border-bottom: 1px dashed var(--el-border-color-lighter);
  text-align: left;
  font: inherit;
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
  cursor: pointer;
}
</style>
