<script setup lang="ts">
import { computed, defineAsyncComponent, reactive, ref, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Bell } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { MeDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { useUploadsStore } from '@/stores/uploads'
import { useBreakpoints } from '@/composables/breakpoints'
import { shellPanel, withoutQuery } from '@/utils/notifications'
import UploadDrawer from '@/components/UploadDrawer.vue'
import SideNav from '@/components/SideNav.vue'
import BrandLogo from '@/components/BrandLogo.vue'
import MiniPlayer from '@/components/player/MiniPlayer.vue'
import { previewState } from '@/composables/preview'
import { desktopWindow } from '@/desktop/bridge'

// 弹窗 / 抽屉第一次打开时才加载(连同它们用到的表格、表单、标签页这些组件),之后一直挂着,关的动画照常。
// 上传抽屉要轮询后台任务、迷你播放条持有全站唯一的 <audio>,这两个照旧常驻
const PreviewModal = defineAsyncComponent(() => import('@/components/preview/PreviewModal.vue'))
const ChangePasswordDialog = defineAsyncComponent(() => import('@/components/ChangePasswordDialog.vue'))
const AppPasswordsDialog = defineAsyncComponent(() => import('@/components/AppPasswordsDialog.vue'))
const MCPAccessDialog = defineAsyncComponent(() => import('@/components/MCPAccessDialog.vue'))
const NotificationDrawer = defineAsyncComponent(() => import('@/components/notify/NotificationDrawer.vue'))
const DesktopUploadPicker = defineAsyncComponent(() => import('@/desktop/DesktopUploadPicker.vue'))

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const queryClient = useQueryClient()

const { isMobile } = useBreakpoints()

// 桌面端的传输助手小窗(doc/29 D3):只要会话本身,导航、上传抽屉、播放条都不要
const embed = computed(() => route.query.embed === '1' || desktopWindow() === 'mini')

// ---------- 移动端导航抽屉 ----------

const navOpen = ref(false)
// 兜底:任何来源的跳转(不只是菜单)都把抽屉收起来
watch(() => route.fullPath, () => (navOpen.value = false))

// ---------- 账户 ----------

const pwdDialogVisible = ref(false)
const davDialogVisible = ref(false)
const mcpDialogVisible = ref(false)

// ---------- 通知 ----------

const notifyVisible = ref(false)

const opened = reactive({ preview: false, pwd: false, dav: false, mcp: false, notify: false })
watchEffect(() => {
  if (previewState.visible) opened.preview = true
  if (pwdDialogVisible.value) opened.pwd = true
  if (davDialogVisible.value) opened.dav = true
  if (mcpDialogVisible.value) opened.mcp = true
  if (notifyVisible.value) opened.notify = true
})

const { data: meData } = useQuery({ queryKey: ['me'], queryFn: () => request(MeDocument) })
const unread = computed(() => meData.value?.me.unreadNotifications ?? 0)

function openNotifications() {
  navOpen.value = false
  notifyVisible.value = true
}

// ---------- ?panel=:通知与推送消息里的链接直达(doc/22 三.4),做完就从地址里去掉 ----------

const uploads = useUploadsStore()

watch(
  () => route.query.panel,
  (raw) => {
    const panel = shellPanel(raw)
    if (!panel) return
    if (panel === 'jobs') uploads.focusJobs()
    else if (panel === 'webdav') davDialogVisible.value = true
    else mcpDialogVisible.value = true
    void router.replace({ path: route.path, query: withoutQuery(route.query, ['panel']), hash: route.hash })
  },
  { immediate: true },
)

async function onLogout() {
  navOpen.value = false
  await auth.logoutAction()
  queryClient.clear()
  await router.push('/login')
}
</script>

<template>
  <el-container class="shell">
    <el-aside v-if="!isMobile && !embed" width="220px" class="aside">
      <SideNav
        @open-dav="davDialogVisible = true"
        @open-mcp="mcpDialogVisible = true"
        @open-pwd="pwdDialogVisible = true"
        @open-notifications="openNotifications"
        @logout="onLogout"
      />
    </el-aside>

    <el-container class="body" direction="vertical">
      <header v-if="isMobile && !embed" class="topbar">
        <el-button text class="topbar-btn" title="打开导航" aria-label="打开导航" @click="navOpen = true">
          <!-- 三道杠;Element 的 Menu 图标是四个方块,像「应用」不像「菜单」 -->
          <svg class="menu-glyph" viewBox="0 0 20 20" aria-hidden="true">
            <path d="M3 5.5h14M3 10h14M3 14.5h14" />
          </svg>
        </el-button>
        <span class="topbar-title"><BrandLogo /></span>
        <el-badge :value="unread" :max="99" :hidden="unread === 0" class="topbar-badge">
          <el-button
            text
            class="topbar-btn"
            :icon="Bell"
            :title="unread ? `通知(${unread} 条未读)` : '通知'"
            aria-label="通知"
            @click="openNotifications"
          />
        </el-badge>
      </header>
      <el-main class="main" :class="{ embed }">
        <router-view />
      </el-main>
      <MiniPlayer v-if="!embed" />
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
        @open-notifications="openNotifications"
        @logout="onLogout"
      />
    </el-drawer>

    <UploadDrawer v-if="!embed" />
    <DesktopUploadPicker v-if="desktopWindow() === 'main'" />
    <PreviewModal v-if="opened.preview" />
    <ChangePasswordDialog v-if="opened.pwd" v-model="pwdDialogVisible" />
    <AppPasswordsDialog v-if="opened.dav" v-model="davDialogVisible" />
    <MCPAccessDialog v-if="opened.mcp" v-model="mcpDialogVisible" />
    <NotificationDrawer v-if="opened.notify" v-model="notifyVisible" />
  </el-container>
</template>

<style scoped>
.shell {
  height: 100%;
}
.aside {
  border-right: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-extra-light);
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
.menu-glyph {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
}
.topbar-title {
  flex: 1;
  padding-left: 4px;
  font-size: 18px;
}
.topbar-badge :deep(.el-badge__content.is-fixed) {
  /* 默认向右平移整个角标宽度,两位数时会顶出屏幕右边:收在按钮里面 */
  top: 8px;
  right: 4px;
  transform: translateY(-50%);
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
.main.embed {
  padding: 8px 10px;
}
</style>

<style>
/* 抽屉是 teleport 到 body 的,内部样式写在这里更稳(不给 scoped 打洞) */
.nav-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
