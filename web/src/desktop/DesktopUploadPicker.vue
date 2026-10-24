<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import MoveDialog from '@/components/MoveDialog.vue'
import { useUploadsStore } from '@/stores/uploads'
import { finishPick, pickQueue, pickTitle } from './pick'

/**
 * 桌面端右键菜单上传时选云端文件夹(doc/29 D5),AppShell 在桌面端主窗口里挂着。
 * 一次只问一批;默认停在当前打开的文件夹。
 */
const route = useRoute()
const router = useRouter()
const uploads = useUploadsStore()
const current = computed(() => pickQueue.value[0] ?? null)
const startId = computed(() => (route.name === 'drive' && typeof route.params.id === 'string' ? route.params.id : null))

// 确认后对话框紧接着会关:关的时候看有没有确认过,决定是交回文件夹还是取消
let confirmed: { requestId: number; folderId: string | null } | null = null

function onConfirm(folderId: string | null) {
  if (current.value) confirmed = { requestId: current.value.requestId, folderId }
}

function onClose(v: boolean) {
  const r = current.value
  if (v || !r) return
  const folderId = confirmed?.requestId === r.requestId ? confirmed.folderId : undefined
  confirmed = null
  void finishPick(r, folderId)
  if (folderId === undefined) return
  // 去目标文件夹看着它传(进度在传输抽屉的「本机」里)
  uploads.collapsed = false
  void router.push(folderId ? `/drive/${folderId}` : '/drive')
}
</script>

<template>
  <MoveDialog
    v-if="current"
    :key="current.requestId"
    :model-value="true"
    mode="upload"
    :title="pickTitle(current)"
    :exclude-ids="[]"
    :start-id="startId"
    @update:model-value="onClose"
    @confirm="onConfirm"
  />
</template>
