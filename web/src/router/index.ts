import { createRouter, createWebHistory } from 'vue-router'

import { singleFlightRefresh } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      // 访客分享页:公开访问,不要求登录
      path: '/s/:token',
      name: 'share-visitor',
      component: () => import('@/views/ShareVisitorView.vue'),
    },
    {
      // 收集文件访客页:公开访问,不要求登录(doc/20)
      path: '/c/:token',
      name: 'collect-visitor',
      component: () => import('@/views/CollectView.vue'),
    },
    {
      // 发布页:功能介绍、安卓安装包、网页版入口、更新日志;公开访问
      path: '/app',
      name: 'app-release',
      component: () => import('@/views/AppReleaseView.vue'),
    },
    {
      // 书签栏小工具弹出的小窗(doc/23 五.3):要登录,但不套侧栏
      path: '/clip',
      name: 'clip-popup',
      component: () => import('@/views/ClipPopupView.vue'),
    },
    {
      path: '/oauth/consent',
      name: 'oauth-consent',
      component: () => import('@/views/OAuthConsentView.vue'),
    },
    {
      path: '/',
      component: () => import('@/views/AppShell.vue'),
      children: [
        { path: '', redirect: '/drive' },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
        },
        {
          path: 'devices',
          name: 'devices',
          component: () => import('@/views/DevicesView.vue'),
        },
        {
          path: 'drive/:folderId?',
          name: 'drive',
          component: () => import('@/views/DriveView.vue'),
        },
        {
          path: 'trash',
          name: 'trash',
          component: () => import('@/views/TrashView.vue'),
        },
        {
          path: 'shares',
          name: 'shares',
          component: () => import('@/views/SharesView.vue'),
        },
        {
          path: 'search',
          name: 'search',
          component: () => import('@/views/SearchView.vue'),
        },
        {
          path: 'activity',
          name: 'activity',
          component: () => import('@/views/ActivityView.vue'),
        },
        {
          path: 'space',
          name: 'space',
          component: () => import('@/views/SpaceView.vue'),
        },
        {
          path: 'feeds',
          name: 'feeds',
          component: () => import('@/views/FeedsView.vue'),
        },
        {
          path: 'clips',
          name: 'clips',
          component: () => import('@/views/ClipsView.vue'),
        },
        {
          path: 'admin',
          name: 'admin',
          component: () => import('@/views/AdminView.vue'),
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/drive' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 分享 / 收集访客页与发布页公开,不做登录校验
  if (to.name === 'share-visitor' || to.name === 'collect-visitor' || to.name === 'app-release') return true

  if (to.path === '/login') {
    // 已登录还访问登录页 → 直接回主界面
    if (auth.accessToken) return { path: '/drive' }
    return true
  }

  if (!auth.accessToken) {
    // 无 token 先静默续登录一次(页面刷新场景)
    const ok = await singleFlightRefresh()
    if (!ok) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 管理页只对 admin 开放;非 admin 静默回主界面,不暴露路由存在
  if (to.name === 'admin' && !auth.user?.isAdmin) {
    return { path: '/drive' }
  }
  return true
})

export default router
