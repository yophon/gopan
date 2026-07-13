<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  CreateMcpapiKeyDocument,
  McpapiKeysDocument,
  OAuthGrantsDocument,
  RevokeMcpapiKeyDocument,
  RevokeOAuthGrantDocument,
} from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'
import { mcpScopeLabel, mcpScopeOptions } from '@/utils/mcpScopes'

const visible = defineModel<boolean>({ required: true })
const queryClient = useQueryClient()

const endpoint = `${location.origin}/mcp`
const activeTab = ref<'keys' | 'oauth'>('keys')
const newName = ref('')
const selectedScopes = ref<string[]>(['files:read'])
const createdKey = ref<string | null>(null)

const { data: keysData, isFetching: keysFetching } = useQuery({
  queryKey: ['mcpAPIKeys'],
  queryFn: () => request(McpapiKeysDocument),
  enabled: visible,
})
const keys = computed(() => keysData.value?.mcpAPIKeys ?? [])

const { data: grantsData, isFetching: grantsFetching } = useQuery({
  queryKey: ['oauthGrants'],
  queryFn: () => request(OAuthGrantsDocument),
  enabled: visible,
})
const grants = computed(() => grantsData.value?.oauthGrants ?? [])

const createMutation = useMutation({
  mutationFn: () =>
    request(CreateMcpapiKeyDocument, {
      name: newName.value.trim(),
      scopes: selectedScopes.value,
    }),
  onSuccess: (data) => {
    createdKey.value = data.createMCPAPIKey.token
    newName.value = ''
    void queryClient.invalidateQueries({ queryKey: ['mcpAPIKeys'] })
  },
  onError: (err) => ElMessage.error(errorText(err, 'API Key 创建失败')),
})

const revokeKeyMutation = useMutation({
  mutationFn: (id: string) => request(RevokeMcpapiKeyDocument, { id }),
  onSuccess: () => {
    ElMessage.success('API Key 已吊销')
    void queryClient.invalidateQueries({ queryKey: ['mcpAPIKeys'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '吊销失败')),
})

const revokeGrantMutation = useMutation({
  mutationFn: (id: string) => request(RevokeOAuthGrantDocument, { id }),
  onSuccess: () => {
    ElMessage.success('OAuth 授权已撤销')
    void queryClient.invalidateQueries({ queryKey: ['oauthGrants'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '撤销失败')),
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
  <el-dialog
    v-model="visible"
    title="Agent 接入"
    width="760px"
    @closed="createdKey = null"
  >
    <div class="endpoint-row">
      <span class="endpoint-label">MCP 地址</span>
      <code class="mono endpoint-value">{{ endpoint }}</code>
      <el-button link type="primary" @click="copy(endpoint)">复制</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="API Key" name="keys">
        <el-alert v-if="createdKey" type="success" :closable="false" class="created">
          <p class="created-label">新 API Key,仅显示一次</p>
          <p class="mono created-value">
            {{ createdKey }}
            <el-button link type="primary" @click="copy(createdKey!)">复制</el-button>
          </p>
        </el-alert>

        <div class="create-grid">
          <el-input
            v-model="newName"
            placeholder="名称,如 Codex / CI"
            maxlength="64"
          />
          <el-button
            type="primary"
            :disabled="!newName.trim() || selectedScopes.length === 0"
            :loading="createMutation.isPending.value"
            @click="createMutation.mutate()"
          >
            创建
          </el-button>
          <el-checkbox-group v-model="selectedScopes" class="scope-picker">
            <el-checkbox
              v-for="scope in mcpScopeOptions"
              :key="scope.value"
              :value="scope.value"
            >
              {{ scope.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <el-table v-loading="keysFetching" :data="keys" row-key="id" empty-text="还没有 API Key">
          <el-table-column label="名称" prop="name" min-width="130" />
          <el-table-column label="权限" min-width="240">
            <template #default="{ row }">
              <div class="scope-tags">
                <el-tag v-for="scope in row.scopes" :key="scope" size="small" effect="plain">
                  {{ mcpScopeLabel(scope) }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="最近使用" width="170">
            <template #default="{ row }">
              {{ row.lastUsedAt ? formatTime(row.lastUsedAt) : '从未' }}
            </template>
          </el-table-column>
          <el-table-column label="" width="72" align="center">
            <template #default="{ row }">
              <el-popconfirm
                title="吊销后立即失效,确定?"
                confirm-button-text="吊销"
                cancel-button-text="取消"
                @confirm="revokeKeyMutation.mutate(row.id)"
              >
                <template #reference>
                  <el-button link type="danger">吊销</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="OAuth 授权" name="oauth">
        <el-table
          v-loading="grantsFetching"
          :data="grants"
          row-key="id"
          empty-text="还没有 OAuth 授权"
        >
          <el-table-column label="应用" prop="clientName" min-width="150" />
          <el-table-column label="权限" min-width="280">
            <template #default="{ row }">
              <div class="scope-tags">
                <el-tag v-for="scope in row.scopes" :key="scope" size="small" effect="plain">
                  {{ mcpScopeLabel(scope) }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="授权时间" width="170">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="" width="72" align="center">
            <template #default="{ row }">
              <el-popconfirm
                title="撤销后该应用立即断开,确定?"
                confirm-button-text="撤销"
                cancel-button-text="取消"
                @confirm="revokeGrantMutation.mutate(row.id)"
              >
                <template #reference>
                  <el-button link type="danger">撤销</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<style scoped>
.endpoint-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}
.endpoint-label {
  color: var(--el-text-color-secondary);
}
.endpoint-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.create-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  margin-bottom: 16px;
}
.scope-picker {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 16px;
}
.scope-picker :deep(.el-checkbox) {
  margin-right: 0;
}
.scope-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
@media (max-width: 680px) {
  .scope-picker {
    grid-template-columns: 1fr;
  }
}
</style>
