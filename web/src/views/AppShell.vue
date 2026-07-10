<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import { Delete, Folder, SwitchButton } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const queryClient = useQueryClient()

const activeMenu = computed(() => (route.path.startsWith('/drive') ? '/drive' : route.path))

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
      <el-menu router :default-active="activeMenu" class="menu">
        <el-menu-item index="/drive">
          <el-icon><Folder /></el-icon>
          <span>我的文件</span>
        </el-menu-item>
        <el-menu-item index="/trash">
          <el-icon><Delete /></el-icon>
          <span>回收站</span>
        </el-menu-item>
      </el-menu>
      <div class="user-area">
        <span class="username" :title="auth.user?.username">{{ auth.user?.username }}</span>
        <el-button link type="danger" :icon="SwitchButton" @click="onLogout">退出</el-button>
      </div>
    </el-aside>
    <el-main class="main">
      <router-view />
    </el-main>
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
  padding: 20px 16px;
  font-size: 18px;
  font-weight: 600;
}
.menu {
  flex: 1;
  border-right: none;
}
.user-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-light);
}
.username {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}
.main {
  padding: 16px 24px;
  overflow: auto;
}
</style>
