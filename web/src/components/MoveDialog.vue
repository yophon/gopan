<script setup lang="ts">
import { computed, ref } from 'vue'
import type Node from 'element-plus/es/components/tree/src/model/node'

import { request } from '@/api/client'
import { ChildrenDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'

/**
 * 移动目标选择器:el-tree 懒加载目录树。
 * 被移动的节点本身会从树中过滤掉(其子树自然不可达),
 * 更深的环移动由服务端 CYCLIC_MOVE 兜底。
 */

interface TreeItem {
  key: string
  label: string
}

const props = defineProps<{
  /** 被移动/复制的节点 id,树中排除 */
  excludeIds: string[]
  /** 复制 / 解压 / 选文件夹(订阅存到哪)模式只改文案,选择逻辑一致 */
  mode?: 'move' | 'copy' | 'extract' | 'pick'
  /** 能不能选根目录「我的文件」;订阅这类必须落在某个文件夹里的不能 */
  allowRoot?: boolean
}>()

const TITLES = { move: '移动到', copy: '复制到', extract: '解压到', pick: '选择文件夹' } as const
const CONFIRMS = { move: '移动到此处', copy: '复制到此处', extract: '解压到此处', pick: '存到这里' } as const

/** 手机上目录树必须全屏,否则点不准 */
const { isMobile } = useBreakpoints()

const emit = defineEmits<{
  /** path:选中文件夹的路径(根为 /),选文件夹模式拿来显示 */
  confirm: [targetParentId: string | null, path: string]
}>()

const visible = defineModel<boolean>({ required: true })

const ROOT_KEY = '__ROOT__'
const selectedKey = ref<string | null>(null)
const selectedPath = ref('/')

async function loadNode(node: Node, resolve: (data: TreeItem[]) => void) {
  if (node.level === 0) {
    resolve([{ key: ROOT_KEY, label: '我的文件' }])
    return
  }
  const item = node.data as TreeItem
  const parentId = item.key === ROOT_KEY ? null : item.key
  const data = await request(ChildrenDocument, { parentId })
  resolve(
    data.children.items
      .filter((n) => n.kind === 'FOLDER' && !props.excludeIds.includes(n.id))
      .map((n) => ({ key: n.id, label: n.name })),
  )
}

function onNodeClick(data: TreeItem, node: Node) {
  selectedKey.value = data.key
  // 沿树往上拼路径,根「我的文件」不算
  const names: string[] = []
  for (let n: Node | null = node; n && n.level > 1; n = n.parent) names.unshift((n.data as TreeItem).label)
  selectedPath.value = '/' + names.join('/')
}

const canConfirm = computed(
  () => !!selectedKey.value && (props.allowRoot !== false || selectedKey.value !== ROOT_KEY),
)

function onConfirm() {
  if (!canConfirm.value) return
  emit('confirm', selectedKey.value === ROOT_KEY ? null : selectedKey.value, selectedPath.value)
  visible.value = false
}

function onOpen() {
  selectedKey.value = null
  selectedPath.value = '/'
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="TITLES[props.mode ?? 'move']"
    width="min(420px, 94vw)"
    :fullscreen="isMobile"
    append-to-body
    destroy-on-close
    @open="onOpen"
  >
    <el-tree
      lazy
      :load="loadNode"
      node-key="key"
      :props="{ label: 'label' }"
      highlight-current
      :expand-on-click-node="false"
      class="move-tree"
      @node-click="onNodeClick"
    />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!canConfirm" @click="onConfirm">
        {{ CONFIRMS[props.mode ?? 'move'] }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.move-tree {
  max-height: 320px;
  overflow: auto;
}
</style>
