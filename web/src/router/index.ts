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
      path: '/',
      component: () => import('@/views/AppShell.vue'),
      children: [
        { path: '', redirect: '/drive' },
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
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/drive' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 分享访客页公开,不做登录校验
  if (to.name === 'share-visitor') return true

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
  return true
})

export default router
