<script setup lang="ts">
import { computed } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { RevokeSessionDocument, SessionsDocument } from '@/api/gen/graphql'
import type { SessionsQuery } from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'
import { describeUserAgent } from '@/utils/userAgent'
import { useBreakpoints } from '@/composables/breakpoints'

type DeviceSession = SessionsQuery['sessions'][number]

const queryClient = useQueryClient()
const { isMobile, isCompact } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: ['sessions'],
  queryFn: () => request(SessionsDocument, {}),
})
const items = computed(() => data.value?.sessions ?? [])

/** el-table 的 slot row 是宽类型 DefaultRow,收窄回业务类型 */
function asSession(row: unknown): DeviceSession {
  return row as DeviceSession
}

const revokeMutation = useMutation({
  mutationFn: (familyId: string) => request(RevokeSessionDocument, { familyId }),
  onSuccess: () => {
    ElMessage.success('已吊销该设备')
    void queryClient.invalidateQueries({ queryKey: ['sessions'] })
  },
  onError: (err) => ElMessage.error(errorText(err)),
})

async function revoke(s: DeviceSession) {
  const who = describeUserAgent(s.userAgent)
  try {
    await ElMessageBox.confirm(
      `吊销后「${who}」需要重新登录才能再访问。确定继续?`,
      '吊销设备',
      { type: 'warning', confirmButtonText: '吊销', cancelButtonText: '取消' },
    )
    revokeMutation.mutate(s.familyId)
  } catch {
    // 取消
  }
}
</script>

<template>
  <div class="devices">
    <h2 class="page-title">登录设备</h2>
    <p class="hint">
      账号当前登录的会话。不在身边的设备可以随时吊销 —— 吊销后那台设备需要重新登录。
      改密码会一次性吊销全部设备。
    </p>

    <el-table
      v-if="!isMobile"
      v-loading="isFetching"
      :data="items"
      row-key="familyId"
      empty-text="没有登录记录"
    >
      <el-table-column label="设备" min-width="240">
        <template #default="{ row }">
          <span class="device-cell">
            <span>{{ describeUserAgent(asSession(row).userAgent) }}</span>
            <el-tag v-if="asSession(row).current" size="small" type="success" effect="plain">
              本机
            </el-tag>
            <el-tag v-else-if="!asSession(row).active" size="small" type="info" effect="plain">
              已失效
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="IP" width="150" prop="ip" />
      <el-table-column label="最近活动" width="175">
        <template #default="{ row }">{{ formatTime(asSession(row).lastSeenAt) }}</template>
      </el-table-column>
      <!-- 1024 以下收起:这张表本来就快满了 -->
      <el-table-column v-if="!isCompact" label="登录时间" width="175">
        <template #default="{ row }">{{ formatTime(asSession(row).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="" width="100" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="asSession(row).current" content="当前设备请用「退出登录」" placement="top">
            <span><el-button link disabled>吊销</el-button></span>
          </el-tooltip>
          <el-button
            v-else-if="asSession(row).active"
            link
            type="danger"
            @click="revoke(asSession(row))"
          >
            吊销
          </el-button>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 手机:设备行只有四五个字段,表格挤不下,用卡片 -->
    <div v-else v-loading="isFetching" class="device-cards">
      <el-empty v-if="items.length === 0" description="没有登录记录" />
      <div v-for="s in items" :key="s.familyId" class="device-card">
        <div class="card-main">
          <span class="card-title">
            {{ describeUserAgent(s.userAgent) }}
            <el-tag v-if="s.current" size="small" type="success" effect="plain">本机</el-tag>
            <el-tag v-else-if="!s.active" size="small" type="info" effect="plain">已失效</el-tag>
          </span>
          <span class="card-sub">{{ s.ip }} · 最近 {{ formatTime(s.lastSeenAt) }}</span>
        </div>
        <el-button
          v-if="s.active && !s.current"
          link
          type="danger"
          class="card-action"
          @click="revoke(s)"
        >
          吊销
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
}
.hint {
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.device-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.device-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.device-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.card-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.card-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.card-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.card-action {
  flex: none;
  min-width: var(--touch-target);
  min-height: var(--touch-target);
}
</style>
