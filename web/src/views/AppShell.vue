<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Connection, Delete, Folder, Key, Lock, Search, Setting, Share, SwitchButton } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { MeDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import UploadDrawer from '@/components/UploadDrawer.vue'
import PreviewModal from '@/components/preview/PreviewModal.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import AppPasswordsDialog from '@/components/AppPasswordsDialog.vue'
import MCPAccessDialog from '@/components/MCPAccessDialog.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const queryClient = useQueryClient()

const activeMenu = computed(() => (route.path.startsWith('/drive') ? '/drive' : route.path))

// ---------- 搜索 ----------

const searchInput = ref(typeof route.query.q === 'string' ? route.query.q : '')

function onSearch() {
  const q = searchInput.value.trim()
  if (!q) return
  void router.push({ path: '/search', query: { q } })
}

// ---------- 配额 ----------

// 上传完成 / 彻删 / 复制的 onSuccess 都会 invalidate ['me'],用量条实时跟进
const { data: meData } = useQuery({
  queryKey: ['me'],
  queryFn: () => request(MeDocument),
})
const me = computed(() => meData.value?.me ?? null)
const quotaPercent = computed(() => {
  if (!me.value || me.value.quotaBytes <= 0) return 0
  return Math.min(100, Math.round((me.value.usedBytes / me.value.quotaBytes) * 100))
})
const quotaStatus = computed(() => {
  if (quotaPercent.value >= 90) return 'exception'
  if (quotaPercent.value >= 70) return 'warning'
  return undefined
})

// ---------- 账户 ----------

const pwdDialogVisible = ref(false)
const davDialogVisible = ref(false)
const mcpDialogVisible = ref(false)

async function onLogout() {
  await auth.logoutAction()
  queryClient.clear()
  await router.push('/login')
}
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px" class="aside">
      <div class="brand">gopan 云盘</div>
      <el-input
        v-model="searchInput"
        class="search-input"
        placeholder="搜索文件"
        :prefix-icon="Search"
        clearable
        @keyup.enter="onSearch"
      />
      <el-menu router :default-active="activeMenu" class="menu">
        <el-menu-item index="/drive">
          <el-icon><Folder /></el-icon>
          <span>我的文件</span>
        </el-menu-item>
        <el-menu-item index="/shares">
          <el-icon><Share /></el-icon>
          <span>我的分享</span>
        </el-menu-item>
        <el-menu-item index="/trash">
          <el-icon><Delete /></el-icon>
          <span>回收站</span>
        </el-menu-item>
        <el-menu-item v-if="me?.isAdmin" index="/admin">
          <el-icon><Setting /></el-icon>
          <span>管理</span>
        </el-menu-item>
      </el-menu>
      <div v-if="me" class="quota-area">
        <el-progress
          :percentage="quotaPercent"
          :status="quotaStatus"
          :stroke-width="6"
          :show-text="false"
        />
        <span class="quota-text">
          {{ formatBytes(me.usedBytes) }} / {{ formatBytes(me.quotaBytes) }}
        </span>
      </div>
      <div class="user-area">
        <span class="username" :title="auth.user?.username">{{ auth.user?.username }}</span>
        <span class="user-actions">
          <el-button link :icon="Connection" title="WebDAV 应用密码" @click="davDialogVisible = true" />
          <el-button link :icon="Key" title="Agent API Key 与 OAuth" @click="mcpDialogVisible = true" />
          <el-button link :icon="Lock" title="修改密码" @click="pwdDialogVisible = true" />
          <el-button link type="danger" :icon="SwitchButton" title="退出" @click="onLogout" />
        </span>
      </div>
    </el-aside>
    <el-main class="main">
      <router-view />
    </el-main>
    <UploadDrawer />
    <PreviewModal />
    <ChangePasswordDialog v-model="pwdDialogVisible" />
    <AppPasswordsDialog v-model="davDialogVisible" />
    <MCPAccessDialog v-model="mcpDialogVisible" />
  </el-container>
</template>

<style scoped>
.shell {
  height: 100%;
}
.aside {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--el-border-color-light);
}
.brand {
  padding: 20px 16px 12px;
  font-size: 18px;
  font-weight: 600;
}
.search-input {
  padding: 0 12px 8px;
}
.menu {
  flex: 1;
  border-right: none;
}
.quota-area {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px 4px;
}
.quota-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.user-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 16px 12px;
}
.username {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}
.user-actions {
  display: flex;
  flex: none;
}
.main {
  padding: 16px 24px;
  overflow: auto;
}
</style>
