<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, EditPen, Folder, FolderAdd, Rank } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ChildrenDocument,
  CreateFolderDocument,
  DeleteNodesDocument,
  MoveNodesDocument,
  NodeDocument,
  RenameNodeDocument,
} from '@/api/gen/graphql'
import type { ChildrenQuery } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import MoveDialog from '@/components/MoveDialog.vue'

type ChildItem = ChildrenQuery['children']['items'][number]

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

/** 当前文件夹 id;null = 根目录 */
const folderId = computed<string | null>(() => {
  const raw = route.params.folderId
  return typeof raw === 'string' && raw !== '' ? raw : null
})

// ---------- 查询 ----------

const { data, isFetching } = useQuery({
  queryKey: ['children', folderId],
  queryFn: () => request(ChildrenDocument, { parentId: folderId.value }),
})
const items = computed(() => data.value?.children.items ?? [])

interface Crumb {
  id: string
  name: string
}

/** 面包屑:从当前文件夹沿 parentId 回溯到根 */
const { data: crumbs } = useQuery({
  queryKey: ['breadcrumb', folderId],
  queryFn: async (): Promise<Crumb[]> => {
    const chain: Crumb[] = []
    let cur = folderId.value
    while (cur) {
      const res = await request(NodeDocument, { id: cur })
      chain.unshift({ id: res.node.id, name: res.node.name })
      cur = res.node.parentId ?? null
    }
    return chain
  },
})

// ---------- 多选 ----------

const selection = ref<ChildItem[]>([])
const selectedIds = computed(() => selection.value.map((n) => n.id))

function onSelectionChange(rows: ChildItem[]) {
  selection.value = rows
}

// ---------- 变更 ----------

function invalidate() {
  // 移动会同时影响旧父与新父,统一按前缀失效最稳妥
  void queryClient.invalidateQueries({ queryKey: ['children'] })
  void queryClient.invalidateQueries({ queryKey: ['breadcrumb'] })
}

const createFolderMutation = useMutation({
  mutationFn: (vars: { parentId: string | null; name: string }) =>
    request(CreateFolderDocument, vars),
  onSuccess: () => {
    ElMessage.success('文件夹已创建')
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const renameMutation = useMutation({
  mutationFn: (vars: { id: string; name: string }) => request(RenameNodeDocument, vars),
  onSuccess: () => {
    ElMessage.success('重命名成功')
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const moveMutation = useMutation({
  mutationFn: (vars: { ids: string[]; targetParentId: string | null }) =>
    request(MoveNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('移动成功')
    selection.value = []
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

const deleteMutation = useMutation({
  mutationFn: (vars: { ids: string[] }) => request(DeleteNodesDocument, vars),
  onSuccess: () => {
    ElMessage.success('已放入回收站')
    selection.value = []
    invalidate()
    void queryClient.invalidateQueries({ queryKey: ['trash'] })
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

// ---------- 工具栏操作 ----------

async function onCreateFolder() {
  try {
    const { value } = await ElMessageBox.prompt('请输入文件夹名称', '新建文件夹', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    createFolderMutation.mutate({ parentId: folderId.value, name: value.trim() })
  } catch {
    // 取消
  }
}

async function onRename() {
  const target = selection.value[0]
  if (!target || selection.value.length !== 1) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: target.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    renameMutation.mutate({ id: target.id, name: value.trim() })
  } catch {
    // 取消
  }
}

const moveDialogVisible = ref(false)

function onMove() {
  if (selection.value.length === 0) return
  moveDialogVisible.value = true
}

function onMoveConfirm(targetParentId: string | null) {
  moveMutation.mutate({ ids: selectedIds.value, targetParentId })
}

function onDelete() {
  if (selection.value.length === 0) return
  deleteMutation.mutate({ ids: selectedIds.value })
}

// ---------- 导航 ----------

/** el-table 的 slot row 是宽类型 DefaultRow,这里收窄回业务类型 */
function asChild(row: unknown): ChildItem {
  return row as ChildItem
}

function onRowDblclick(row: ChildItem) {
  if (row.kind === 'FOLDER') {
    void router.push(`/drive/${row.id}`)
  }
}
</script>

<template>
  <div class="drive">
    <el-breadcrumb separator="/" class="breadcrumb">
      <el-breadcrumb-item :to="{ path: '/drive' }">我的文件</el-breadcrumb-item>
      <el-breadcrumb-item
        v-for="crumb in crumbs ?? []"
        :key="crumb.id"
        :to="{ path: `/drive/${crumb.id}` }"
      >
        {{ crumb.name }}
      </el-breadcrumb-item>
    </el-breadcrumb>

    <div class="toolbar">
      <el-button type="primary" :icon="FolderAdd" @click="onCreateFolder">
        新建文件夹
      </el-button>
      <el-button
        :icon="EditPen"
        :disabled="selection.length !== 1"
        @click="onRename"
      >
        重命名
      </el-button>
      <el-button :icon="Rank" :disabled="selection.length === 0" @click="onMove">
        移动
      </el-button>
      <el-popconfirm
        title="确定将所选项目放入回收站?"
        confirm-button-text="删除"
        cancel-button-text="取消"
        @confirm="onDelete"
      >
        <template #reference>
          <el-button type="danger" :icon="Delete" :disabled="selection.length === 0">
            删除
          </el-button>
        </template>
      </el-popconfirm>
      <span v-if="selection.length > 0" class="selection-hint">
        已选 {{ selection.length }} 项
      </span>
    </div>

    <el-table
      v-loading="isFetching"
      :data="items"
      row-key="id"
      empty-text="这里空空如也"
      @selection-change="onSelectionChange"
      @row-dblclick="onRowDblclick"
    >
      <el-table-column type="selection" width="44" />
      <el-table-column label="名称" min-width="320">
        <template #default="{ row }">
          <span class="name-cell" :class="{ folder: asChild(row).kind === 'FOLDER' }">
            <el-icon class="name-icon">
              <Folder v-if="asChild(row).kind === 'FOLDER'" />
              <Document v-else />
            </el-icon>
            {{ asChild(row).name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ asChild(row).kind === 'FOLDER' ? '—' : formatBytes(asChild(row).size) }}
        </template>
      </el-table-column>
      <el-table-column label="修改时间" width="180">
        <template #default="{ row }">
          {{ formatTime(asChild(row).updatedAt) }}
        </template>
      </el-table-column>
    </el-table>

    <MoveDialog
      v-model="moveDialogVisible"
      :exclude-ids="selectedIds"
      @confirm="onMoveConfirm"
    />
  </div>
</template>

<style scoped>
.breadcrumb {
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}
.selection-hint {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.name-cell.folder {
  cursor: pointer;
}
.name-icon {
  color: var(--el-color-primary);
}
</style>
