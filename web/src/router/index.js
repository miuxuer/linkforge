import { createRouter, createWebHistory } from 'vue-router'

import { useUserStore } from '@/store/user'

/**
 * 路由表。
 *
 * meta 里两个标记决定守卫怎么放行：
 * - requiresAuth  需要登录
 * - requiresAdmin 需要管理员（隐含 requiresAuth）
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册' }
  },
  {
    path: '/',
    component: () => import('@/views/layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '数据看板', requiresAuth: true }
      },
      {
        path: 'links',
        name: 'links',
        component: () => import('@/views/LinkListView.vue'),
        meta: { title: '我的短链', requiresAuth: true }
      },
      {
        path: 'links/:id',
        name: 'link-detail',
        component: () => import('@/views/LinkDetailView.vue'),
        meta: { title: '短链详情', requiresAuth: true }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人中心', requiresAuth: true }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManageView.vue'),
        meta: { title: '用户管理', requiresAuth: true, requiresAdmin: true }
      },
      {
        path: 'admin/links',
        name: 'admin-links',
        component: () => import('@/views/admin/LinkManageView.vue'),
        meta: { title: '短链管理', requiresAuth: true, requiresAdmin: true }
      },
      {
        path: 'admin/logs',
        name: 'admin-logs',
        component: () => import('@/views/admin/LogManageView.vue'),
        meta: { title: '操作日志', requiresAuth: true, requiresAdmin: true }
      }
    ]
  },
  {
    // 兜底：访问不存在的路径时回首页，而不是白屏
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  // history 模式（URL 里没有 #）。需要服务端把未匹配的路径都回退到 index.html，
  // 否则用户刷新 /links 会 404
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫：登录校验 + 角色校验。
 *
 * 为什么前端也要做一遍（后端明明已经拦了）：后端的拦截是安全边界，
 * 前端的拦截是体验 —— 没登录的用户不该先看到一个空白的列表页再被踢走。
 * 两者都要有，但不能只靠前端（前端的代码用户随便改）。
 */
router.beforeEach(async (to) => {
  const userStore = useUserStore()

  document.title = to.meta.title ? `${to.meta.title} - LinkForge` : 'LinkForge'

  const requiresAuth = to.meta.requiresAuth
  if (!requiresAuth) {
    return true
  }

  if (!userStore.isLoggedIn) {
    // 记下本来想去的地址，登录成功后跳回去
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 有 token 但还没拉过资料（比如刷新页面后）—— 拉一次。
  // 这一步同时也是在验证 token 还有没有效：失效的话 axios 拦截器会跳登录页
  if (!userStore.profile) {
    try {
      await userStore.loadProfile()
    } catch {
      // 拦截器已经处理了提示和跳转，这里只要拦住这次导航
      return false
    }
  }

  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    // 登录了但角色不够。回看板而不是登录页 —— 重新登录一万次也变不成管理员
    return { path: '/dashboard' }
  }

  return true
})

export default router
