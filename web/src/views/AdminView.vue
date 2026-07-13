<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, RefreshRight } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  AdminCreateUserDocument,
  AdminOverviewDocument,
  AdminResetPasswordDocument,
  AdminRetryFailedTasksDocument,
  AdminSetDisabledDocument,
  AdminSetQuotaDocument,
  AdminUsersDocument,
} from '@/api/gen/graphql'
import type { AdminUsersQuery } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { formatBytes, formatTime } from '@/utils/format'

type AdminUser = AdminUsersQuery['adminUsers'][number]

const GB = 1 << 30
const auth = useAuthStore()
const queryClient = useQueryClient()

const { data: usersData, isFetching } = useQuery({
  queryKey: ['adminUsers'],
  queryFn: () => request(AdminUsersDocument),
})
const users = computed(() => usersData.value?.adminUsers ?? [])

const { data: overviewData } = useQuery({
  queryKey: ['adminOverview'],
  queryFn: () => request(AdminOverviewDocument),
})
const overview = computed(() => overviewData.value?.adminOverview ?? null)
const failedTasks = computed(
  () => overview.value?.taskCounts.find((t) => t.status === 'failed')?.count ?? 0,
)
const taskSummary = computed(() =>
  (overview.value?.taskCounts ?? [])
    .map((t) => `${t.status} ${t.count}`)
    .join(' / ') || '空',
)

function invalidate() {
  void queryClient.invalidateQueries({ queryKey: ['adminUsers'] })
  void queryClient.invalidateQueries({ queryKey: ['adminOverview'] })
}

function asUser(row: unknown): AdminUser {
  return row as AdminUser
}

// ---------- 建号 ----------

const createVisible = ref(false)
const createForm = reactive({ username: '', password: '', quotaGb: 10 })

const createMutation = useMutation({
  mutationFn: () =>
    request(AdminCreateUserDocument, {
      username: createForm.username,
      password: createForm.password,
      quotaBytes: Math.round(createForm.quotaGb * GB),
    }),
  onSuccess: () => {
    ElMessage.success(`已创建 ${createForm.username}`)
    createVisible.value = false
    createForm.username = ''
    createForm.password = ''
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err, '创建失败')),
})

// ---------- 配额 ----------

async function onSetQuota(u: AdminUser) {
  let input: string
  try {
    ;({ value: input } = await ElMessageBox.prompt(
      `当前 ${formatBytes(u.quotaBytes)},已用 ${formatBytes(u.usedBytes)}`,
      `调整 ${u.username} 的配额(GB)`,
      { inputValue: String(u.quotaBytes / GB), inputPattern: /^\d+(\.\d+)?$/, inputErrorMessage: '请输入数字' },
    ))
  } catch {
    return
  }
  try {
    await request(AdminSetQuotaDocument, { userId: u.id, quotaBytes: Math.round(Number(input) * GB) })
    ElMessage.success('配额已更新')
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '更新失败'))
  }
}

// ---------- 禁用 / 启用 ----------

async function onToggleDisabled(u: AdminUser) {
  try {
    await request(AdminSetDisabledDocument, { userId: u.id, disabled: !u.disabled })
    ElMessage.success(u.disabled ? `已启用 ${u.username}` : `已禁用 ${u.username},其会话与分享即刻失效`)
    invalidate()
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  }
}

// ---------- 重置密码 ----------

async function onResetPassword(u: AdminUser) {
  try {
    await ElMessageBox.confirm(
      `将为 ${u.username} 生成随机新密码并踢下线所有设备,继续?`,
      '重置密码',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const data = await request(AdminResetPasswordDocument, { userId: u.id })
    await ElMessageBox.alert(
      `新密码:${data.adminResetPassword}\n只显示这一次,请立即转告用户。`,
      `${u.username} 的新密码`,
      { confirmButtonText: '已复制' },
    )
  } catch (err) {
    ElMessage.error(errorText(err, '重置失败'))
  }
}

// ---------- 失败任务重排 ----------

const retryMutation = useMutation({
  mutationFn: () => request(AdminRetryFailedTasksDocument),
  onSuccess: (data) => {
    ElMessage.success(`已重排 ${data.adminRetryFailedTasks} 个失败任务`)
    invalidate()
  },
  onError: (err) => ElMessage.error(errorText(err, '重排失败')),
})
</script>

<template>
  <div class="admin">
    <h3 class="page-title">管理</h3>

    <div v-if="overview" class="cards">
      <el-card shadow="never">
        <div class="card-label">用户</div>
        <div class="card-value">{{ overview.userCount }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="card-label">逻辑用量</div>
        <div class="card-value">{{ formatBytes(overview.totalUsedBytes) }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="card-label">物理存储({{ overview.blobCount }} blob)</div>
        <div class="card-value">{{ formatBytes(overview.blobBytes) }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="card-label">任务队列</div>
        <div class="card-value task-value">
          <span>{{ taskSummary }}</span>
          <el-button
            v-if="failedTasks > 0"
            size="small"
            type="warning"
            :icon="RefreshRight"
            :loading="retryMutation.isPending.value"
            @click="retryMutation.mutate()"
          >
            重排失败任务
          </el-button>
        </div>
      </el-card>
    </div>

    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="createVisible = true">新建用户</el-button>
    </div>

    <el-table v-loading="isFetching" :data="users" row-key="id">
      <el-table-column label="用户名" min-width="180">
        <template #default="{ row }">
          <span class="name-cell">
            {{ asUser(row).username }}
            <el-tag v-if="asUser(row).isAdmin" size="small" type="warning">admin</el-tag>
            <el-tag v-if="asUser(row).disabled" size="small" type="danger">已禁用</el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="用量 / 配额" min-width="180">
        <template #default="{ row }">
          {{ formatBytes(asUser(row).usedBytes) }} / {{ formatBytes(asUser(row).quotaBytes) }}
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(asUser(row).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="onSetQuota(asUser(row))">配额</el-button>
          <el-button link type="primary" @click="onResetPassword(asUser(row))">重置密码</el-button>
          <el-button
            v-if="asUser(row).id !== auth.user?.id"
            link
            :type="asUser(row).disabled ? 'success' : 'danger'"
            @click="onToggleDisabled(asUser(row))"
          >
            {{ asUser(row).disabled ? '启用' : '禁用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建用户" width="420px">
      <el-form label-width="72px" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" placeholder="2~32 个字符" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" placeholder="至少 8 位" show-password />
        </el-form-item>
        <el-form-item label="配额">
          <el-input-number v-model="createForm.quotaGb" :min="0.1" :step="1" :precision="1" />
          <span class="unit">GB</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="createMutation.isPending.value"
          :disabled="!createForm.username || createForm.password.length < 8"
          @click="createMutation.mutate()"
        >
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.card-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
}
.task-value {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
}
.toolbar {
  margin-bottom: 12px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.unit {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
</style>
