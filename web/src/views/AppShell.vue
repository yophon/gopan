<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import { Menu as MenuIcon } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/breakpoints'
import UploadDrawer from '@/components/UploadDrawer.vue'
import PreviewModal from '@/components/preview/PreviewModal.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import AppPasswordsDialog from '@/components/AppPasswordsDialog.vue'
import MCPAccessDialog from '@/components/MCPAccessDialog.vue'
import SideNav from '@/components/SideNav.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const queryClient = useQueryClient()

const { isMobile } = useBreakpoints()

// ---------- 移动端导航抽屉 ----------

const navOpen = ref(false)
// 兜底:任何来源的跳转(不只是菜单)都把抽屉收起来
watch(() => route.fullPath, () => (navOpen.value = false))

// ---------- 账户 ----------

const pwdDialogVisible = ref(false)
const davDialogVisible = ref(false)
const mcpDialogVisible = ref(false)

async function onLogout() {
  navOpen.value = false
  await auth.logoutAction()
  queryClient.clear()
  await router.push('/login')
}
</script>

<template>
  <el-container class="shell">
    <el-aside v-if="!isMobile" width="220px" class="aside">
      <SideNav
        @open-dav="davDialogVisible = true"
        @open-mcp="mcpDialogVisible = true"
        @open-pwd="pwdDialogVisible = true"
        @logout="onLogout"
      />
    </el-aside>

    <el-container class="body" direction="vertical">
      <header v-if="isMobile" class="topbar">
        <el-button
          text
          class="topbar-btn"
          :icon="MenuIcon"
          title="打开导航"
          aria-label="打开导航"
          @click="navOpen = true"
        />
        <span class="topbar-title">gopan 云盘</span>
      </header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <el-drawer
      v-model="navOpen"
      direction="ltr"
      size="82%"
      :with-header="false"
      class="nav-drawer"
    >
      <SideNav
        @navigate="navOpen = false"
        @open-dav="davDialogVisible = true"
        @open-mcp="mcpDialogVisible = true"
        @open-pwd="pwdDialogVisible = true"
        @logout="onLogout"
      />
    </el-drawer>

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
  border-right: 1px solid var(--el-border-color-light);
}
.body {
  min-width: 0;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 4px;
  height: calc(48px + var(--sat));
  padding: var(--sat) 8px 0;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.topbar-btn {
  width: var(--touch-target);
  height: var(--touch-target);
  font-size: 20px;
}
.topbar-title {
  font-size: 16px;
  font-weight: 600;
}
.main {
  padding: 16px 24px;
  overflow: auto;
}

/* 手机:内边距收到 12px,底部留安全区,内容区不横向溢出 */
@media (max-width: 767px) {
  .main {
    padding: 12px 12px calc(12px + var(--sab));
    overflow-x: hidden;
  }
}
</style>

<style>
/* 抽屉是 teleport 到 body 的,内部样式写在这里更稳(不给 scoped 打洞) */
.nav-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
