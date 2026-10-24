<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useEventListener } from '@vueuse/core'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import {
  ArrowRight,
  Bell,
  Cellphone,
  ChatDotRound,
  Clock,
  Collection,
  Connection,
  Delete,
  Folder,
  Key,
  Lock,
  Memo,
  Monitor,
  Scissor,
  Search,
  Setting,
  Share,
  Star,
  SwitchButton,
  VideoPlay,
} from '@element-plus/icons-vue'

import BrandLogo from '@/components/BrandLogo.vue'
import { request } from '@/api/client'
import { MeDocument } from '@/api/gen/graphql'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import { useThemeMode, type ThemeMode } from '@/composables/themeMode'
import { searchKeyword } from '@/utils/search'
import { desktopWindow, invokeDesktop } from '@/desktop/bridge'

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
  (e: 'open-notifications'): void
  (e: 'open-recent'): void
  (e: 'logout'): void
}>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => (route.path.startsWith('/drive') ? '/drive' : route.path))

// ---------- 搜索 ----------

const searchInput = ref(searchKeyword(route.query))
// 去别的页再回来、或者地址里的关键词变了:搜索框跟着显示当前的关键词
watch(
  () => route.fullPath,
  () => {
    if (route.path === '/search') searchInput.value = searchKeyword(route.query)
  },
)

// 按 / 或 Ctrl+K 跳到搜索框(正在打字时不抢)
const searchRef = ref<{ focus: () => void } | null>(null)
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  const t = e.target as HTMLElement | null
  const typing = !!t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))
  const slash = e.key === '/' && !typing && !e.ctrlKey && !e.metaKey && !e.altKey
  const ctrlK = (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k'
  if (!slash && !ctrlK) return
  if (document.querySelector('.el-overlay:not([style*="display: none"])')) return
  e.preventDefault()
  searchRef.value?.focus()
})

function onSearch() {
  const q = searchInput.value.trim()
  if (!q) return
  // 正在看「内容」标签时换关键词,留在内容标签
  const inContent = route.path === '/search' && route.query.in === 'content'
  // 关键词放在 k 里而不是 q:有的浏览器插件(比如 ChatGPT 的)会把任何 /search?q= 当成搜索引擎结果页劫持走
  void router.push({ path: '/search', query: inContent ? { k: q, in: 'content' } : { k: q } })
  emit('navigate')
}

// ---------- 配额 ----------
// 与 AppShell 无关的独立订阅:vue-query 按 key 共享缓存,这里不会多打一次请求
const { data: meData } = useQuery({
  queryKey: ['me'],
  queryFn: () => request(MeDocument),
})
const me = computed(() => meData.value?.me ?? null)
const unread = computed(() => me.value?.unreadNotifications ?? 0)
const quotaPercent = computed(() => {
  if (!me.value || me.value.quotaBytes <= 0) return 0
  return Math.min(100, Math.round((me.value.usedBytes / me.value.quotaBytes) * 100))
})
// ---------- 账户菜单:外观、登录设备、接入凭证、改密码、退出 ----------

const { mode: themeMode, setMode: setThemeMode } = useThemeMode()
const themeOptions: { label: string; value: ThemeMode }[] = [
  { label: '跟随系统', value: 'system' },
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' },
]
const accountOpen = ref(false)

function accountAction(action: () => void) {
  accountOpen.value = false
  action()
}

function goDevices() {
  void router.push('/devices')
  emit('navigate')
}

const quotaStatus = computed(() => {
  if (quotaPercent.value >= 90) return 'exception'
  if (quotaPercent.value >= 70) return 'warning'
  return undefined
})
</script>

<template>
  <div class="side-nav">
    <div class="brand">
      <BrandLogo />
      <el-badge :value="unread" :max="99" :hidden="unread === 0" class="bell-badge">
        <el-button
          link
          class="bell-btn"
          :icon="Bell"
          :title="unread ? `通知(${unread} 条未读)` : '通知'"
          aria-label="通知"
          @click="emit('open-notifications')"
        />
      </el-badge>
    </div>
    <el-input
      ref="searchRef"
      v-model="searchInput"
      class="search-input"
      placeholder="搜索文件"
      :prefix-icon="Search"
      clearable
      @keyup.enter="onSearch"
    >
      <template v-if="!searchInput" #suffix><kbd class="search-kbd">/</kbd></template>
    </el-input>
    <el-menu router :default-active="activeMenu" class="menu" @select="emit('navigate')">
      <el-menu-item index="/drive">
        <el-icon><Folder /></el-icon>
        <span>我的文件</span>
      </el-menu-item>
      <el-menu-item index="/recent">
        <el-icon><Clock /></el-icon>
        <span>最近</span>
      </el-menu-item>
      <el-menu-item index="/starred">
        <el-icon><Star /></el-icon>
        <span>收藏</span>
      </el-menu-item>
      <el-menu-item index="/chat">
        <el-icon><ChatDotRound /></el-icon>
        <span>传输助手</span>
      </el-menu-item>
      <el-menu-item index="/shares">
        <el-icon><Share /></el-icon>
        <span>我的分享</span>
      </el-menu-item>
      <el-menu-item index="/feeds">
        <el-icon><Collection /></el-icon>
        <span>订阅</span>
      </el-menu-item>
      <el-menu-item index="/clips">
        <el-icon><Scissor /></el-icon>
        <span>剪藏</span>
      </el-menu-item>
      <el-menu-item index="/activity">
        <el-icon><Memo /></el-icon>
        <span>动态</span>
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
    <button type="button" class="nav-extra" @click="emit('open-recent')">
      <el-icon><VideoPlay /></el-icon>
      <span>最近播放</span>
    </button>
    <router-link to="/app" class="nav-extra" @click="emit('navigate')">
      <el-icon><Cellphone /></el-icon>
      <span>安卓 App · 更新日志</span>
    </router-link>
    <!-- 用量条可点:进空间分析(doc/23 三) -->
    <router-link
      v-if="me"
      to="/space"
      class="quota-area"
      :class="{ active: route.path === '/space' }"
      title="空间分析:看看空间花在哪"
      @click="emit('navigate')"
    >
      <el-progress
        :percentage="quotaPercent"
        :status="quotaStatus"
        :stroke-width="6"
        :show-text="false"
      />
      <span class="quota-text">
        <span>{{ formatBytes(me.usedBytes) }} / {{ formatBytes(me.quotaBytes) }}</span>
        <span class="quota-more">空间分析 ›</span>
      </span>
    </router-link>
    <el-popover
      v-model:visible="accountOpen"
      trigger="click"
      placement="top-start"
      :width="248"
      :show-arrow="false"
      :offset="6"
      popper-class="account-popper"
    >
      <template #reference>
        <button type="button" class="user-area" :class="{ open: accountOpen }" aria-label="账户与设置">
          <span class="avatar">{{ (auth.user?.username ?? '?').slice(0, 1).toUpperCase() }}</span>
          <span class="username" :title="auth.user?.username">{{ auth.user?.username }}</span>
          <el-icon class="user-more"><ArrowRight /></el-icon>
        </button>
      </template>
      <div class="account-menu">
        <div class="account-section">
          <span class="account-label">外观</span>
          <el-segmented
            :model-value="themeMode"
            :options="themeOptions"
            size="small"
            block
            @change="(v: string | number | boolean) => setThemeMode(v as ThemeMode)"
          />
        </div>
        <button type="button" class="account-item" @click="accountAction(goDevices)">
          <el-icon><Monitor /></el-icon>登录设备
        </button>
        <button type="button" class="account-item" @click="accountAction(() => emit('open-dav'))">
          <el-icon><Connection /></el-icon>WebDAV 应用密码
        </button>
        <button type="button" class="account-item" @click="accountAction(() => emit('open-mcp'))">
          <el-icon><Key /></el-icon>Agent 接入
        </button>
        <button type="button" class="account-item" @click="accountAction(() => emit('open-pwd'))">
          <el-icon><Lock /></el-icon>修改密码
        </button>
        <button
          v-if="desktopWindow()"
          type="button"
          class="account-item"
          @click="accountAction(() => void invokeDesktop('desktop_open_settings').catch(() => {}))"
        >
          <el-icon><Setting /></el-icon>桌面端设置
        </button>
        <button type="button" class="account-item danger" @click="accountAction(() => emit('logout'))">
          <el-icon><SwitchButton /></el-icon>退出登录
        </button>
      </div>
    </el-popover>
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 22px 18px 16px 20px;
  font-size: 19px;
}
.bell-btn {
  font-size: 18px;
}
.bell-badge :deep(.el-badge__content) {
  font-weight: 400;
}
/* 默认向右平移整个角标宽度,两位数时会顶出侧栏、出现横向滚动条:只往外移半个 */
.bell-badge :deep(.el-badge__content.is-fixed) {
  transform: translateY(-50%) translateX(50%);
}
.nav-extra {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 38px;
  margin: 1px 10px;
  padding: 0 12px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--gp-ink-2);
  font: inherit;
  font-size: 14px;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}
.nav-extra:hover {
  background: var(--gp-panel);
  color: var(--gp-ink);
}
.nav-extra .el-icon {
  margin-right: 5px;
  font-size: 18px;
}
.search-input {
  padding: 0 12px 10px;
}
.search-input :deep(.el-input__wrapper) {
  border-radius: 999px;
  background: var(--gp-panel);
  box-shadow: none;
}
.search-input :deep(.el-input__wrapper.is-focus) {
  background: var(--el-bg-color);
  box-shadow: 0 0 0 1px var(--gp-ink) inset;
}
.search-kbd {
  padding: 0 5px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  font: 11px/16px var(--gp-font);
  color: var(--el-text-color-placeholder);
}
@media (hover: none) {
  .search-kbd {
    display: none;
  }
}
/* 菜单项做成圆角块:选中是暖灰底加粗,不再是蓝字 */
.menu {
  --el-menu-bg-color: transparent;
  --el-menu-item-height: 38px;
  --el-menu-base-level-padding: 12px;
  --el-menu-text-color: var(--gp-ink-2);
  --el-menu-active-color: var(--gp-ink);
  --el-menu-hover-text-color: var(--gp-ink);
  --el-menu-hover-bg-color: var(--gp-panel);
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-right: none;
}
.menu :deep(.el-menu-item) {
  margin: 1px 10px;
  border-radius: 10px;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--gp-panel-2);
  font-weight: 600;
}
.quota-area {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 6px 10px 0;
  padding: 10px 10px 8px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
}
.quota-area:hover,
.quota-area.active {
  background: var(--gp-panel);
}
.quota-text {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.quota-more {
  color: var(--gp-ink);
  font-weight: 600;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 10px;
  width: calc(100% - 20px);
  margin: 8px 10px 10px;
  padding: 8px 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.user-area:hover,
.user-area.open {
  background: var(--gp-panel);
}
.avatar {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--gp-ink);
  color: var(--el-bg-color);
  font-size: 13px;
  font-weight: 700;
}
.username {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--gp-ink);
  font-size: 14px;
  font-weight: 600;
}
.user-more {
  flex: none;
  color: var(--gp-ink-3);
  transform: rotate(-90deg);
}

/* 抽屉里给底部留出安全区,免得账户操作被 Home Indicator 压住 */
@media (max-width: 767px) {
  .brand {
    padding-top: calc(12px + var(--sat));
  }
  .user-area {
    margin-bottom: calc(10px + var(--sab));
    min-height: var(--touch-target);
  }
}
</style>

<style>
/* 账户菜单是 teleport 到 body 的 popover,样式不走 scoped */
.account-popper.el-popover {
  padding: 6px;
}
.account-menu {
  display: flex;
  flex-direction: column;
}
.account-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 6px 8px 10px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.account-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.account-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-primary);
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}
.account-item .el-icon {
  font-size: 16px;
  color: var(--el-text-color-secondary);
}
.account-item:hover {
  background: var(--el-fill-color-light);
}
.account-item.danger,
.account-item.danger .el-icon {
  color: var(--el-color-danger);
}
</style>
