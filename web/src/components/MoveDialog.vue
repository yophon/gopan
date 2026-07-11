<script setup lang="ts">
import { ref } from 'vue'
import type Node from 'element-plus/es/components/tree/src/model/node'

import { request } from '@/api/client'
import { ChildrenDocument } from '@/api/gen/graphql'

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
  /** 复制模式只改文案,选择逻辑一致 */
  mode?: 'move' | 'copy'
}>()

const emit = defineEmits<{
  confirm: [targetParentId: string | null]
}>()

const visible = defineModel<boolean>({ required: true })

const ROOT_KEY = '__ROOT__'
const selectedKey = ref<string | null>(null)

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

function onNodeClick(data: TreeItem) {
  selectedKey.value = data.key
}

function onConfirm() {
  if (!selectedKey.value) return
  emit('confirm', selectedKey.value === ROOT_KEY ? null : selectedKey.value)
  visible.value = false
}

function onOpen() {
  selectedKey.value = null
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="props.mode === 'copy' ? '复制到' : '移动到'"
    width="420px"
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
      <el-button type="primary" :disabled="!selectedKey" @click="onConfirm">
        {{ props.mode === 'copy' ? '复制到此处' : '移动到此处' }}
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
