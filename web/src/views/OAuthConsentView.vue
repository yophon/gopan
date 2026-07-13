<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  DecideOAuthAuthorizationDocument,
  OAuthAuthorizationRequestDocument,
  type OAuthAuthorizationInput,
} from '@/api/gen/graphql'
import { mcpScopeLabel } from '@/utils/mcpScopes'

const route = useRoute()

function queryString(name: string): string {
  const value = route.query[name]
  return typeof value === 'string' ? value : ''
}

const input = computed<OAuthAuthorizationInput>(() => ({
  clientId: queryString('client_id'),
  redirectUri: queryString('redirect_uri'),
  responseType: queryString('response_type'),
  scope: queryString('scope') || null,
  state: queryString('state') || null,
  codeChallenge: queryString('code_challenge'),
  codeChallengeMethod: queryString('code_challenge_method'),
}))

const { data, isFetching, error } = useQuery({
  queryKey: ['oauthAuthorizationRequest', route.fullPath],
  queryFn: () => request(OAuthAuthorizationRequestDocument, { input: input.value }),
})

const decision = useMutation({
  mutationFn: (approved: boolean) =>
    request(DecideOAuthAuthorizationDocument, { input: input.value, approved }),
  onSuccess: (result) => {
    location.assign(result.decideOAuthAuthorization.redirectUrl)
  },
  onError: (err) => ElMessage.error(errorText(err, '授权操作失败')),
})
</script>

<template>
  <main class="consent-page">
    <section class="consent-panel">
      <div class="brand">gopan 云盘</div>
      <template v-if="isFetching">
        <el-skeleton :rows="4" animated />
      </template>
      <el-result
        v-else-if="error || !data"
        icon="error"
        title="授权请求无效"
        :sub-title="errorText(error, '请返回 MCP 客户端重新连接')"
      />
      <template v-else>
        <h1>{{ data.oauthAuthorizationRequest.clientName }}</h1>
        <p class="account">请求访问你的 gopan 云盘</p>
        <div class="permissions">
          <div
            v-for="scope in data.oauthAuthorizationRequest.scopes"
            :key="scope"
            class="permission-row"
          >
            {{ mcpScopeLabel(scope) }}
          </div>
        </div>
        <div class="actions">
          <el-button
            :loading="decision.isPending.value"
            @click="decision.mutate(false)"
          >
            拒绝
          </el-button>
          <el-button
            type="primary"
            :loading="decision.isPending.value"
            @click="decision.mutate(true)"
          >
            允许
          </el-button>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.consent-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--el-fill-color-light);
}
.consent-panel {
  width: min(460px, 100%);
  padding: 28px;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  border-radius: 8px;
}
.brand {
  margin-bottom: 24px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
h1 {
  margin: 0;
  font-size: 22px;
  line-height: 1.35;
}
.account {
  margin: 8px 0 20px;
  color: var(--el-text-color-secondary);
}
.permissions {
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.permission-row {
  min-height: 44px;
  display: flex;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
}
.permission-row:last-child {
  border-bottom: 0;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
}
</style>
