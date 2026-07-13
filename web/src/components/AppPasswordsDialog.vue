<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  AppPasswordsDocument,
  CreateAppPasswordDocument,
  RevokeAppPasswordDocument,
} from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'

const visible = defineModel<boolean>({ required: true })

const queryClient = useQueryClient()
const { data, isFetching } = useQuery({
  queryKey: ['appPasswords'],
  queryFn: () => request(AppPasswordsDocument),
  enabled: visible,
})
const items = computed(() => data.value?.appPasswords ?? [])

const davUrl = `${location.origin}/dav`
const newName = ref('')
const created = ref<string | null>(null) // 刚生成的明文,只展示一次

const createMutation = useMutation({
  mutationFn: () => request(CreateAppPasswordDocument, { name: newName.value.trim() }),
  onSuccess: (d) => {
    created.value = d.createAppPassword
    newName.value = ''
    void queryClient.invalidateQueries({ queryKey: ['appPasswords'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '生成失败')),
})

const revokeMutation = useMutation({
  mutationFn: (id: string) => request(RevokeAppPasswordDocument, { id }),
  onSuccess: () => {
    ElMessage.success('已吊销,该设备立即失效')
    void queryClient.invalidateQueries({ queryKey: ['appPasswords'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '吊销失败')),
})

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败,请手动复制')
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="WebDAV 应用密码" width="560px" @closed="created = null">
    <p class="hint">
      在 Finder / 资源管理器 / rclone / 手机文件 App 里挂载:地址
      <code class="mono">{{ davUrl }}</code>
      <el-button link type="primary" size="small" @click="copy(davUrl)">复制</el-button>
      ,账号是你的用户名,密码用下面生成的应用密码(不是登录密码)。
    </p>

    <el-alert v-if="created" type="success" :closable="false" class="created">
      <p class="created-label">新应用密码(只显示这一次,关掉就没了):</p>
      <p class="mono created-value">
        {{ created }}
        <el-button link type="primary" size="small" @click="copy(created!)">复制</el-button>
      </p>
    </el-alert>

    <div class="create-row">
      <el-input
        v-model="newName"
        placeholder="设备名,如 MacBook / rclone-nas"
        maxlength="64"
        @keyup.enter="newName.trim() && createMutation.mutate()"
      />
      <el-button
        type="primary"
        :disabled="!newName.trim()"
        :loading="createMutation.isPending.value"
        @click="createMutation.mutate()"
      >
        生成
      </el-button>
    </div>

    <el-table v-loading="isFetching" :data="items" row-key="id" empty-text="还没有应用密码">
      <el-table-column label="设备" prop="name" min-width="140" />
      <el-table-column label="创建于" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="170">
        <template #default="{ row }">{{ row.lastUsedAt ? formatTime(row.lastUsedAt) : '从未' }}</template>
      </el-table-column>
      <el-table-column label="" width="80" align="center">
        <template #default="{ row }">
          <el-popconfirm title="吊销后该设备立即断开,确定?" confirm-button-text="吊销" cancel-button-text="再想想" @confirm="revokeMutation.mutate(row.id)">
            <template #reference>
              <el-button link type="danger">吊销</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.created {
  margin-bottom: 12px;
}
.created-label {
  margin: 0 0 4px;
  font-size: 12px;
}
.created-value {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}
.create-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
</style>
