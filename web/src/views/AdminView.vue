<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Plus, RefreshRight } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  AdminCreateUserDocument,
  AdminLinkStatsDocument,
  AdminOverviewDocument,
  AdminPauseLinkDocument,
  AdminRevokeLinkDocument,
  AdminResetPasswordDocument,
  AdminRetryFailedTasksDocument,
  AdminSetDisabledDocument,
  AdminSetQuotaDocument,
  AdminUsersDocument,
} from '@/api/gen/graphql'
import type { AdminLinkStatsQuery, AdminUsersQuery } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { formatBytes, formatTime } from '@/utils/format'
import { absoluteLinkUrl, linkStatusMeta } from '@/utils/links'
import { useBreakpoints } from '@/composables/breakpoints'
import { useLinksEnabled } from '@/composables/links'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import type { SheetItem } from '@/components/nodes/types'

type AdminUser = AdminUsersQuery['adminUsers'][number]
type AdminLinkRow = AdminLinkStatsQuery['adminLinkStats']['top'][number]

const GB = 1 << 30
const origin = location.origin
const auth = useAuthStore()
const { isMobile } = useBreakpoints()
const queryClient = useQueryClient()

// ---------- 手机:账号操作菜单 ----------

const userSheet = ref<{ visible: boolean; user: AdminUser | null }>({
  visible: false,
  user: null,
})

const userSheetItems = computed<SheetItem[]>(() => {
  const u = userSheet.value.user
  if (!u) return []
  return [
    { key: 'quota', label: '调整配额' },
    { key: 'reset', label: '重置密码' },
    // 自己不能禁用自己,与桌面表格里的条件保持一致
    ...(u.id === auth.user?.id
      ? []
      : [{ key: 'toggle', label: u.disabled ? '启用账号' : '禁用账号', danger: !u.disabled }]),
  ]
})

function openUserSheet(user: AdminUser) {
  userSheet.value = { visible: true, user }
}

function onUserSheetSelect(key: string) {
  const u = userSheet.value.user
  if (!u) return
  if (key === 'quota') void onSetQuota(u)
  if (key === 'reset') void onResetPassword(u)
  if (key === 'toggle') void onToggleDisabled(u)
}

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

/** 内容索引:有文字可搜的份数,其余分类一行列出(只列非零的) */
const indexed = computed(() => {
  const ci = overview.value?.contentIndex
  return ci ? ci.done + ci.truncated : 0
})
const indexSummary = computed(() => {
  const ci = overview.value?.contentIndex
  if (!ci) return ''
  const parts: string[] = []
  if (ci.pending) parts.push(`待处理 ${ci.pending}`)
  if (ci.truncated) parts.push(`截断 ${ci.truncated}`)
  if (ci.empty) parts.push(`无文字 ${ci.empty}`)
  if (ci.unsupported) parts.push(`不支持 ${ci.unsupported}`)
  if (ci.failed) parts.push(`失败 ${ci.failed}`)
  return parts.join(' · ') || '全部处理完'
})
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

// ---------- 直链流量(doc/22 四.4):本月用量 / 上限,全站前 10,能暂停、吊销任何人的 ----------

const linksEnabled = useLinksEnabled()
const { data: linkStatsData } = useQuery({
  queryKey: ['adminLinkStats'],
  queryFn: () => request(AdminLinkStatsDocument),
  enabled: linksEnabled,
})
const linkStats = computed(() => linkStatsData.value?.adminLinkStats ?? null)
const linkPercent = computed(() => {
  const s = linkStats.value
  if (!s || s.monthlyBytesCap <= 0) return 0
  return Math.min(100, Math.round((s.monthBytes / s.monthlyBytesCap) * 100))
})

function asLink(row: unknown): AdminLinkRow {
  return row as AdminLinkRow
}

async function onAdminPauseLink(l: AdminLinkRow) {
  const pause = l.status !== 'PAUSED'
  try {
    await request(AdminPauseLinkDocument, { id: l.id, paused: pause })
    ElMessage.success(pause ? `已暂停 ${l.name}` : `已恢复 ${l.name}`)
    void queryClient.invalidateQueries({ queryKey: ['adminLinkStats'] })
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  }
}

async function onAdminRevokeLink(l: AdminLinkRow) {
  try {
    await ElMessageBox.confirm(`${l.owner} 的「${l.name}」直链会立即失效(404),文件本身不受影响。`, '吊销直链?', {
      type: 'warning',
      confirmButtonText: '吊销',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await request(AdminRevokeLinkDocument, { id: l.id })
    ElMessage.success('已吊销')
    void queryClient.invalidateQueries({ queryKey: ['adminLinkStats'] })
  } catch (err) {
    ElMessage.error(errorText(err, '吊销失败'))
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
      <el-card shadow="never">
        <div class="card-label">内容索引{{ overview.contentIndex.enabled ? '' : '(已关闭)' }}</div>
        <div class="card-value">{{ indexed }} 份 · {{ formatBytes(overview.contentIndex.textBytes) }}</div>
        <div class="card-sub">{{ indexSummary }}</div>
      </el-card>
      <el-card v-if="linkStats" shadow="never">
        <div class="card-label">直链流量(本月)</div>
        <div class="card-value">{{ formatBytes(linkStats.monthBytes) }} / {{ formatBytes(linkStats.monthlyBytesCap) }}</div>
        <el-progress
          :percentage="linkPercent"
          :status="linkPercent >= 100 ? 'exception' : linkPercent >= 80 ? 'warning' : undefined"
          :stroke-width="6"
          :show-text="false"
          class="card-progress"
        />
        <div class="card-sub">{{ linkStats.monthHits }} 次访问</div>
      </el-card>
    </div>

    <div v-if="linkStats && linkStats.top.length" class="link-top">
      <h4 class="section-title">本月直链流量前 10</h4>
      <el-table v-if="!isMobile" :data="linkStats.top" row-key="id" size="small">
        <el-table-column label="用户" prop="owner" width="120" />
        <el-table-column label="文件" min-width="200">
          <template #default="{ row }">
            <a class="link-name" :href="absoluteLinkUrl(asLink(row).path, origin)" target="_blank" rel="noopener">
              {{ asLink(row).name }}
            </a>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="linkStatusMeta(asLink(row).status).type">
              {{ linkStatusMeta(asLink(row).status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="访问" width="90" align="right">
          <template #default="{ row }">{{ asLink(row).monthHits }}</template>
        </el-table-column>
        <el-table-column label="流量" width="100" align="right">
          <template #default="{ row }">{{ formatBytes(asLink(row).monthBytes) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="asLink(row).status === 'EXPIRED'"
              @click="onAdminPauseLink(asLink(row))"
            >
              {{ asLink(row).status === 'PAUSED' ? '恢复' : '暂停' }}
            </el-button>
            <el-button link type="danger" @click="onAdminRevokeLink(asLink(row))">吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="user-cards">
        <div v-for="l in linkStats.top" :key="l.id" class="user-card">
          <div class="user-main">
            <div class="user-name">
              {{ l.name }}
              <el-tag size="small" :type="linkStatusMeta(l.status).type">{{ linkStatusMeta(l.status).label }}</el-tag>
            </div>
            <div class="user-meta">{{ l.owner }} · {{ l.monthHits }} 次 · {{ formatBytes(l.monthBytes) }}</div>
          </div>
          <el-button link type="primary" :disabled="l.status === 'EXPIRED'" @click="onAdminPauseLink(l)">
            {{ l.status === 'PAUSED' ? '恢复' : '暂停' }}
          </el-button>
          <el-button link type="danger" @click="onAdminRevokeLink(l)">吊销</el-button>
        </div>
      </div>
    </div>

    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="createVisible = true">新建用户</el-button>
    </div>

    <el-table v-if="!isMobile" v-loading="isFetching" :data="users" row-key="id">
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

    <!-- 手机:账号卡片,逐项操作走底部菜单(表格 800px 起,手机上横向滚动太难受) -->
    <div v-else v-loading="isFetching" class="user-cards">
      <div v-for="u in users" :key="u.id" class="user-card">
        <div class="user-main">
          <div class="user-name">
            {{ u.username }}
            <el-tag v-if="u.isAdmin" size="small" type="warning">admin</el-tag>
            <el-tag v-if="u.disabled" size="small" type="danger">已禁用</el-tag>
          </div>
          <div class="user-meta">
            {{ formatBytes(u.usedBytes) }} / {{ formatBytes(u.quotaBytes) }}
          </div>
        </div>
        <el-button
          class="user-more"
          link
          :icon="MoreFilled"
          title="账号操作"
          aria-label="账号操作"
          @click="openUserSheet(u)"
        />
      </div>
      <el-empty v-if="!users.length && !isFetching" description="还没有用户" />
    </div>

    <el-dialog
      v-model="createVisible"
      title="新建用户"
      width="min(420px, 94vw)"
      :fullscreen="isMobile"
    >
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

    <NodeActionSheet
      v-model:visible="userSheet.visible"
      :title="userSheet.user?.username"
      :items="userSheetItems"
      @select="onUserSheetSelect"
    />
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
}
.user-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.user-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.user-main {
  flex: 1;
  min-width: 0;
}
.user-name {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.user-more {
  width: var(--touch-target);
  height: var(--touch-target);
  font-size: 18px;
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
.card-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-progress {
  margin-top: 6px;
}
.link-top {
  margin-bottom: 20px;
}
.section-title {
  margin: 0 0 8px;
  font-size: 14px;
}
.link-name {
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.link-name:hover {
  color: var(--el-color-primary);
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
