<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, FolderAdd } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ChildrenDocument, CreateFolderDocument, NodeDocument } from '@/api/gen/graphql'
import type { ChildrenQuery } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import NodeIcon from '@/components/nodes/NodeIcon.vue'

/**
 * 选目标文件夹(移动 / 复制 / 解压 / 订阅存到哪)。像文件管理器那样一层层点进去:
 * 打开时就在当前所在的文件夹,上面是可点的路径,下面是子文件夹,点进去就是选它;
 * 没有合适的可以就地新建。被移动的节点本身不出现(进不去它的子树),更深的环由服务端 CYCLIC_MOVE 兜底。
 */

const props = defineProps<{
  /** 被移动/复制的节点 id,列表里排除 */
  excludeIds: string[]
  /** 复制 / 解压 / 选文件夹(订阅存到哪)模式只改文案,选择逻辑一致 */
  mode?: 'move' | 'copy' | 'extract' | 'pick'
  /** 能不能选根目录「我的文件」;订阅这类必须落在某个文件夹里的不能 */
  allowRoot?: boolean
  /** 打开时停在哪个文件夹(一般是当前所在的);不传就从根开始 */
  startId?: string | null
}>()

const TITLES = { move: '移动到', copy: '复制到', extract: '解压到', pick: '选择文件夹' } as const
const VERBS = { move: '移动到', copy: '复制到', extract: '解压到', pick: '存到' } as const

const { isMobile } = useBreakpoints()
const queryClient = useQueryClient()

const emit = defineEmits<{
  /** path:选中文件夹的路径(根为 /),选文件夹模式拿来显示 */
  confirm: [targetParentId: string | null, path: string]
}>()

const visible = defineModel<boolean>({ required: true })

interface Crumb {
  id: string
  name: string
}

/** 正在看的文件夹(null = 根)与它的路径 */
const trail = ref<Crumb[]>([])
const here = computed<string | null>(() => trail.value.at(-1)?.id ?? null)
const hereName = computed(() => trail.value.at(-1)?.name ?? '我的文件')
const opening = ref(false)

async function chainOf(id: string | null): Promise<Crumb[]> {
  const chain: Crumb[] = []
  let cur = id
  while (cur) {
    const res = await request(NodeDocument, { id: cur })
    chain.unshift({ id: res.node.id, name: res.node.name })
    cur = res.node.parentId ?? null
  }
  return chain
}

watch(visible, async (v) => {
  if (!v) return
  opening.value = true
  try {
    // 起点是被移动的东西本身(比如从它里面打开)就退到根
    const start = props.startId && !props.excludeIds.includes(props.startId) ? props.startId : null
    trail.value = await chainOf(start).catch(() => [])
  } finally {
    opening.value = false
  }
})

async function foldersOf(parentId: string | null) {
  const out: ChildrenQuery['children']['items'] = []
  let cursor: string | null = null
  do {
    const res: ChildrenQuery = await request(ChildrenDocument, { parentId, cursor })
    out.push(...res.children.items.filter((n) => n.kind === 'FOLDER'))
    cursor = res.children.nextCursor ?? null
  } while (cursor)
  return out
}

const { data: folders, isPending } = useQuery({
  queryKey: ['folderPicker', here],
  queryFn: () => foldersOf(here.value),
  enabled: visible,
})

const shown = computed(() => (folders.value ?? []).filter((n) => !props.excludeIds.includes(n.id)))

function enter(node: { id: string; name: string }) {
  trail.value = [...trail.value, { id: node.id, name: node.name }]
}

function jump(index: number) {
  trail.value = trail.value.slice(0, index)
}

async function newFolder() {
  try {
    const { value } = await ElMessageBox.prompt('', '新建文件夹', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputValue: '新建文件夹',
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const res = await request(CreateFolderDocument, { parentId: here.value, name: value.trim() })
    void queryClient.invalidateQueries({ queryKey: ['children'] })
    void queryClient.invalidateQueries({ queryKey: ['folderPicker'] })
    enter({ id: res.createFolder.id, name: res.createFolder.name })
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err, '新建文件夹失败'))
  }
}

/** 移动到它原来就在的文件夹没有意义 */
const sameAsStart = computed(() => props.mode === 'move' && here.value === (props.startId ?? null))
const canConfirm = computed(() => !opening.value && !sameAsStart.value && (props.allowRoot !== false || here.value !== null))

function onConfirm() {
  if (!canConfirm.value) return
  emit('confirm', here.value, '/' + trail.value.map((c) => c.name).join('/'))
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="TITLES[props.mode ?? 'move']"
    width="min(460px, 94vw)"
    :fullscreen="isMobile"
    append-to-body
    class="folder-picker"
  >
    <nav class="fp-trail">
      <button type="button" class="fp-crumb" :class="{ current: trail.length === 0 }" @click="jump(0)">我的文件</button>
      <template v-for="(c, i) in trail" :key="c.id">
        <span class="fp-sep">/</span>
        <button type="button" class="fp-crumb" :class="{ current: i === trail.length - 1 }" @click="jump(i + 1)">{{ c.name }}</button>
      </template>
    </nav>
    <div v-loading="opening || (isPending && !folders)" class="fp-list">
      <button v-for="f in shown" :key="f.id" type="button" class="fp-row" @click="enter(f)">
        <NodeIcon :node="f" :size="24" />
        <span class="fp-name">{{ f.name }}</span>
        <el-icon class="fp-arrow"><ArrowRight /></el-icon>
      </button>
      <div v-if="!opening && folders && shown.length === 0" class="fp-empty">这里没有子文件夹</div>
    </div>
    <template #footer>
      <div class="fp-footer">
        <el-button text :icon="FolderAdd" @click="newFolder">新建文件夹</el-button>
        <span class="fp-spacer" />
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="!canConfirm" :title="sameAsStart ? '已经在这个文件夹里了' : undefined" @click="onConfirm">
          {{ VERBS[props.mode ?? 'move'] }}「{{ hereName }}」
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.fp-trail {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  margin: -6px 0 10px;
}
.fp-crumb {
  max-width: 160px;
  padding: 3px 6px;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--gp-ink-3);
  font: inherit;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.fp-crumb:hover {
  background: var(--el-fill-color-light);
  color: var(--gp-ink);
}
.fp-crumb.current {
  color: var(--gp-ink);
  font-weight: 600;
}
.fp-sep {
  color: var(--el-border-color-darker);
}
.fp-list {
  display: flex;
  flex-direction: column;
  min-height: 220px;
  max-height: 46vh;
  overflow: auto;
  margin: 0 -8px;
}
.fp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 0 10px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.fp-row:hover {
  background: var(--el-fill-color-light);
}
.fp-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fp-arrow {
  color: var(--el-text-color-placeholder);
}
.fp-empty {
  padding: 60px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.fp-footer {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fp-footer :deep(.el-button + .el-button) {
  margin-left: 0;
}
.fp-spacer {
  flex: 1;
}
.fp-footer .el-button--primary {
  max-width: 220px;
  overflow: hidden;
}
.fp-footer .el-button--primary :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
}
@media (max-width: 767px) {
  .fp-list {
    max-height: none;
    flex: 1;
  }
}
</style>
