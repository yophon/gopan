<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ChatDotRound, Connection, Delete, Folder, Key, Lock, Monitor, Search, Setting, Share, SwitchButton } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { MeDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'

/**
 * 侧栏内容。桌面直接放进 el-aside,手机放进 el-drawer ——
 * 两处共用这一份,避免菜单与账户操作两个版本漂移。
 * 导航完成后 emit('navigate'),由 AppShell 决定要不要关抽屉。
 */
const emit = defineEmits<{
  (e: 'navigate'): void
  (e: 'open-dav'): void
  (e: 'open-mcp'): void
  (e: 'open-pwd'): void
  (e: 'logout'): void
}>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => (route.path.startsWith('/drive') ? '/drive' : route.path))

// ---------- 搜索 ----------

const searchInput = ref(typeof route.query.q === 'string' ? route.query.q : '')

function onSearch() {
  const q = searchInput.value.trim()
  if (!q) return
  void router.push({ path: '/search', query: { q } })
  emit('navigate')
}

// ---------- 配额 ----------
// 与 AppShell 无关的独立订阅:vue-query 按 key 共享缓存,这里不会多打一次请求
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
</script>

<template>
  <div class="side-nav">
    <div class="brand">gopan 云盘</div>
    <el-input
      v-model="searchInput"
      class="search-input"
      placeholder="搜索文件"
      :prefix-icon="Search"
      clearable
      @keyup.enter="onSearch"
    />
    <el-menu router :default-active="activeMenu" class="menu" @select="emit('navigate')">
      <el-menu-item index="/chat">
        <el-icon><ChatDotRound /></el-icon>
        <span>传输助手</span>
      </el-menu-item>
      <el-menu-item index="/devices">
        <el-icon><Monitor /></el-icon>
        <span>登录设备</span>
      </el-menu-item>
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
        <el-button link :icon="Connection" title="WebDAV 应用密码" @click="emit('open-dav')" />
        <el-button link :icon="Key" title="Agent API Key 与 OAuth" @click="emit('open-mcp')" />
        <el-button link :icon="Lock" title="修改密码" @click="emit('open-pwd')" />
        <el-button link type="danger" :icon="SwitchButton" title="退出" @click="emit('logout')" />
      </span>
    </div>
  </div>
</template>

<style scoped>
.side-nav {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
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
  min-height: 0;
  overflow-y: auto;
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

/* 抽屉里给底部留出安全区,免得账户操作被 Home Indicator 压住 */
@media (max-width: 767px) {
  .brand {
    padding-top: calc(12px + var(--sat));
  }
  .user-area {
    padding-bottom: calc(12px + var(--sab));
  }
  .user-actions :deep(.el-button) {
    min-width: var(--touch-target);
    min-height: var(--touch-target);
  }
}
</style>
